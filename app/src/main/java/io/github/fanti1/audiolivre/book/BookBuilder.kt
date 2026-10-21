package io.github.fanti1.audiolivre.book

import io.github.fanti1.audiolivre.formats.TextBlock
import io.github.fanti1.audiolivre.formats.TocEntry
import io.github.fanti1.audiolivre.tts.TextCleaner
import kotlin.math.abs
import kotlin.math.ceil

object BookBuilder {

    private const val HEADING_FONT_RATIO = 1.35f
    private const val FONT_CHANGE_RATIO = 1.2f
    private const val MAX_HEADING_LENGTH = 120
    private const val MAX_SUBTITLE_LENGTH = 80
    private const val CENTER_TOLERANCE = 0.1f
    private const val PARAGRAPH_GAP_RATIO = 1.5f
    private const val INDENT_EM = 0.8f
    private const val SHORT_LINE_FRACTION = 0.15f
    private const val SHORT_LINE_CHAR_FRACTION = 0.7f
    // Two left edges closer than this belong to the same column; a gutter is much wider.
    private const val MIN_COLUMN_TOLERANCE = 24f
    private const val COLUMN_TOLERANCE_EM = 3f
    // A real column carries much of the page; a handful of centred title lines does not.
    private const val MIN_COLUMN_SHARE = 0.2f
    private const val MIN_COLUMN_LINES = 8
    private const val SUBTITLE_FONT_DELTA = 0.05f

    private val TTS_TRIM_START = charArrayOf('—', '–', '-', '“', '”', '"', '«', '»')
    private val TTS_TRIM_END = charArrayOf('—', '–', '-', '“', '”', '"', '«', '»')
    private val WHITESPACE = Regex("""\s+""")

    fun build(lines: List<PdfLine>, outline: List<OutlineEntry>, pageCount: Int, title: String): BookDocument {
        val cleaned = lines.map { it.copy(text = TextCleaner.cleanLine(it.text).replace(WHITESPACE, " ").trim()) }
            .filter { it.text.isNotEmpty() }
        val bodyFontSize = median(cleaned.map { it.fontSize }.filter { it > 0f })
        // The lines already arrive in reading order (LineCollectingStripper splits and orders columns); the
        // drop cap is the one glyph whose position lies about where it belongs.
        val ordered = DropCap.merge(cleaned, bodyFontSize)
        val kept = HeaderFooterFilter.filter(ordered, bodyFontSize)
        val context = AssemblyContext(
            layout = PageLayout.of(kept),
            dehyphenator = Dehyphenator.fromLines(kept.map { it.text }),
            outlinePages = outline.filter { it.page in 0 until pageCount }.map { it.page }.toSet(),
            bodyFontSize = bodyFontSize,
        )
        val raw = assembleParagraphs(kept, context)
        val chapters = ChapterDetector.detect(
            raw.map { ChapterDetector.Candidate(it.text.toString(), it.page, it.isHeading) },
            outline.filter { it.page in 0 until pageCount },
            title,
        )
        return toDocument(title, raw.map { Draft(it.text.toString(), it.isHeading, it.page, it::pageAt) }, chapters)
    }

    // Formats that already know their paragraphs and headings (EPUB, Word, MOBI…): no page geometry to read, only
    // text to clean. Each block's index stands in for the PDF page, so a table of contents can point at it.
    fun fromBlocks(blocks: List<TextBlock>, toc: List<TocEntry>, title: String): BookDocument {
        val kept = blocks.mapIndexedNotNull { i, block ->
            val text = TextCleaner.clean(block.text).replace(WHITESPACE, " ").trim()
            if (text.isEmpty()) null else Draft(text, block.isHeading, i)
        }
        val chapters = ChapterDetector.detect(
            kept.map { ChapterDetector.Candidate(it.text, it.page, it.isHeading) },
            toc.map { OutlineEntry(it.title, it.block, it.level) },
            title,
        )
        return toDocument(title, kept, chapters)
    }

    // A paragraph ready to become sentences, whichever path assembled it.
    private class Draft(val text: String, val isHeading: Boolean, val page: Int, val pageAt: (Int) -> Int = { page })

    private class AssemblyContext(
        val layout: PageLayout,
        val dehyphenator: Dehyphenator,
        val outlinePages: Set<Int>,
        val bodyFontSize: Float,
    ) {
        fun isHeadingSized(line: PdfLine) = bodyFontSize > 0f && line.fontSize >= bodyFontSize * HEADING_FONT_RATIO
    }

