package io.github.fanti1.audiolivre.reader

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.fanti1.audiolivre.R
import io.github.fanti1.audiolivre.book.BookDocument
import io.github.fanti1.audiolivre.data.CrashLog
import io.github.fanti1.audiolivre.data.ReaderPreferences
import io.github.fanti1.audiolivre.data.ReaderPreferencesStore
import io.github.fanti1.audiolivre.data.ReaderTheme
import io.github.fanti1.audiolivre.service.SleepTimer
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.content.Context
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.DisposableEffect
import io.github.fanti1.audiolivre.data.VoiceSettings
import io.github.fanti1.audiolivre.data.VoiceSettingsStore
import io.github.fanti1.audiolivre.playback.PlaybackState
import io.github.fanti1.audiolivre.service.PlaybackService
import io.github.fanti1.audiolivre.ui.ErrorReportDialog
import io.github.fanti1.audiolivre.ui.theme.Amber
import io.github.fanti1.audiolivre.ui.theme.Ink
import io.github.fanti1.audiolivre.ui.theme.InterFamily
import io.github.fanti1.audiolivre.ui.theme.NightBackground
import io.github.fanti1.audiolivre.ui.theme.NightSurface
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val HEADER_HEIGHT = 32.dp
private val FOOTER_HEIGHT = 32.dp
private const val WORDS_PER_MINUTE = 150

data class ReadingColors(val background: Color, val chrome: Color, val text: Color, val highlight: Color)

fun readingColors(theme: ReaderTheme): ReadingColors = when (theme) {
    ReaderTheme.DARK -> ReadingColors(NightBackground, NightSurface, Ink, Amber.copy(alpha = 0.24f))
    ReaderTheme.SEPIA -> ReadingColors(Color(0xFFF4ECD8), Color(0xFFE9DDC3), Color(0xFF5B4636), Color(0xFFD9A441).copy(alpha = 0.36f))
    ReaderTheme.LIGHT -> ReadingColors(Color(0xFFFAF8F4), Color(0xFFEEEAE3), Color(0xFF1C1B19), Amber.copy(alpha = 0.40f))
    // Pure white on black, and an opaque blue behind the sentence read: white on it still reads at 7:1.
    ReaderTheme.HIGH_CONTRAST -> ReadingColors(Color.Black, Color(0xFF141414), Color.White, Color(0xFF1F4FFF))
}

fun defaultFontSize(widthDp: Dp): Int = when {
    widthDp < 600.dp -> 18
    widthDp < 840.dp -> 20
    else -> 22
}

private class PagedBook(val layoutKey: String, val spec: ReadingSpec, val pages: List<Page>)

