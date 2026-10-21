package io.github.fanti1.audiolivre.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import io.github.fanti1.audiolivre.data.BookStorage
import io.github.fanti1.audiolivre.data.CrashLog
import io.github.fanti1.audiolivre.data.LibraryStore
import io.github.fanti1.audiolivre.data.RecentFilesStore
import io.github.fanti1.audiolivre.formats.BookFormat
import io.github.fanti1.audiolivre.data.ResumePositionStore
import io.github.fanti1.audiolivre.service.PlaybackService
import io.github.fanti1.audiolivre.translate.TranslationJobs
import io.github.fanti1.audiolivre.ui.theme.EbookTheme
import io.github.fanti1.audiolivre.ui.theme.enableEdgeToEdgeBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryActivity : ComponentActivity() {

    // Bumped on every resume so the shelf refreshes the reading progress after the reader closes.
    private var resumedAt by mutableStateOf(0L)

    override fun onResume() {
        super.onResume()
        resumedAt = System.currentTimeMillis()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdgeBars()
        super.onCreate(savedInstanceState)

        val library = LibraryStore(applicationContext)
        val storage = BookStorage(applicationContext)
        val resume = ResumePositionStore(applicationContext)
        val legacy = RecentFilesStore(applicationContext)

        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val snackbar = remember { SnackbarHostState() }
            val books by library.books.collectAsState(initial = emptyList())
            val covers = remember { mutableStateMapOf<String, ImageBitmap>() }
            val available = remember { mutableStateMapOf<String, Boolean>() }
            var offsets by remember { mutableStateOf(emptyMap<String, Int>()) }
            val translations by TranslationJobs.all.collectAsState()

            LaunchedEffect(Unit) {
                val old = legacy.recentUris.first()
                if (old.isNotEmpty()) {
                    library.migrate(old) { uri -> displayName(context, Uri.parse(uri))?.let(::bookTitle) }
                    // Emptying the old list keeps a shelf the user cleared from filling up again on the next start.
                    legacy.clear()
                }
            }

            // Covers and "is the file still there?" are disk and provider work: never on the way to the first frame.
            // Re-runs when coming back from the reader, so progress and covers are up to date.
            LaunchedEffect(books, resumedAt) {
                offsets = resume.offsets(books.map { it.uri })
                for (book in books) {
                    val uri = Uri.parse(book.uri)
                    if (available[book.uri] == null) {
                        available[book.uri] = withContext(Dispatchers.IO) { displayName(context, uri) != null }
                    }
                    if (covers[book.uri] == null && available[book.uri] == true) {
                        val bitmap = withContext(Dispatchers.IO) {
                            storage.cover(uri)?.let { BitmapFactory.decodeFile(it.absolutePath) }
                        }
                        if (bitmap != null) covers[book.uri] = bitmap.asImageBitmap()
                    }
                }
            }

            val entries = books.map { book ->
                LibraryEntry(
                    book = book.copy(charOffset = offsets[book.uri] ?: book.charOffset),
                    cover = covers[book.uri],
                    available = available[book.uri] ?: true,
                    translation = (translations[book.uri] as? TranslationJobs.Status.Running)?.step,
                )
            }

            val pickBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch { addBook(uri, library, snackbar, books.map { it.uri }) }
            }

            var lastCrash by remember { mutableStateOf(CrashLog.read(context)) }

            EbookTheme {
                LibraryScreen(
                    entries = entries,
                    snackbarHost = snackbar,
                    onAdd = { pickBook.launch(BookFormat.MIME_TYPES) },
                    onAction = { entry, action ->
                        scope.launch { perform(entry, action, library, storage, resume, snackbar, covers) }
                    },
                    onSettings = { startActivity(Intent(context, VoiceSettingsActivity::class.java)) },
                    onManual = { startActivity(Intent(context, ManualActivity::class.java)) },
                    onAbout = { startActivity(Intent(context, AboutActivity::class.java)) },
                )
                lastCrash?.let { report ->
                    ErrorReportDialog(
                        title = "O app fechou por um erro",
                        intro = "Da última vez o app fechou sozinho. Toque em \"Copiar erro\" e cole o texto numa issue em github.com/fanti1/audiolivre/issues.",
                        report = report,
                        onDismiss = {
                            CrashLog.clear(context)
                            lastCrash = null
                        },
                    )
                }
            }
        }
    }

    private suspend fun addBook(uri: Uri, library: LibraryStore, snackbar: SnackbarHostState, known: List<String>) {
        // Without this, the URI stops being readable the next time the app (and this grant) restarts.
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val already = uri.toString() in known
        val name = withContext(Dispatchers.IO) { displayName(this@LibraryActivity, uri) }
        library.addOrPromote(uri.toString(), bookTitle(name ?: "Livro"), sizeOf(this@LibraryActivity, uri))
        if (already) snackbar.showMessage("Esse livro já estava na biblioteca")
        openReader(uri)
    }

    private suspend fun perform(
        entry: LibraryEntry,
        action: BookAction,
        library: LibraryStore,
        storage: BookStorage,
        resume: ResumePositionStore,
        snackbar: SnackbarHostState,
        covers: MutableMap<String, ImageBitmap>,
    ) {
        val uri = Uri.parse(entry.uri)
        when (action) {
            BookAction.OPEN -> {
                if (!entry.available) {
                    snackbar.showMessage("Não encontrei o arquivo deste livro. Ele pode ter sido movido ou apagado.")
                    return
                }
                library.markOpened(entry.uri)
                openReader(uri)
            }

            BookAction.REIMPORT -> {
                stopTranslating(entry.uri)
                storage.clearExtraction(uri)
                covers.remove(entry.uri)
                library.markOpened(entry.uri)
                openReader(uri)
            }

            BookAction.RESTART -> {
                stopReading(entry.uri)
                resume.clear(entry.uri)
                library.resetProgress(entry.uri)
                snackbar.showMessage("“${entry.title}” volta ao começo")
            }

            BookAction.REMOVE -> {
                stopReading(entry.uri)
                stopTranslating(entry.uri)
                val offset = entry.book.charOffset
                library.remove(entry.uri)
                storage.forget(uri)
                covers.remove(entry.uri)
                val result = snackbar.showSnackbar("“${entry.title}” saiu da biblioteca", actionLabel = "Desfazer",
                    duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) {
                    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    library.addOrPromote(entry.uri, entry.title, entry.book.sizeBytes)
                    if (offset > 0) {
                        resume.save(entry.uri, offset)
                        library.updateReadingState(entry.uri, entry.book.totalChars, offset)
                    }
                }
            }
        }
    }

    private fun openReader(uri: Uri) = startActivity(ReaderActivity.intent(this, uri))

    private fun stopTranslating(uriString: String) {
        if (TranslationJobs.all.value[uriString] is TranslationJobs.Status.Running) TranslationJobs.cancel(this, uriString)
    }

    // The service may not be running, and starting one just to stop a book must never break the library.
    private fun stopReading(uriString: String) {
        runCatching { PlaybackService.stopReading(this, uriString) }
    }
}

private suspend fun SnackbarHostState.showMessage(text: String) {
    showSnackbar(text, duration = SnackbarDuration.Short)
}

internal fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null }
} catch (e: Exception) {
    null
}

private fun sizeOf(context: Context, uri: Uri): Long = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L } ?: 0L
} catch (e: Exception) {
    0L
}
