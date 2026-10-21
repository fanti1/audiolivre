package io.github.fanti1.audiolivre.formats

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.fanti1.audiolivre.book.BookBuilder
import io.github.fanti1.audiolivre.book.BookDocument
import io.github.fanti1.audiolivre.data.EventLog
import io.github.fanti1.audiolivre.pdf.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

// Everything but PDFs is parsed from memory; a book this big is a scanned image dump, not text.
private const val MAX_BOOK_BYTES = 200L * 1024 * 1024
private const val HEAD_BYTES = 1024

// The one door every file comes in through: finds out what the file really is and hands it to the right reader.
class BookImporter(private val context: Context) {

    suspend fun extract(
        uri: Uri,
        fallbackTitle: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): BookDocument {
        val started = System.currentTimeMillis()
        var format = BookFormat.PDF
        var size = 0
        val document = runCatching {
            if (isPdf(uri)) return@runCatching PdfTextExtractor().extractBook(uri, context, fallbackTitle, onProgress)
            withContext(Dispatchers.Default) {
                val bytes = readAll(uri)
                size = bytes.size
                format = BookFormat.detect(displayName(uri), bytes)
                if (format == BookFormat.PDF) return@withContext PdfTextExtractor().extractBook(uri, context, fallbackTitle, onProgress)
                val book = BookReaders.read(format, bytes, onProgress)
                BookBuilder.fromBlocks(book.blocks, book.toc, cleanTitle(book.title) ?: fallbackTitle)
            }
        }.onFailure { EventLog.record("importação falhou (${format.label}, ${size / 1024} KB): ${it.javaClass.simpleName}") }.getOrThrow()
        EventLog.record(
            "importado: ${format.label}${if (size > 0) ", ${size / 1024} KB" else ""} → ${document.paragraphs.size} parágrafos, " +
                "${document.sentences.size} frases, ${document.chapters.size} capítulos em ${System.currentTimeMillis() - started} ms",
        )
        return document
    }

    // The picture a book carries for its cover, when its format has one. PDFs are rendered instead.
    suspend fun embeddedCover(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            if (isPdf(uri)) return@runCatching null
            val bytes = readAll(uri)
            BookReaders.cover(BookFormat.detect(displayName(uri), bytes), bytes)
        }.getOrNull()
    }

    suspend fun isPdf(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            open(uri).use { input ->
                val head = ByteArray(HEAD_BYTES)
                val n = input.readFully(head)
                BookFormat.isPdf(head.copyOf(n))
            }
        }.getOrDefault(false)
    }

    private fun readAll(uri: Uri): ByteArray = open(uri).use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            total += n
            if (total > MAX_BOOK_BYTES) throw UnsupportedBookException("Este arquivo é grande demais para ser aberto como livro.")
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }

    private fun open(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw UnsupportedBookException("Não consegui abrir o arquivo.")

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null }
    }.getOrNull()

    private fun cleanTitle(title: String?): String? =
        title?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.length in 2..160 && it.any(Char::isLetter) }

    private fun InputStream.readFully(target: ByteArray): Int {
        var filled = 0
        while (filled < target.size) {
            val n = read(target, filled, target.size - filled)
            if (n == -1) break
            filled += n
        }
        return filled
    }
}
