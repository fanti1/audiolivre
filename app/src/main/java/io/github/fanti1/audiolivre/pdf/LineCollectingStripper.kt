package io.github.fanti1.audiolivre.pdf

import io.github.fanti1.audiolivre.book.PdfLine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.max
import kotlin.math.roundToInt

// PDFTextStripper calls writeString once per word and signals line/paragraph boundaries through the
// write* hooks below; overriding them (instead of parsing the output text) keeps each line's geometry,
// which BookBuilder needs to detect indents, short last lines and vertical gaps.
//
// It also groups every glyph sharing a baseline into one line. On a page set in columns that is wrong: the
// first line of the left column and the first line of the right column sit at the same height, so they are
// handed over as a single line and every sentence ends up spliced to a stranger. Pages are therefore held
// until the end, where a gutter running down the page is found and the lines crossing it are cut in two.
internal class LineCollectingStripper(
    private val onPage: (pageNumber: Int, pageCount: Int) -> Unit,
) : PDFTextStripper() {

    private class Piece(val text: String, val positions: List<TextPosition>) {
        val left get() = positions.first().xDirAdj
        val right get() = positions.last().let { it.xDirAdj + it.widthDirAdj }
    }

    private class PendingLine(val pieces: List<Piece>, val paragraphStart: Boolean) {
        // PdfBox measures y downward from the top of the page, so the smallest y is the topmost line.
        val top get() = pieces.minOf { piece -> piece.positions.minOf { it.yDirAdj } }
    }

    private val lines = mutableListOf<PdfLine>()
    private val pieces = mutableListOf<Piece>()
    private val pending = mutableListOf<PendingLine>()
    private val lineBuffer = StringBuilder()
    private val positions = mutableListOf<TextPosition>()
    private var nextLineStartsParagraph = true
    private var pageCount = 0

    init {
        // Reads top-to-bottom, then left-to-right: fixes PDFs whose content stream is out of order.
        sortByPosition = true
        // PdfBox measures the vertical gap against glyph height, not line spacing: ordinary leading is
        // already ~2x glyph height, so a low threshold turned almost every line into a new paragraph.
        // BookBuilder detects real gaps against the page's median line spacing instead.
        dropThreshold = 10f
    }

    fun collect(document: PDDocument): List<PdfLine> {
        pageCount = document.numberOfPages
        getText(document)
        return lines
    }

    override fun startPage(page: PDPage) {
        super.startPage(page)
        onPage(currentPageNo, pageCount)
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        if (textPositions.isEmpty()) return
        lineBuffer.append(text)
        positions += textPositions
        pieces += Piece(text, textPositions.toList())
    }

    override fun writeWordSeparator() {
        lineBuffer.append(' ')
    }

    override fun writeLineSeparator() = flushLine()

    override fun writeParagraphStart() {
        flushLine()
        nextLineStartsParagraph = true
    }

    override fun writeParagraphEnd() = flushLine()

    override fun writePageStart() {
        nextLineStartsParagraph = true
    }

    override fun writePageEnd() {
        flushLine()
        flushPage()
    }

    private fun flushLine() {
        if (lineBuffer.toString().isNotBlank() && pieces.isNotEmpty()) {
            pending += PendingLine(pieces.toList(), nextLineStartsParagraph)
            nextLineStartsParagraph = false
        }
        lineBuffer.setLength(0)
        positions.clear()
        pieces.clear()
    }

    // Emits the page's lines. On a page set in columns they are first cut at the gutter and then handed over
    // column by column, which is the order a person reads them in — not the order they sit at on the sheet.
    private fun flushPage() {
        if (pending.isEmpty()) return
        val gutter = findGutter(pending)
        if (gutter == null) {
            splitDropCaps(pending).forEach { emit(it.pieces, it.paragraphStart) }
            pending.clear()
            return
        }

        val parts = mutableListOf<PendingLine>()
        for (line in splitDropCaps(pending)) {
            val cut = line.pieces.indices.drop(1).firstOrNull { i ->
                line.pieces[i - 1].right <= gutter && line.pieces[i].left >= gutter
            }
            if (cut == null) {
                parts += line
            } else {
                parts += PendingLine(line.pieces.subList(0, cut), line.paragraphStart)
                parts += PendingLine(line.pieces.subList(cut, line.pieces.size), false)
            }
        }

        val left = mutableListOf<PendingLine>()
        val right = mutableListOf<PendingLine>()
        fun drain() {
            left.forEach { emit(it.pieces, it.paragraphStart) }
            right.forEach { emit(it.pieces, it.paragraphStart) }
            left.clear()
            right.clear()
        }
        for (part in parts.sortedBy { it.top }) {
            when {
                // A title or a wide figure spans the gutter and divides the page into bands; the columns
                // start over beneath it.
                part.pieces.first().left < gutter && part.pieces.last().right > gutter -> {
                    drain()
                    emit(part.pieces, part.paragraphStart)
                }
                part.pieces.first().left >= gutter -> right += part
                else -> left += part
            }
        }
        drain()
        pending.clear()
    }

    // A chapter's large initial letter sits low enough that PdfBox files it with the line beside it, which
    // hides it from the page assembler: the chapter then opens on "arina chegou" with a stray M further in.
    // Cutting it loose here lets DropCap put it back where it belongs.
    private fun splitDropCaps(page: List<PendingLine>): List<PendingLine> {
        val body = dominantFontSize(page.flatMap { line -> line.pieces.flatMap { it.positions } })
        if (body <= 0f) return page
        return page.flatMap { line ->
            val first = line.pieces.first()
            val isCap = first.positions.size == 1 &&
                first.text.trim().length == 1 &&
                first.text.trim().first().isLetter() &&
                first.positions.first().fontSizeInPt >= body * DROP_CAP_RATIO
            if (!isCap || line.pieces.size < 2) {
                listOf(line)
            } else {
                listOf(
                    PendingLine(line.pieces.subList(0, 1), line.paragraphStart),
                    PendingLine(line.pieces.subList(1, line.pieces.size), false),
                )
            }
        }
    }

    private fun emit(pieces: List<Piece>, paragraphStart: Boolean) {
        val text = buildString {
            for ((i, piece) in pieces.withIndex()) {
                // A gap wider than a space is where PDFBox would have written a word separator.
                if (i > 0 && pieces[i - 1].right < piece.left - spaceWidth(piece)) append(' ')
                append(piece.text)
            }
        }.trim()
        if (text.isEmpty()) return
        val all = pieces.flatMap { it.positions }
        lines += PdfLine(
            page = currentPageNo - 1,
            text = text,
            x = pieces.first().left,
            right = pieces.last().right,
            y = all.maxOf { it.yDirAdj },
            height = all.maxOf { it.heightDir },
            fontSize = dominantFontSize(all),
            paragraphStart = paragraphStart,
            bold = all.count { isBold(it) } * 2 > all.size,
        )
    }

    private fun spaceWidth(piece: Piece) = max(piece.positions.first().widthOfSpace, 1f) * 0.5f

    // A gutter is a vertical band that several lines leave empty at the same place. One wide gap is a tab
    // or a table; a column break repeats down the page, which is what makes it safe to cut there.
    private fun findGutter(page: List<PendingLine>): Float? {
        val fontSize = dominantFontSize(page.flatMap { line -> line.pieces.flatMap { it.positions } })
        val minimum = max(MIN_GUTTER_POINTS, fontSize * MIN_GUTTER_EMS)
        val gaps = page.flatMap { line ->
            line.pieces.indices.drop(1).mapNotNull { i ->
                val from = line.pieces[i - 1].right
                val to = line.pieces[i].left
                if (to - from >= minimum) from to to else null
            }
        }
        if (gaps.size < MIN_GUTTER_LINES || gaps.size < page.size * MIN_GUTTER_FRACTION) return null
        // The gutter is the x that the largest number of those gaps have in common.
        val best = gaps.map { (from, to) -> (from + to) / 2f }
            .maxByOrNull { candidate -> gaps.count { it.first <= candidate && candidate <= it.second } }
            ?: return null
        val shared = gaps.count { it.first <= best && best <= it.second }
        return if (shared >= MIN_GUTTER_LINES && shared >= page.size * MIN_GUTTER_FRACTION) best else null
    }

    // Font names carry the weight ("Times-Bold", "Arial,Bold", "AAAAAA+Roboto-Medium").
    private fun isBold(position: TextPosition): Boolean {
        val name = runCatching { position.font?.name }.getOrNull() ?: return false
        return name.contains("bold", ignoreCase = true) || name.contains("black", ignoreCase = true)
    }

    // The most common glyph size, so a drop cap or a superscript footnote marker doesn't
    // turn a body line into a "heading".
    private fun dominantFontSize(positions: List<TextPosition>): Float =
        positions.groupingBy { (it.fontSizeInPt * 2).roundToInt() }.eachCount()
            .maxByOrNull { it.value }?.key?.div(2f) ?: 0f

    private companion object {
        const val MIN_GUTTER_POINTS = 8f
        const val MIN_GUTTER_EMS = 1.2f
        const val MIN_GUTTER_LINES = 4
        const val MIN_GUTTER_FRACTION = 0.2
        const val DROP_CAP_RATIO = 1.8f
    }
}
