package io.github.fanti1.audiolivre.book

class Dehyphenator(private val plainWords: Set<String>, private val hyphenatedWords: Set<String>) {

    // Works on the paragraph's builder in place: a paragraph can be thousands of lines in a badly
    // structured PDF, so re-copying the whole text per line would be quadratic.
    fun append(paragraph: StringBuilder, line: String) {
        if (line.isEmpty()) return
        if (paragraph.isEmpty()) {
            paragraph.append(line)
            return
        }
        val last = paragraph.last()
        if (last == SOFT_HYPHEN) {
            paragraph.setLength(paragraph.length - 1)
            paragraph.append(line)
            return
        }
        val beforeHyphen = paragraph.getOrNull(paragraph.length - 2)
        if (last !in HYPHENS || beforeHyphen == null || !beforeHyphen.isLetter()) {
            paragraph.append(' ').append(line)
            return
        }
        val first = line.first()
        when {
            // Portuguese repeats the hyphen of a compound on the next line ("guarda-" / "-chuva").
            first in HYPHENS -> paragraph.append(line, 1, line.length)
            !first.isLetter() -> paragraph.append(' ').append(line)
            first.isUpperCase() || keepsHyphen(paragraph, line) -> paragraph.append(line)
            else -> {
                paragraph.setLength(paragraph.length - 1)
                paragraph.append(line)
            }
        }
    }

    private fun keepsHyphen(paragraph: StringBuilder, line: String): Boolean {
        var i = paragraph.length - 2
        while (i >= 0 && paragraph[i].isLetter()) i--
        val head = paragraph.substring(i + 1, paragraph.length - 1).lowercase()
        val tail = line.takeWhile { it.isLetter() }.lowercase()
        if (tail in CLITICS) return true
        if ("$head$tail" in plainWords) return false
        return "$head-$tail" in hyphenatedWords
    }

    companion object {
        private const val SOFT_HYPHEN = '\u00AD'
        private val HYPHENS = setOf('-', '‐')
        private val CLITICS = setOf(
            "lo", "la", "los", "las", "me", "te", "se", "nos", "vos", "lhe", "lhes",
            "o", "a", "os", "as", "ei", "á", "ão", "emos", "ia", "iam",
        )
        private val WORD = Regex("""\p{L}+(?:[-‐]\p{L}+)*""")

        fun fromLines(lines: List<String>): Dehyphenator {
            val plain = HashSet<String>()
            val hyphenated = HashSet<String>()
            for (line in lines) {
                for (match in WORD.findAll(line)) {
                    val word = match.value.lowercase().replace('‐', '-')
                    if ('-' in word) hyphenated += word else plain += word
                }
            }
            return Dehyphenator(plain, hyphenated)
        }
    }
}
