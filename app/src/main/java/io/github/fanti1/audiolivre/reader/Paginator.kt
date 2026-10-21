package io.github.fanti1.audiolivre.reader

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.fanti1.audiolivre.book.BookDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class Paginator(private val context: Context) {

    // One resolver for every measurer: each new one would load and cache the font files again, once per chapter
    // of the book being paginated (the resolver is safe to share between threads).
    private val fonts by lazy { createFontFamilyResolver(context) }

    // Pages are drawn by re-measuring whole paragraphs with an identically configured measurer, so the
    // line breaks match the ones the page table was built from.
    fun newMeasurer(cacheSize: Int = 0): TextMeasurer =
        TextMeasurer(fonts, Density(context), LayoutDirection.Ltr, cacheSize)

    suspend fun paginate(
        book: BookDocument,
        spec: ReadingSpec,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<Page> = withContext(Dispatchers.Default) {
        val ranges = chapterRanges(book)
        val done = AtomicInteger()
        // Chapters always start on a new page, so each one paginates independently and in parallel.
        ranges.mapIndexed { chapter, range ->
            async {
                val measurer = newMeasurer()
                val lines = range.map { p ->
                    ensureActive()
                    measureLines(measurer, book, p, spec)
                }
                PageBuilder.build(chapter, lines, book.paragraphStarts, spec.metrics)
                    .also { onProgress(done.incrementAndGet(), ranges.size) }
            }
        }.awaitAll().flatten()
    }

    private fun measureLines(measurer: TextMeasurer, book: BookDocument, p: Int, spec: ReadingSpec): ParagraphLines {
        val paragraph = book.paragraphs[p]
        if (paragraph.text.isBlank()) return ParagraphLines(p, paragraph.isHeading, FloatArray(0), IntArray(0), IntArray(0))
        val layout = measureParagraph(measurer, paragraph.text, paragraph.isHeading, spec)
        val n = layout.lineCount
        return ParagraphLines(
            paragraph = p,
            isHeading = paragraph.isHeading,
            lineHeights = FloatArray(n) { layout.getLineBottom(it) - layout.getLineTop(it) },
            lineStarts = IntArray(n) { layout.getLineStart(it) },
            lineEnds = IntArray(n) { layout.getLineEnd(it) },
        )
    }
}

// A fixed width (not just a max) so short centered headings are centered in the column: with only a max,
// Compose shrinks the layout to the text's own width and centering has no visible effect.
fun measureParagraph(measurer: TextMeasurer, text: String, isHeading: Boolean, spec: ReadingSpec): TextLayoutResult =
    measurer.measure(
        AnnotatedString(text),
        style = spec.textStyle(isHeading),
        constraints = Constraints.fixedWidth(spec.columnWidthPx),
    )

// Front matter before the first detected chapter is folded into chapter 0 so no paragraph is left out.
fun chapterRanges(book: BookDocument): List<IntRange> {
    if (book.chapters.isEmpty()) return listOf(book.paragraphs.indices)
    return book.chapters.indices.map { c ->
        val start = if (c == 0) 0 else book.chapters[c].firstParagraph
        val end = book.chapters.getOrNull(c + 1)?.firstParagraph ?: book.paragraphs.size
        start until end
    }
}
