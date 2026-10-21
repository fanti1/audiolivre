package io.github.fanti1.audiolivre.reader

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.fanti1.audiolivre.data.ReadingFont
import io.github.fanti1.audiolivre.data.TextAlignment
import io.github.fanti1.audiolivre.ui.theme.AtkinsonFamily
import io.github.fanti1.audiolivre.ui.theme.InterFamily
import io.github.fanti1.audiolivre.ui.theme.OpenDyslexicFamily
import io.github.fanti1.audiolivre.ui.theme.LiterataFamily

// Bump whenever anything that affects line breaks or page filling changes, so cached page tables are rebuilt.
private const val PAGINATION_VERSION = 3
private const val HEADING_SCALE = 1.4f
private const val PARAGRAPH_INDENT_EM = 1.5f
const val COLUMN_MAX_EM = 34

data class ReadingSpec(
    val columnWidthPx: Int,
    val pageHeightPx: Int,
    val density: Float,
    val fontScale: Float,
    val fontSizeSp: Int,
    val lineSpacing: Float,
    val font: ReadingFont,
    val alignment: TextAlignment,
) {
    private val lineHeightPx: Float get() = fontSizeSp * fontScale * density * lineSpacing

    val metrics: PageMetrics
        get() = PageMetrics(
            pageHeight = pageHeightPx.toFloat(),
            chapterTopSpace = pageHeightPx * 0.14f,
            paragraphGap = 0f,
            headingGap = lineHeightPx,
        )

    fun textStyle(isHeading: Boolean): TextStyle = TextStyle(
        fontFamily = readingFamily(font),
        fontSize = if (isHeading) (fontSizeSp * HEADING_SCALE).sp else fontSizeSp.sp,
        fontWeight = if (isHeading) FontWeight.SemiBold else FontWeight.Normal,
        lineHeight = lineSpacing.em,
        textAlign = when {
            isHeading -> TextAlign.Center
            alignment == TextAlignment.JUSTIFY -> TextAlign.Justify
            else -> TextAlign.Start
        },
        textIndent = if (isHeading) TextIndent.None else TextIndent(firstLine = PARAGRAPH_INDENT_EM.em),
        // Without an explicit pt-BR locale, a phone set to English would hyphenate with English patterns.
        localeList = LocaleList("pt-BR"),
        hyphens = Hyphens.Auto,
        lineBreak = LineBreak.Paragraph,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    fun cacheKey(): String = listOf(
        "v$PAGINATION_VERSION", columnWidthPx, pageHeightPx, density, fontScale, fontSizeSp, lineSpacing, font, alignment,
    ).joinToString("|")
}

fun readingFamily(font: ReadingFont): FontFamily = when (font) {
    ReadingFont.LITERATA -> LiterataFamily
    ReadingFont.INTER -> InterFamily
    ReadingFont.ATKINSON -> AtkinsonFamily
    ReadingFont.OPENDYSLEXIC -> OpenDyslexicFamily
}