@Composable
fun KindleReaderScreen(
    book: BookDocument,
    bookKey: String,
    service: PlaybackService,
    preferencesStore: ReaderPreferencesStore,
    onBack: () -> Unit,
    // Hands the reader's keyboard shortcuts to the activity, which receives the key presses.
    onKeys: (((Int) -> Boolean)?) -> Unit = {},
) {
    val prefs by preferencesStore.preferences.collectAsState(initial = null)
    val colors = readingColors(prefs?.theme ?: ReaderTheme.DARK)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        prefs?.let { ReaderContent(book, bookKey, service, preferencesStore, it, colors, onBack, onKeys) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderContent(
    book: BookDocument,
    bookKey: String,
    service: PlaybackService,
    preferencesStore: ReaderPreferencesStore,
    prefs: ReaderPreferences,
    colors: ReadingColors,
    onBack: () -> Unit,
    onKeys: (((Int) -> Boolean)?) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val paginator = remember { Paginator(context.applicationContext) }
    val pageCache = remember { PageCache(context.applicationContext) }
    val measurer = remember { paginator.newMeasurer(cacheSize = 48) }
    val playback by service.state.collectAsState()

    // With TalkBack on, the controls stay on screen: there is no way to know a tap on the middle shows them.
    val screenReader = remember { isScreenReaderOn(context) }
    var menuVisible by remember { mutableStateOf(screenReader) }
    var showAppearance by remember { mutableStateOf(false) }
    var anchorOffset by remember { mutableIntStateOf(book.sentences.getOrNull(playback.currentIndex)?.charOffset ?: 0) }

    BrightnessEffect(prefs.brightnessOverride)
    ImmersiveEffect(
        hideBars = !menuVisible && !showAppearance,
        lightBars = prefs.theme == ReaderTheme.LIGHT || prefs.theme == ReaderTheme.SEPIA,
    )
    BackHandler(enabled = menuVisible && !screenReader) { menuVisible = false }
    val fontSize = prefs.fontSizeSp ?: defaultFontSize(LocalConfiguration.current.screenWidthDp.dp)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Insets that ignore visibility keep the page size stable when the menu shows the system bars.
        val insets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
        val topInsetPx = insets.getTop(density)
        val bottomInsetPx = insets.getBottom(density)
        val headerPx = with(density) { HEADER_HEIGHT.roundToPx() }
        val footerPx = with(density) { FOOTER_HEIGHT.roundToPx() }
        val marginPx = with(density) { prefs.margin.dp.dp.roundToPx() }
        val maxColumnPx = with(density) { (fontSize * COLUMN_MAX_EM).sp.roundToPx() }
        val spec = ReadingSpec(
            columnWidthPx = minOf(constraints.maxWidth - 2 * marginPx, maxColumnPx),
            pageHeightPx = constraints.maxHeight - topInsetPx - bottomInsetPx - headerPx - footerPx,
            density = density.density,
            fontScale = density.fontScale,
            fontSizeSp = fontSize,
            lineSpacing = prefs.lineSpacing,
            font = prefs.font,
            alignment = prefs.alignment,
        )
        val layoutKey = "${spec.cacheKey()}|${book.paragraphs.size}|${book.paragraphStarts.last()}"

        var paged by remember { mutableStateOf<PagedBook?>(null) }
        var progress by remember { mutableFloatStateOf(0f) }
        var paginationError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(layoutKey) {
            progress = 0f
            paginationError = null
            try {
                val pages = pageCache.load(bookKey, layoutKey)
                    ?: paginator.paginate(book, spec) { done, total -> progress = done.toFloat() / total }
                        .also { pageCache.save(bookKey, layoutKey, it) }
                paged = PagedBook(layoutKey, spec, pages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // An exception escaping a LaunchedEffect would kill the whole app.
                paginationError = CrashLog.describe(e, "diagramando \"${book.title}\"")
            }
        }

        val current = paged
        val error = paginationError
        if (error != null) {
            PaginationFailed(error, colors, onBack)
        } else if (current == null || current.layoutKey != layoutKey || current.pages.isEmpty()) {
            PaginatingIndicator(progress, colors)
        } else {
            key(current) {
                PagedReader(
                    book = book,
                    pages = current.pages,
                    spec = current.spec,
                    measurer = measurer,
                    colors = colors,
                    service = service,
                    playback = playback,
                    topInset = with(density) { topInsetPx.toDp() },
                    bottomInset = with(density) { bottomInsetPx.toDp() },
                    sideMargin = prefs.margin.dp.dp,
                    initialOffset = anchorOffset,
                    onAnchor = { anchorOffset = it },
                    menuVisible = menuVisible,
                    onMenuVisibleChange = { menuVisible = it || screenReader },
                    prefs = prefs,
                    onKeys = onKeys,
                    onShowAppearance = { showAppearance = true },
                    onBack = onBack,
                )
            }
        }
    }

    if (showAppearance) {
        AppearanceSheet(
            prefs = prefs,
            fontSize = fontSize,
            onDismiss = { showAppearance = false },
            store = preferencesStore,
            scope = scope,
        )
    }
}

@Composable
private fun PagedReader(
    book: BookDocument,
    pages: List<Page>,
    spec: ReadingSpec,
    measurer: TextMeasurer,
    colors: ReadingColors,
    service: PlaybackService,
    playback: PlaybackState,
    topInset: Dp,
    bottomInset: Dp,
    sideMargin: Dp,
    initialOffset: Int,
    onAnchor: (Int) -> Unit,
    menuVisible: Boolean,
    onMenuVisibleChange: (Boolean) -> Unit,
    onShowAppearance: () -> Unit,
    onBack: () -> Unit,
    prefs: ReaderPreferences,
    onKeys: (((Int) -> Boolean)?) -> Unit,
) {
    val sentences = book.sentences
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val voiceStore = remember { VoiceSettingsStore(context.applicationContext) }
    val speed = voiceStore.settings.collectAsState(initial = null).value?.speed ?: 1f
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pagerState = rememberPagerState(initialPage = PageBuilder.pageForOffset(pages, initialOffset)) { pages.size }

    val sleepTimer by service.sleepTimer.collectAsState()
    val audioIndex = playback.currentIndex.coerceIn(0, sentences.lastIndex)
    val audioSentence = sentences[audioIndex]
    val audioPage = PageBuilder.pageForOffset(pages, audioSentence.charOffset)

    var follow by remember { mutableStateOf(true) }
    var userMoved by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var longPress by remember { mutableStateOf<Pair<IntOffset, Int>?>(null) }

    fun audioPageNow(): Int {
        val index = service.state.value.currentIndex.coerceIn(0, sentences.lastIndex)
        return PageBuilder.pageForOffset(pages, sentences[index].charOffset)
    }

    fun goTo(page: Int, animate: Boolean = true) {
        val target = page.coerceIn(0, pages.lastIndex)
        if (target == pagerState.currentPage) return
        userMoved = true
        scope.launch { if (animate) pagerState.animateScrollToPage(target) else pagerState.scrollToPage(target) }
    }

    fun togglePlayback() {
        if (playback.isPlaying) {
            service.pause()
            return
        }
        val shown = pagerState.currentPage
        if (shown != audioPage) service.seekTo(firstSentenceOnPage(book, pages[shown]))
        follow = true
        service.play()
    }

    fun skipSentence(delta: Int) {
        follow = true
        service.seekTo((audioIndex + delta).coerceIn(0, sentences.lastIndex))
    }

    fun readFrom(sentence: Int) {
        follow = true
        service.seekTo(sentence)
        if (!service.state.value.isPlaying) service.play()
    }

    // A keyboard (or a switch device sending keys): arrows and page keys turn pages, space plays and pauses.
    DisposableEffect(pages) {
        onKeys { key ->
            when (key) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> { goTo(pagerState.currentPage + 1); true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> { goTo(pagerState.currentPage - 1); true }
                KeyEvent.KEYCODE_SPACE -> { togglePlayback(); true }
                else -> false
            }
        }
        onDispose { onKeys(null) }
    }

    LaunchedEffect(audioPage, follow) {
        if (follow && pagerState.currentPage != audioPage) pagerState.animateScrollToPage(audioPage)
    }
    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userMoved = true }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            onAnchor(pages[settled].startOffset)
            if (!userMoved) return@collect
            userMoved = false
            if (service.state.value.isPlaying) {
                if (settled != audioPageNow()) follow = false
            } else {
                // Leafing through while paused moves the resume point, like a Kindle bookmark.
                service.seekTo(firstSentenceOnPage(book, pages[settled]))
                follow = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            key = { it },
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val page = pages[index]
            Box(modifier = Modifier.fillMaxSize()) {
                if (!page.opensChapter) {
                    Text(
                        book.chapters.getOrNull(page.chapter)?.title ?: book.title,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = topInset, start = sideMargin, end = sideMargin)
                            .height(HEADER_HEIGHT)
                            .padding(top = 10.dp),
                        color = colors.text.copy(alpha = 0.5f),
                        fontFamily = InterFamily,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                KindlePage(
                    page = page,
                    book = book,
                    spec = spec,
                    measurer = measurer,
                    textColor = colors.text,
                    highlightColor = colors.highlight,
                    highlight = audioSentence.takeIf { playback.isPlaying },
                    highlightStyle = prefs.highlightStyle,
                    focusMode = prefs.focusMode,
                    actions = PageActions(
                        onTap = { x, width ->
                            when {
                                menuVisible -> onMenuVisibleChange(false)
                                x < width * 0.3f -> goTo(pagerState.currentPage - 1)
                                x > width * 0.7f -> goTo(pagerState.currentPage + 1)
                                else -> onMenuVisibleChange(true)
                            }
                        },
                        onLongPress = { position, sentence ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            val y = position.y + with(density) { (topInset + HEADER_HEIGHT).toPx() }
                            longPress = IntOffset(position.x.roundToInt(), y.roundToInt()) to sentence
                        },
                        onReadFrom = ::readFrom,
                        onNextPage = { goTo(pagerState.currentPage + 1) },
                        onPreviousPage = { goTo(pagerState.currentPage - 1) },
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topInset + HEADER_HEIGHT, bottom = bottomInset + FOOTER_HEIGHT),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = bottomInset, start = sideMargin, end = sideMargin)
                        .height(FOOTER_HEIGHT),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FooterText("Página ${index + 1} de ${pages.size}", colors, Modifier.weight(1f))
                    FooterText("${(index + 1) * 100 / pages.size}%", colors)
                }
            }
        }

        AnimatedVisibility(
            visible = playback.isPlaying && !follow && !menuVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomInset + FOOTER_HEIGHT + 12.dp),
        ) {
            Surface(
                onClick = { follow = true },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Voltar ao áudio · pág. ${audioPage + 1}", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        AnimatedVisibility(
            visible = menuVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopMenu(
                title = book.title,
                chapter = book.chapters.getOrNull(pages[pagerState.currentPage].chapter)?.title,
                colors = colors,
                onBack = onBack,
                onToc = { showToc = true },
                onAppearance = onShowAppearance,
            )
        }

        AnimatedVisibility(
            visible = menuVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomMenu(
                currentPage = pagerState.currentPage,
                pageCount = pages.size,
                chapterTimeLeft = remember(pagerState.currentPage, speed) {
                    chapterTimeLeft(book, firstSentenceOnPage(book, pages[pagerState.currentPage]), speed)
                },
                isPlaying = playback.isPlaying,
                speed = speed,
                colors = colors,
                onSeekPage = { goTo(it, animate = false) },
                onSpeed = { scope.launch { voiceStore.setSpeed(it) } },
                onPrevious = { skipSentence(-1) },
                onPlayPause = ::togglePlayback,
                onNext = { skipSentence(1) },
                large = prefs.largeControls,
                sleepTimer = sleepTimer,
                onSleepTimer = service::setSleepTimer,
            )
        }

        longPress?.let { (offset, sentence) ->
            Popup(popupPositionProvider = AboveFingerPosition(offset), onDismissRequest = { longPress = null }) {
                Surface(shape = RoundedCornerShape(50), color = colors.chrome, contentColor = colors.text, shadowElevation = 8.dp) {
                    TextButton(onClick = {
                        longPress = null
                        readFrom(sentence)
                    }) {
                        Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Ler a partir daqui")
                    }
                }
            }
        }
    }

    if (showToc) {
        TableOfContentsSheet(
            book = book,
            pages = pages,
            currentChapter = pages[pagerState.currentPage].chapter,
            onDismiss = { showToc = false },
            onSelect = { page ->
                showToc = false
                onMenuVisibleChange(false)
                goTo(page, animate = false)
            },
        )
    }
}

