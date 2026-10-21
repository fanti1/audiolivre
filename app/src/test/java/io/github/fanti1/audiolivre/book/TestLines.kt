package io.github.fanti1.audiolivre.book

// Geometry mimics a typical PDF text block: left margin at 72pt, right edge at 500pt, 11pt body text,
// baselines 14pt apart. "short" lines (a paragraph's last line) end well before the right edge.
const val LEFT = 72f
const val FULL = 500f
const val SHORT = 300f
const val BODY = 11f
const val SPACING = 14f

fun line(
    page: Int,
    text: String,
    y: Float,
    x: Float = LEFT,
    right: Float = FULL,
    size: Float = BODY,
    paragraphStart: Boolean = false,
) = PdfLine(page, text, x, right, y, size * 0.7f, size, paragraphStart)

// Builds consecutive lines on one page from (text, right edge) pairs, starting at the given baseline.
fun pageLines(page: Int, vararg texts: Pair<String, Float>, startY: Float = 100f): List<PdfLine> =
    texts.mapIndexed { i, (text, right) -> line(page, text, startY + i * SPACING, right = right) }

fun build(lines: List<PdfLine>, outline: List<OutlineEntry> = emptyList(), title: String = "Livro Teste") =
    BookBuilder.build(lines, outline, pageCount = (lines.maxOfOrNull { it.page } ?: 0) + 1, title = title)

fun BookDocument.displayTexts(): List<String> =
    sentences.map { paragraphs[it.paragraphIndex].text.substring(it.startInParagraph, it.endInParagraph) }
