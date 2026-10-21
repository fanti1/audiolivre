package io.github.fanti1.audiolivre.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderFooterFilterTest {

    private val words = listOf("alfa", "bravo", "charlie", "delta", "eco", "foxtrote", "golfe", "hotel", "índia", "julieta")

    private fun bookPages(pageCount: Int): List<PdfLine> = (0 until pageCount).flatMap { page ->
        val header = if (page % 2 == 0) "O Mistério da Casa Velha" else "Capítulo ${page / 4 + 1}: A Chegada"
        listOf(
            line(page, header, y = 40f, x = 200f, right = 380f, size = 9f),
            line(page, "Texto corrido da página ${words[page]} que continua", y = 100f),
            line(page, "e segue adiante sem parar por nenhum motivo aparente", y = 114f),
            line(page, "até chegar ao fim do parágrafo ${words[page]}.", y = 128f, right = SHORT),
            line(page, "${page + 10}", y = 760f, x = 280f, right = 292f, size = 9f),
        )
    }

    @Test
    fun `removes running headers per page parity and page numbers`() {
        val kept = HeaderFooterFilter.filter(bookPages(10), BODY)

        assertEquals(30, kept.size)
        assertTrue(kept.none { "Mistério" in it.text || "A Chegada" in it.text })
        assertTrue(kept.none { it.text.all(Char::isDigit) })
    }

    @Test
    fun `drops roman page numbers and decorated page labels`() {
        val lines = listOf(
            line(0, "Uma frase qualquer do prefácio.", y = 100f),
            line(0, "xii", y = 760f, x = 280f, right = 292f),
            line(1, "Outra frase do prefácio.", y = 100f),
            line(1, "- 13 -", y = 760f, x = 280f, right = 300f),
            line(2, "Página 14", y = 760f, x = 280f, right = 320f),
        )

        val kept = HeaderFooterFilter.filter(lines, BODY).map { it.text }

        assertEquals(listOf("Uma frase qualquer do prefácio.", "Outra frase do prefácio."), kept)
    }

    @Test
    fun `keeps prose that only happens to sit in a header slot on few pages`() {
        val lines = (0 until 6).flatMap { page ->
            listOf(
                line(page, if (page < 2) "— Vamos embora!" else "Linha diferente ${words[page]} do texto.", y = 100f),
                line(page, "Mais texto da página ${words[page]}.", y = 114f),
            )
        }

        val kept = HeaderFooterFilter.filter(lines, BODY).map { it.text }

        assertEquals(12, kept.size)
        assertEquals(2, kept.count { it == "— Vamos embora!" })
    }

    @Test
    fun `keeps a large chapter number even though it looks like a page number`() {
        val lines = listOf(
            line(0, "3", y = 200f, x = 280f, right = 300f, size = 28f),
            line(0, "Começou a chover naquela tarde.", y = 260f),
        )

        val kept = HeaderFooterFilter.filter(lines, BODY).map { it.text }

        assertEquals(listOf("3", "Começou a chover naquela tarde."), kept)
    }
}
