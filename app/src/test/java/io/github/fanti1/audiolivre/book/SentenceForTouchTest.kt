package io.github.fanti1.audiolivre.book

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceForTouchTest {

    // Built by hand so the gaps between sentences are exactly where the test needs them.
    private fun book(vararg paragraphs: Pair<String, List<IntRange>>): BookDocument {
        val paras = paragraphs.map { (text, _) -> Paragraph(text, 0, false, 0, emptyList()) }
        val sentences = ArrayList<BookSentence>()
        var offset = 0
        paragraphs.forEachIndexed { p, (text, spans) ->
            for (span in spans) {
                sentences += BookSentence(
                    sentences.size, p, span.first, span.last + 1, SegmentKind.NARRATION, GenderHint.UNKNOWN, 0,
                    offset + span.first, text,
                )
            }
            offset += text.length + 1
        }
        return BookDocument("t", emptyList(), paras, sentences)
    }

    @Test
    fun `a touch inside a sentence picks that sentence, its last letter included`() {
        val doc = book("Uma frase. Outra frase." to listOf(0..9, 11..22))
        assertEquals(0, doc.sentenceForTouch(0))
        assertEquals(0, doc.sentenceForTouch(5))
        assertEquals(0, doc.sentenceForTouch(10))
        assertEquals(1, doc.sentenceForTouch(11))
        assertEquals(1, doc.sentenceForTouch(23))
    }

    @Test
    fun `a touch before a paragraph's first sentence starts there, not at the end of the previous paragraph`() {
        // The second paragraph's only sentence starts after two characters nothing is read from.
        val doc = book(
            "Fim do anterior." to listOf(0..15),
            "  Começo do novo." to listOf(2..16),
        )
        val secondStart = "Fim do anterior.".length + 1
        assertEquals(1, doc.sentenceForTouch(secondStart))
        assertEquals(1, doc.sentenceForTouch(secondStart + 1))
    }

    @Test
    fun `a touch on a paragraph with nothing to read picks the next sentence`() {
        val doc = book(
            "Antes." to listOf(0..5),
            "* * *" to emptyList(),
            "Depois." to listOf(0..6),
        )
        assertEquals(1, doc.sentenceForTouch("Antes.".length + 1 + 2))
    }

    @Test
    fun `past the last sentence stays on the last sentence`() {
        val doc = book("Só esta." to listOf(0..7))
        assertEquals(0, doc.sentenceForTouch(100))
        assertEquals(0, doc.sentenceForTouch(-3))
    }
}
