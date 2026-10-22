package io.github.fanti1.audiolivre.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.fanti1.audiolivre.book.BookDocument
import io.github.fanti1.audiolivre.data.BookCache
import io.github.fanti1.audiolivre.data.CrashLog
import io.github.fanti1.audiolivre.data.EventLog
import io.github.fanti1.audiolivre.data.LibraryStore
import io.github.fanti1.audiolivre.data.VoiceSettingsStore
import io.github.fanti1.audiolivre.data.ReaderPreferencesStore
import io.github.fanti1.audiolivre.data.ResumePositionStore
import io.github.fanti1.audiolivre.data.SavedPosition
import io.github.fanti1.audiolivre.data.sha256Hex
import io.github.fanti1.audiolivre.formats.BookImporter
import io.github.fanti1.audiolivre.reader.KindleReaderScreen
import io.github.fanti1.audiolivre.service.PlaybackService
import io.github.fanti1.audiolivre.translate.BookTranslationFlow
import io.github.fanti1.audiolivre.translate.LanguageChoice
import io.github.fanti1.audiolivre.translate.TranslationJobs
import io.github.fanti1.audiolivre.translate.TranslationStep
import io.github.fanti1.audiolivre.tts.InstallProgress
import io.github.fanti1.audiolivre.tts.InstallStage
import io.github.fanti1.audiolivre.tts.ModelDownloader
import io.github.fanti1.audiolivre.tts.VoiceModels
import io.github.fanti1.audiolivre.ui.theme.EbookTheme
import io.github.fanti1.audiolivre.ui.theme.enableEdgeToEdgeBars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

private const val EXTRA_URI = "uri"

private sealed interface ReaderState {
    data class Extracting(val page: Int, val pageCount: Int) : ReaderState
    data class Translation(val step: TranslationStep) : ReaderState
    data class DownloadingVoice(val progress: InstallProgress?) : ReaderState
    data object PreparingVoice : ReaderState
    data class Ready(val book: BookDocument) : ReaderState
    data class Error(val message: String, val details: String? = null) : ReaderState
}

class ReaderActivity : ComponentActivity() {

