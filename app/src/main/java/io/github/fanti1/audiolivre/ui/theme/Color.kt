package io.github.fanti1.audiolivre.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val NightBackground = Color(0xFF121110)
val NightSurfaceLow = Color(0xFF171614)
val NightSurface = Color(0xFF1B1A17)
val NightSurfaceHigh = Color(0xFF26241F)
val NightSurfaceHighest = Color(0xFF302D27)

val Amber = Color(0xFFF0B45A)
val OnAmber = Color(0xFF2A1B00)
val AmberContainer = Color(0xFF4A3510)
val OnAmberContainer = Color(0xFFFFDDA8)

val Ink = Color(0xFFEDE6DA)
val InkMuted = Color(0xFFB5AC9E)
val Outline = Color(0xFF4A443B)
val OutlineVariant = Color(0xFF34302A)

val NightAmberColors = darkColorScheme(
    primary = Amber,
    onPrimary = OnAmber,
    primaryContainer = AmberContainer,
    onPrimaryContainer = OnAmberContainer,
    inversePrimary = Color(0xFF7A5510),
    secondary = Color(0xFFD9C29A),
    onSecondary = Color(0xFF3B2F17),
    secondaryContainer = Color(0xFF3A3326),
    onSecondaryContainer = Color(0xFFF1E0BE),
    tertiary = Color(0xFFB9CDA8),
    onTertiary = Color(0xFF253419),
    background = NightBackground,
    onBackground = Ink,
    surface = NightBackground,
    onSurface = Ink,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = InkMuted,
    surfaceTint = Amber,
    inverseSurface = Ink,
    inverseOnSurface = NightBackground,
    error = Color(0xFFFFB4A9),
    onError = Color(0xFF690002),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD4),
    outline = Outline,
    outlineVariant = OutlineVariant,
    scrim = Color.Black,
    surfaceBright = NightSurfaceHighest,
    surfaceDim = NightBackground,
    surfaceContainerLowest = Color(0xFF0D0C0B),
    surfaceContainerLow = NightSurfaceLow,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceHigh,
    surfaceContainerHighest = NightSurfaceHighest,
)

// The same amber and paper, for people who read better on a light screen (the phone set to light mode).
// The amber darkens to keep 4.5:1 on paper.
val DayAmberColors = lightColorScheme(
    primary = Color(0xFF855400),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB0),
    onPrimaryContainer = Color(0xFF2A1800),
    inversePrimary = Amber,
    secondary = Color(0xFF6B5A3E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E1C2),
    onSecondaryContainer = Color(0xFF251A06),
    tertiary = Color(0xFF4D6440),
    onTertiary = Color.White,
    background = Color(0xFFFBF8F2),
    onBackground = Color(0xFF1D1B18),
    surface = Color(0xFFFBF8F2),
    onSurface = Color(0xFF1D1B18),
    surfaceVariant = Color(0xFFECE6DB),
    onSurfaceVariant = Color(0xFF4F483E),
    surfaceTint = Color(0xFF855400),
    inverseSurface = Color(0xFF33302B),
    inverseOnSurface = Color(0xFFF6F0E7),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF837B70),
    outlineVariant = Color(0xFFD5CCBF),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE2DCD2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F3EC),
    surfaceContainer = Color(0xFFF2EDE4),
    surfaceContainerHigh = Color(0xFFECE6DB),
    surfaceContainerHighest = Color(0xFFE5DED2),
)
