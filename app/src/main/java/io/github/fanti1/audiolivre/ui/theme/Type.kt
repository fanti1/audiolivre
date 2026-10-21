package io.github.fanti1.audiolivre.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.fanti1.audiolivre.R

// Both fonts ship as a single variable TTF; each declared weight must also set the matching
// `wght` axis value, otherwise every weight renders as the file's default instance.
@OptIn(ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: FontWeight) =
    Font(resId, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

private val declaredWeights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

val InterFamily = FontFamily(declaredWeights.map { variableFont(R.font.inter, it) })
val LiterataFamily = FontFamily(declaredWeights.map { variableFont(R.font.literata, it) })
val AtkinsonFamily = FontFamily(Font(R.font.atkinson_regular, FontWeight.Normal), Font(R.font.atkinson_bold, FontWeight.Bold))
val OpenDyslexicFamily = FontFamily(Font(R.font.opendyslexic_regular, FontWeight.Normal), Font(R.font.opendyslexic_bold, FontWeight.Bold))

private val base = Typography()

private fun TextStyle.inter(weight: FontWeight? = null) =
    copy(fontFamily = InterFamily, fontWeight = weight ?: fontWeight)

val AppTypography = Typography(
    displayLarge = base.displayLarge.inter(),
    displayMedium = base.displayMedium.inter(),
    displaySmall = base.displaySmall.inter(),
    headlineLarge = base.headlineLarge.inter(FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.inter(FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.inter(FontWeight.SemiBold),
    titleLarge = base.titleLarge.inter(FontWeight.SemiBold),
    titleMedium = base.titleMedium.inter(FontWeight.SemiBold),
    titleSmall = base.titleSmall.inter(FontWeight.Medium),
    bodyLarge = base.bodyLarge.inter(),
    bodyMedium = base.bodyMedium.inter(),
    bodySmall = base.bodySmall.inter(),
    labelLarge = base.labelLarge.inter(FontWeight.SemiBold),
    labelMedium = base.labelMedium.inter(FontWeight.Medium),
    labelSmall = base.labelSmall.inter(FontWeight.Medium),
)