    companion object {
        fun intent(context: Context, uri: Uri): Intent =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_URI, uri.toString())
    }

    // Bridges the plain ServiceConnection callback (not Compose-aware) into Compose state so
    // the reader recomposes once the service is actually bound.
    private var boundService by mutableStateOf<PlaybackService?>(null)

    // The reader's keyboard shortcuts, while a book is on screen. Keys the focused control doesn't use end here.
    private var keyHandler: ((Int) -> Boolean)? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        keyHandler?.invoke(keyCode) == true || super.onKeyDown(keyCode, event)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            boundService = (service as PlaybackService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdgeBars()
        super.onCreate(savedInstanceState)

        val uri = Uri.parse(requireNotNull(intent.getStringExtra(EXTRA_URI)))
        val uriString = uri.toString()
        val title = bookTitle(resolveDisplayName(this, uri) ?: "Leitura")
        val library = LibraryStore(applicationContext)
        val voiceSettings = VoiceSettingsStore(applicationContext)
        val downloader = ModelDownloader(applicationContext)
        val resumeStore = ResumePositionStore(applicationContext)
        val preferencesStore = ReaderPreferencesStore(applicationContext)
        val bookCache = BookCache(applicationContext)
        val openedAt = System.currentTimeMillis()

        // A bound-only service is destroyed the instant its last client unbinds — startService()
        // marks it "started" so it keeps running independently once this Activity goes away
        // (screen lock, task switch, this Activity finishing). bindService() is only for getting
        // a direct call/StateFlow interface, not for keeping the service alive.
        startService(Intent(this, PlaybackService::class.java))
        bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)

        val requestNotificationPermission =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* playback works either way */ }

        setContent {
            var state by remember { mutableStateOf<ReaderState>(ReaderState.Extracting(0, 0)) }
            var attempt by remember { mutableIntStateOf(0) }

            // The voice model takes a second or two to load; it loads while the book is being read, imported or
            // translated, not after.
            LaunchedEffect(Unit) {
                val narrator = VoiceModels.byId(voiceSettings.settings.first().narratorVoice) ?: VoiceModels.DEFAULT_NARRATOR
                if (downloader.isDownloaded(narrator)) {
                    runCatching { snapshotFlow { boundService }.filterNotNull().first().ensureEngine(narrator.id) }
                }
            }

            LaunchedEffect(uri, attempt) {
                state = try {
                    // The cache holds the book as the app keeps it: already translated when it was not in
                    // Portuguese. Only the reader giving up on the translation leaves nothing to show.
                    val book = bookCache.load(uri) ?: run {
                        // A translation already running for this book (the reader left and came back, or
                        // tapped the notification) is followed, not started again.
                        if (TranslationJobs.all.value[uriString] !is TranslationJobs.Status.Running) {
                            val extracted = BookImporter(applicationContext).extract(uri, title) { page, pageCount ->
                                state = ReaderState.Extracting(page, pageCount)
                            }
                            val choice = BookTranslationFlow(applicationContext)
                                .chooseLanguage(extracted, sha256Hex(uriString)) { state = ReaderState.Translation(it) }
                            when (choice) {
                                LanguageChoice.Keep -> return@run extracted.also { bookCache.save(uri, it) }
                                LanguageChoice.Cancel -> {
                                    finish()
                                    return@LaunchedEffect
                                }
                                is LanguageChoice.Translate -> {
                                    // The progress and the "ready" message come as notifications.
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    TranslationJobs.start(applicationContext, uriString, title, choice.language, extracted)
                                }
                            }
                        }
                        // The translation runs in TranslationService; this screen only shows how it goes.
                        val outcome = TranslationJobs.all
                            .map { it[uriString] }
                            .onEach { if (it is TranslationJobs.Status.Running) state = ReaderState.Translation(it.step) }
                            .first { it !is TranslationJobs.Status.Running }
                        TranslationJobs.clear(uriString)
                        when (outcome) {
                            is TranslationJobs.Status.Failed -> {
                                state = ReaderState.Error(outcome.message, outcome.details)
                                return@LaunchedEffect
                            }
                            // Cancelled, here or from the notification.
                            null -> {
                                finish()
                                return@LaunchedEffect
                            }
                            else -> bookCache.load(uri) ?: error("a tradução terminou, mas o livro traduzido não foi salvo")
                        }
                    }
                    if (book.sentences.isEmpty()) {
                        ReaderState.Error("Não foi possível extrair texto deste arquivo. Se for um PDF escaneado (só imagens), ele não tem texto para ler.")
                    } else {
                        // Only the narrator's voice is needed to start reading; a character voice is downloaded
                        // from the voice screen and, until then, that character speaks with the narrator's voice.
                        val narrator = VoiceModels.byId(voiceSettings.settings.first().narratorVoice)
                            ?: VoiceModels.DEFAULT_NARRATOR
                        if (!downloader.isDownloaded(narrator)) {
                            state = ReaderState.DownloadingVoice(null)
                            downloader.ensureDownloaded(narrator) { state = ReaderState.DownloadingVoice(it) }
                            downloader.deleteLegacyVoices()
                        }
                        state = ReaderState.PreparingVoice
                        val service = snapshotFlow { boundService }.filterNotNull().first()
                        service.ensureEngine(narrator.id)
                        val initialIndex = when (val saved = resumeStore.load(uriString)) {
                            is SavedPosition.Offset -> book.sentenceAtOffset(saved.charOffset)
                            is SavedPosition.Legacy -> book.sentenceForLegacyPosition(saved.page, saved.indexInPage)
                            null -> 0
                        }
                        service.loadBook(uriString, book.title, book, resumeStore, initialIndex)
                        library.renameIfDifferent(uriString, book.title)
                        // Lets the shelf show how far this book got, without opening it again.
                        library.updateReadingState(
                            uriString,
                            book.paragraphStarts.last(),
                            book.sentences.getOrNull(initialIndex)?.charOffset ?: 0,
                        )
                        // Asked only once the book is ready: asking on open covered the voice download screen.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        EventLog.record("livro aberto em ${System.currentTimeMillis() - openedAt} ms (${book.paragraphs.size} parágrafos)")
                        ReaderState.Ready(book)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Errors (not just Exceptions) land here too: a huge or oddly built file can exhaust
                    // memory or the stack, and that must show an error screen instead of killing the app.
                    ReaderState.Error(friendlyMessage(e), CrashLog.describe(e, "abrindo \"$title\""))
                }
            }

            EbookTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val s = state) {
                        is ReaderState.Extracting -> LoadingScreen("Preparando livro…", s.page, s.pageCount)
                        is ReaderState.Translation -> TranslationScreen(
                            step = s.step,
                            title = title,
                            onCancel = {
                                if (s.step !is TranslationStep.Ask) TranslationJobs.cancel(applicationContext, uriString)
                                finish()
                            },
                            onLeave = { finish() },
                        )
                        is ReaderState.DownloadingVoice -> VoiceDownloadScreen(s.progress, onCancel = { finish() })
                        is ReaderState.PreparingVoice -> LoadingScreen("Carregando a voz…")
                        is ReaderState.Ready -> {
                            val service = boundService
                            if (service == null) {
                                LoadingScreen("Preparando voz…")
                            } else {
                                KindleReaderScreen(
                                    book = s.book,
                                    bookKey = sha256Hex(uriString),
                                    service = service,
                                    preferencesStore = preferencesStore,
                                    onBack = { finish() },
                                    onKeys = { keyHandler = it },
                                )
                            }
                        }
                        is ReaderState.Error -> ReaderErrorScreen(
                            message = s.message,
                            details = s.details,
                            onRetry = { attempt++ },
                            onBack = { finish() },
                        )
                    }
                }
            }
        }
    }

    // Unbind only — NOT stopService. The service keeps running/playing independently as a
    // foreground service; this is exactly what lets audio survive this Activity being destroyed
    // (screen lock, task switch, etc).
    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }
}

