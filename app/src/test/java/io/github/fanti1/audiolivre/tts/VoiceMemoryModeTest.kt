package io.github.fanti1.audiolivre.tts

import io.github.fanti1.audiolivre.playback.SynthesisPace
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceMemoryModeTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `a phone with memory to spare always reads fast`() {
        assertEquals(VoiceMemoryMode.FAST, VoiceMemoryMode.choose(8 * gb, lowRamDevice = false, pace = SynthesisPace.inMemory(0.1f)))
    }

    @Test
    fun `a phone short of memory reads lean when its processor has headroom`() {
        assertEquals(VoiceMemoryMode.LEAN, VoiceMemoryMode.choose(4 * gb, lowRamDevice = false, pace = SynthesisPace.inMemory(0.2f)))
        assertEquals(VoiceMemoryMode.LEAN, VoiceMemoryMode.choose(8 * gb, lowRamDevice = true, pace = SynthesisPace.inMemory(0.2f)))
    }

    @Test
    fun `a slow phone short of memory still reads fast, so the voice keeps up`() {
        assertEquals(VoiceMemoryMode.FAST, VoiceMemoryMode.choose(3 * gb, lowRamDevice = false, pace = SynthesisPace.inMemory(0.5f)))
        // Measured in lean mode at 0.7: in fast mode it would be 0.44, and lean costs 0.7 > 0.6.
        assertEquals(VoiceMemoryMode.FAST, VoiceMemoryMode.choose(3 * gb, lowRamDevice = false, pace = SynthesisPace.inMemory(0.7f, lean = true)))
    }

    @Test
    fun `switching mode rescales the measured pace`() {
        val pace = SynthesisPace.inMemory(0.2f)
        pace.useLean(true, VoiceMemoryMode.LEAN_SLOWDOWN)
        assertEquals(0.32f, pace.ratio, 0.001f)
        pace.useLean(false, VoiceMemoryMode.LEAN_SLOWDOWN)
        assertEquals(0.2f, pace.ratio, 0.001f)
    }
}
