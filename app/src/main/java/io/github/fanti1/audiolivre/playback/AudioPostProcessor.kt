package io.github.fanti1.audiolivre.playback

import kotlin.math.abs

private const val SILENCE_THRESHOLD = 0.01f
private const val TRIM_MARGIN_MS = 40

// Piper pads each sentence with an inconsistent amount of leading/trailing silence; trimming it
// lets the pipeline insert a controlled, predictable pause between sentences instead.
fun trimSilence(samples: FloatArray, sampleRate: Int): FloatArray {
    val first = samples.indexOfFirst { abs(it) > SILENCE_THRESHOLD }
    if (first == -1) return FloatArray(0)
    val last = samples.indexOfLast { abs(it) > SILENCE_THRESHOLD }
    val margin = TRIM_MARGIN_MS * sampleRate / 1000
    val start = (first - margin).coerceAtLeast(0)
    val end = (last + margin + 1).coerceAtMost(samples.size)
    return samples.copyOfRange(start, end)
}

fun silenceOf(durationMs: Int, sampleRate: Int): FloatArray =
    FloatArray((durationMs.toLong() * sampleRate / 1000).toInt())
