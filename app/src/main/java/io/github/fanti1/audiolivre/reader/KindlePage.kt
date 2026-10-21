package io.github.fanti1.audiolivre.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import io.github.fanti1.audiolivre.book.BookDocument
import io.github.fanti1.audiolivre.book.BookSentence
import io.github.fanti1.audiolivre.data.HighlightStyle
import kotlin.math.roundToInt

// What the reader asks of the page besides drawing it.
class PageActions(
    val onTap: (x: Float, width: Float) -> Unit,
    val onLongPress: (position: Offset, sentenceIndex: Int) -> Unit,
    // The accessibility actions: what a long press or a tap at the edge does for someone using TalkBack.
    val onReadFrom: (sentenceIndex: Int) -> Unit,
    val onNextPage: () -> Unit,
    val onPreviousPage: () -> Unit,
)

@Composable
fun KindlePage(
    page: Page,
    book: BookDocument,
    spec: ReadingSpec,
    measurer: TextMeasurer,
    textColor: Color,
    highlightColor: Color,
    highlight: BookSentence?,
    highlightStyle: HighlightStyle,
    // Dims everything but the sentence being read, so the eye has one place to be.
    focusMode: Boolean,
    actions: PageActions,
    modifier: Modifier = Modifier,
) {
    val layouts = remember(page, spec, measurer) {
        page.slices.map { slice ->
            val paragraph = book.paragraphs[slice.paragraph]
            measureParagraph(measurer, paragraph.text, paragraph.isHeading, spec)
        }
    }

    // The gesture detector outlives recompositions (it restarts only when the page changes), so it must read
    // the latest callbacks: a stale one kept acting on the menu state from when the page first appeared.
    val currentActions by rememberUpdatedState(actions)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val left = (widthPx - spec.columnWidthPx) / 2f

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(page, layouts) {
                    detectTapGestures(
                        onTap = { currentActions.onTap(it.x, size.width.toFloat()) },
                        onLongPress = { position ->
                            sentenceAt(position, size.width.toFloat(), page, layouts, book, spec)?.let { currentActions.onLongPress(position, it) }
                        },
                    )
                },
        ) {
            page.slices.forEachIndexed { i, slice ->
                val layout = layouts[i]
                val lines = visibleLines(slice, layout) ?: return@forEachIndexed
                val lineTop = layout.getLineTop(lines.first)
                val lineBottom = layout.getLineBottom(lines.last)
                translate(left = left, top = slice.y - lineTop) {
                    // The paragraph layout holds all of its lines; only this page's lines may show.
                    clipRect(left = -left, top = lineTop, right = size.width - left, bottom = lineBottom) {
                        val range = highlight?.takeIf { it.paragraphIndex == slice.paragraph }?.let {
                            val length = layout.layoutInput.text.length
                            val start = it.startInParagraph.coerceIn(0, length)
                            start to it.endInParagraph.coerceIn(start, length)
                        }
                        if (range != null) drawHighlight(layout, range.first, range.second, highlightColor, highlightStyle)
                        when {
                            !focusMode || highlight == null -> drawText(layout, color = textColor)
                            range == null -> drawText(layout, color = textColor.copy(alpha = DIMMED))
                            else -> {
                                drawText(layout, color = textColor.copy(alpha = DIMMED))
                                clipPath(sentencePath(layout, range.first, range.second)) { drawText(layout, color = textColor) }
                            }
                        }
                    }
                }
            }
        }

        // For TalkBack, one item per paragraph on the page, in reading order, laid over the drawn text.
        page.slices.forEachIndexed { i, slice ->
            val layout = layouts[i]
            val lines = visibleLines(slice, layout) ?: return@forEachIndexed
            val paragraph = book.paragraphs[slice.paragraph]
            val start = layout.getLineStart(lines.first)
            val text = paragraph.text.substring(start, layout.getLineEnd(lines.last))
            val height = layout.getLineBottom(lines.last) - layout.getLineTop(lines.first)
            val sentence = book.sentenceAtOffset(book.paragraphStarts[slice.paragraph] + start)
            Box(
                modifier = Modifier
                    .offset { IntOffset(left.roundToInt(), slice.y.roundToInt()) }
                    .size(with(density) { spec.columnWidthPx.toDp() }, with(density) { height.toDp() })
                    .semantics {
                        contentDescription = text
                        if (paragraph.isHeading) heading()
                        customActions = listOf(
                            CustomAccessibilityAction("Ler a partir daqui") { currentActions.onReadFrom(sentence); true },
                            CustomAccessibilityAction("Próxima página") { currentActions.onNextPage(); true },
                            CustomAccessibilityAction("Página anterior") { currentActions.onPreviousPage(); true },
                        )
                    },
            )
        }
    }
}

