package io.github.fanti1.audiolivre.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.fanti1.audiolivre.formats.BookImporter
import io.github.fanti1.audiolivre.translate.deleteTranslationJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "BookStorage"
private const val COVER_WIDTH = 360

// Everything the app keeps on disk for one book, in one place: the extracted text, the page tables, the cover and
// the reading position. Removing a book has to leave nothing behind, and re-importing has to leave nothing stale.
class BookStorage(private val context: Context) {

    private val resume = ResumePositionStore(context)
    private val importer = BookImporter(context)

    // A PDF's first page, or the picture an EPUB, Kindle, FB2 or Office file carries as its cover. Formats with
    // neither (plain text, RTF, HTML) get a typographic cover drawn by the shelf instead.
    suspend fun cover(uri: Uri): File? = withContext(Dispatchers.IO) {
        val file = coverFile(uri)
        if (file.isFile && file.length() > 0) return@withContext file
        // Looking for a cover reads the whole file; a book found to have none is not read again for it on every
        // return to the shelf.
        val none = noCoverFile(uri)
        if (none.exists()) return@withContext null
        val cover = runCatching { if (importer.isPdf(uri)) renderCover(uri, file) else embeddedCover(uri, file) }.getOrElse {
            Log.w(TAG, "could not render a cover for $uri", it)
            null
        }
        if (cover == null) runCatching { none.parentFile?.mkdirs(); none.createNewFile() }
        cover
    }

    private suspend fun embeddedCover(uri: Uri, target: File): File? {
        val bytes = importer.embeddedCover(uri) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_WIDTH) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val height = (COVER_WIDTH.toFloat() / decoded.width * decoded.height).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(decoded, COVER_WIDTH, height, true)
        if (scaled !== decoded) decoded.recycle()
        return writeJpeg(scaled, target)
    }

    private fun writeJpeg(bitmap: Bitmap, target: File): File? {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        bitmap.recycle()
        if (!temp.renameTo(target)) {
            temp.delete()
            return null
        }
        return target
    }

    private fun renderCover(uri: Uri, target: File): File? {
        val descriptor: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val height = (COVER_WIDTH.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(COVER_WIDTH, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return writeJpeg(bitmap, target)
                }
            }
        }
    }

    // Drops the extracted (or translated) text, page tables and cover, so the next open reads the file again
    // from scratch.
    suspend fun clearExtraction(uri: Uri) = withContext(Dispatchers.IO) {
        val key = sha256Hex(uri.toString())
        File(context.filesDir, "books/$key.book").delete()
        File(context.filesDir, "books/$key.json").delete()
        BookCache.forget()
        File(context.filesDir, "pages").listFiles { f -> f.name.startsWith("$key-") }?.forEach { it.delete() }
        coverFile(uri).delete()
        noCoverFile(uri).delete()
        deleteTranslationJournal(context, key)
        Unit
    }

    suspend fun forget(uri: Uri) {
        clearExtraction(uri)
        resume.clear(uri.toString())
        releasePermission(uri)
    }

    private fun releasePermission(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun coverFile(uri: Uri) = File(context.filesDir, "covers/${sha256Hex(uri.toString())}.jpg")

    private fun noCoverFile(uri: Uri) = File(context.filesDir, "covers/${sha256Hex(uri.toString())}.none")
}
