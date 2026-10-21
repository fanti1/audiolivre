package io.github.fanti1.audiolivre.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookJsonTest {

    private val stamp = SourceStamp(size = 123_456L, lastModified = 1_700_000_000_000L)

    private val book = build(
        listOf(line(0, "CAPÍTULO I", y = 100f, x = 240f, right = 330f)) +
            pageLines(0, "Um texto com “aspas” e acentuação: ção, ã, é." to SHORT, startY = 150f) +
            pageLines(1, "— Não vou — disse ela. — Nunca!" to SHORT),
        outline = listOf(OutlineEntry("Capítulo I", 0)),
    )

    @Test
    fun `round trip keeps the whole book`() {
        val decoded = BookJson.decode(BookJson.encode(book, stamp), stamp)

        assertEquals(book, decoded)
    }

    @Test
    fun `a changed source file invalidates the cache`() {
        val json = BookJson.encode(book, stamp)

        assertNull(BookJson.decode(json, stamp.copy(size = 1L)))
        assertNull(BookJson.decode(json, stamp.copy(lastModified = 1L)))
    }

    @Test
    fun `an older format version or corrupt data is ignored`() {
        val older = BookJson.encode(book, stamp).replace("\"version\":${BookJson.FORMAT_VERSION}", "\"version\":0")

        assertNull(BookJson.decode(older, stamp))
        assertNull(BookJson.decode("{not json", stamp))
        assertNull(BookJson.decode("{}", stamp))
    }
}
