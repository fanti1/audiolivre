package io.github.fanti1.audiolivre.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class TimeStretchTest {

    private val rate = 44_100

    private fun tone(hz: Double, seconds: Double) = FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun zeroCrossingsPerSecond(samples: FloatArray): Double {
        var crossings = 0
        for (i in 1 until samples.size) if ((samples[i - 1] < 0f) != (samples[i] < 0f)) crossings++
        return crossings * rate.toDouble() / samples.size
    }

    @Test
    fun `speeding up shortens the audio by the rate`() {
        val input = tone(220.0, 3.0)
        val output = TimeStretch.speedUp(input, rate, 1.25f)
        assertEquals(input.size / 1.25, output.size.toDouble(), rate * 0.05)
    }

    @Test
    fun `the pitch stays the same`() {
        val input = tone(220.0, 3.0)
        val output = TimeStretch.speedUp(input, rate, 1.5f)
        val before = zeroCrossingsPerSecond(input)
        val after = zeroCrossingsPerSecond(output)
        assertEquals(before, after, before * 0.03)
    }

    @Test
    fun `normal speed leaves the audio untouched`() {
        val input = tone(220.0, 1.0)
        assertSame(input, TimeStretch.speedUp(input, rate, 1.0f))
    }

    @Test
    fun `a very short sound is left as is`() {
        val input = tone(220.0, 0.02)
        assertSame(input, TimeStretch.speedUp(input, rate, 1.5f))
    }

    // For the listening QA: stretches the WAVs listen_check.py synthesized, so Whisper can judge this exact code.
    // Runs only when QA_STRETCH_DIR (a listen_check output folder) and QA_STRETCH_RATE are set.
    @Test
    fun `stretch qa audio`() {
        val dir = System.getenv("QA_STRETCH_DIR")?.let(::File)
        val stretch = System.getenv("QA_STRETCH_RATE")?.toFloatOrNull()
        assumeTrue(dir != null && stretch != null)
        val out = File(dir, "wav_stretched").apply { mkdirs() }
        File(dir!!, "wav").listFiles { f -> f.extension == "wav" }!!.forEach { wav ->
            val (samples, sampleRate) = readWav(wav)
            writeWav(File(out, wav.name), TimeStretch.speedUp(samples, sampleRate, stretch!!), sampleRate)
        }
    }

    private fun readWav(file: File): Pair<FloatArray, Int> {
        val bytes = file.readBytes()
        val header = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = header.getInt(24)
        val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(pcm.remaining()) { pcm.get(it) / 32768f } to sampleRate
    }

    private fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(samples.size * 2)
        }
        file.writeBytes(header.array() + data.array())
    }
}
