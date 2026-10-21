package io.github.fanti1.audiolivre.formats

// One paragraph or heading as a reflowable format gives it. Unlike a PDF, these formats already say where each
// paragraph starts and ends, so none of the page geometry the PDF path has to guess at is involved.
data class TextBlock(val text: String, val isHeading: Boolean = false)

// A table-of-contents entry pointing at the block where its chapter starts.
data class TocEntry(val title: String, val block: Int, val level: Int = 0)

class ImportedBook(
    val title: String?,
    val author: String?,
    val blocks: List<TextBlock>,
    val toc: List<TocEntry> = emptyList(),
) {
    // Project Gutenberg wraps every book in an English header and a long license; read aloud, they'd be the
    // first and last thing the listener hears. Both are fenced by "*** START/END OF ... PROJECT GUTENBERG".
    // The header names the book ("Title: Dom Casmurro"), which plain text has no other way to say, so it is read
    // before being dropped; the volunteers' credit line that opens the text goes too.
    fun withoutGutenbergFrame(): ImportedBook {
        val start = blocks.indexOfFirst { GUTENBERG_START.containsMatchIn(it.text) }
        val end = blocks.indexOfFirst { GUTENBERG_END.containsMatchIn(it.text) }.takeIf { it > start } ?: blocks.size
        val cleanTitle = title?.let { GUTENBERG_TITLE.matchEntire(it)?.groupValues?.get(1) ?: it }
        if (start == -1 && end == blocks.size) {
            return if (cleanTitle == title) this else ImportedBook(cleanTitle, author, blocks, toc)
        }
        val header = blocks.subList(0, maxOf(start, 0))
        var first = start + 1
        while (first < end && first <= start + 3 && CREDITS.containsMatchIn(blocks[first].text)) first++
        return ImportedBook(
            title = cleanTitle ?: header.firstNotNullOfOrNull { TITLE_LINE.matchEntire(it.text)?.groupValues?.get(1) },
            author = author ?: header.firstNotNullOfOrNull { AUTHOR_LINE.matchEntire(it.text)?.groupValues?.get(1) },
            blocks = blocks.subList(first, end),
            toc = toc.filter { it.block in first until end }.map { it.copy(block = it.block - first) },
        )
    }

    private companion object {
        val GUTENBERG_START = Regex("""\*\*\*\s*START OF (THE|THIS) PROJECT GUTENBERG""", RegexOption.IGNORE_CASE)
        val GUTENBERG_END = Regex("""\*\*\*\s*END OF (THE|THIS) PROJECT GUTENBERG""", RegexOption.IGNORE_CASE)
        val GUTENBERG_TITLE = Regex("""^The Project Gutenberg e-?book of (.+?)(,? by .+)?\.?$""", RegexOption.IGNORE_CASE)
        val TITLE_LINE = Regex("""^Title:\s*(.+)$""")
        val AUTHOR_LINE = Regex("""^Author:\s*(.+)$""")
        val CREDITS = Regex("""^(Produced by|E-text prepared by|Transcribed (from|by)|This e-?book was produced)""", RegexOption.IGNORE_CASE)
    }
}

// The message is shown to the reader as is, so it is written for them.
class UnsupportedBookException(message: String) : Exception(message)
