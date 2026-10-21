package io.github.fanti1.audiolivre

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fanti1.audiolivre.book.BookBuilder
import io.github.fanti1.audiolivre.formats.BookFormat
import io.github.fanti1.audiolivre.formats.BookReaders
import io.github.fanti1.audiolivre.translate.BookTranslator
import io.github.fanti1.audiolivre.translate.LanguageDetector
import io.github.fanti1.audiolivre.translate.SlimtTranslator
import io.github.fanti1.audiolivre.translate.TranslationModelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Translates the start of a real book with different chunk sizes and logs the speed under "TranslationSpeed".
// The book goes in the app's own folder first:
//   adb push book.epub /sdcard/Android/data/io.github.fanti1.audiolivre/files/bench.epub
@RunWith(AndroidJUnit4::class)
class TranslationSpeedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun comparesChunkSizes() = runBlocking {
        val file = File(context.getExternalFilesDir(null), "bench.epub")
        assumeTrue("no bench.epub", file.isFile && SlimtTranslator.isAvailable)
        val bytes = file.readBytes()
        val imported = BookReaders.read(BookFormat.detect(file.name, bytes), bytes) { _, _ -> }
        val whole = BookBuilder.fromBlocks(imported.blocks, imported.toc, imported.title ?: "bench")
        val book = BookBuilder.fromBlocks(
            whole.paragraphs.take(PARAGRAPHS).map { io.github.fanti1.audiolivre.formats.TextBlock(it.text, it.isHeading) },
            emptyList(),
            whole.title,
        )
        val language = LanguageDetector.detect(book.paragraphs.map { it.text }) ?: "en"
        val store = TranslationModelStore(context)
        val route = store.catalog().route(language)!!
        store.install(route)
        val words = book.paragraphs.sumOf { it.text.split(' ').size }
        SlimtTranslator.open(store, route).use { translator ->
            translator.translate(listOf("Warm-up."))
            var reference: List<String>? = null
            for (chunk in listOf(2_500, 8_000, 20_000, 40_000, 80_000)) {
                val started = System.nanoTime()
                val result = BookTranslator.translate(book, translator, "bench-$chunk-${System.nanoTime()}", chunkChars = chunk)
                val seconds = (System.nanoTime() - started) / 1e9
                val texts = result.paragraphs.map { it.text }
                // The chunk size must change how fast, never what: the same text, word for word.
                val differing = reference?.let { ref -> texts.indices.count { texts[it] != ref.getOrNull(it) } } ?: 0
                if (reference == null) reference = texts
                Log.i(TAG, "chunk $chunk chars: $words words in ${"%.1f".format(seconds)} s = ${(words / seconds).toInt()} words/s, $differing paragraphs differ from the first run")
            }
        }
    }

    private companion object {
        const val TAG = "TranslationSpeed"
        const val PARAGRAPHS = 500
    }
}
