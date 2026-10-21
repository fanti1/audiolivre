package io.github.fanti1.audiolivre.tts

import android.util.Log
import io.github.fanti1.audiolivre.data.EventLog
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "PiperEngine"
// The narrator plus one voice per character gender; a fourth would only cost memory.
private const val MAX_LOADED_VOICES = 3

// Speaks with the Brazilian Piper voices: one model per voice, loaded when a role first needs it and dropped
// again when a fourth voice shows up. A missing voice falls back to the narrator instead of silence.
class PiperEngine(
    private val voicesDir: File,
    private val sharedDataDir: File,
    private val numThreads: Int = 4,
    // ONNX Runtime session settings, as sherpa-onnx reads them from a "cpu:<file>" provider (see VoiceMemoryMode).
    private val sessionConfig: File? = null,
) {
    private val mutex = Mutex()
    private val stateLock = Any()
    private val loaded = LinkedHashMap<String, OfflineTts>(MAX_LOADED_VOICES, 0.75f, true)
    private var busy = false
    private var releaseRequested = false

    fun sampleRate(): Int = VoiceModels.PLAYBACK_SAMPLE_RATE

    fun isReady(voiceId: String): Boolean = File(File(voicesDir, voiceId), COMPLETE_MARKER).isFile

    // One synthesis at a time: a cancelled playback's last sentence may still be running natively when the next
    // playback starts, and two of them must not share a model.
    suspend fun synthesize(text: String, voiceId: String, fallbackVoiceId: String, speed: Float): FloatArray =
        mutex.withLock {
            withContext(Dispatchers.Default) {
                synchronized(stateLock) {
                    check(!releaseRequested) { "voice engine already released" }
                    busy = true
                }
                try {
                    val id = if (isReady(voiceId)) voiceId else fallbackVoiceId
                    val voice = VoiceModels.byId(id) ?: VoiceModels.DEFAULT_NARRATOR
                    val tts = engineFor(voice)
                    val audio = tts.generateWithConfig(text, GenerationConfig(speed = speed, sid = 0))
                    resample(audio.samples, audio.sampleRate, VoiceModels.PLAYBACK_SAMPLE_RATE)
                } finally {
                    synchronized(stateLock) {
                        busy = false
                        if (releaseRequested) releaseNow()
                    }
                }
            }
        }

    // Loading a model takes a moment and the first synthesis after it is the slowest; doing both while the
    // "loading the voice" screen shows keeps the first press of play quick.
    // Only a voice not loaded yet needs it: opening another book with the same voice skips the extra synthesis.
    suspend fun warmUp(voiceId: String) {
        if (mutex.withLock { voiceId in loaded }) return
        runCatching { synthesize("Olá.", voiceId, voiceId, speed = 1f) }
            .onFailure { Log.w(TAG, "could not warm up $voiceId", it) }
    }

    private fun engineFor(voice: VoiceModel): OfflineTts {
        loaded[voice.id]?.let { return it }
        val dir = File(voicesDir, voice.id)
        val model = dir.listFiles { f -> f.extension == "onnx" }?.firstOrNull()
            ?: error("voice ${voice.id} is missing its model file")
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dataDir = sharedDataDir.absolutePath,
                    noiseScale = voice.noiseScale,
                    noiseScaleW = voice.noiseScaleW,
                ),
                numThreads = numThreads,
                debug = false,
                provider = sessionConfig?.let { "cpu:${it.absolutePath}" } ?: "cpu",
            ),
            // Measured to be inert for VITS in sherpa-onnx 1.13.8: changing it returns byte-identical audio.
            // The pauses a listener hears all come from Prosody instead.
            silenceScale = 1f,
        )
        val tts = OfflineTts(config = config)
        loaded[voice.id] = tts
        EventLog.record("voz ${voice.id} carregada na memória (${loaded.size} carregadas)")
        // LinkedHashMap in access order: the first entry is the one untouched for longest.
        while (loaded.size > MAX_LOADED_VOICES) {
            val oldest = loaded.keys.first()
            loaded.remove(oldest)?.release()
        }
        return tts
    }

    // Drops the loaded voices not in `keep`, between two sentences (never under a running synthesis).
    suspend fun releaseVoices(keep: Set<String>) = mutex.withLock {
        synchronized(stateLock) {
            val drop = loaded.keys.filter { it !in keep }
            drop.forEach { id -> loaded.remove(id)?.let { runCatching { it.release() } } }
        }
    }

    // Freeing a model under a running synthesis would crash, so that case defers it to the end of it.
    fun release() = synchronized(stateLock) {
        releaseRequested = true
        if (!busy) releaseNow()
    }

    private fun releaseNow() {
        loaded.values.forEach { runCatching { it.release() } }
        loaded.clear()
    }

    companion object {
        private const val COMPLETE_MARKER = ".complete"

        // Edresson speaks at 16 kHz while the others use 22.05 kHz; everything is played as one stream.
        private fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
            if (from == to || samples.isEmpty()) return samples
            val length = (samples.size.toLong() * to / from).toInt()
            val step = (samples.size - 1).toFloat() / (length - 1).coerceAtLeast(1)
            return FloatArray(length) { i ->
                val position = i * step
                val index = position.toInt()
                val fraction = position - index
                val next = samples.getOrElse(index + 1) { samples[index] }
                samples[index] * (1 - fraction) + next * fraction
            }
        }
    }
}