    private class RawParagraph(val page: Int, val firstLine: PdfLine) {
        val text = StringBuilder(firstLine.text)
        val pageMarks = mutableListOf(0 to page)
        var lastLine = firstLine
        var maxFontSize = firstLine.fontSize
        var isHeading = false

        fun append(line: PdfLine, dehyphenator: Dehyphenator) {
            if (line.page != lastLine.page) pageMarks += text.length to line.page
            dehyphenator.append(text, line.text)
            lastLine = line
            maxFontSize = maxOf(maxFontSize, line.fontSize)
        }

        fun pageAt(offset: Int): Int = pageMarks.lastOrNull { it.first <= offset }?.second ?: page
    }

    private fun assembleParagraphs(lines: List<PdfLine>, ctx: AssemblyContext): List<RawParagraph> {
        val result = mutableListOf<RawParagraph>()
        for ((_, pageLines) in lines.groupBy { it.page }.toSortedMap()) {
            var current: RawParagraph? = null
            for ((i, line) in pageLines.withIndex()) {
                val previous = if (i == 0) result.lastOrNull() else current
                val continues = when {
                    previous == null -> false
                    i == 0 -> continuesAcrossPage(previous, line, ctx)
                    else -> !startsNewParagraph(pageLines[i - 1], line, ctx)
                }
                if (continues && previous != null) {
                    previous.append(line, ctx.dehyphenator)
                    current = previous
                } else {
                    current = RawParagraph(line.page, line).also { result += it }
                }
            }
        }
        result.forEachIndexed { i, paragraph ->
            paragraph.isHeading = isHeading(paragraph, ctx) || (i > 0 && result[i - 1].isHeading && isSubtitle(paragraph, ctx))
        }
        return result
    }

    private fun startsNewParagraph(prev: PdfLine, line: PdfLine, ctx: AssemblyContext): Boolean {
        val layout = ctx.layout
        val gap = line.y - prev.y
        // PdfBox also flags a line that starts left of the previous one when that one wasn't itself flagged, which
        // splits every paragraph whose indented first line follows another indented line (typical in dialogue).
        // Its flag is only trusted when the line really is indented.
        // A dash only opens a reply after a finished sentence: in justified text a mid-sentence aside
        // ("— como já disse —") often wraps so that its dash lands at the start of a line.
        return (line.paragraphStart && layout.isIndented(line)) ||
            (DialogueSplitter.startsWithDialogueDash(line.text) && endsWithTerminalPunctuation(prev.text)) ||
            isDecorative(line.text) || isDecorative(prev.text) ||
            ChapterDetector.isChapterLine(line.text) || ChapterDetector.isChapterLine(prev.text) ||
            fontChanged(prev, line) ||
            (layout.lineSpacing > 0f && gap > PARAGRAPH_GAP_RATIO * layout.lineSpacing) ||
            // Moving up the page means the text jumped to the next column. That is the same kind of break as
            // turning a page, and a paragraph survives both: it cannot legitimately end mid-sentence.
            (gap < 0f && breaksAfterJump(prev, line, ctx)) ||
            (endsWithTerminalPunctuation(prev.text) && layout.isShort(prev)) ||
            (layout.isIndented(line) && (layout.isShort(prev) || endsWithTerminalPunctuation(prev.text)))
    }

    private fun breaksAfterJump(prev: PdfLine, line: PdfLine, ctx: AssemblyContext): Boolean = when {
        isDecorative(line.text) || isDecorative(prev.text) -> true
        ChapterDetector.isChapterLine(line.text) -> true
        !endsWithTerminalPunctuation(prev.text) -> false
        DialogueSplitter.startsWithDialogueDash(line.text) -> true
        ctx.layout.isIndented(line) -> true
        line.text.first().isUpperCase() && ctx.layout.isShort(prev) -> true
        else -> false
    }

    private fun continuesAcrossPage(previous: RawParagraph, line: PdfLine, ctx: AssemblyContext): Boolean {
        val layout = ctx.layout
        return when {
            previous.isHeadingCandidate(ctx) || ctx.isHeadingSized(line) -> false
            line.page in ctx.outlinePages -> false
            isDecorative(line.text) || isDecorative(previous.lastLine.text) -> false
            ChapterDetector.isChapterLine(line.text) || ChapterDetector.isChapterLine(previous.lastLine.text) -> false
            // A paragraph can't legitimately end mid-sentence, so a dash or an indent only matters after a full stop.
            !endsWithTerminalPunctuation(previous.lastLine.text) -> true
            DialogueSplitter.startsWithDialogueDash(line.text) -> false
            layout.isIndented(line) -> false
            line.text.first().isUpperCase() && layout.isShort(previous.lastLine) -> false
            else -> true
        }
    }

