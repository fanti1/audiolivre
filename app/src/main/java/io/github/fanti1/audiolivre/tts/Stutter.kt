package io.github.fanti1.audiolivre.tts

// Speech that stutters in fear: "N-n-não", "S-sim", "E-eu". A lone letter would be read by its name ("ene,
// ene, não"). Chosen by ear on the app's voice: a consonant stutter repeats the first syllable with a catch
// between ("nã... nã... não"); a vowel one repeats the whole word ("eu, eu vi").
object Stutter {

    private const val VOWELS = "aeiouáàâãéêíóôõúü"

    // One or two letters hyphenated onto a word they begin.
    private val STUTTER = Regex("""(?<![\p{L}-])((?:\p{L}{1,2}-)+)(\p{L}+)""")

    fun speak(text: String): String =
        STUTTER.replace(text) { m -> stutter(m.groupValues[1], m.groupValues[2]) ?: m.value }

    private fun stutter(fragments: String, word: String): String? {
        val parts = fragments.trimEnd('-').split('-')
        val lowerWord = word.lowercase()
        // Only a fragment the word itself starts with: "e-mail", "dá-me" and "vê-lo" are not stutters.
        if (parts.any { !lowerWord.startsWith(it.lowercase()) }) return null
        // "Ai-ai-ai", "au-au" repeat the whole word: that is a sound or emphasis, and Onomatopoeia reads it.
        if (parts.all { it.equals(word, ignoreCase = true) }) return null
        if (word.first().lowercaseChar() in VOWELS) return (parts.map { matchCase(it, word.lowercase()) } + word).joinToString(", ")
        val syllable = firstSyllable(word) ?: return null
        return (parts.map { matchCase(it, syllable) } + word).joinToString("... ")
    }

    private fun firstSyllable(word: String): String? {
        val lower = word.lowercase()
        var i = 0
        while (i < lower.length && lower[i] !in VOWELS) i++
        // In "que", "qui", "gue", "gui" the u is silent and belongs to the consonant.
        if (i > 0 && lower[i - 1] in "qg" && lower.getOrNull(i) == 'u' && lower.getOrNull(i + 1) in listOf('e', 'i', 'é', 'ê', 'í')) i++
        if (i >= lower.length) return null
        return lower.substring(0, i + 1)
    }

    private fun matchCase(original: String, replacement: String): String =
        if (original.firstOrNull()?.isUpperCase() == true) replacement.replaceFirstChar { it.uppercaseChar() } else replacement
}
