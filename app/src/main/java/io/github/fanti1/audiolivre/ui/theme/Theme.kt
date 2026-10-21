package io.github.fanti1.audiolivre.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

// Dark by default, as the app was born; light when the phone is set to light mode, for those who read better so.
@Composable
fun EbookTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) NightAmberColors else DayAmberColors,
        typography = AppTypography,
        content = content,
    )
}

// The system bar icons follow the app's theme: light icons on the dark one, dark icons on the light one.
fun ComponentActivity.enableEdgeToEdgeBars() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
    )
}
