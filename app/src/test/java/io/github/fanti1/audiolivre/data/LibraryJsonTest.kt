package io.github.fanti1.audiolivre.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryJsonTest {

    private val book = LibraryBook(
        uri = "content://docs/1",
        title = "Dom Casmurro",
        addedAt = 1_700_000_000_000,
        lastOpenedAt = 1_700_000_500_000,
        sizeBytes = 561_000,
        totalChars = 400_000,
        charOffset = 100_000,
    )

    @Test
    fun `a shelf survives a round trip`() {
        val books = listOf(book, book.copy(uri = "content://docs/2", title = "Outro livro", charOffset = 0))
        assertEquals(books, LibraryJson.decode(LibraryJson.encode(books)))
    }

    @Test
    fun `progress comes from the position within the book`() {
        assertEquals(0.25f, book.progress, 1e-4f)
        assertTrue(book.started)
        assertEquals(0f, book.copy(charOffset = 0).progress, 1e-4f)
        // A book whose length isn't known yet shows no progress instead of a wrong one.
        assertEquals(0f, book.copy(totalChars = 0).progress, 1e-4f)
        // A position past the end (the book was re-imported shorter) never goes over 100%.
        assertEquals(1f, book.copy(charOffset = 999_999).progress, 1e-4f)
    }

    @Test
    fun `broken or missing storage reads as an empty shelf`() {
        assertEquals(emptyList<LibraryBook>(), LibraryJson.decode(null))
        assertEquals(emptyList<LibraryBook>(), LibraryJson.decode(""))
        assertEquals(emptyList<LibraryBook>(), LibraryJson.decode("{não é json"))
    }

    @Test
    fun `a book written by an older version keeps its defaults`() {
        val books = LibraryJson.decode("""[{"uri":"content://docs/9","title":"Antigo"}]""")
        assertEquals(1, books.size)
        assertEquals("content://docs/9", books[0].uri)
        assertEquals(0, books[0].totalChars)
        assertEquals(0f, books[0].progress, 1e-4f)
    }
}
