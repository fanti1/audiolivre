package io.github.fanti1.audiolivre.tts

import io.github.fanti1.audiolivre.book.FULL
import io.github.fanti1.audiolivre.book.SHORT
import io.github.fanti1.audiolivre.book.build
import io.github.fanti1.audiolivre.book.line
import io.github.fanti1.audiolivre.book.pageLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProsodyTest {

    @Test
    fun `pause length grows with the size of the break, and a title is set apart`() {
        val book = build(
            listOf(line(0, "CAPÍTULO I", y = 100f, x = 240f, right = 330f)) +
                pageLines(0, "A primeira frase chegou. A segunda" to FULL, "frase chegou." to SHORT, startY = 150f) +
                listOf(line(0, "Outro parágrafo.", y = 178f, x = 90f, right = SHORT, paragraphStart = true)) +
                listOf(line(1, "CAPÍTULO II", y = 100f, x = 240f, right = 330f)) +
                pageLines(1, "Fim." to SHORT, startY = 150f),
        )

        assertEquals(
            listOf("CAPÍTULO I", "A primeira frase chegou.", "A segunda frase chegou.", "Outro parágrafo.", "CAPÍTULO II", "Fim."),
            book.sentences.map { it.ttsText },
        )
        assertEquals(
            listOf(
                Prosody.HEADING_TAIL_MS,     // after the chapter title
                Prosody.SENTENCE_PAUSE_MS,   // between two sentences of one paragraph
                Prosody.PARAGRAPH_PAUSE_MS,  // into the next paragraph
                Prosody.CHAPTER_PAUSE_MS,    // into the next chapter
                Prosody.HEADING_TAIL_MS,
                0,                           // end of the book
            ),
            book.sentences.indices.map { Prosody.pauseAfterMs(book, it) },
        )
    }

    @Test
    fun `a chapter number and its title are announced together`() {
        // Full-width body lines give the page its margins, so the short centred lines above read as a title.
        val book = build(
            listOf(
                line(0, "CAPÍTULO I", y = 100f, x = 240f, right = 330f),
                line(0, "Do título.", y = 128f, x = 240f, right = 330f),
            ) + pageLines(0, "Primeira frase e o resto dela" to FULL, "até o fim." to SHORT, startY = 170f),
        )

        assertEquals(listOf("CAPÍTULO I", "Do título.", "Primeira frase e o resto dela até o fim."),
            book.sentences.map { it.ttsText })
        assertEquals(Prosody.HEADING_SUBTITLE_MS, Prosody.pauseAfterMs(book, 0))
        assertEquals(Prosody.HEADING_TAIL_MS, Prosody.pauseAfterMs(book, 1))
    }

    @Test
    fun `a title is announced more slowly than the text`() {
        val book = build(
            listOf(line(0, "CAPÍTULO I", y = 100f, x = 240f, right = 330f)) +
                pageLines(0, "Primeira frase." to SHORT, startY = 150f),
        )
        assertEquals(Prosody.HEADING_SPEED, Prosody.speedFactor(book, 0), 1e-4f)
        assertEquals(1f, Prosody.speedFactor(book, 1), 1e-4f)
    }

    @Test
    fun `a scene break is heard as silence, not read out loud`() {
        val book = build(
            pageLines(0, "Fim da cena." to SHORT, startY = 100f) +
                listOf(line(0, "* * *", y = 128f, x = 240f, right = 330f)) +
                listOf(line(0, "Começa outra.", y = 156f, x = 90f, right = SHORT, paragraphStart = true)),
        )

        // The break is a paragraph of its own but never becomes a sentence, so the voice never reads it.
        assertEquals(listOf("Fim da cena.", "Começa outra."), book.sentences.map { it.ttsText })
        assertTrue("no scene-break paragraph in ${book.paragraphs.map { it.text }}",
            book.paragraphs.indices.any { Prosody.isSceneBreak(book, it) })
        // Crossing it is what the listener hears: a longer silence than between two paragraphs.
        assertEquals(Prosody.SCENE_PAUSE_MS, Prosody.pauseAfterMs(book, 0))
    }

    @Test
    fun `a suspended sentence and a change of speaker get their own pauses`() {
        val book = build(
            pageLines(0, "Ele parou..." to FULL, "Depois seguiu." to SHORT, startY = 100f) +
                listOf(line(0, "— Não vou — disse ele. — Nunca.", y = 142f, x = 90f, right = SHORT, paragraphStart = true)),
        )

        val texts = book.sentences.map { it.ttsText }
        val suspended = texts.indexOfFirst { it.endsWith("...") || it.endsWith("…") }
        assertTrue("no suspended sentence in $texts", suspended >= 0)
        assertEquals(Prosody.ELLIPSIS_PAUSE_MS, Prosody.pauseAfterMs(book, suspended))

        val reply = texts.indexOfFirst { it.startsWith("Não vou") }
        assertTrue("no reply in $texts", reply >= 0)
        assertEquals(Prosody.SPEAKER_PAUSE_MS, Prosody.pauseAfterMs(book, reply))
    }

    @Test
    fun `an interrupted line gives way at once, even to the next paragraph`() {
        val book = build(
            pageLines(0, "— Eu só queria—" to SHORT, startY = 100f) +
                listOf(line(0, "— Cala a boca!", y = 128f, x = 90f, right = SHORT, paragraphStart = true)),
        )
        val cut = book.sentences.indexOfFirst { it.ttsText.startsWith("Eu só queria") }
        assertTrue("no interrupted line in ${book.sentences.map { it.ttsText }}", cut >= 0)
        assertEquals(Prosody.CUT_OFF_PAUSE_MS, Prosody.pauseAfterMs(book, cut))
    }

    @Test
    fun `a one-word sentence is a beat the narrator lets land`() {
        val book = build(pageLines(0, "Tum. Tum. Tum. Depois, o silêncio voltou" to FULL, "para a casa." to SHORT, startY = 100f))
        val texts = book.sentences.map { it.ttsText }
        assertEquals(listOf("Tum.", "Tum.", "Tum.", "Depois, o silêncio voltou para a casa."), texts)
        assertEquals(Prosody.FRAGMENT_PAUSE_MS, Prosody.pauseAfterMs(book, 0))
        assertEquals(Prosody.FRAGMENT_PAUSE_MS, Prosody.pauseAfterMs(book, 2))
    }

    @Test
    fun `a stretched scream is read slower so it lasts`() {
        val book = build(pageLines(0, "Aaaaaaaah!" to SHORT, "Ela correu pela escada abaixo sem olhar" to FULL, "para trás." to SHORT, startY = 100f))
        val scream = book.sentences.indexOfFirst { it.ttsText.startsWith("Aaaa") }
        assertTrue("no scream in ${book.sentences.map { it.ttsText }}", scream >= 0)
        assertEquals(Prosody.SCREAM_SPEED, Prosody.speedFactor(book, scream), 1e-4f)
        assertEquals(1f, Prosody.speedFactor(book, book.sentences.lastIndex), 1e-4f)
    }
}
