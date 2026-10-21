package io.github.fanti1.audiolivre.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SynthesisPaceTest {

    @Test
    fun `a fast phone keeps little ahead and starts at once`() {
        val pace = SynthesisPace.inMemory(ratio = 0.2f)
        assertEquals(6_000L, pace.aheadMs)
        assertEquals(0L, pace.startBufferMs)
    }

    @Test
    fun `a phone close to real time keeps more ahead and buffers before the first word`() {
        val pace = SynthesisPace.inMemory(ratio = 0.9f)
        assertTrue(pace.aheadMs.toString(), pace.aheadMs in 30_000L..45_000L)
        assertTrue(pace.startBufferMs.toString(), pace.startBufferMs in 4_000L..10_000L)
    }

    @Test
    fun `the figure follows how fast sentences are actually spoken`() {
        val pace = SynthesisPace.inMemory(ratio = 0.3f)
        repeat(30) { pace.record(workMs = 900, audioMs = 1_000) }
        assertTrue(pace.ratio.toString(), pace.ratio > 0.85f)
        // A tiny piece says nothing reliable about speed.
        val before = pace.ratio
        pace.record(workMs = 5_000, audioMs = 100)
        assertEquals(before, pace.ratio)
    }

    @Test
    fun `running dry makes the estimate more careful`() {
        val pace = SynthesisPace.inMemory(ratio = 0.5f)
        val ahead = pace.aheadMs
        pace.recordUnderrun()
        assertEquals(1, pace.underruns)
        assertTrue(pace.aheadMs > ahead)
    }

    @Test
    fun `threads leave room for audio on phones with few cores`() {
        assertEquals(4, SynthesisPace.threadsFor(8))
        assertEquals(3, SynthesisPace.threadsFor(4))
        assertEquals(2, SynthesisPace.threadsFor(2))
    }
}
