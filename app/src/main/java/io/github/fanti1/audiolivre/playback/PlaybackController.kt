package io.github.fanti1.audiolivre.playback

import android.util.Log
import io.github.fanti1.audiolivre.book.BookDocument
import io.github.fanti1.audiolivre.data.EventLog
import io.github.fanti1.audiolivre.data.ResumePositionStore
import io.github.fanti1.audiolivre.data.VoiceSettings
import io.github.fanti1.audiolivre.tts.Prosody
import io.github.fanti1.audiolivre.tts.PtBrNormalizer
import io.github.fanti1.audiolivre.tts.SpeechChunker
import io.github.fanti1.audiolivre.tts.PiperEngine
import io.github.fanti1.audiolivre.tts.VoiceCast
import io.github.fanti1.audiolivre.tts.VoiceRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

private const val TAG = "PlaybackController"

// The highlight moves sentence by sentence; checking 12 times a second is still well under a word.
private const val FOLLOW_INTERVAL_MS = 80L
// The reading position is written at most this often while playing, and always when playback stops.
private const val SAVE_POSITION_EVERY_MS = 5_000L
private const val SPEED_CHECK_EVERY = 20
private const val AHEAD_POLL_MS = 50L
// Running dry twice in a row is one stall; it should slow the pace estimate down once, not twice.
private const val UNDERRUN_COOLDOWN_MS = 10_000L

private class Chunk(val sentence: Int, val samples: FloatArray, val pauseMs: Int) {
    fun durationMs(sampleRate: Int) = samples.size * 1000L / sampleRate + pauseMs
}

// The frame at which a sentence's first sample reaches the speaker.
private class SentenceMark(val frame: Long, val sentence: Int)

