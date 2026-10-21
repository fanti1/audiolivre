package io.github.fanti1.audiolivre.playback

import android.content.Context
import android.content.SharedPreferences

// How fast this phone turns text into speech, as milliseconds of work per millisecond of speech, and what that
// means for playback. A fast phone needs little audio ready ahead; a slow one, or one slowed down by heat
// halfway through a chapter, needs more queued, and a head start before the first word, or the reading
// stutters. The figure follows every sentence synthesized and is remembered between sessions.
class SynthesisPace private constructor(private val prefs: SharedPreferences?) {

    @Volatile
    var ratio: Float = prefs?.getFloat(KEY_RATIO, DEFAULT_RATIO) ?: DEFAULT_RATIO
        private set

    @Volatile
    var underruns: Int = prefs?.getInt(KEY_UNDERRUNS, 0) ?: 0
        private set

    // Whether the figure was measured with the voice engine in its lean memory mode (VoiceMemoryMode.LEAN).
    @Volatile
    var lean: Boolean = prefs?.getBoolean(KEY_LEAN, false) ?: false
        private set

    // The engine switches memory mode: the figure is rescaled at once, so the buffer fits from the first sentence.
    fun useLean(value: Boolean, slowdown: Float) {
        if (value == lean) return
        ratio = if (value) ratio * slowdown else ratio / slowdown
        lean = value
        save()
    }

    private var unsaved = 0

    // One sentence (or clause) done: blends its speed into the figure, recent sentences weighing most.
    fun record(workMs: Long, audioMs: Long) {
        if (audioMs < MIN_SAMPLE_MS) return
        val sample = (workMs.toFloat() / audioMs).coerceIn(0.01f, 5f)
        ratio = ratio * (1 - SMOOTHING) + sample * SMOOTHING
        if (++unsaved >= SAVE_EVERY) save()
    }

    // The speaker ran out of audio: whatever the average says, this phone is slower than it thought.
    fun recordUnderrun() {
        underruns++
        ratio = (ratio * UNDERRUN_PENALTY).coerceAtMost(5f)
        save()
    }

    // Speech kept ready ahead of what is heard. At half real time 17 s, at real time the most memory allows.
    val aheadMs: Long
        get() = (MIN_AHEAD_MS + (ratio - FAST_RATIO).coerceAtLeast(0f) * AHEAD_PER_RATIO).toLong().coerceAtMost(MAX_AHEAD_MS)

    // Speech gathered before the first word plays. A phone that keeps up easily starts at once; one close to
    // real time waits a few seconds, which is better than a pause after the first sentence.
    val startBufferMs: Long
        get() = if (ratio < SLOW_RATIO) 0L else (1_500 + (ratio - SLOW_RATIO) * 20_000).toLong().coerceAtMost(MAX_START_MS)

    private fun save() {
        unsaved = 0
        prefs?.edit()?.putFloat(KEY_RATIO, ratio)?.putInt(KEY_UNDERRUNS, underruns)?.putBoolean(KEY_LEAN, lean)?.apply()
    }

    companion object {
        private const val PREFS = "synthesis_pace"
        private const val KEY_RATIO = "ratio"
        private const val KEY_UNDERRUNS = "underruns"
        private const val KEY_LEAN = "lean"
        private const val DEFAULT_RATIO = 0.5f
        private const val SMOOTHING = 0.15f
        private const val SAVE_EVERY = 10
        private const val MIN_SAMPLE_MS = 300L
        private const val UNDERRUN_PENALTY = 1.25f
        private const val FAST_RATIO = 0.3f
        private const val SLOW_RATIO = 0.6f
        private const val MIN_AHEAD_MS = 6_000f
        private const val AHEAD_PER_RATIO = 55_000f
        private const val MAX_AHEAD_MS = 45_000L
        private const val MAX_START_MS = 10_000L

        @Volatile
        private var shared: SynthesisPace? = null

        fun of(context: Context): SynthesisPace = shared ?: synchronized(this) {
            shared ?: SynthesisPace(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { shared = it }
        }

        // For tests: a fresh figure that is not saved anywhere.
        internal fun inMemory(ratio: Float = DEFAULT_RATIO, lean: Boolean = false) =
            SynthesisPace(null).also {
                it.ratio = ratio
                it.lean = lean
            }

        // Threads per synthesis. Phones have 8 cores of which 4 are fast ones; on a phone with fewer cores the
        // audio and the screen need one left over, or the voice itself competes with playing it.
        fun threadsFor(cores: Int): Int = when {
            cores >= 6 -> 4
            cores >= 4 -> 3
            else -> 2
        }
    }
}
