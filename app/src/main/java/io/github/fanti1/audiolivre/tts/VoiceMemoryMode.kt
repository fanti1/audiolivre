package io.github.fanti1.audiolivre.tts

import io.github.fanti1.audiolivre.playback.SynthesisPace
import java.io.File

// How the voice engine (ONNX Runtime, inside sherpa-onnx) handles its working memory. Both ways compute the same
// audio, sample for sample; they trade memory for processor time (VoiceMemoryTest, on the emulator):
//   FAST: the engine keeps a memory pool as large as its longest sentence. About 620 MB while reading, and the
//         quickest (it beats ONNX Runtime's own default, which also plans memory per sentence length: 800 MB).
//   LEAN: every sentence takes its memory and gives it back. About 60 MB, but about 60% more processor time.
// A phone with memory to spare reads FAST. One short of memory reads LEAN, as long as its processor has the
// headroom for it (a voice that can't keep up is worse than a fuller memory).
enum class VoiceMemoryMode(private val configText: String) {
    FAST("EnableMemPattern=0\n"),
    LEAN("EnableCpuMemArena=0\nEnableMemPattern=0\n"),
    ;

    // sherpa-onnx reads ONNX Runtime session settings from a file named in the provider: "cpu:<file>".
    fun configFile(dir: File): File = File(dir, "onnx-session-${name.lowercase()}.config").apply {
        if (!isFile || readText() != configText) {
            parentFile?.mkdirs()
            writeText(configText)
        }
    }

    companion object {
        // Processor time LEAN takes over FAST, measured.
        const val LEAN_SLOWDOWN = 1.6f
        // Below 6 GB, 600 MB held by a background app is what Android's memory killer goes after first.
        private const val ROOMY_RAM_BYTES = 6L * 1024 * 1024 * 1024
        // LEAN only while speech takes at most this much of real time to make: room left for a slower moment.
        private const val LEAN_MAX_RATIO = 0.6f

        fun choose(totalRamBytes: Long, lowRamDevice: Boolean, pace: SynthesisPace): VoiceMemoryMode {
            if (!lowRamDevice && totalRamBytes >= ROOMY_RAM_BYTES) return FAST
            // The pace was measured in whichever mode was in use; this is what LEAN would cost.
            val fastRatio = if (pace.lean) pace.ratio / LEAN_SLOWDOWN else pace.ratio
            return if (fastRatio * LEAN_SLOWDOWN <= LEAN_MAX_RATIO) LEAN else FAST
        }
    }
}
