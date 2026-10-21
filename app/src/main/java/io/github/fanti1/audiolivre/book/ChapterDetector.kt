package io.github.fanti1.audiolivre.book

object ChapterDetector {

    data class Candidate(val text: String, val page: Int, val isHeading: Boolean)

    // Requires a numeral or a capitalized word after "Capítulo" so prose lines like
    // "Capítulo após capítulo, ele leu..." aren't mistaken for headings.
    private val CHAPTER_LINE = Regex(
        """^(CAP[ÍI]TULO|Cap[íi]tulo|CAP\.)\s+([0-9]+|[IVXLCDM]+|\p{Lu}\p{L}*)\b.{0,40}$|^(PRÓLOGO|Prólogo|EPÍLOGO|Epílogo)\.?$""",
    )
    private val CHAPTER_NUMBER = Regex("""^([IVXLCDM]{1,7}|\d{1,3})\.?$""")
    private const val MAX_TITLE_LENGTH = 80
    private const val MIN_TITLE_MATCH_LENGTH = 4
    private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")

    fun isChapterLine(text: String): Boolean = CHAPTER_LINE.matches(text.trim())

    fun detect(paragraphs: List<Candidate>, outline: List<OutlineEntry>, bookTitle: String): List<Chapter> {
        if (paragraphs.isEmpty()) return listOf(Chapter(bookTitle, 0))
        val found = fromOutline(paragraphs, outline).ifEmpty { fromHeadings(paragraphs) }
        val chapters = found.sortedBy { it.firstParagraph }.distinctBy { it.firstParagraph }
        if (chapters.isEmpty()) return listOf(Chapter(bookTitle, 0))
        return if (chapters.first().firstParagraph == 0) chapters else listOf(Chapter(bookTitle, 0)) + chapters
    }

    private fun fromOutline(paragraphs: List<Candidate>, outline: List<OutlineEntry>): List<Chapter> {
        // Nested outlines list a part and its first chapter on the same page ("Parte I", "Capítulo 1");
        // the later, more specific entry wins. sortedBy is stable, so outline order is kept within a page.
        val byPage = LinkedHashMap<Int, OutlineEntry>()
        for (entry in outline.sortedBy { it.page }) byPage[entry.page] = entry
        return byPage.values.mapNotNull { entry ->
            val first = paragraphs.indexOfFirst { it.page >= entry.page }
            if (first == -1) return@mapNotNull null
            // Chapters often start mid-page; the heading that matches the outline title marks the real start.
            val titled = (first until paragraphs.size)
                .takeWhile { paragraphs[it].page == entry.page }
                .firstOrNull { matchesTitle(paragraphs[it].text, entry.title) }
            Chapter(entry.title.take(MAX_TITLE_LENGTH), titled ?: first)
        }
    }

    private fun fromHeadings(paragraphs: List<Candidate>): List<Chapter> {
        val chapterLines = paragraphs.indices.filter { isChapterLine(paragraphs[it].text) }
        if (chapterLines.isNotEmpty()) {
            return chapterLines.map { i ->
                val subtitle = paragraphs.getOrNull(i + 1)?.takeIf { it.isHeading && !isChapterLine(it.text) }?.text
                val title = if (subtitle != null) "${paragraphs[i].text} — $subtitle" else paragraphs[i].text
                Chapter(title.take(MAX_TITLE_LENGTH), i)
            }
        }
        val headings = paragraphs.indices.filter { paragraphs[it].isHeading }
        if (headings.size < 2) return emptyList()
        val chapters = mutableListOf<Chapter>()
        var run = mutableListOf(headings.first())
        // A bare chapter number ("IV") opens a chapter even right after other headings, such as a title page.
        for (i in headings.drop(1)) {
            if (i == run.last() + 1 && !CHAPTER_NUMBER.matches(paragraphs[i].text)) {
                run += i
            } else {
                chapters += headingRun(paragraphs, run)
                run = mutableListOf(i)
            }
        }
        chapters += headingRun(paragraphs, run)
        return chapters
    }

    private fun headingRun(paragraphs: List<Candidate>, run: List<Int>): Chapter =
        Chapter(run.joinToString(" — ") { paragraphs[it].text }.take(MAX_TITLE_LENGTH), run.first())

    private fun matchesTitle(paragraph: String, title: String): Boolean {
        if (paragraph.length > MAX_TITLE_LENGTH * 2) return false
        val p = normalize(paragraph)
        val t = normalize(title)
        if (p.length < MIN_TITLE_MATCH_LENGTH || t.length < MIN_TITLE_MATCH_LENGTH) return false
        return p.startsWith(t) || t.startsWith(p)
    }

    private fun normalize(text: String): String = text.lowercase().replace(NON_ALPHANUMERIC, " ").trim()
}