    private fun RawParagraph.isHeadingCandidate(ctx: AssemblyContext) =
        ctx.bodyFontSize > 0f && maxFontSize >= ctx.bodyFontSize * HEADING_FONT_RATIO

    private fun isBoldTitle(paragraph: RawParagraph): Boolean {
        val text = paragraph.text.toString()
        return paragraph.firstLine.bold && paragraph.firstLine === paragraph.lastLine &&
            text.length <= MAX_HEADING_LENGTH && !endsWithTerminalPunctuation(text) &&
            !DialogueSplitter.startsWithDialogueDash(text)
    }

    private fun isHeading(paragraph: RawParagraph, ctx: AssemblyContext): Boolean {
        val text = paragraph.text
        if (text.length > MAX_HEADING_LENGTH) return false
        return paragraph.isHeadingCandidate(ctx) || isBoldTitle(paragraph) ||
            ChapterDetector.isChapterLine(text.toString())
    }

    // A chapter's title line ("Do título.") is often set only slightly larger than the body, so its size alone
    // doesn't make it a heading; a single centered line right under a heading does.
    // The line under a chapter number that names the chapter. Books centre it, but not all of them; what it
    // always does is stand out from the body — a different size or weight — and stop without a full stop.
    private fun isSubtitle(paragraph: RawParagraph, ctx: AssemblyContext): Boolean {
        if (paragraph.firstLine !== paragraph.lastLine) return false
        val text = paragraph.text.toString()
        if (text.length > MAX_SUBTITLE_LENGTH) return false
        if (ctx.layout.isCentered(paragraph.firstLine)) return true
        val line = paragraph.firstLine
        val differs = line.bold ||
            (ctx.bodyFontSize > 0f && abs(line.fontSize - ctx.bodyFontSize) > ctx.bodyFontSize * SUBTITLE_FONT_DELTA)
        return differs && !endsWithTerminalPunctuation(text) && !DialogueSplitter.startsWithDialogueDash(text)
    }

    private fun fontChanged(a: PdfLine, b: PdfLine): Boolean {
        if (a.fontSize <= 0f || b.fontSize <= 0f) return false
        return maxOf(a.fontSize, b.fontSize) / minOf(a.fontSize, b.fontSize) > FONT_CHANGE_RATIO
    }

    private fun isDecorative(text: String) = text.none { it.isLetterOrDigit() }

    private fun toDocument(title: String, raw: List<Draft>, chapters: List<Chapter>): BookDocument {
        val chapterOfParagraph = IntArray(raw.size)
        chapters.forEachIndexed { c, chapter ->
            val end = chapters.getOrNull(c + 1)?.firstParagraph ?: raw.size
            for (p in chapter.firstParagraph until end) chapterOfParagraph[p] = c
        }

        val paragraphs = ArrayList<Paragraph>(raw.size)
        val sentences = ArrayList<BookSentence>()
        var offset = 0
        raw.forEachIndexed { p, r ->
            val text = r.text
            val segments = if (r.isHeading) headingSegments(text) else DialogueSplitter.split(text)
            paragraphs += Paragraph(text, chapterOfParagraph[p], r.isHeading, r.page, segments)
            for (segment in segments) {
                for (span in SentenceSplitter.sentenceSpans(text.substring(segment.start, segment.end))) {
                    val start = segment.start + span.start
                    val end = segment.start + span.end
                    val tts = ttsTextOf(text.substring(start, end))
                    if (tts.isEmpty()) continue
                    sentences += BookSentence(
                        index = sentences.size,
                        paragraphIndex = p,
                        startInParagraph = start,
                        endInParagraph = end,
                        kind = segment.kind,
                        genderHint = segment.genderHint,
                        sourcePage = r.pageAt(start),
                        charOffset = offset + start,
                        paragraphText = text,
                    )
                }
            }
            offset += text.length + 1
        }
        return BookDocument(title, chapters, paragraphs, sentences)
    }

