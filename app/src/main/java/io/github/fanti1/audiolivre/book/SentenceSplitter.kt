package io.github.fanti1.audiolivre.book

import java.text.BreakIterator
import java.util.Locale

data class TextSpan(val start: Int, val end: Int)

object SentenceSplitter {

    private val PAGE_NUMBER_ONLY = Regex("""^[\-–—•*\s]*(p[áa]g(ina)?\.?\s*)?\d{1,4}[\-–—•*\s]*$""", RegexOption.IGNORE_CASE)
    private val HAS_LETTERS = Regex("""\p{L}""")
    private const val MIN_UNPUNCTUATED_LENGTH = 3

    // Periods after these don't end a sentence ("O Sr. Almeida chegou.").
    private val ABBREVIATIONS = setOf(
        "sr.", "sra.", "srta.", "srs.", "sras.", "dr.", "dra.", "drs.", "dras.", "prof.", "profa.", "profs.",
        "exmo.", "exma.", "exa.", "ex.", "p.", "av.", "n.º", "nº.", "pág.", "págs.", "cap.", "séc.", "art.", "vol.",
        "mr.", "mrs.", "ms.", "st.", "sto.", "sta.", "pe.", "fr.", "gen.", "cel.", "ten.", "sgt.",
    )
    private val INITIAL = Regex("""\p{Lu}\.""")
    // A capital letter after these is a label ("vitamina C."), not a name initial.
    private val LETTER_LABELS = setOf(
        "letra", "vitamina", "raio", "tipo", "classe", "plano", "item", "nota", "grupo", "bloco", "série", "fase",
        "anexo", "opção", "alternativa",
    )

    fun sentenceSpans(text: String): List<TextSpan> {
        val boundary = BreakIterator.getSentenceInstance(Locale("pt", "BR"))
        boundary.setText(text)
        val raw = mutableListOf<TextSpan>()
        var start = boundary.first()
        var end = boundary.next()
        while (end != BreakIterator.DONE) {
            var s = start
            var e = end
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            if (s < e) raw += TextSpan(s, e)
            start = end
            end = boundary.next()
        }
        return mergeAfterAbbreviations(text, raw).filterNot { isJunkFragment(text.substring(it.start, it.end)) }
    }

    private fun mergeAfterAbbreviations(text: String, spans: List<TextSpan>): List<TextSpan> {
        val merged = mutableListOf<TextSpan>()
        for (span in spans) {
            val previous = merged.lastOrNull()
            if (previous != null && endsWithAbbreviation(text.substring(previous.start, previous.end))) {
                merged[merged.lastIndex] = TextSpan(previous.start, span.end)
            } else {
                merged += span
            }
        }
        return merged
    }

    private fun endsWithAbbreviation(fragment: String): Boolean {
        val words = fragment.split(Regex("""\s+"""))
        val last = words.last().trimStart { !it.isLetterOrDigit() }
        if (last.lowercase() in ABBREVIATIONS) return true
        if (!INITIAL.matches(last)) return false
        val before = words.getOrNull(words.size - 2)?.trimStart { !it.isLetterOrDigit() }?.lowercase()
        return before !in LETTER_LABELS
    }

    fun isJunkFragment(fragment: String): Boolean {
        if (fragment.isBlank()) return true
        val hasLetters = HAS_LETTERS.containsMatchIn(fragment)
        val hasDigits = fragment.any { it.isDigit() }
        if (!hasLetters && !hasDigits) return true
        if (PAGE_NUMBER_ONLY.matches(fragment)) return true
        val endsWithSentencePunctuation = fragment.last() in ".!?…\"'”"
        if (fragment.length < MIN_UNPUNCTUATED_LENGTH && !endsWithSentencePunctuation) return true
        return false
    }
}
