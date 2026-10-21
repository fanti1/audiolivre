package io.github.fanti1.audiolivre.tts

import java.text.Normalizer

// A voice reading letters guesses at all-caps words: "EUA" came out as "Zewa", "RG" as "ESG". Acronyms are
// spelled the way Brazilians say them ("cê pê éfe"), unless they read as a word ("ONU", "FIFA"), and headings
// or shouted lines in capitals are simply read as words.
object Acronyms {

    private val LETTER_NAMES = mapOf(
        'A' to "á", 'B' to "bê", 'C' to "cê", 'D' to "dê", 'E' to "é", 'F' to "éfe", 'G' to "gê", 'H' to "agá",
        'I' to "i", 'J' to "jota", 'K' to "cá", 'L' to "éle", 'M' to "ême", 'N' to "êne", 'O' to "ó", 'P' to "pê",
        'Q' to "quê", 'R' to "érre", 'S' to "ésse", 'T' to "tê", 'U' to "u", 'V' to "vê", 'W' to "dáblio",
        'X' to "xis", 'Y' to "ípsilon", 'Z' to "zê",
    )
    // Pronounceable by the rules below but spelled in speech, and the reverse.
    private val ALWAYS_SPELLED = setOf("UTI", "OAB", "CEO", "USA")
    // Spelled vowels run together in the voice ("é u á" came out as "Zewa"); narrators say the name instead.
    private val SAID_AS_WORD = mapOf(
        "OK" to "okei", "ONG" to "ong", "ONGS" to "ongs", "AIDS" to "aids", "EUA" to "Estados Unidos", "UE" to "União Europeia",
    )

    private val UPPER_TOKEN = Regex("""(?<![\p{L}\p{N}])\p{Lu}{2,}(?![\p{L}\p{N}])""")
    private val CONSONANT_RUN = Regex("""[^aeiou]+""")
    private val ONSETS = setOf("br", "cr", "dr", "fr", "gr", "pr", "tr", "vr", "bl", "cl", "fl", "gl", "pl", "ch", "lh", "nh")
    private const val CODAS = "lmnrsxz"
    private const val LONG_TOKEN = 6

    fun speak(text: String): String {
        val shouted = isShouted(text)
        return UPPER_TOKEN.replace(text) { m -> spoken(m.value, shouted) }
    }

    // In a line written in capitals most tokens are ordinary words ("INSTRUÇÕES"); only short unpronounceable
    // ones are still acronyms there. Long tokens are words even in mixed text: acronyms are short.
    private fun spoken(token: String, shouted: Boolean): String = when {
        token in SAID_AS_WORD -> SAID_AS_WORD.getValue(token)
        token in ALWAYS_SPELLED -> spell(token)
        token.length >= LONG_TOKEN || (shouted && token.length > 3) || isPronounceable(token) -> token.lowercase()
        else -> spell(token)
    }

    private fun spell(token: String) = token.map { LETTER_NAMES[baseLetter(it)] ?: it.toString() }.joinToString(" ")

    // A heading or a shouted line: mostly capitals and more than one real word.
    private fun isShouted(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 6) return false
        val words = text.split(' ').count { word -> word.count { it.isLetter() } >= 2 }
        return words >= 2 && letters.count { it.isUpperCase() } >= letters.length * 0.8
    }

    // Reads as a Portuguese word: has vowels, and every consonant cluster could start a syllable ("TR")
    // or close one and start the next ("NESCO").
    private fun isPronounceable(token: String): Boolean {
        val w = token.map { baseLetter(it).lowercaseChar() }.joinToString("")
        if (w.none { it in "aeiou" }) return false
        if (w.all { it in "aeiou" }) return w.length <= 2
        if (w.length <= 4 && w[0] in "aeiou" && w[1] in "aeiou") return false
        for (run in CONSONANT_RUN.findAll(w)) {
            val cluster = run.value
            val atStart = run.range.first == 0
            val atEnd = run.range.last == w.lastIndex
            when {
                cluster.length >= 3 -> return false
                cluster.length == 2 && atEnd -> return false
                cluster.length == 2 && atStart && cluster !in ONSETS -> return false
                cluster.length == 2 && !atStart && cluster !in ONSETS && cluster[0] !in CODAS -> return false
            }
        }
        return true
    }

    private fun baseLetter(c: Char): Char = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0]
}
