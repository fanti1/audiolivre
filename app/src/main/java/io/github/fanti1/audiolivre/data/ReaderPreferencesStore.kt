package io.github.fanti1.audiolivre.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.readerPrefsDataStore by preferencesDataStore(name = "reader_preferences")

enum class ReaderTheme { LIGHT, DARK, SEPIA, HIGH_CONTRAST }

// Atkinson Hyperlegible was drawn for low vision, OpenDyslexic for dyslexia.
enum class ReadingFont { LITERATA, INTER, ATKINSON, OPENDYSLEXIC }

// How the sentence being read stands out: a soft or strong fill, or a bar under it that needs no colour vision.
enum class HighlightStyle { SOFT, STRONG, UNDERLINE }

enum class PageMargin(val dp: Int) { NARROW(12), NORMAL(24), WIDE(40) }

enum class TextAlignment { JUSTIFY, LEFT }

val FONT_SIZES_SP = listOf(14, 15, 16, 17, 18, 20, 22, 24, 27, 30, 34, 38, 44)
val LINE_SPACINGS = listOf(1.3f, 1.5f, 1.7f, 1.9f)

data class ReaderPreferences(
    // null = pick a size that suits the screen width.
    val fontSizeSp: Int? = null,
    val lineSpacing: Float = 1.5f,
    val margin: PageMargin = PageMargin.NORMAL,
    val alignment: TextAlignment = TextAlignment.JUSTIFY,
    val font: ReadingFont = ReadingFont.LITERATA,
    val theme: ReaderTheme = ReaderTheme.DARK,
    // -1f means "use system brightness"; 0f..1f is an explicit override.
    val brightnessOverride: Float = -1f,
    val highlightStyle: HighlightStyle = HighlightStyle.SOFT,
    val focusMode: Boolean = false,
    // Bigger play and skip buttons, for hands that don't hit small targets.
    val largeControls: Boolean = false,
)

class ReaderPreferencesStore(private val context: Context) {

    val preferences: Flow<ReaderPreferences> = context.readerPrefsDataStore.data.map { prefs ->
        ReaderPreferences(
            fontSizeSp = prefs[FONT_SIZE_KEY],
            lineSpacing = prefs[LINE_SPACING_KEY] ?: 1.5f,
            margin = enumOrNull<PageMargin>(prefs[MARGIN_KEY]) ?: PageMargin.NORMAL,
            alignment = enumOrNull<TextAlignment>(prefs[ALIGNMENT_KEY]) ?: TextAlignment.JUSTIFY,
            font = enumOrNull<ReadingFont>(prefs[FONT_KEY]) ?: ReadingFont.LITERATA,
            theme = enumOrNull<ReaderTheme>(prefs[THEME_KEY]) ?: ReaderTheme.DARK,
            brightnessOverride = prefs[BRIGHTNESS_KEY] ?: -1f,
            highlightStyle = enumOrNull<HighlightStyle>(prefs[HIGHLIGHT_KEY]) ?: HighlightStyle.SOFT,
            focusMode = prefs[FOCUS_KEY] ?: false,
            largeControls = prefs[LARGE_CONTROLS_KEY] ?: false,
        )
    }

    suspend fun setFontSize(sp: Int) = edit { it[FONT_SIZE_KEY] = sp }
    suspend fun setLineSpacing(value: Float) = edit { it[LINE_SPACING_KEY] = value }
    suspend fun setMargin(value: PageMargin) = edit { it[MARGIN_KEY] = value.name }
    suspend fun setAlignment(value: TextAlignment) = edit { it[ALIGNMENT_KEY] = value.name }
    suspend fun setFont(value: ReadingFont) = edit { it[FONT_KEY] = value.name }
    suspend fun setTheme(value: ReaderTheme) = edit { it[THEME_KEY] = value.name }
    suspend fun setBrightnessOverride(value: Float) = edit { it[BRIGHTNESS_KEY] = value }
    suspend fun setHighlightStyle(value: HighlightStyle) = edit { it[HIGHLIGHT_KEY] = value.name }
    suspend fun setFocusMode(value: Boolean) = edit { it[FOCUS_KEY] = value }
    suspend fun setLargeControls(value: Boolean) = edit { it[LARGE_CONTROLS_KEY] = value }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.readerPrefsDataStore.edit { block(it) }
    }

    private companion object {
        val FONT_SIZE_KEY = intPreferencesKey("font_size_sp")
        val LINE_SPACING_KEY = floatPreferencesKey("line_spacing")
        val MARGIN_KEY = stringPreferencesKey("margin")
        val ALIGNMENT_KEY = stringPreferencesKey("alignment")
        val FONT_KEY = stringPreferencesKey("font")
        val THEME_KEY = stringPreferencesKey("theme")
        val BRIGHTNESS_KEY = floatPreferencesKey("brightness_override")
        val HIGHLIGHT_KEY = stringPreferencesKey("highlight_style")
        val FOCUS_KEY = booleanPreferencesKey("focus_mode")
        val LARGE_CONTROLS_KEY = booleanPreferencesKey("large_controls")
    }
}

private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
