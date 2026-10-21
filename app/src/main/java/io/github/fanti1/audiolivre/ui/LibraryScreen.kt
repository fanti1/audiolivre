package io.github.fanti1.audiolivre.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fanti1.audiolivre.R
import io.github.fanti1.audiolivre.data.LibraryBook
import io.github.fanti1.audiolivre.formats.BookFormat
import io.github.fanti1.audiolivre.translate.TranslationStep
import io.github.fanti1.audiolivre.ui.theme.LiterataFamily

// What the shelf shows for one book: the stored data plus the cover, once it has been rendered.
data class LibraryEntry(
    val book: LibraryBook,
    val cover: ImageBitmap? = null,
    val available: Boolean = true,
    // Set while the book is being translated in the background.
    val translation: TranslationStep? = null,
) {
    val uri: String get() = book.uri
    val title: String get() = book.title
}

enum class BookAction { OPEN, REIMPORT, RESTART, REMOVE }

internal fun bookTitle(displayName: String) = BookFormat.stripExtension(displayName)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    entries: List<LibraryEntry>,
    snackbarHost: SnackbarHostState,
    onAdd: () -> Unit,
    onAction: (LibraryEntry, BookAction) -> Unit,
    onSettings: () -> Unit,
    onManual: () -> Unit,
    onAbout: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var confirmRemoval by remember { mutableStateOf<LibraryEntry?>(null) }
    var confirmReimport by remember { mutableStateOf<LibraryEntry?>(null) }
    val continueReading = entries.firstOrNull { it.book.started && it.book.progress < 1f && it.available }
    val gridState = rememberLazyGridState()

    // The grid keeps whatever item was first on screen in place. When "Continuar lendo" appears above it (back
    // from the reader, after the first page of a book), it would sit just out of view; at the top of the shelf,
    // the shelf follows it up.
    LaunchedEffect(continueReading?.uri) {
        if (continueReading != null && gridState.firstVisibleItemIndex <= 2) gridState.scrollToItem(0)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            LargeTopAppBar(
                title = { Text("Biblioteca", modifier = Modifier.semantics { heading() }) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(painterResource(R.drawable.ic_voice), contentDescription = "Voz")
                    }
                    IconButton(onClick = onManual) {
                        Icon(painterResource(R.drawable.ic_help), contentDescription = "Como usar")
                    }
                    IconButton(onClick = onAbout) {
                        Icon(painterResource(R.drawable.ic_info), contentDescription = "Sobre")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (entries.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text("Adicionar livro") },
                    // The button's label lives in an animated slot TalkBack doesn't read; name it outright.
                    modifier = Modifier.semantics { contentDescription = "Adicionar livro" },
                )
            }
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyLibrary(onAdd = onAdd, modifier = Modifier.padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 148.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    // Keeps the last row clear of the extended button.
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (continueReading != null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                        ContinueReading(continueReading, onClick = { onAction(continueReading, BookAction.OPEN) })
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                    Text(
                        "Todos os livros",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                items(entries, key = { it.uri }) { entry ->
                    BookCard(
                        entry = entry,
                        onOpen = { onAction(entry, BookAction.OPEN) },
                        onReimport = { confirmReimport = entry },
                        onRestart = { onAction(entry, BookAction.RESTART) },
                        onRemove = { confirmRemoval = entry },
                    )
                }
            }
        }
    }

    confirmRemoval?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmRemoval = null },
            title = { Text("Remover “${entry.title}”?") },
            text = {
                Text(
                    "Sai da biblioteca, junto com o texto já extraído e a posição de leitura. " +
                        "O arquivo do livro continua no seu celular.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoval = null
                    onAction(entry, BookAction.REMOVE)
                }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoval = null }) { Text("Cancelar") } },
        )
    }

    confirmReimport?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmReimport = null },
            title = { Text("Importar de novo?") },
            text = {
                Text(
                    "O texto é extraído do arquivo outra vez e as páginas são remontadas. Sua posição de leitura é " +
                        "mantida. Um livro traduzido é traduzido de novo, o que leva alguns minutos.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReimport = null
                    onAction(entry, BookAction.REIMPORT)
                }) { Text("Importar de novo") }
            },
            dismissButton = { TextButton(onClick = { confirmReimport = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ContinueReading(entry: LibraryEntry, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(entry, modifier = Modifier.width(62.dp))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Continuar lendo",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Progress(entry.book.progress)
                Spacer(Modifier.height(4.dp))
                Text(
                    progressLabel(entry.book),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                painterResource(R.drawable.ic_play),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    entry: LibraryEntry,
    onOpen: () -> Unit,
    onReimport: () -> Unit,
    onRestart: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val status = when {
        !entry.available -> "Arquivo não encontrado"
        entry.translation != null -> translationLabel(entry.translation)
        else -> progressLabel(entry.book)
    }
    // For TalkBack the cover, title, progress and menu are one item: "Dom Casmurro, 37% lido", opened with a
    // double tap, with the menu's choices as actions. The cover and its button keep working for touch.
    val actions = buildList {
        if (entry.available) add(CustomAccessibilityAction("Importar de novo") { onReimport(); true })
        if (entry.book.started) add(CustomAccessibilityAction("Recomeçar do início") { onRestart(); true })
        add(CustomAccessibilityAction("Remover da biblioteca") { onRemove(); true })
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "${entry.title}, $status"
                role = Role.Button
                onClick(label = "Abrir") { onOpen(); true }
                customActions = actions
            },
    ) {
        Box(modifier = Modifier.clearAndSetSemantics { }) {
            Cover(
                entry,
                modifier = Modifier
                    .fillMaxWidth()
                    // Long press opens the same menu as the corner button, which is easier to hit.
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = { menuOpen = true },
                        onClickLabel = "Abrir “${entry.title}”",
                        onLongClickLabel = "Opções",
                    ),
            )
            // 48 dp to touch (the minimum for a finger), drawn as a 32 dp circle so it doesn't cover the cover.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Opções") { menuOpen = true },
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
                    shape = CircleShape,
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.ic_more_vert),
                            contentDescription = "Opções de “${entry.title}”",
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuItem("Abrir", R.drawable.ic_play, enabled = entry.available) {
                        menuOpen = false
                        onOpen()
                    }
                    MenuItem("Importar de novo", R.drawable.ic_refresh, enabled = entry.available) {
                        menuOpen = false
                        onReimport()
                    }
                    MenuItem("Recomeçar do início", R.drawable.ic_restart, enabled = entry.book.started) {
                        menuOpen = false
                        onRestart()
                    }
                    MenuItem("Remover da biblioteca", R.drawable.ic_delete) {
                        menuOpen = false
                        onRemove()
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            entry.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            maxLines = 1,
        )
        if (entry.book.started && entry.available) {
            Spacer(Modifier.height(6.dp))
            Progress(entry.book.progress)
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: Int, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        enabled = enabled,
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

@Composable
private fun Progress(progress: Float) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        drawStopIndicator = {},
    )
}

@Composable
private fun Cover(entry: LibraryEntry, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .aspectRatio(0.72f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val cover = entry.cover
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (entry.available) {
            TypographicCover(entry.title)
        } else {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

// Cloth-bound colours for books that bring no picture (plain text, RTF, HTML): the same title always gets
// the same colour, so a shelf of them still tells books apart at a glance.
private val COVER_CLOTH = listOf(
    Color(0xFF7A2E2E), Color(0xFF2E4A7A), Color(0xFF2F5D46), Color(0xFF8A6A2B),
    Color(0xFF5B3A6B), Color(0xFF2D5F66), Color(0xFF3A3A3A), Color(0xFF7A4B2E),
)

@Composable
private fun TypographicCover(title: String) {
    val cloth = COVER_CLOTH[Math.floorMod(title.hashCode(), COVER_CLOTH.size)]
    val ink = Color(0xFFF3E6CC)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(cloth)
            .padding(5.dp)
            .border(0.8.dp, ink.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Sized from the cover's width so a title reads the same on the small list cover and the big one.
        val size = (maxWidth.value / 7.5f).coerceIn(8f, 22f).sp
        Text(
            title,
            color = ink,
            fontFamily = LiterataFamily,
            fontSize = size,
            lineHeight = size * 1.15f,
            textAlign = TextAlign.Center,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun translationLabel(step: TranslationStep): String = when (step) {
    is TranslationStep.Translating -> "Traduzindo… ${step.percent}%"
    is TranslationStep.Downloading -> "Baixando o tradutor…"
    else -> "Preparando a tradução…"
}

private fun progressLabel(book: LibraryBook): String = when {
    !book.started -> "Não começado"
    book.progress >= 0.995f -> "Concluído"
    book.totalChars > 0 -> "${(book.progress * 100).toInt()}% lido"
    else -> "Em leitura"
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_menu_book),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("Sua biblioteca está vazia", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Adicione um livro (PDF, EPUB, Kindle, Word, TXT…) e o app lê pra você em voz alta, mesmo sem internet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Adicionar livro")
        }
    }
}
