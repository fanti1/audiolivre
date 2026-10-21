package io.github.fanti1.audiolivre.book

object HeaderFooterFilter {

    private const val SLOT_LINES = 2
    private const val MIN_REPEATED_PAGES = 3
    private const val REPEATED_PAGE_FRACTION = 0.3
    private const val RUNNING_HEADER_MIN_PAGES = 4
    // The same short line at the very top or bottom of two different pages is furniture, not prose.
    private const val MIN_SLOT_REPEATS = 2
    private const val RUNNING_HEADER_MAX_LENGTH = 60
    private const val HEADING_FONT_RATIO = 1.2f

    private val PAGE_NUMBER = Regex("""^[\-–—•*·\s]*(p[áa]g(ina)?\.?\s*)?\d{1,4}[\-–—•*·\s]*$""", RegexOption.IGNORE_CASE)
    private val ROMAN_PAGE_NUMBER = Regex("""^[\-–—•*·\s]*[ivxlcdm]{1,7}[\-–—•*·\s]*$""", RegexOption.IGNORE_CASE)
    private val DIGITS = Regex("""\d+""")
    private val SPACES = Regex("""\s+""")

    fun filter(lines: List<PdfLine>, bodyFontSize: Float): List<PdfLine> {
        val indicesByPage = lines.indices.groupBy { lines[it].page }
        val slotIndices = HashSet<Int>()
        for (indices in indicesByPage.values) {
            slotIndices += indices.take(SLOT_LINES)
            slotIndices += indices.takeLast(SLOT_LINES)
        }

        // Books alternate running headers between left and right pages (book title / chapter title), so
        // repetition is counted separately for odd and even pages.
        val pagesByKey = HashMap<Pair<Int, String>, MutableSet<Int>>()
        for (i in slotIndices) {
            val line = lines[i]
            pagesByKey.getOrPut(line.page % 2 to normalize(line.text)) { mutableSetOf() } += line.page
        }
        val pagesPerParity = indicesByPage.keys.groupingBy { it % 2 }.eachCount()

        // Counted across both parities too: a running header that names the chapter changes with every
        // chapter, so in a short book it may sit on only two pages — one left, one right.
        val pagesByText = HashMap<String, MutableSet<Int>>()
        for (i in slotIndices) pagesByText.getOrPut(normalize(lines[i].text)) { mutableSetOf() } += lines[i].page

        fun looksLikeFurniture(line: PdfLine) =
            line.text.length <= RUNNING_HEADER_MAX_LENGTH &&
                !endsWithTerminalPunctuation(line.text) &&
                !DialogueSplitter.startsWithDialogueDash(line.text)

        fun isRepeated(line: PdfLine): Boolean {
            val count = pagesByKey[line.page % 2 to normalize(line.text)]?.size ?: 0
            if (count >= MIN_REPEATED_PAGES &&
                count >= REPEATED_PAGE_FRACTION * (pagesPerParity[line.page % 2] ?: 0)
            ) return true
            // A chapter title used as running header repeats only across its own chapter's pages. Real prose
            // almost never repeats verbatim at the very top or bottom of two different pages, and headers
            // don't end like sentences or dialogue.
            if (count >= RUNNING_HEADER_MIN_PAGES && looksLikeFurniture(line)) return true
            val anyParity = pagesByText[normalize(line.text)]?.size ?: 0
            return anyParity >= MIN_SLOT_REPEATS && looksLikeFurniture(line)
        }

        return lines.filterIndexed { i, line ->
            val isHeadingSized = bodyFontSize > 0f && line.fontSize >= bodyFontSize * HEADING_FONT_RATIO
            when {
                isHeadingSized -> true
                PAGE_NUMBER.matches(line.text) -> false
                i in slotIndices && ROMAN_PAGE_NUMBER.matches(line.text) -> false
                i in slotIndices && isRepeated(line) -> false
                else -> true
            }
        }
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(DIGITS, "#").replace(SPACES, " ").trim()
}

internal fun endsWithTerminalPunctuation(text: String): Boolean =
    text.trimEnd().lastOrNull()?.let { it in ".!?…:»”\"" } ?: false
