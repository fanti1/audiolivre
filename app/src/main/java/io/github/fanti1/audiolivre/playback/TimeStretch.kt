package io.github.fanti1.audiolivre.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

// Speeds speech up without raising its pitch (WSOLA: waveform-similarity overlap-add). Used on top of the
// voice's own speed, which starts swallowing words above ~1.4x: the listening QA measured 5.6% word errors at
// 1.6x but 22.5% at 2.0x from the model alone.
object TimeStretch {

    private const val FRAME_MS = 30
    private const val SEARCH_MS = 10
    // Correlation on every 4th sample is plenty to line up voiced speech and keeps this cheap on a phone.
    private const val CORRELATION_STEP = 4

    fun speedUp(input: FloatArray, sampleRate: Int, rate: Float): FloatArray {
        val frame = sampleRate * FRAME_MS / 1000
        if (rate <= 1.01f || input.size < frame * 2) return input
        val hop = frame / 2
        val analysisHop = (hop * rate).roundToInt()
        val search = sampleRate * SEARCH_MS / 1000
        val window = FloatArray(frame) { (0.5 - 0.5 * cos(2 * PI * it / frame)).toFloat() }

        val frames = (input.size - frame - search) / analysisHop
        if (frames < 1) return input
        val out = FloatArray(frames * hop + frame)
        val weight = FloatArray(out.size)
        var previous = 0
        for (k in 0..frames) {
            val nominal = k * analysisHop
            // The best cut is where the input looks most like the natural continuation of the last frame.
            val start = if (k == 0) 0 else bestStart(input, previous + hop, nominal, search, hop)
            val at = k * hop
            for (i in 0 until frame) {
                val src = start + i
                val dst = at + i
                if (src >= input.size || dst >= out.size) break
                out[dst] += input[src] * window[i]
                weight[dst] += window[i]
            }
            previous = start
        }
        val length = frames * hop + hop
        return FloatArray(length.coerceAtMost(out.size)) { i -> if (weight[i] > 1e-3f) out[i] / weight[i] else out[i] }
    }

    private fun bestStart(input: FloatArray, continuation: Int, nominal: Int, search: Int, length: Int): Int {
        val from = (nominal - search).coerceAtLeast(0)
        val to = (nominal + search).coerceAtMost(input.size - length - 1)
        if (to <= from || continuation + length >= input.size) return nominal.coerceIn(0, input.size - length - 1)
        var best = nominal.coerceIn(from, to)
        var bestScore = Float.NEGATIVE_INFINITY
        var candidate = from
        while (candidate <= to) {
            var score = 0f
            var i = 0
            while (i < length) {
                score += input[candidate + i] * input[continuation + i]
                i += CORRELATION_STEP
            }
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
            candidate += 2
        }
        return best
    }
}
