package io.github.fanti1.audiolivre.book

import io.github.fanti1.audiolivre.formats.TextBlock
import io.github.fanti1.audiolivre.formats.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BookStoreTest {

    private val source = SourceStamp(size = 1234, lastModified = 5678)

    private fun book(): BookDocument = BookBuilder.fromBlocks(
        listOf(
            TextBlock("CAPÍTULO I", isHeading = true),
            TextBlock("— Bom dia — disse ela. — Vamos?"),
            TextBlock("Ele respondeu que sim. Saíram juntos pela rua antiga, " + "longa ".repeat(300)),
            TextBlock("* * *"),
            TextBlock("Um parágrafo com acentuação: ção, ñ, ü, 😀 e aspas “curvas”. “Quem é?”, perguntou ele."),
        ),
        listOf(TocEntry("CAPÍTULO I", 0)),
        "Título de teste",
    )

    private fun tempFile(): File = File(Files.createTempDirectory("bookstore").toFile(), "book.book")

    @Test
    fun `round trip keeps the whole book`() {
        val original = book()
        val file = tempFile()
        BookStore.write(file, original, source)
        val read = BookStore.read(file, source)
        assertEquals(original, read)
        // Positions and spoken text are worked out again, not stored: they must come back the same.
        assertEquals(original.sentences.map { it.charOffset }, read!!.sentences.map { it.charOffset })
        assertEquals(original.sentences.map { it.ttsText }, read.sentences.map { it.ttsText })
    }

    @Test
    fun `a changed source file or a damaged file reads as missing`() {
        val file = tempFile()
        BookStore.write(file, book(), source)
        assertNull(BookStore.read(file, SourceStamp(1234, 9999)))
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() / 2))
        assertNull(BookStore.read(file, source))
        file.writeText("{\"version\":2}")
        assertNull(BookStore.read(file, source))
    }

    @Test
    fun `the binary book is much smaller than the JSON one`() {
        val original = book()
        val file = tempFile()
        BookStore.write(file, original, source)
        val json = BookJson.encode(original, source).toByteArray().size
        assertTrue("binary ${file.length()} vs json $json", file.length() < json / 2)
    }
}
