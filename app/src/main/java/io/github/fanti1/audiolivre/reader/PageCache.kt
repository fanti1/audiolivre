package io.github.fanti1.audiolivre.reader

import android.content.Context
import io.github.fanti1.audiolivre.data.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Keeps only the latest page table per book: switching font size back and forth just re-paginates.
class PageCache(private val context: Context) {

    private val dir get() = File(context.filesDir, "pages")

    suspend fun load(bookKey: String, layoutKey: String): List<Page>? = withContext(Dispatchers.IO) {
        val file = fileFor(bookKey, layoutKey)
        if (!file.exists()) return@withContext null
        runCatching { PageJson.decode(file.readText()) }.getOrNull()
    }

    suspend fun save(bookKey: String, layoutKey: String, pages: List<Page>) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        dir.listFiles { f -> f.name.startsWith("$bookKey-") }?.forEach { it.delete() }
        val file = fileFor(bookKey, layoutKey)
        val temp = File(dir, "${file.name}.tmp")
        temp.writeText(PageJson.encode(pages))
        if (!temp.renameTo(file)) temp.delete()
    }

    private fun fileFor(bookKey: String, layoutKey: String) =
        File(dir, "$bookKey-${sha256Hex(layoutKey).take(16)}.json")
}