private const val DIMMED = 0.32f

private fun DrawScope.drawHighlight(layout: TextLayoutResult, start: Int, end: Int, color: Color, style: HighlightStyle) {
    when (style) {
        HighlightStyle.SOFT -> drawPath(layout.getPathForRange(start, end), color)
        HighlightStyle.STRONG -> drawPath(layout.getPathForRange(start, end), color.copy(alpha = (color.alpha * 2.2f).coerceAtMost(1f)))
        // A bar under each line of the sentence: visible without telling colours apart.
        HighlightStyle.UNDERLINE -> {
            val thickness = layout.layoutInput.density.density * 3f
            val solid = color.copy(alpha = 1f)
            for (line in layout.getLineForOffset(start)..layout.getLineForOffset((end - 1).coerceAtLeast(start))) {
                val from = if (line == layout.getLineForOffset(start)) layout.getHorizontalPosition(start, true) else layout.getLineLeft(line)
                val to = if (end <= layout.getLineEnd(line)) layout.getHorizontalPosition(end, true) else layout.getLineRight(line)
                val bottom = layout.getLineBottom(line)
                drawRect(solid, topLeft = Offset(minOf(from, to), bottom - thickness), size = androidx.compose.ui.geometry.Size(kotlin.math.abs(to - from), thickness))
            }
        }
    }
}

// The area the sentence's lines take, line by line, to redraw it at full strength in focus mode.
private fun sentencePath(layout: TextLayoutResult, start: Int, end: Int): Path {
    val path = Path()
    val first = layout.getLineForOffset(start)
    val last = layout.getLineForOffset((end - 1).coerceAtLeast(start))
    for (line in first..last) {
        val from = if (line == first) layout.getHorizontalPosition(start, true) else layout.getLineLeft(line)
        val to = if (line == last) layout.getHorizontalPosition(end, true) else layout.getLineRight(line)
        path.addRect(Rect(minOf(from, to), layout.getLineTop(line), maxOf(from, to), layout.getLineBottom(line)))
    }
    return path
}

// Pages are drawn by re-measuring paragraphs; if the re-measure ever yields fewer lines than the page table
// expects (e.g. a font fallback differs), draw what exists instead of crashing on a missing line.
private fun visibleLines(slice: PageSlice, layout: TextLayoutResult): IntRange? {
    val last = minOf(slice.lastLine, layout.lineCount - 1)
    return if (slice.firstLine > last) null else slice.firstLine..last
}

private fun sentenceAt(
    position: Offset,
    width: Float,
    page: Page,
    layouts: List<TextLayoutResult>,
    book: BookDocument,
    spec: ReadingSpec,
): Int? {
    val left = (width - spec.columnWidthPx) / 2f
    page.slices.forEachIndexed { i, slice ->
        val layout = layouts[i]
        val lines = visibleLines(slice, layout) ?: return@forEachIndexed
        val lineTop = layout.getLineTop(lines.first)
        val height = layout.getLineBottom(lines.last) - lineTop
        if (position.y >= slice.y && position.y <= slice.y + height) {
            val offset = layout.getOffsetForPosition(Offset(position.x - left, position.y - slice.y + lineTop))
            return book.sentenceAtOffset(book.paragraphStarts[slice.paragraph] + offset)
        }
    }
    return null
}

fun firstSentenceOnPage(book: BookDocument, page: Page): Int {
    val index = book.sentenceAtOffset(page.startOffset)
    val sentence = book.sentences.getOrNull(index) ?: return 0
    if (sentence.charOffset < page.startOffset) {
        val next = book.sentences.getOrNull(index + 1)
        if (next != null && next.charOffset < page.endOffset) return index + 1
    }
    return index
}
