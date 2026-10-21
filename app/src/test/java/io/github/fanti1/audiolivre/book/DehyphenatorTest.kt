package io.github.fanti1.audiolivre.book

import org.junit.Assert.assertEquals
import org.junit.Test

class DehyphenatorTest {

    private fun join(left: String, right: String, bookLines: List<String> = emptyList()): String {
        val text = StringBuilder(left)
        Dehyphenator.fromLines(bookLines).append(text, right)
        return text.toString()
    }

    @Test
    fun `merges a word broken at the end of a line`() {
        assertEquals("a palavra certa", join("a pala-", "vra certa"))
    }

    @Test
    fun `joins a compound split with the repeated hyphen using a single hyphen`() {
        assertEquals("o guarda-chuva molhado", join("o guarda-", "-chuva molhado"))
    }

    @Test
    fun `keeps the hyphen before enclitic and mesoclitic pronouns`() {
        assertEquals("para dá-lo depois", join("para dá-", "lo depois"))
        assertEquals("ele fá-lo-ei amanhã", join("ele fá-", "lo-ei amanhã"))
    }

    @Test
    fun `uses the book's own words to decide ambiguous breaks`() {
        assertEquals("o bem-estar dele", join("o bem-", "estar dele", listOf("sentia um bem-estar enorme")))
        assertEquals("a guardanapo", join("a guarda-", "napo", listOf("pegou o guardanapo")))
    }

    @Test
    fun `merges when nothing is known about the word`() {
        assertEquals("a desventura", join("a desven-", "tura"))
    }

    @Test
    fun `soft hyphen always merges`() {
        assertEquals("a palavra", join("a pala­", "vra"))
    }

    @Test
    fun `keeps the hyphen before a capitalized continuation`() {
        assertEquals("o Rio-Grande", join("o Rio-", "Grande"))
    }

    @Test
    fun `joins ordinary lines with a space`() {
        assertEquals("a casa grande", join("a casa", "grande"))
        assertEquals("disse - e saiu", join("disse -", "e saiu"))
    }
}
