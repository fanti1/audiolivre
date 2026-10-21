package io.github.fanti1.audiolivre

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fanti1.audiolivre.data.BookStorage
import io.github.fanti1.audiolivre.data.LibraryStore
import io.github.fanti1.audiolivre.data.ResumePositionStore
import io.github.fanti1.audiolivre.data.sha256Hex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// The shelf and the per-book files on a real device: adding, re-importing and removing have to leave the app in a
// clean state, since a leftover extraction would make a re-imported book look unchanged.
@RunWith(AndroidJUnit4::class)
class LibraryStorageTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val uri = Uri.parse("content://io.github.fanti1.audiolivre.test/livro-${System.nanoTime()}.pdf")
    private val key = sha256Hex(uri.toString())

    private fun seedExtraction(): List<File> {
        val files = listOf(
            File(context.filesDir, "books/$key.json"),
            File(context.filesDir, "pages/$key-abc.json"),
            File(context.filesDir, "covers/$key.jpg"),
        )
        files.forEach {
            it.parentFile?.mkdirs()
            it.writeText("x")
        }
        return files
    }

    @Test
    fun addingReimportingAndRemovingABook() = runBlocking {
        val library = LibraryStore(context)
        val storage = BookStorage(context)
        val resume = ResumePositionStore(context)

        library.addOrPromote(uri.toString(), "Livro de teste", sizeBytes = 1234)
        library.updateReadingState(uri.toString(), totalChars = 1000, charOffset = 250)
        resume.save(uri.toString(), 250)

        val added = library.books.first().first { it.uri == uri.toString() }
        assertEquals("Livro de teste", added.title)
        assertEquals(0.25f, added.progress, 1e-4f)
        assertEquals(mapOf(uri.toString() to 250), resume.offsets(listOf(uri.toString())))

        // Re-importing drops what was extracted from the PDF but keeps the book and where the reading stopped.
        val files = seedExtraction()
        storage.clearExtraction(uri)
        files.forEach { assertFalse(it.path, it.exists()) }
        assertEquals(250, library.books.first().first { it.uri == uri.toString() }.charOffset)
        assertEquals(mapOf(uri.toString() to 250), resume.offsets(listOf(uri.toString())))

        // Starting over only clears the position.
        resume.clear(uri.toString())
        library.resetProgress(uri.toString())
        assertNull(resume.load(uri.toString()))
        assertFalse(library.books.first().first { it.uri == uri.toString() }.started)

        // Removing leaves nothing behind.
        seedExtraction()
        resume.save(uri.toString(), 99)
        library.remove(uri.toString())
        storage.forget(uri)
        assertTrue(library.books.first().none { it.uri == uri.toString() })
        assertNull(resume.load(uri.toString()))
        assertFalse(File(context.filesDir, "books/$key.json").exists())
        assertFalse(File(context.filesDir, "pages/$key-abc.json").exists())
        assertFalse(File(context.filesDir, "covers/$key.jpg").exists())
    }

    @Test
    fun theShelfKeepsOneEntryPerBookAndPromotesIt() = runBlocking {
        val library = LibraryStore(context)
        val other = "content://io.github.fanti1.audiolivre.test/outro-${System.nanoTime()}.pdf"
        library.addOrPromote(uri.toString(), "Primeiro", 10)
        library.addOrPromote(other, "Segundo", 10)
        library.addOrPromote(uri.toString(), "Primeiro", 10)

        val books = library.books.first()
        assertEquals(1, books.count { it.uri == uri.toString() })
        assertEquals(uri.toString(), books.first().uri)

        library.remove(uri.toString())
        library.remove(other)
    }
}
