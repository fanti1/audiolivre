package io.github.fanti1.audiolivre.tts

import java.text.Normalizer

// Undoes what PDF text extraction leaves behind and a character-reading voice stumbles on: typographic
// ligatures ("ﬁ" is one character the voice doesn't know), invisible hyphenation points and zero-width
// characters inside words, exotic spaces, and accents stored as separate combining marks.
object TextCleaner {

    private const val SOFT_HYPHEN = '\u00AD'
    private val LIGATURES = mapOf(
        '\uFB00' to "ff", '\uFB01' to "fi", '\uFB02' to "fl", '\uFB03' to "ffi", '\uFB04' to "ffl",
        '\uFB05' to "st", '\uFB06' to "st",
    )
    private val INVISIBLE = Regex("[\u00AD\u200B-\u200D\u2060\uFEFF]")
    private val ODD_SPACES = Regex("[\u00A0\u2000-\u200A\u202F\u205F\u3000\t]")

    fun clean(text: String): String {
        var t = if (Normalizer.isNormalized(text, Normalizer.Form.NFC)) text else Normalizer.normalize(text, Normalizer.Form.NFC)
        if (t.any { it in LIGATURES }) t = buildString(t.length + 4) { for (c in t) append(LIGATURES[c] ?: c) }
        t = INVISIBLE.replace(t, "")
        return ODD_SPACES.replace(t, " ")
    }

    // A soft hyphen ending a line marks where the PDF broke a word, and the dehyphenator relies on it to join
    // the halves; soft hyphens anywhere else are simply dropped.
    fun cleanLine(text: String): String {
        val trimmed = text.trimEnd()
        return if (trimmed.endsWith(SOFT_HYPHEN)) clean(trimmed.dropLast(1)) + SOFT_HYPHEN else clean(text)
    }
}
