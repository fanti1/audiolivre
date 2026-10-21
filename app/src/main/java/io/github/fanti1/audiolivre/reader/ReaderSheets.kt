package io.github.fanti1.audiolivre.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fanti1.audiolivre.book.BookDocument
import io.github.fanti1.audiolivre.data.FONT_SIZES_SP
import io.github.fanti1.audiolivre.data.HighlightStyle
import io.github.fanti1.audiolivre.data.LINE_SPACINGS
import io.github.fanti1.audiolivre.data.PageMargin
import io.github.fanti1.audiolivre.data.ReaderPreferences
import io.github.fanti1.audiolivre.data.ReaderPreferencesStore
import io.github.fanti1.audiolivre.data.ReaderTheme
import io.github.fanti1.audiolivre.data.ReadingFont
import io.github.fanti1.audiolivre.data.TextAlignment
import io.github.fanti1.audiolivre.ui.theme.AtkinsonFamily
import io.github.fanti1.audiolivre.ui.theme.InterFamily
import io.github.fanti1.audiolivre.ui.theme.OpenDyslexicFamily
import io.github.fanti1.audiolivre.ui.theme.LiterataFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSheet(
    prefs: ReaderPreferences,
    fontSize: Int,
    onDismiss: () -> Unit,
    store: ReaderPreferencesStore,
    scope: CoroutineScope,
) {
    fun update(block: suspend ReaderPreferencesStore.() -> Unit) {
        scope.launch { store.block() }
    }

    val sizeIndex = FONT_SIZES_SP.indices.minBy { abs(FONT_SIZES_SP[it] - fontSize) }
    val spacing = LINE_SPACINGS.minBy { abs(it - prefs.lineSpacing) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Text("Aparência", style = MaterialTheme.typography.titleLarge)

            SectionLabel("Fonte")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FontChoice("Literata", LiterataFamily, prefs.font == ReadingFont.LITERATA, Modifier.weight(1f)) {
                    update { setFont(ReadingFont.LITERATA) }
                }
                FontChoice("Inter", InterFamily, prefs.font == ReadingFont.INTER, Modifier.weight(1f)) {
                    update { setFont(ReadingFont.INTER) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                FontChoice("Atkinson", AtkinsonFamily, prefs.font == ReadingFont.ATKINSON, Modifier.weight(1f), hint = "para baixa visão") {
                    update { setFont(ReadingFont.ATKINSON) }
                }
                FontChoice("OpenDyslexic", OpenDyslexicFamily, prefs.font == ReadingFont.OPENDYSLEXIC, Modifier.weight(1f), hint = "para dislexia") {
                    update { setFont(ReadingFont.OPENDYSLEXIC) }
                }
            }

            SectionLabel("Tamanho do texto")
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = { update { setFontSize(FONT_SIZES_SP[sizeIndex - 1]) } },
                    enabled = sizeIndex > 0,
                ) { Text("A", fontFamily = LiterataFamily, fontSize = 15.sp) }
                Text(
                    "${FONT_SIZES_SP[sizeIndex]}",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                )
                FilledTonalButton(
                    onClick = { update { setFontSize(FONT_SIZES_SP[sizeIndex + 1]) } },
                    enabled = sizeIndex < FONT_SIZES_SP.lastIndex,
                ) { Text("A", fontFamily = LiterataFamily, fontSize = 22.sp) }
            }

            SectionLabel("Espaço entre linhas")
            Segmented(
                options = LINE_SPACINGS.zip(listOf("Compacto", "Normal", "Amplo", "Extra")),
                selected = spacing,
                onSelect = { update { setLineSpacing(it) } },
            )

            SectionLabel("Margens")
            Segmented(
                options = listOf(PageMargin.NARROW to "Estreitas", PageMargin.NORMAL to "Normais", PageMargin.WIDE to "Largas"),
                selected = prefs.margin,
                onSelect = { update { setMargin(it) } },
            )

            SectionLabel("Alinhamento")
            Segmented(
                options = listOf(TextAlignment.JUSTIFY to "Justificado", TextAlignment.LEFT to "À esquerda"),
                selected = prefs.alignment,
                onSelect = { update { setAlignment(it) } },
            )

            SectionLabel("Tema da página")
            Segmented(
                options = listOf(
                    ReaderTheme.DARK to "Escuro",
                    ReaderTheme.SEPIA to "Sépia",
                    ReaderTheme.LIGHT to "Claro",
                    ReaderTheme.HIGH_CONTRAST to "Contraste",
                ),
                selected = prefs.theme,
                onSelect = { update { setTheme(it) } },
            )

            SectionLabel("Destaque da frase lida")
            Segmented(
                options = listOf(HighlightStyle.SOFT to "Suave", HighlightStyle.STRONG to "Forte", HighlightStyle.UNDERLINE to "Sublinhado"),
                selected = prefs.highlightStyle,
                onSelect = { update { setHighlightStyle(it) } },
            )
            ToggleRow(
                title = "Modo foco",
                description = "Escurece o resto da página enquanto lê, deixando só a frase da vez.",
                checked = prefs.focusMode,
                onChange = { value -> update { setFocusMode(value) } },
            )
            ToggleRow(
                title = "Controles grandes",
                description = "Botões de tocar e pular frase maiores, mais fáceis de acertar.",
                checked = prefs.largeControls,
                onChange = { value -> update { setLargeControls(value) } },
            )

            SectionLabel("Brilho")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Usar o brilho do sistema", modifier = Modifier.weight(1f))
                Switch(
                    checked = prefs.brightnessOverride < 0f,
                    onCheckedChange = { useSystem -> update { setBrightnessOverride(if (useSystem) -1f else 0.5f) } },
                )
            }
            if (prefs.brightnessOverride >= 0f) {
                Slider(
                    value = prefs.brightnessOverride,
                    onValueChange = { value -> update { setBrightnessOverride(value) } },
                    valueRange = 0.02f..1f,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableOfContentsSheet(
    book: BookDocument,
    pages: List<Page>,
    currentChapter: Int,
    onDismiss: () -> Unit,
    onSelect: (page: Int) -> Unit,
) {
    val firstPageOfChapter = remember(pages) {
        val result = mutableMapOf<Int, Int>()
        pages.forEachIndexed { i, page -> result.putIfAbsent(page.chapter, i) }
        result
    }
    val chapters = book.chapters.indices.filter { it in firstPageOfChapter }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Sumário",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        if (chapters.size <= 1) {
            Text(
                "Este livro não tem capítulos marcados.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(chapters) { c ->
                    val current = c == currentChapter
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(firstPageOfChapter.getValue(c)) }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            book.chapters[c].title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${firstPageOfChapter.getValue(c) + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp),
    )
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
            ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun FontChoice(
    name: String,
    family: FontFamily,
    selected: Boolean,
    modifier: Modifier = Modifier,
    hint: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Aa", style = TextStyle(fontFamily = family, fontSize = 26.sp))
            Text(name, style = MaterialTheme.typography.labelLarge)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