// Keeps the "Ler a partir daqui" bubble just above the finger and fully on screen.
private class AboveFingerPosition(private val touch: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left + touch.x - popupContentSize.width / 2
        val y = anchorBounds.top + touch.y - popupContentSize.height - popupContentSize.height / 2
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

@Composable
private fun FooterText(text: String, colors: ReadingColors, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = colors.text.copy(alpha = 0.5f),
        fontFamily = InterFamily,
        fontSize = 12.sp,
        maxLines = 1,
    )
}

@Composable
private fun PaginationFailed(details: String, colors: ReadingColors, onBack: () -> Unit) {
    var showDetails by remember { mutableStateOf(false) }
    if (showDetails) {
        ErrorReportDialog(
            title = "Detalhes do erro",
            intro = "Copie e cole numa issue em github.com/fanti1/audiolivre/issues.",
            report = details,
            onDismiss = { showDetails = false },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Não consegui montar as páginas deste livro", style = MaterialTheme.typography.titleLarge, color = colors.text)
        TextButton(onClick = { showDetails = true }, modifier = Modifier.padding(top = 16.dp)) {
            Text("Ver detalhes do erro")
        }
        TextButton(onClick = onBack) { Text("Voltar") }
    }
}

@Composable
private fun PaginatingIndicator(progress: Float, colors: ReadingColors) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress },
            color = MaterialTheme.colorScheme.primary,
            trackColor = colors.text.copy(alpha = 0.12f),
        )
        Text(
            "Diagramando páginas…",
            modifier = Modifier.padding(top = 18.dp),
            color = colors.text.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TopMenu(
    title: String,
    chapter: String?,
    colors: ReadingColors,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onAppearance: () -> Unit,
) {
    Surface(color = colors.chrome, contentColor = colors.text, shadowElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Voltar à biblioteca")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (chapter != null && chapter != title) {
                    Text(
                        chapter,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.text.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onToc) {
                Icon(painterResource(R.drawable.ic_menu_book), contentDescription = "Sumário")
            }
            IconButton(onClick = onAppearance) {
                Icon(painterResource(R.drawable.ic_text_fields), contentDescription = "Aparência")
            }
        }
    }
}

