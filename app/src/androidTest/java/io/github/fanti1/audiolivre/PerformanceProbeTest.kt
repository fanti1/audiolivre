package io.github.fanti1.audiolivre

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fanti1.audiolivre.book.BookDocument
import io.github.fanti1.audiolivre.book.BookStore
import io.github.fanti1.audiolivre.data.PageMargin
import io.github.fanti1.audiolivre.data.ReadingFont
import io.github.fanti1.audiolivre.data.TextAlignment
import io.github.fanti1.audiolivre.reader.Paginator
import io.github.fanti1.audiolivre.reader.ReadingSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Times the heavy steps of opening a book on the device, on whatever books are cached there, and logs them
// under "PerformanceProbe": reading the saved book, and paginating it. For comparing before and after an
// optimization; it asserts nothing.
@RunWith(AndroidJUnit4::class)
class PerformanceProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun timesOpeningAndPaginatingCachedBooks() = runBlocking {
        val files = File(context.filesDir, "books").listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }.orEmpty()
        assumeTrue("no cached books", files.isNotEmpty())
        var biggest: BookDocument? = null
        for (file in files.sortedBy { it.length() }) {
            val book = timedRead("as saved", file) ?: continue
            // The same book in the binary format, for comparison when the saved one is still JSON.
            if (file.name.endsWith(".json")) {
                val binary = File(context.cacheDir, "probe.book")
                BookStore.write(binary, book, io.github.fanti1.audiolivre.book.SourceStamp(0, 0))
                timedRead("as binary", binary)
            }
            if (biggest == null || book.paragraphs.size > biggest.paragraphs.size) biggest = book
        }
        val book = biggest ?: return@runBlocking
        val density = context.resources.displayMetrics.density
        val spec = ReadingSpec(
            columnWidthPx = context.resources.displayMetrics.widthPixels - (2 * PageMargin.NORMAL.dp * density).toInt(),
            pageHeightPx = (context.resources.displayMetrics.heightPixels * 0.82f).toInt(),
            density = density,
            fontScale = 1f,
            fontSizeSp = 18,
            lineSpacing = 1.5f,
            font = ReadingFont.LITERATA,
            alignment = TextAlignment.JUSTIFY,
        )
        repeat(2) { run ->
            val started = System.nanoTime()
            val pages = Paginator(context).paginate(book, spec) { _, _ -> }
            Log.i(TAG, "paginate run ${run + 1}: ${pages.size} pages, ${book.chapters.size} chapters in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }

    private fun timedRead(label: String, file: File): BookDocument? {
        repeat(2) { System.gc() }
        val heapBefore = usedHeapKb()
        val started = System.nanoTime()
        val book = BookStore.readUnchecked(file) ?: return null
        val ms = (System.nanoTime() - started) / 1_000_000
        // Garbage left by reading is what the next collection has to clean up; what stays is the book itself.
        val withGarbage = usedHeapKb() - heapBefore
        repeat(2) { System.gc() }
        val kept = usedHeapKb() - heapBefore
        Log.i(TAG, "read $label ${file.name.take(8)} (${file.length() / 1024} KB on disk, ${book.paragraphs.size} paragraphs): $ms ms, heap +$withGarbage KB right after, book itself $kept KB")
        return book
    }

    private fun usedHeapKb(): Long = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1024 }

    private companion object {
        const val TAG = "PerformanceProbe"
    }
}
