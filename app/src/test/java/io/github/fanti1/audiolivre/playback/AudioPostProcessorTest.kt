package io.github.fanti1.audiolivre.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPostProcessorTest {

    // 1000 Hz makes the 40 ms trim margin exactly 40 samples.
    private val sampleRate = 1000

    @Test
    fun `trims leading and trailing silence keeping a margin`() {
        val samples = FloatArray(100) + FloatArray(50) { 0.5f } + FloatArray(100)

        val trimmed = trimSilence(samples, sampleRate)

        assertEquals(40 + 50 + 40, trimmed.size)
        assertEquals(0f, trimmed[39])
        assertEquals(0.5f, trimmed[40])
        assertEquals(0.5f, trimmed[89])
        assertEquals(0f, trimmed[90])
    }

    @Test
    fun `margin is clamped at the array edges`() {
        val samples = FloatArray(10) + FloatArray(20) { -0.3f } + FloatArray(5)

        val trimmed = trimSilence(samples, sampleRate)

        assertEquals(samples.size, trimmed.size)
    }

    @Test
    fun `all-silent input becomes empty`() {
        val samples = FloatArray(500) { 0.005f }

        assertEquals(0, trimSilence(samples, sampleRate).size)
    }

    @Test
    fun `keeps quiet but audible sound inside the sentence`() {
        val samples = FloatArray(50) { 0.4f } + FloatArray(300) + FloatArray(50) { 0.4f }

        val trimmed = trimSilence(samples, sampleRate)

        assertEquals(samples.size, trimmed.size)
    }

    @Test
    fun `silence has the requested duration and is all zeros`() {
        val silence = silenceOf(350, 22050)

        assertEquals(7717, silence.size)
        assertTrue(silence.all { it == 0f })
    }
}
