package io.github.fanti1.audiolivre.translate

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.fanti1.audiolivre.book.BookDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

// The translations running in TranslationService, by book URI, for the reader screen and the shelf to follow.
object TranslationJobs {

    sealed interface Status {
        data class Running(val step: TranslationStep) : Status
        // The translated book is in the cache; the reader opens it from there.
        data object Done : Status
        data class Failed(val message: String, val details: String?) : Status
    }

    private val _all = MutableStateFlow<Map<String, Status>>(emptyMap())
    val all: StateFlow<Map<String, Status>> = _all.asStateFlow()

    // The extracted book, handed from the reader screen to the service in the same process.
    private val pending = ConcurrentHashMap<String, Pending>()

    internal class Pending(val book: BookDocument, val title: String, val language: String)

    fun start(context: Context, uri: String, title: String, language: String, book: BookDocument) {
        pending[uri] = Pending(book, title, language)
        set(uri, Status.Running(TranslationStep.Starting))
        ContextCompat.startForegroundService(
            context,
            Intent(context, TranslationService::class.java).setAction(TranslationService.ACTION_START).putExtra(TranslationService.EXTRA_URI, uri),
        )
    }

    fun cancel(context: Context, uri: String) {
        pending.remove(uri)
        clear(uri)
        context.startService(
            Intent(context, TranslationService::class.java).setAction(TranslationService.ACTION_CANCEL).putExtra(TranslationService.EXTRA_URI, uri),
        )
    }

    internal fun take(uri: String): Pending? = pending.remove(uri)

    internal fun set(uri: String, status: Status) = _all.update { it + (uri to status) }

    fun clear(uri: String) = _all.update { it - uri }
}
