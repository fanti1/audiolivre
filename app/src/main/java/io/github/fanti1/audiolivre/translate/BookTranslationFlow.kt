package io.github.fanti1.audiolivre.translate

import android.content.Context
import io.github.fanti1.audiolivre.book.BookDocument
import io.github.fanti1.audiolivre.data.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// What the reader screen shows while a book in another language becomes a Portuguese one.
sealed interface TranslationStep {
    // `detected` is the language the text looks like, or null when it could not tell. `answer` gets the
    // language the reader confirms (TARGET_LANGUAGE for "it is already in Portuguese"), or null to give up.
    data class Ask(
        val detected: String?,
        val languages: List<String>,
        val downloadMb: Map<String, Int>,
        val answer: CompletableDeferred<String?>,
    ) : TranslationStep

    // Handed to the translation service; it reports Downloading and Translating from there.
    data object Starting : TranslationStep

    data class Downloading(val language: String, val percent: Int?) : TranslationStep

    data class Translating(val language: String, val done: Int, val total: Int, val secondsLeft: Long?) : TranslationStep {
        val percent: Int get() = (done.toLong() * 100 / total.coerceAtLeast(1)).toInt()
    }
}

// What the reader chose for a book that may need translating.
sealed interface LanguageChoice {
    // Already Portuguese (or it can't be told and there is no translator): read as it is.
    data object Keep : LanguageChoice
    data object Cancel : LanguageChoice
    data class Translate(val language: String) : LanguageChoice
}

private fun journalDir(context: Context) = File(context.filesDir, "translation/journal")

fun translationJournalFile(context: Context, bookKey: String) = File(journalDir(context), "$bookKey.jsonl")

// The language a half-finished translation was started from, so reopening the book carries on without asking.
private fun languageFile(context: Context, bookKey: String) = File(journalDir(context), "$bookKey.lang")

fun deleteTranslationJournal(context: Context, bookKey: String) {
    translationJournalFile(context, bookKey).delete()
    languageFile(context, bookKey).delete()
}

// The step between reading a file and reading it aloud: a book that is not in Portuguese is translated, with
// the reader's consent, and the translation is what the app keeps. Choosing happens on the reader screen;
// the translation itself runs in TranslationService, so it goes on with the app closed.
class BookTranslationFlow(private val context: Context) {

    private val store = TranslationModelStore(context)

    suspend fun chooseLanguage(book: BookDocument, bookKey: String, onStep: (TranslationStep) -> Unit): LanguageChoice {
        val detected = LanguageDetector.detect(book.paragraphs.map { it.text })
        if (detected == TARGET_LANGUAGE) return LanguageChoice.Keep
        if (!SlimtTranslator.isAvailable) {
            // Without the engine there is nothing to offer; a book it could not place is read as it is.
            if (detected == null) return LanguageChoice.Keep
            throw IllegalStateException(
                "Este livro está em ${languageName(detected)}, e esta versão do app ainda não traz o tradutor.",
            )
        }
        // A translation already under way was confirmed before; it simply continues.
        languageFile(context, bookKey).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return LanguageChoice.Translate(it)
        }

        val catalog = store.catalog()
        val languages = catalog.sourceLanguages()
        val downloadMb = languages.associateWith { megabytes(store.missingBytes(catalog.route(it).orEmpty())) }
        val answer = CompletableDeferred<String?>()
        onStep(TranslationStep.Ask(detected, languages, downloadMb, answer))
        val language = answer.await() ?: return LanguageChoice.Cancel
        return if (language == TARGET_LANGUAGE) LanguageChoice.Keep else LanguageChoice.Translate(language)
    }

    // Downloads what the route lacks and translates the whole book. Run by TranslationService.
    suspend fun translate(book: BookDocument, bookKey: String, language: String, onStep: (TranslationStep) -> Unit): BookDocument {
        val catalog = store.catalog()
        val route = catalog.route(language) ?: throw IllegalStateException("Ainda não há tradutor de ${languageName(language)} para português.")
        journalDir(context).mkdirs()
        languageFile(context, bookKey).writeText(language)

        if (store.missingBytes(route) > 0) {
            onStep(TranslationStep.Downloading(language, null))
            store.install(route) { onStep(TranslationStep.Downloading(language, it.percent)) }
        }

        val journal = TranslationJournal(translationJournalFile(context, bookKey))
        val routeId = route.joinToString("+") { "${it.id}@${it.version}" }
        val clock = Eta()
        val words = book.paragraphs.sumOf { it.text.count(Char::isWhitespace) + 1 }
        val started = System.currentTimeMillis()
        EventLog.record("tradução começou: $routeId, $words palavras")
        val translated = runCatching {
            withContext(Dispatchers.Default) {
                SlimtTranslator.open(store, route).use { translator ->
                    BookTranslator.translate(book, translator, routeId, journal) { done, total ->
                        onStep(TranslationStep.Translating(language, done, total, clock.secondsLeft(done, total)))
                    }
                }
            }
        }.onFailure { EventLog.record("tradução parou: ${it.javaClass.simpleName}") }.getOrThrow()
        EventLog.record("tradução terminou em ${(System.currentTimeMillis() - started) / 1000} s")
        deleteTranslationJournal(context, bookKey)
        return translated
    }

    private fun megabytes(bytes: Long) = ((bytes + 999_999) / 1_000_000).toInt()

    // Time left at the pace of this session (a resumed translation starts its own count).
    private class Eta {
        private var startedAt = 0L
        private var startDone = 0

        fun secondsLeft(done: Int, total: Int): Long? {
            val now = System.currentTimeMillis()
            if (startedAt == 0L) {
                startedAt = now
                startDone = done
                return null
            }
            val elapsed = now - startedAt
            val progressed = done - startDone
            // The first seconds include loading the models; a guess from them would be far off.
            if (elapsed < 8_000 || progressed <= 0) return null
            return (total - done).toLong() * elapsed / progressed / 1000
        }
    }
}
