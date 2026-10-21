package io.github.fanti1.audiolivre.pdf

import android.content.Context
import android.net.Uri
import io.github.fanti1.audiolivre.book.BookBuilder
import io.github.fanti1.audiolivre.book.BookDocument
import io.github.fanti1.audiolivre.book.OutlineEntry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private const val MAX_OUTLINE_DEPTH = 6
private const val MAX_OUTLINE_ENTRIES = 2000
private const val EXTRACTION_STACK_BYTES = 64L * 1024 * 1024
private const val MAX_TITLE_LENGTH = 120
private val FILE_EXTENSION = Regex("""\.(pdf|doc|docx|indd|qxd|tex|odt|rtf)$""", RegexOption.IGNORE_CASE)
private val NOT_TITLES = setOf("untitled", "sem título", "sem titulo", "documento", "document", "pdf", "book", "livro")

// PdfBox walks nested content streams and forms recursively; the default ~1 MB thread stack can overflow
// on complex PDFs, so parsing runs on one dedicated thread with a much larger stack.
private val extractionDispatcher = Executors.newSingleThreadExecutor { task ->
    Thread(null, task, "pdf-extraction", EXTRACTION_STACK_BYTES)
}.asCoroutineDispatcher()

class PdfTextExtractor {

    suspend fun extractBook(
        uri: Uri,
        context: Context,
        title: String,
        onProgress: (page: Int, pageCount: Int) -> Unit,
    ): BookDocument = withContext(extractionDispatcher) {
        val document = context.contentResolver.openInputStream(uri)?.use { PDDocument.load(it) }
            ?: error("Could not open $uri")
        document.use { doc ->
            val lines = LineCollectingStripper(onProgress).collect(doc)
            BookBuilder.build(lines, readOutline(doc), doc.numberOfPages, documentTitle(doc) ?: title)
        }
    }

    // Many PDFs carry the real book title, which reads better on the shelf than the file name. Tools leave junk
    // there too ("Microsoft Word - final2.doc", "untitled"), so only a clean, title-looking value is accepted.
    private fun documentTitle(document: PDDocument): String? {
        val raw = runCatching { document.documentInformation?.title }.getOrNull()?.trim() ?: return null
        val cleaned = raw.removePrefix("Microsoft Word - ").removePrefix("Microsoft PowerPoint - ")
            .replace(FILE_EXTENSION, "").trim()
        if (cleaned.length !in 2..MAX_TITLE_LENGTH) return null
        if (cleaned.lowercase() in NOT_TITLES) return null
        if (cleaned.none { it.isLetter() }) return null
        return cleaned
    }

    // Outlines come from arbitrary PDFs: destinations can be missing, broken or even cyclic, so any
    // failure just means "no outline" and chapter detection falls back to headings.
    private fun readOutline(document: PDDocument): List<OutlineEntry> = try {
        val entries = mutableListOf<OutlineEntry>()
        fun walk(node: PDOutlineNode, level: Int) {
            if (level > MAX_OUTLINE_DEPTH) return
            for (item in node.children()) {
                if (entries.size >= MAX_OUTLINE_ENTRIES) return
                val page = runCatching { item.findDestinationPage(document) }.getOrNull()
                val index = page?.let { document.pages.indexOf(it) } ?: -1
                val title = item.title?.trim().orEmpty()
                if (index >= 0 && title.isNotEmpty()) entries += OutlineEntry(title, index, level)
                walk(item, level + 1)
            }
        }
        document.documentCatalog.documentOutline?.let { walk(it, 0) }
        entries
    } catch (e: Exception) {
        emptyList()
    }
}