@Composable
private fun BottomMenu(
    currentPage: Int,
    pageCount: Int,
    chapterTimeLeft: String,
    isPlaying: Boolean,
    speed: Float,
    colors: ReadingColors,
    onSeekPage: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    large: Boolean,
    sleepTimer: SleepTimer?,
    onSleepTimer: (SleepTimer?) -> Unit,
) {
    val skipSize = if (large) 72.dp else 48.dp
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val shownPage = if (dragging) dragValue.roundToInt() else currentPage

    Surface(color = colors.chrome, contentColor = colors.text, shadowElevation = 4.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Página ${shownPage + 1} de $pageCount", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(chapterTimeLeft, style = MaterialTheme.typography.bodySmall, color = colors.text.copy(alpha = 0.65f))
            }
            if (pageCount > 1) {
                Slider(
                    value = if (dragging) dragValue else currentPage.toFloat(),
                    onValueChange = {
                        dragging = true
                        dragValue = it
                    },
                    onValueChangeFinished = {
                        dragging = false
                        onSeekPage(dragValue.roundToInt())
                    },
                    valueRange = 0f..(pageCount - 1).toFloat(),
                    colors = SliderDefaults.colors(inactiveTrackColor = colors.text.copy(alpha = 0.15f)),
                )
            }
            // Speed and the sleep timer on a row of their own: beside the large controls there is no room left.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SpeedButton(speed, colors, onSpeed)
                Spacer(modifier = Modifier.weight(1f))
                SleepTimerButton(sleepTimer, colors, onSleepTimer)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (large) 20.dp else 32.dp, Alignment.CenterHorizontally),
            ) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(skipSize)) {
                    Icon(
                        painterResource(R.drawable.ic_skip_previous),
                        contentDescription = "Frase anterior",
                        modifier = Modifier.size(if (large) 40.dp else 24.dp),
                    )
                }
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(if (large) 96.dp else 64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    Icon(
                        painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (isPlaying) "Pausar" else "Ouvir",
                        modifier = Modifier.size(if (large) 50.dp else 34.dp),
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(skipSize)) {
                    Icon(
                        painterResource(R.drawable.ic_skip_next),
                        contentDescription = "Próxima frase",
                        modifier = Modifier.size(if (large) 40.dp else 24.dp),
                    )
                }
            }
        }
    }
}

