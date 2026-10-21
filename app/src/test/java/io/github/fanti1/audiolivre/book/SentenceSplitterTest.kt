package io.github.fanti1.audiolivre.book

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSplitterTest {

    @Test
    fun `splits normal sentences and drops standalone page-number lines`() {
        val book = build(
            listOf(
                line(0, "42", y = 40f, right = 90f),
                line(0, "O sol nasceu cedo naquele dia. Maria caminhou até a janela e observou o jardim.", y = 100f),
                line(0, "Ela sorriu ao ver as flores.", y = 114f, right = 250f),
                line(0, "- 42 -", y = 700f, right = 110f),
            ),
        )

        assertEquals(
            listOf(
                "O sol nasceu cedo naquele dia.",
                "Maria caminhou até a janela e observou o jardim.",
                "Ela sorriu ao ver as flores.",
            ),
            book.displayTexts(),
        )
    }

    @Test
    fun `keeps short real sentences that end with punctuation`() {
        val text = "Ele parou. Por quê? Não sei."

        val sentences = SentenceSplitter.sentenceSpans(text).map { text.substring(it.start, it.end) }

        assertEquals(listOf("Ele parou.", "Por quê?", "Não sei."), sentences)
    }

    @Test
    fun `does not split after abbreviations`() {
        val text = "O Sr. Almeida chegou cedo. A Dra. Silva e o Prof. Souza esperavam."

        val sentences = SentenceSplitter.sentenceSpans(text).map { text.substring(it.start, it.end) }

        assertEquals(listOf("O Sr. Almeida chegou cedo.", "A Dra. Silva e o Prof. Souza esperavam."), sentences)
    }

    @Test
    fun `keeps name initials together`() {
        val text = "O livro de J. R. R. Tolkien é famoso. Leia."

        val sentences = SentenceSplitter.sentenceSpans(text).map { text.substring(it.start, it.end) }

        assertEquals(listOf("O livro de J. R. R. Tolkien é famoso.", "Leia."), sentences)
    }

    @Test
    fun `a capital letter used as a label still ends the sentence`() {
        val text = "Ele tomou vitamina C. Depois dormiu."

        val sentences = SentenceSplitter.sentenceSpans(text).map { text.substring(it.start, it.end) }

        assertEquals(listOf("Ele tomou vitamina C.", "Depois dormiu."), sentences)
    }

    @Test
    fun `assigns sequential indexes only to kept sentences`() {
        val book = build(
            listOf(
                line(5, "7", y = 40f, right = 80f),
                line(5, "Primeira frase real. Segunda frase real.", y = 100f),
            ),
        )

        assertEquals(listOf(0, 1), book.sentences.map { it.index })
        assertEquals(listOf(5, 5), book.sentences.map { it.sourcePage })
    }

    @Test
    fun `drops decorative separator lines`() {
        val book = build(
            listOf(
                line(0, "* * *", y = 100f, x = 250f, right = 290f),
                line(0, "Uma frase de verdade aqui.", y = 114f, right = 260f),
            ),
        )

        assertEquals(listOf("Uma frase de verdade aqui."), book.sentences.map { it.ttsText })
    }

    @Test
    fun `multi-page input preserves page boundaries`() {
        val book = build(
            listOf(
                line(0, "Frase da primeira página.", y = 100f, right = 260f),
                line(1, "Frase da segunda página.", y = 100f, right = 260f),
            ),
        )

        assertEquals(listOf(0, 1), book.sentences.map { it.sourcePage })
    }
}