class PlaybackController(
    private val book: BookDocument,
    private val engine: PiperEngine,
    private val roles: List<VoiceRole>,
    initialSettings: VoiceSettings,
    private val resumeStore: ResumePositionStore,
    private val uriString: String,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PlaybackState>,
    // How far ahead to synthesize and how long to buffer before speaking, from this phone's measured speed.
    private val pace: SynthesisPace,
) {
    private val sentences = book.sentences
    private var settings = initialSettings
    private var player: AudioTrackPlayer? = null
    private var job: Job? = null

    fun play(fromIndex: Int = state.value.currentIndex) {
        stopPlayback()
        if (sentences.isEmpty()) return
        val startIndex = fromIndex.coerceIn(0, sentences.size - 1)
        val newPlayer = AudioTrackPlayer(engine.sampleRate())
        player = newPlayer
        state.update { it.copy(currentIndex = startIndex, isPlaying = true, isFinished = false) }

        // Any failure ends this playback with an error state instead of taking the whole app down.
        val onError = CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "playback failed", error)
            newPlayer.release()
            if (player === newPlayer) state.update { it.copy(isPlaying = false) }
        }
        job = scope.launch(Dispatchers.Default + onError) { runPipeline(newPlayer, startIndex, settings) }
    }

    private suspend fun runPipeline(player: AudioTrackPlayer, startIndex: Int, cast: VoiceSettings) = coroutineScope {
        // Unbounded: the producer paces itself by how much speech is ready ahead (see produce()).
        val chunks = Channel<Chunk>(capacity = Channel.UNLIMITED)
        val marks = ConcurrentLinkedQueue<SentenceMark>()
        val producedFrames = AtomicLong(0)
        val sampleRate = engine.sampleRate()
        EventLog.record(
            "leitura: voz ${cast.narratorVoice}, velocidade ${cast.speed}, ritmo %.2f, à frente %d s, início %d s"
                .format(pace.ratio, pace.aheadMs / 1000, pace.startBufferMs / 1000),
        )
        val producer = launch { produce(startIndex, cast, chunks, player, producedFrames) }
        val follower = launch { follow(player, marks) }
        try {
            val silences = HashMap<Int, FloatArray>()
            var lastSentence = -1
            var lastUnderrunAt = 0L
            suspend fun write(chunk: Chunk) {
                // Everything written so far has already been played: the listener is hearing silence.
                val queued = player.queuedFrames
                if (queued > 0 && player.playedFrames() >= queued) {
                    val now = System.currentTimeMillis()
                    if (now - lastUnderrunAt > UNDERRUN_COOLDOWN_MS) {
                        lastUnderrunAt = now
                        pace.recordUnderrun()
                        EventLog.record("áudio esvaziou antes da hora (ritmo agora %.2f)".format(pace.ratio))
                    }
                }
                if (chunk.sentence != lastSentence) {
                    marks += SentenceMark(player.queuedFrames, chunk.sentence)
                    lastSentence = chunk.sentence
                }
                player.write(chunk.samples)
                if (chunk.pauseMs > 0) player.write(silences.getOrPut(chunk.pauseMs) { silenceOf(chunk.pauseMs, sampleRate) })
            }

            // On a phone that barely keeps up, a few seconds gathered before the first word avoid a stall
            // right after it.
            val head = ArrayList<Chunk>()
            var headMs = 0L
            val startBuffer = pace.startBufferMs
            val queue = chunks.iterator()
            while (headMs < startBuffer && queue.hasNext()) {
                val chunk = queue.next()
                head += chunk
                headMs += chunk.durationMs(sampleRate)
            }
            for (chunk in head) write(chunk)
            while (queue.hasNext()) write(queue.next())
            player.drain()
            ensureActive()
            state.update { it.copy(isPlaying = false, isFinished = true) }
            savePosition()
        } finally {
            producer.cancel()
            follower.cancel()
        }
    }

    private suspend fun produce(
        startIndex: Int,
        cast: VoiceSettings,
        chunks: Channel<Chunk>,
        player: AudioTrackPlayer,
        producedFrames: AtomicLong,
    ) {
        try {
            val sampleRate = engine.sampleRate()
            var synthesisMs = 0L
            var audioMs = 0L
            var measured = 0
            suspend fun send(chunk: Chunk) {
                chunks.send(chunk)
                producedFrames.addAndGet(chunk.durationMs(sampleRate) * sampleRate / 1000)
            }
            for (i in startIndex until sentences.size) {
                val voiceId = VoiceCast.voiceId(roles[i], cast)
                val speed = VoiceCast.modelSpeed(voiceId, cast) * Prosody.speedFactor(book, i)
                val stretch = VoiceCast.stretch(voiceId, cast)
                val pieces = SpeechChunker.split(PtBrNormalizer.normalize(sentences[i].ttsText))
                // A scene break has nothing to say; it is heard as the silence that follows it, and still
                // reaches the reader so the highlight moves past it.
                if (pieces.isEmpty()) {
                    send(Chunk(i, FloatArray(0), (Prosody.pauseAfterMs(book, i) / cast.speed).roundToInt()))
                    continue
                }
                pieces.forEachIndexed { p, piece ->
                    // Far enough ahead of the listener already: wait instead of filling memory with speech.
                    while (true) {
                        val played = player.playedFrames()
                        if (played < 0) return
                        if (producedFrames.get() - played <= pace.aheadMs * sampleRate / 1000) break
                        delay(AHEAD_POLL_MS)
                    }
                    val startedAt = System.currentTimeMillis()
                    val samples = try {
                        val spoken = engine.synthesize(piece, voiceId, cast.narratorVoice, speed)
                        TimeStretch.speedUp(trimSilence(spoken, sampleRate), sampleRate, stretch)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One unreadable sentence shouldn't end the book: skip it and keep reading.
                        Log.w(TAG, "could not synthesize sentence $i: \"$piece\"", e)
                        EventLog.record("frase $i não pôde ser falada: ${e.javaClass.simpleName}")
                        FloatArray(0)
                    }
                    // Synthesis has to stay well ahead of playback; if it doesn't, the reading stutters and the
                    // log says so instead of leaving a silent gap to guess about.
                    val workMs = System.currentTimeMillis() - startedAt
                    val pieceMs = samples.size * 1000L / sampleRate
                    pace.record(workMs, pieceMs)
                    synthesisMs += workMs
                    audioMs += pieceMs
                    if (++measured % SPEED_CHECK_EVERY == 0 && synthesisMs > audioMs * 0.8) {
                        Log.w(TAG, "synthesis is barely keeping up: ${synthesisMs}ms of work for ${audioMs}ms of speech")
                    }
                    val pause = if (p == pieces.lastIndex) Prosody.pauseAfterMs(book, i) else Prosody.CLAUSE_PAUSE_MS
                    // Faster reading shortens the pauses too, or they'd feel ever longer relative to speech.
                    send(Chunk(i, samples, (pause / cast.speed).roundToInt()))
                }
            }
        } finally {
            chunks.close()
        }
    }

    // The highlight and the resume point follow what is audible now, not what was just queued.
    private suspend fun follow(player: AudioTrackPlayer, marks: ConcurrentLinkedQueue<SentenceMark>) = coroutineScope {
        var savedAt = 0L
        while (isActive) {
            val played = player.playedFrames()
            if (played < 0) return@coroutineScope
            var reached = -1
            while (true) {
                val mark = marks.peek() ?: break
                if (mark.frame > played) break
                marks.poll()
                reached = mark.sentence
            }
            if (reached >= 0) {
                state.update { it.copy(currentIndex = reached) }
                val now = System.currentTimeMillis()
                if (now - savedAt >= SAVE_POSITION_EVERY_MS) {
                    savedAt = now
                    resumeStore.save(uriString, sentences[reached].charOffset)
                }
            }
            delay(FOLLOW_INTERVAL_MS)
        }
    }

    // Audio already synthesized ahead used the old voice/speed, so restart from the current sentence.
    fun updateSettings(newSettings: VoiceSettings) {
        if (newSettings == settings) return
        settings = newSettings
        if (state.value.isPlaying) play(state.value.currentIndex)
    }

    fun pause() {
        stopPlayback()
        state.update { it.copy(isPlaying = false) }
        savePosition()
    }

    private fun savePosition() {
        val index = state.value.currentIndex.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
        if (sentences.isNotEmpty()) scope.launch { resumeStore.save(uriString, sentences[index].charOffset) }
    }

    fun seekTo(index: Int) {
        if (sentences.isEmpty()) return
        val clamped = index.coerceIn(0, sentences.size - 1)
        if (state.value.isPlaying) {
            play(clamped)
        } else {
            state.update { it.copy(currentIndex = clamped) }
            scope.launch { resumeStore.save(uriString, sentences[clamped].charOffset) }
        }
    }

    fun release() {
        if (state.value.isPlaying) savePosition()
        stopPlayback()
    }

    // Cancelled first so the old job can't mistake the closed player for the end of the book; releasing then
    // silences it at once (the player's lock keeps this safe against a write in flight).
    private fun stopPlayback() {
        job?.cancel()
        job = null
        player?.release()
        player = null
    }
}