// Stops the reading after a while, for listening in bed: minutes, or the end of the chapter.
@Composable
private fun SleepTimerButton(timer: SleepTimer?, colors: ReadingColors, onSet: (SleepTimer?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // The minutes left, recounted while the menu is on screen.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer) {
        while (timer?.endsAt != null) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(15_000)
        }
    }
    val label = when {
        timer == null -> null
        timer.endsAt == null -> "fim do cap."
        else -> "${((timer.endsAt - now) / 60_000 + 1).coerceAtLeast(1)} min"
    }
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.semantics { contentDescription = if (label == null) "Timer para parar a leitura" else "Timer: para em $label" },
        ) {
            Icon(painterResource(R.drawable.ic_timer), contentDescription = null, tint = colors.text, modifier = Modifier.size(20.dp))
            if (label != null) {
                Spacer(Modifier.width(6.dp))
                Text(label, color = colors.text, style = MaterialTheme.typography.labelMedium)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(15, 30, 45, 60).forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(if (minutes == 60) "Parar em 1 hora" else "Parar em $minutes minutos") },
                    onClick = {
                        open = false
                        onSet(SleepTimer(System.currentTimeMillis() + minutes * 60_000L))
                    },
                )
            }
            DropdownMenuItem(text = { Text("Parar no fim do capítulo") }, onClick = {
                open = false
                onSet(SleepTimer(null))
            })
            if (timer != null) {
                DropdownMenuItem(text = { Text("Desligar o timer") }, onClick = {
                    open = false
                    onSet(null)
                })
            }
        }
    }
}