    private fun headingSegments(text: String): List<Segment> {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start == -1) return emptyList()
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        return listOf(Segment(SegmentKind.NARRATION, start, end, GenderHint.UNKNOWN))
    }

    internal fun ttsTextOf(display: String): String =
        display.trim().trimStart(*TTS_TRIM_START).trimEnd(*TTS_TRIM_END).replace(WHITESPACE, " ").trim()

    // One text column: where it starts and where its lines end. A page in two columns has two of these, and
    // measuring a line against the whole page instead would call every line of the right column "indented"
    // and every line of the left one "short" — which is every paragraph rule at once, all wrong.
    private class Column(val left: Float, val right: Float)

    private class PageLayout(
        private val columns: Map<Int, List<Column>>,
        private val medianChars: Int,
        val lineSpacing: Float,
    ) {
        private fun columnOf(line: PdfLine): Column? {
            val page = columns[line.page % 2] ?: return null
            return page.lastOrNull { it.left <= line.x + INDENT_EM * line.fontSize.coerceAtLeast(1f) }
                ?: page.firstOrNull()
        }

        fun isIndented(line: PdfLine): Boolean {
            val column = columnOf(line) ?: return false
            return line.x > column.left + INDENT_EM * line.fontSize.coerceAtLeast(1f)
        }

        fun isCentered(line: PdfLine): Boolean {
            val column = columnOf(line) ?: return false
            val width = column.right - column.left
            if (width <= 0f || line.right <= 0f) return false
            val leftGap = line.x - column.left
            val rightGap = column.right - line.right
            return leftGap > CENTER_TOLERANCE * width && rightGap > CENTER_TOLERANCE * width &&
                abs(leftGap - rightGap) < CENTER_TOLERANCE * width
        }

        fun isShort(line: PdfLine): Boolean {
            val column = columnOf(line)
            if (line.right > 0f && column != null && column.right > column.left) {
                return column.right - line.right > SHORT_LINE_FRACTION * (column.right - column.left)
            }
            return medianChars > 0 && line.text.length < SHORT_LINE_CHAR_FRACTION * medianChars
        }

        companion object {
            fun of(lines: List<PdfLine>): PageLayout {
                // Books mirror their margins on facing pages, so geometry is measured per page parity.
                val byParity = lines.groupBy { it.page % 2 }
                val bodyFontSize = median(lines.map { it.fontSize }.filter { it > 0f })
                val spacings = lines.zipWithNext()
                    .filter { (a, b) -> a.page == b.page && b.y > a.y }
                    .map { (a, b) -> b.y - a.y }
                return PageLayout(
                    columns = byParity.mapValues { (_, ls) -> columnsOf(ls, bodyFontSize) },
                    medianChars = median(lines.map { it.text.length.toFloat() }).toInt(),
                    lineSpacing = median(spacings),
                )
            }

            // Lines start at the margin of their own column, so the left edges of a set of lines fall into
            // one cluster per column. A paragraph indent is about one em; a gutter is far wider, which is
            // what separates the two.
            private fun columnsOf(lines: List<PdfLine>, bodyFontSize: Float): List<Column> {
                val tolerance = maxOf(MIN_COLUMN_TOLERANCE, bodyFontSize * COLUMN_TOLERANCE_EM)
                val starts = lines.map { it.x }.sorted()
                val clusters = mutableListOf<MutableList<Float>>()
                for (x in starts) {
                    val last = clusters.lastOrNull()
                    if (last != null && x - last.last() <= tolerance) last += x else clusters += mutableListOf(x)
                }
                val real = clusters.filter { it.size >= MIN_COLUMN_LINES && it.size >= lines.size * MIN_COLUMN_SHARE }
                    .ifEmpty { listOf(starts.toMutableList()) }
                val edges = real.map { median(it) }
                return edges.mapIndexed { i, left ->
                    val next = edges.getOrNull(i + 1) ?: Float.MAX_VALUE
                    val own = lines.filter { it.x >= left - tolerance && it.x < next - tolerance }
                    val right = percentile(own.map { it.right }.filter { it > 0f }, 0.9f)
                    Column(left, right)
                }.filter { it.right > it.left }
            }
        }
    }
}

private fun median(values: List<Float>): Float = percentile(values, 0.5f)

// Nearest-rank, so pages with only a few lines still pick a real line's value instead of rounding down.
private fun percentile(values: List<Float>, fraction: Float): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    return sorted[(ceil(fraction * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
}