private fun resolveDisplayName(context: Context, uri: Uri): String? =
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: SecurityException) {
        null
    }

@Composable
private fun LoadingScreen(message: String, page: Int = 0, pageCount: Int = 0) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (pageCount > 0) {
            Text(message, style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { page.toFloat() / pageCount },
                modifier = Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth(),
            )
            Text(
                "${page * 100 / pageCount}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp).announcedPercent(page * 100 / pageCount),
            )
        } else {
            CircularProgressIndicator()
            Text(message, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
        }
    }
}

@Composable
private fun VoiceDownloadScreen(progress: InstallProgress?, onCancel: () -> Unit) {
    val installing = progress?.stage == InstallStage.INSTALLING
    val percent = progress?.percent
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (installing) "Instalando a voz do narrador" else "Baixando a voz do narrador",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            if (installing) {
                "Descompactando o modelo no aparelho. Falta pouco."
            } else {
                // Only a narrator chosen in place of the bundled one ever gets here.
                "São cerca de 63 MB, só desta vez. Depois o app lê sem internet."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (percent == null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .padding(top = 28.dp)
                    .fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier
                    .padding(top = 28.dp)
                    .fillMaxWidth(),
            )
            Text(
                "$percent%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp).announcedPercent(percent),
            )
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.padding(top = 24.dp)) {
            Text("Cancelar")
        }
    }
}

private fun friendlyMessage(error: Throwable): String = when (error) {
    is OutOfMemoryError -> "Este livro é grande demais para a memória do aparelho."
    is StackOverflowError -> "Este arquivo tem uma estrutura que o app ainda não consegue processar."
    else -> error.message ?: error.javaClass.simpleName
}

@Composable
private fun ReaderErrorScreen(message: String, details: String?, onRetry: () -> Unit, onBack: () -> Unit) {
    var showDetails by remember { mutableStateOf(false) }
    if (showDetails && details != null) {
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
            .safeDrawingPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Não deu pra abrir este livro", style = MaterialTheme.typography.titleLarge)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) {
            Text("Tentar de novo")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
            Text("Voltar")
        }
        if (details != null) {
            TextButton(onClick = { showDetails = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Ver detalhes do erro")
            }
        }
    }
}
