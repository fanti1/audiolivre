package io.github.fanti1.audiolivre.translate

// The translation engine takes one sentence at a time: a whole paragraph reaches the model as one endless
// "sentence", is cut every 127 tokens without regard for words, and comes back with the pieces glued together
// ("estado atualProvavelmente"). So paragraphs are split here, in the source language, before translating,
// and sentences long enough to be cut are split again where a clause ends.
internal object SourceSentences {

    // A sentence ends at ., ! or ? (and the Devanagari, Arabic and Armenian marks), with any closing quotes or
    // brackets that belong to it, and the next one starts after whitespace, maybe behind opening quotes, a
    // dialogue dash or Spanish ¿ ¡.
    private val BOUNDARY = Regex("""([.!?…।؟։]+["'”’)\]»]*)\s+(["'“‘(\[¿¡«—–]*)(\S)""")
    // Chinese and Japanese end sentences with full-width marks and no space.
    private val CJK_END = Regex("""[。！？]+[」』”’）]*""")

    // Words that end in a period in the middle of a sentence and precede a capital: titles and common
    // abbreviations of the languages books come in. A single capital letter (an initial) is handled apart.
    private val NONBREAKING = setOf(
        "Mr", "Mrs", "Ms", "Dr", "Drs", "Prof", "Rev", "Hon", "St", "Fr", "Gen", "Col", "Maj", "Capt", "Lt",
        "Sgt", "Messrs", "Jr", "Sr", "Bros", "vs", "Mt", "Ave", "Inc", "Ltd", "Co", "No", "e.g", "i.e",
        "Sra", "Srta", "Ud", "Uds", "Vd", "Dña", "Dra", "Sto", "Sta",
        "MM", "Mme", "Mlle", "Me", "Mgr",
        "Hr", "Nr", "Str", "z.B", "bzw", "usw", "ca", "Dott", "Sig", "Sigg",
    )

    // About 127 subword tokens fit in one pass; German compounds take several tokens per word.
    private const val MAX_WORDS = 50

    fun split(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        return sentences(trimmed).flatMap(::shortened)
    }

    private fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (match in BOUNDARY.findAll(text)) {
            val end = match.groups[1]!!.range.last + 1
            val next = match.groups[3]!!.value[0]
            val endsWithPeriod = match.groups[1]!!.value.trimEnd('"', '\'', '”', '’', ')', ']', '»').all { it == '.' }
            // A lone period is the overloaded one (3.14, "Mr. Smith", "e.g."): it only ends a sentence before a
            // capital or a quote, and never after an abbreviation. ! and ? end one either way.
            if (endsWithPeriod) {
                val opener = match.groups[2]!!.value
                if (!next.isUpperCase() && !next.isDigit() && opener.isEmpty()) continue
                if (isAbbreviation(text.substring(start, match.range.first))) continue
            }
            out += text.substring(start, end).trim()
            start = match.groups[2]!!.range.first.takeIf { match.groups[2]!!.value.isNotEmpty() } ?: match.groups[3]!!.range.first
        }
        out += text.substring(start).trim()
        return out.filter { it.isNotEmpty() }.flatMap(::cjkSentences)
    }

    private fun cjkSentences(text: String): List<String> {
        if (!CJK_END.containsMatchIn(text)) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        for (match in CJK_END.findAll(text)) {
            val end = match.range.last + 1
            if (end < text.length) {
                out += text.substring(start, end).trim()
                start = end
            }
        }
        out += text.substring(start).trim()
        return out.filter { it.isNotEmpty() }
    }

    private fun isAbbreviation(before: String): Boolean {
        val word = before.trimEnd().substringAfterLast(' ').trimStart { !it.isLetterOrDigit() }
        if (word.isEmpty()) return false
        if (word.length == 1 && word[0].isUpperCase()) return true
        return word in NONBREAKING
    }

    // A sentence too long for one pass is cut where a clause ends (; : or ,) nearest its middle, again and
    // again, and only between two words when it has no such place.
    private fun shortened(sentence: String): List<String> {
        val words = sentence.split(' ')
        if (words.size <= MAX_WORDS) return listOf(sentence)
        val middle = sentence.length / 2
        val cuts = Regex("""[;:,]\s""").findAll(sentence).map { it.range.first + 1 }.toList()
        val cut = cuts.minByOrNull { kotlin.math.abs(it - middle) }
            ?: sentence.indexOf(' ', middle).takeIf { it > 0 }
            ?: return listOf(sentence)
        return shortened(sentence.substring(0, cut).trim()) + shortened(sentence.substring(cut).trim())
    }
}