// Reading speed at hand while listening, as in audiobook players; the full range is in the Voz screen.
@Composable
private fun SpeedButton(speed: Float, colors: ReadingColors, onSpeed: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(speedLabel(speed), style = MaterialTheme.typography.titleSmall, color = colors.text)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            VoiceSettings.SPEED_PRESETS.forEach { preset ->
                val selected = abs(preset - speed) < 0.01f
                DropdownMenuItem(
                    text = {
                        Text(
                            speedLabel(preset) + if (preset == 1f) "  (audiolivro)" else "",
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        open = false
                        onSpeed(preset)
                    },
                )
            }
        }
    }
}

private fun speedLabel(speed: Float): String {
    val pattern = if ((speed * 100).roundToInt() % 10 == 0) "%.1f×" else "%.2f×"
    return pattern.format(Locale("pt", "BR"), speed)
}

private fun chapterTimeLeft(book: BookDocument, fromSentence: Int, speed: Float): String {
    if (book.sentences.isEmpty()) return ""
    val chapter = book.chapterOf(fromSentence)
    var words = 0
    var i = fromSentence
    while (i < book.sentences.size && book.chapterOf(i) == chapter) {
        words += book.sentences[i].ttsText.split(' ').count { it.isNotBlank() }
        i++
    }
    val minutes = (words / (WORDS_PER_MINUTE * speed)).toInt()
    return when {
        minutes < 1 -> "menos de 1 min no capítulo"
        minutes < 60 -> "$minutes min restantes no capítulo"
        else -> "${minutes / 60}h${"%02d".format(minutes % 60)} restantes no capítulo"
    }
}

@Composable
private fun BrightnessEffect(brightnessOverride: Float) {
    val activity = LocalContext.current as? Activity
    LaunchedEffect(brightnessOverride) {
        val window = activity?.window ?: return@LaunchedEffect
        window.attributes = window.attributes.apply {
            screenBrightness = if (brightnessOverride >= 0f) brightnessOverride else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
}

@Composable
private fun ImmersiveEffect(hideBars: Boolean, lightBars: Boolean) {
    val view = LocalView.current
    val window = (LocalContext.current as? Activity)?.window ?: return
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    LaunchedEffect(hideBars, lightBars) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = lightBars
        controller.isAppearanceLightNavigationBars = lightBars
        if (hideBars) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(controller) {
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }
}

private fun isScreenReaderOn(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return manager.isEnabled && manager.isTouchExplorationEnabled
}
