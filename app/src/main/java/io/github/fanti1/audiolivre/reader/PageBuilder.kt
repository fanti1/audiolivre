package io.github.fanti1.audiolivre.reader

data class PageSlice(val paragraph: Int, val firstLine: Int, val lastLine: Int, val y: Float)

data class Page(
    val chapter: Int,
    val slices: List<PageSlice>,
    val startOffset: Int,
    val endOffset: Int,
    val opensChapter: Boolean,
)

class ParagraphLines(
    val paragraph: Int,
    val isHeading: Boolean,
    val lineHeights: FloatArray,
    val lineStarts: IntArray,
    val lineEnds: IntArray,
)

data class PageMetrics(
    val pageHeight: Float,
    val chapterTopSpace: Float,
    val paragraphGap: Float,
    val headingGap: Float,
)

object PageBuilder {

    fun build(
        chapter: Int,
        paragraphs: List<ParagraphLines>,
        paragraphStarts: IntArray,
        metrics: PageMetrics,
    ): List<Page> {
        val byIndex = paragraphs.associateBy { it.paragraph }
        val pages = mutableListOf<Page>()
        val slices = mutableListOf<PageSlice>()
        var y = 0f
        var opensChapter = false

        fun flush() {
            if (slices.isNotEmpty()) {
                val first = slices.first()
                val last = slices.last()
                pages += Page(
                    chapter = chapter,
                    slices = slices.toList(),
                    startOffset = paragraphStarts[first.paragraph] + byIndex.getValue(first.paragraph).lineStarts[first.firstLine],
                    endOffset = paragraphStarts[last.paragraph] + byIndex.getValue(last.paragraph).lineEnds[last.lastLine],
                    opensChapter = opensChapter,
                )
            }
            slices.clear()
            y = 0f
            opensChapter = false
        }

        paragraphs.forEachIndexed { i, para ->
            val heights = para.lineHeights
            val n = heights.size
            if (n == 0) return@forEachIndexed

            if (i == 0 && para.isHeading) {
                opensChapter = true
                y = metrics.chapterTopSpace
            } else if (slices.isNotEmpty()) {
                // Space separates a heading block from body text on either side; a chapter number and its title
                // line stay together as one block.
                val previousIsHeading = paragraphs[i - 1].isHeading
                y += when {
                    para.isHeading && previousIsHeading -> 0f
                    para.isHeading || previousIsHeading -> metrics.headingGap
                    else -> metrics.paragraphGap
                }
                // A heading stranded at the bottom of a page reads as a layout bug; keep it with its first lines.
                if (para.isHeading) {
                    val next = paragraphs.getOrNull(i + 1)
                    val followUp = next?.lineHeights?.take(2)?.sum() ?: 0f
                    if (y + heights.sum() + metrics.headingGap + followUp > metrics.pageHeight) flush()
                }
            }

            var line = 0
            while (line < n) {
                var fit = 0
                var height = 0f
                while (line + fit < n && y + height + heights[line + fit] <= metrics.pageHeight) {
                    height += heights[line + fit]
                    fit++
                }
                if (fit == 0) {
                    if (slices.isNotEmpty()) {
                        flush()
                        continue
                    }
                    // A single line taller than the page: place it anyway rather than loop forever.
                    fit = 1
                    height = heights[line]
                }
                // Orphan: never leave a paragraph's first line alone at the bottom of a page.
                if (line == 0 && fit == 1 && n > 1 && slices.isNotEmpty()) {
                    flush()
                    continue
                }
                // Widow: never carry a paragraph's last line alone onto the next page.
                if (n - (line + fit) == 1 && fit >= 3) {
                    fit--
                    height -= heights[line + fit]
                }
                slices += PageSlice(para.paragraph, line, line + fit - 1, y)
                y += height
                line += fit
                if (line < n) flush()
            }
        }
        flush()
        return pages
    }

    fun pageForOffset(pages: List<Page>, offset: Int): Int {
        if (pages.isEmpty()) return 0
        var low = 0
        var high = pages.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (pages[mid].startOffset <= offset) low = mid else high = mid - 1
        }
        return low
    }
}
