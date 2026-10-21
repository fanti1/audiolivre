package io.github.fanti1.audiolivre.tts

// A very long input makes playback wait before the first sound, so the longest sentences are voiced in
// pieces cut where a reader would breathe.
//
// Every cut also costs something: the voice restarts its intonation mid-sentence and the words on either
// side of the join come out worse. The old 180-character limit was inherited from an engine that read long
// inputs badly; qa/tune_delivery.py measured Piper over 24 long book sentences and 500 beat it on both
// counts — naturalness 3.425 against 3.414, word errors 5.7% against 6.7%, and no garbled sentence at all.
object SpeechChunker {

    const val MAX_CHARS = 500
    private const val MIN_FRACTION = 0.35f

    private val CONJUNCTIONS = listOf(" e ", " mas ", " porém ", " que ", " quando ", " porque ", " onde ", " como ", " ou ", " se ")

    fun split(text: String, maxChars: Int = MAX_CHARS): List<String> {
        val pieces = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > maxChars) {
            val cut = bestCut(rest, maxChars)
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }

    // Cut position (exclusive end of the first piece) inside the first maxChars characters: the strongest
    // break (";" or ":", then ",", then before a conjunction, then any space) that isn't too early.
    private fun bestCut(text: String, maxChars: Int): Int {
        val min = (maxChars * MIN_FRACTION).toInt()
        val window = text.substring(0, maxChars + 1)
        fun lastAfter(chars: String): Int? =
            (window.length - 1 downTo min).firstOrNull { i -> window[i] in chars && window.getOrNull(i + 1)?.isWhitespace() == true }
                ?.let { it + 1 }
        lastAfter(";:")?.let { return it }
        lastAfter(",")?.let { return it }
        CONJUNCTIONS.mapNotNull { c -> window.lastIndexOf(c).takeIf { it >= min } }.maxOrNull()?.let { return it }
        return window.lastIndexOf(' ').takeIf { it >= min } ?: maxChars
    }
}
