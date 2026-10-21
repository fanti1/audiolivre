package io.github.fanti1.audiolivre.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageBuilderTest {

    private val lineHeight = 10f
    private val charsPerLine = 10

    private fun paragraph(index: Int, lines: Int, heading: Boolean = false, height: Float = lineHeight) =
        ParagraphLines(
            paragraph = index,
            isHeading = heading,
            lineHeights = FloatArray(lines) { height },
            lineStarts = IntArray(lines) { it * charsPerLine },
            lineEnds = IntArray(lines) { (it + 1) * charsPerLine },
        )

    private fun starts(paragraphs: List<ParagraphLines>): IntArray {
        val result = IntArray(paragraphs.size + 1)
        paragraphs.forEachIndexed { i, p -> result[i + 1] = result[i] + p.lineHeights.size * charsPerLine + 1 }
        return result
    }

    private fun metrics(pageHeight: Float, chapterTop: Float = 0f, headingGap: Float = 0f) =
        PageMetrics(pageHeight = pageHeight, chapterTopSpace = chapterTop, paragraphGap = 0f, headingGap = headingGap)

    private fun lines(pages: List<Page>) = pages.flatMap { page ->
        page.slices.flatMap { s -> (s.firstLine..s.lastLine).map { s.paragraph to it } }
    }

    @Test
    fun `covers every line exactly once and in order`() {
        val paragraphs = listOf(paragraph(0, 3), paragraph(1, 7), paragraph(2, 1), paragraph(3, 5), paragraph(4, 2))
        val pages = PageBuilder.build(0, paragraphs, starts(paragraphs), metrics(45f))

        val expected = paragraphs.flatMap { p -> (0 until p.lineHeights.size).map { p.paragraph to it } }
        assertEquals(expected, lines(pages))
    }

    @Test
    fun `no page overflows its height`() {
        val paragraphs = listOf(paragraph(0, 3), paragraph(1, 9), paragraph(2, 4), paragraph(3, 6))
        val pages = PageBuilder.build(0, paragraphs, starts(paragraphs), metrics(45f))

        for (page in pages) {
            val bottom = page.slices.maxOf { it.y + (it.lastLine - it.firstLine + 1) * lineHeight }
            assertTrue("page overflows: $bottom", bottom <= 45f)
        }
    }

    @Test
    fun `continuation of a paragraph starts at the top of the next page`() {
        val paragraphs = listOf(paragraph(0, 6))
        val pages = PageBuilder.build(0, paragraphs, starts(paragraphs), metrics(45f))

        assertEquals(2, pages.size)
        assertEquals(PageSlice(0, 0, 3, 0f), pages[0].slices.single())
        assertEquals(PageSlice(0, 4, 5, 0f), pages[1].slices.single())
    }

    @Test
    fun `a lone first line moves to the next page`() {
        val paragraphs = listOf(paragraph(0, 3), paragraph(1, 3))
        val pages = PageBuilder.build(0, paragraphs, starts(paragraphs), metrics(45f))

        assertEquals(listOf(0), pages[0].slices.map { it.paragraph })
        assertEquals(PageSlice(1, 0, 2, 0f), pages[1].slices.first())
    }

    @Test
    fun `a lone last line is not carried to the next page`() {
        val paragraphs = listOf(paragraph(0, 1), paragraph(1, 4))
        val pages = PageBuilder.build(0, paragraphs, starts(paragraphs), metrics(45f))

        assertEquals(PageSlice(1, 0, 1, 10f), pages[0].slices.last())
        assertEquals(PageSlice(1, 2, 3, 0f), pages[1].slices.first())
    }

    @Test
    fun `chapter heading opens the first page with top space`() {
        val paragraphs = listOf(paragraph(0, 1, heading = true), paragraph(1, 8))
        val pages = PageBuilder.build(3, paragraphs, starts(paragraphs), metrics(60f, chapterTop = 20f))

        assertTrue(pages[0].opensChapter)
        assertEquals(20f, pages[0].slices.first().y)
        assertFalse(pages[1].opensChapter)
        assertTrue(pages.all { it.chapter == 3 })
    }

    @Test
    fun `a heading that would end a page moves down with its paragraph`() {
        val paragraphs = listOf(paragraph(0, 3), paragraph(1, 1, heading = true), paragraph(2, 3))
        val pages = PageBuilder.build(0, paragraphs, starts(paragraphs), metrics(50f, headingGap = 5f))

        assertEquals(listOf(0), pages[0].slices.map { it.paragraph })
        assertEquals(PageSlice(1, 0, 0, 0f), pages[1].slices.first())
    }

    @Test
    fun `a chapter number and its title stay together with space before the text`() {
        val paragraphs = listOf(paragraph(0, 1, heading = true), paragraph(1, 1, heading = true), paragraph(2, 2))
        val pages = PageBuilder.build(0, paragraphs, starts(paragraphs), metrics(100f, chapterTop = 20f, headingGap = 5f))

        assertEquals(listOf(20f, 30f, 45f), pages.single().slices.map { it.y })
    }

    @Test
    fun `a line taller than the page is placed alone`() {
        val paragraphs = listOf(paragraph(0, 1, height = 100f), paragraph(1, 2))
        val pages = PageBuilder.build(0, paragraphs, starts(paragraphs), metrics(45f))

        assertEquals(PageSlice(0, 0, 0, 0f), pages[0].slices.single())
        assertEquals(1, pages[1].slices.first().paragraph)
    }

    @Test
    fun `page offsets cover the text it shows`() {
        val paragraphs = listOf(paragraph(0, 2), paragraph(1, 6))
        val paragraphStarts = starts(paragraphs)
        val pages = PageBuilder.build(0, paragraphs, paragraphStarts, metrics(45f))

        assertEquals(0, pages[0].startOffset)
        assertEquals(paragraphStarts[1] + 2 * charsPerLine, pages[1].startOffset)
        assertEquals(paragraphStarts[1] + 6 * charsPerLine, pages.last().endOffset)
        assertEquals(1, PageBuilder.pageForOffset(pages, pages[1].startOffset + 3))
        assertEquals(0, PageBuilder.pageForOffset(pages, 0))
    }
}
