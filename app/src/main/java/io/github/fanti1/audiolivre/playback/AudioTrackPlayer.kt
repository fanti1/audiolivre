package io.github.fanti1.audiolivre.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.delay

// A buffer of most of a second, topped up every 80 ms: the processor wakes about 12 times a second while
// reading instead of 50, and still has 600 ms of sound queued when it does.
private const val BUFFER_MS = 750
private const val POLL_MS = 80L
private const val START_THRESHOLD_MS = 100

// Every call into the AudioTrack happens under one lock and checks `closed` first. A blocking native write
// can't be interrupted by coroutine cancellation, and releasing the track while a write is in flight kills the
// app ("Unable to retrieve AudioTrack pointer for write()"), so writes are non-blocking steps instead.
class AudioTrackPlayer(sampleRate: Int) {

    private val lock = Any()
    private var closed = false
    private var framesWritten = 0L

    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(
            maxOf(
                AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 2,
                // Whole frames: a size that is not a multiple of the 4-byte sample is refused.
                sampleRate * BUFFER_MS / 1000 * Float.SIZE_BYTES,
            )
        )
        .build()

    init {
        // A streaming track waits for a full buffer before it makes a sound; a short first sentence should start
        // at once instead. (Older Androids have no such setting and start when the first sentence fills it.)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            runCatching { track.setStartThresholdInFrames(sampleRate * START_THRESHOLD_MS / 1000) }
        }
        track.play()
    }

    // The frame at which the next written sample will be heard.
    val queuedFrames: Long get() = synchronized(lock) { framesWritten }

    // Frames the speaker has actually played so far, or -1 once released.
    fun playedFrames(): Long = synchronized(lock) {
        if (closed) -1L else track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
    }

    // Returns once everything is queued (writing paces itself to playback) or the player was released.
    suspend fun write(samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            val written = synchronized(lock) {
                if (closed) return
                track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    .also { if (it > 0) framesWritten += it }
            }
            check(written >= 0) { "AudioTrack.write failed with code $written" }
            offset += written
            if (offset < samples.size) delay(POLL_MS)
        }
    }

    // A streaming track may hold back a final partial buffer until stop(), which plays out what's queued.
    suspend fun drain() {
        synchronized(lock) { if (!closed) track.stop() }
        while (true) {
            val played = playedFrames()
            if (played < 0 || played >= queuedFrames) return
            delay(POLL_MS)
        }
    }

    // Silences immediately (pause + flush discards what's buffered) and frees the native track.
    fun release() {
        synchronized(lock) {
            if (closed) return
            closed = true
            track.pause()
            track.flush()
            track.release()
        }
    }
}
