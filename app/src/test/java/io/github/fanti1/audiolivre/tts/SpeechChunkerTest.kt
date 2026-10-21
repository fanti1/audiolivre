package io.github.fanti1.audiolivre.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {

    @Test
    fun `short sentences are voiced whole`() {
        val text = "Era uma vez um reino muito distante."
        assertEquals(listOf(text), SpeechChunker.split(text))
    }

    @Test
    fun `long sentences break at the strongest pause within reach`() {
        val text = "Naquela manhã de inverno, quando a neblina ainda cobria os telhados da pequena cidade; " +
            "Helena saiu de casa com a carta no bolso do casaco, decidida a entregá-la pessoalmente ao juiz da comarca."
        val pieces = SpeechChunker.split(text, maxChars = 100)
        assertEquals("Naquela manhã de inverno, quando a neblina ainda cobria os telhados da pequena cidade;", pieces[0])
        assertTrue(pieces.all { it.length <= 100 })
        assertEquals(text, pieces.joinToString(" "))
    }

    @Test
    fun `without punctuation it breaks before a conjunction, then at a space`() {
        val text = "Ele caminhou por horas sem parar pela estrada deserta e poeirenta que levava até a velha casa da colina"
        val pieces = SpeechChunker.split(text, maxChars = 60)
        assertTrue(pieces[1], pieces[1].startsWith("e ") || pieces[1].startsWith("que "))
        assertEquals(text, pieces.joinToString(" "))
    }

    @Test
    fun `a single endless word is still cut`() {
        val pieces = SpeechChunker.split("a".repeat(250), maxChars = 100)
        assertEquals(listOf(100, 100, 50), pieces.map { it.length })
    }
}
