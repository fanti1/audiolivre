package io.github.fanti1.audiolivre.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.fanti1.audiolivre.book.BookDocument
import io.github.fanti1.audiolivre.book.BookJson
import io.github.fanti1.audiolivre.book.BookStore
import io.github.fanti1.audiolivre.book.SourceStamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.SoftReference

// The prepared (and, for books in other languages, translated) text of each book, so opening it again skips
// reading and translating the file.
class BookCache(private val context: Context) {

    suspend fun load(uri: Uri): BookDocument? = withContext(Dispatchers.IO) {
        val stamp = stampOf(uri)
        val file = fileFor(uri)
        remembered(file, stamp)?.let { return@withContext it }
        val book = BookStore.read(file, stamp) ?: migrateLegacy(uri, stamp)
        book?.also { remember(file, stamp, it) }
    }

    suspend fun save(uri: Uri, book: BookDocument) = withContext(Dispatchers.IO) {
        val stamp = stampOf(uri)
        val file = fileFor(uri)
        file.parentFile?.mkdirs()
        // Write-then-rename so a crash mid-write never leaves a truncated cache that fails to parse forever.
        val temp = File(file.parentFile, "${file.name}.tmp")
        BookStore.write(temp, book, stamp)
        if (!temp.renameTo(file)) temp.delete()
        remember(file, stamp, book)
    }

    // Books saved by versions before the binary format were JSON. They are read once more and saved again in
    // the new form: a translated book must never be translated twice just because the format changed.
    private fun migrateLegacy(uri: Uri, stamp: SourceStamp): BookDocument? {
        val legacy = legacyFileFor(uri)
        if (!legacy.isFile) return null
        val book = BookJson.decode(legacy.readText(), stamp) ?: return null
        val file = fileFor(uri)
        val temp = File(file.parentFile, "${file.name}.tmp")
        BookStore.write(temp, book, stamp)
        if (temp.renameTo(file)) legacy.delete() else temp.delete()
        return book
    }

    private fun fileFor(uri: Uri) = File(context.filesDir, "books/${sha256Hex(uri.toString())}.book")

    private fun legacyFileFor(uri: Uri) = File(context.filesDir, "books/${sha256Hex(uri.toString())}.json")

    private fun stampOf(uri: Uri): SourceStamp = try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use UNKNOWN
            val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getLong) ?: -1L
            val modified = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                .takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong) ?: -1L
            SourceStamp(size, modified)
        } ?: UNKNOWN
    } catch (e: Exception) {
        UNKNOWN
    }

    companion object {
        private val UNKNOWN = SourceStamp(-1L, -1L)

        // The book last opened, while memory allows: back from the shelf to the same book, it is there at once.
        // A soft reference lets the system take it back under memory pressure; the playing book is held by the
        // playback service anyway, so this costs nothing extra.
        private var last: Triple<String, SourceStamp, SoftReference<BookDocument>>? = null

        @Synchronized
        private fun remembered(file: File, stamp: SourceStamp): BookDocument? =
            last?.takeIf { it.first == file.path && it.second == stamp }?.third?.get()

        @Synchronized
        private fun remember(file: File, stamp: SourceStamp, book: BookDocument) {
            last = Triple(file.path, stamp, SoftReference(book))
        }

        // Forgets the remembered book, when its files are deleted.
        @Synchronized
        fun forget() {
            last = null
        }
    }
}
