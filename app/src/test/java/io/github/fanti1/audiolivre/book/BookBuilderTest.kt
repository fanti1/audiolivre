package io.github.fanti1.audiolivre.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookBuilderTest {

    private fun paragraphs(book: BookDocument) = book.paragraphs.map { it.text }

    @Test
    fun `lines of one paragraph are joined and a flagged indent starts a new one`() {
        val book = build(
            pageLines(
                0,
                "A chuva caía forte sobre os telhados da vila" to FULL,
                "quando o velho abriu a porta da taverna." to SHORT,
            ) + line(0, "Ninguém se mexeu no salão escuro e abafado,", y = 128f, x = 90f, paragraphStart = true) +
                line(0, "nem mesmo o cão.", y = 142f, right = SHORT),
        )

        assertEquals(
            listOf(
                "A chuva caía forte sobre os telhados da vila quando o velho abriu a porta da taverna.",
                "Ninguém se mexeu no salão escuro e abafado, nem mesmo o cão.",
            ),
            paragraphs(book),
        )
    }

    @Test
    fun `short line ending a sentence closes the paragraph even without an indent flag`() {
        val book = build(
            pageLines(
                0,
                "Era uma vez um reino muito distante onde todos" to FULL,
                "viviam em paz." to SHORT,
                "Um dia, porém, tudo mudou de maneira terrível" to FULL,
                "e sem aviso." to SHORT,
            ),
        )

        assertEquals(
            listOf("Era uma vez um reino muito distante onde todos viviam em paz.", "Um dia, porém, tudo mudou de maneira terrível e sem aviso."),
            paragraphs(book),
        )
    }

    @Test
    fun `a full-width line ending with a period does not close the paragraph`() {
        val book = build(
            pageLines(
                0,
                "Ele olhou em volta, respirou fundo e entrou na sala." to FULL,
                "Lá dentro, tudo estava quieto demais para aquela hora" to FULL,
                "da noite." to SHORT,
            ),
        )

        assertEquals(1, book.paragraphs.size)
    }

    @Test
    fun `a dialogue dash starts a paragraph`() {
        val book = build(
            pageLines(
                0,
                "Ele entrou sem bater na porta da cozinha escura." to FULL,
                "— Quem está aí? — perguntou a mulher, assustada," to FULL,
                "largando a panela no chão." to SHORT,
            ),
        )

        assertEquals(
            listOf(
                "Ele entrou sem bater na porta da cozinha escura.",
                "— Quem está aí? — perguntou a mulher, assustada, largando a panela no chão.",
            ),
            paragraphs(book),
        )
    }

    @Test
    fun `a dash that wraps to the start of a line mid-sentence does not start a paragraph`() {
        val book = build(
            pageLines(
                0,
                "O que ele queria, afinal, era apenas o seu nome" to FULL,
                "— o nome inteiro, com todos os títulos — e nada mais." to SHORT,
            ),
        )

        assertEquals(1, book.paragraphs.size)
    }

    @Test
    fun `a large vertical gap starts a paragraph`() {
        val book = build(
            listOf(
                line(0, "A primeira parte terminava ali mesmo no meio", y = 100f),
                line(0, "da estrada deserta e poeirenta", y = 114f),
                line(0, "de onde ninguém jamais tinha voltado vivo", y = 128f),
                line(0, "A segunda parte começava muito depois disso", y = 200f),
                line(0, "com outra história completamente diferente", y = 214f),
            ),
        )

        assertEquals(2, book.paragraphs.size)
    }

    @Test
    fun `a paragraph that continues mid-sentence on the next page is merged`() {
        val book = build(
            pageLines(0, "Ela correu pela floresta escura sem olhar para" to FULL, "trás, até que finalmente" to FULL) +
                pageLines(1, "chegou à beira do rio gelado." to SHORT),
        )

        assertEquals(
            listOf("Ela correu pela floresta escura sem olhar para trás, até que finalmente chegou à beira do rio gelado."),
            paragraphs(book),
        )
        assertEquals(listOf(0), book.paragraphs.map { it.sourcePage })
    }

    @Test
    fun `a word hyphenated across pages is rejoined`() {
        val book = build(
            pageLines(0, "No alto da colina havia uma casa abando-" to FULL) +
                pageLines(1, "nada há muitos anos." to SHORT),
        )

        assertEquals(listOf("No alto da colina havia uma casa abandonada há muitos anos."), paragraphs(book))
    }

    @Test
    fun `a new paragraph at the top of the next page is kept separate`() {
        val book = build(
            pageLines(0, "O sino tocou três vezes naquela madrugada fria" to FULL, "e silenciosa." to SHORT) +
                pageLines(1, "Na manhã seguinte, ninguém falava sobre o assunto" to FULL, "na vila." to SHORT) +
                pageLines(2, "— Você ouviu o sino? — perguntou o menino." to SHORT),
        )

        assertEquals(3, book.paragraphs.size)
        assertEquals(listOf(0, 1, 2), book.paragraphs.map { it.sourcePage })
    }

    @Test
    fun `chapters come from the outline, including chapters that start mid-page`() {
        val book = build(
            pageLines(0, "Um prefácio breve sobre a obra e o autor." to SHORT) +
                line(1, "Capítulo 1", y = 100f, x = 220f, right = 320f, size = 18f) +
                pageLines(1, "Era uma vez um reino muito distante." to SHORT, startY = 140f) +
                pageLines(2, "E assim terminou a primeira parte." to SHORT) +
                line(2, "Capítulo 2", y = 160f, x = 220f, right = 320f, size = 18f) +
                pageLines(2, "Tudo mudou naquele dia." to SHORT, startY = 200f),
            outline = listOf(
                OutlineEntry("Parte I", 1, level = 0),
                OutlineEntry("Capítulo 1", 1, level = 1),
                OutlineEntry("Capítulo 2", 2, level = 1),
            ),
        )

        assertEquals(
            listOf(
                "Um prefácio breve sobre a obra e o autor.",
                "Capítulo 1",
                "Era uma vez um reino muito distante.",
                "E assim terminou a primeira parte.",
                "Capítulo 2",
                "Tudo mudou naquele dia.",
            ),
            paragraphs(book),
        )
        assertEquals(
            listOf(Chapter("Livro Teste", 0), Chapter("Capítulo 1", 1), Chapter("Capítulo 2", 4)),
            book.chapters,
        )
        assertEquals(listOf(0, 1, 1, 1, 2, 2), book.paragraphs.map { it.chapterIndex })
        assertEquals(listOf(false, true, false, false, true, false), book.paragraphs.map { it.isHeading })
    }

    @Test
    fun `without an outline chapter headings are detected from the text`() {
        val book = build(
            listOf(line(0, "CAPÍTULO IV", y = 100f, x = 240f, right = 330f)) +
                pageLines(0, "Era uma noite escura e tempestuosa." to SHORT, startY = 150f) +
                listOf(line(1, "CAPÍTULO V", y = 100f, x = 240f, right = 330f)) +
                pageLines(1, "O dia amanheceu cinzento." to SHORT, startY = 150f),
        )

        assertEquals(listOf(Chapter("CAPÍTULO IV", 0), Chapter("CAPÍTULO V", 2)), book.chapters)
        assertEquals(listOf(true, false, true, false), book.paragraphs.map { it.isHeading })
    }

    @Test
    fun `a centered title line under a chapter heading is part of the heading`() {
        val book = build(
            listOf(
                line(0, "CAPÍTULO I", y = 100f, x = 240f, right = 330f, size = 15f),
                line(0, "Do título.", y = 124f, x = 262f, right = 310f, size = 12f),
            ) + pageLines(
                0,
                "Uma noite destas, vindo da cidade para o Engenho Novo," to FULL,
                "encontrei no trem da Central um rapaz aqui do bairro." to SHORT,
                "Fim." to 100f,
                startY = 160f,
            ),
        )

        assertEquals(listOf(true, true, false, false), book.paragraphs.map { it.isHeading })
        assertEquals(
            "Uma noite destas, vindo da cidade para o Engenho Novo, encontrei no trem da Central um rapaz aqui do bairro.",
            book.paragraphs[2].text,
        )
    }

    @Test
    fun `a book without chapter markers is a single chapter named after the book`() {
        val book = build(pageLines(0, "Apenas um texto simples." to SHORT), title = "Meu Livro")

        assertEquals(listOf(Chapter("Meu Livro", 0)), book.chapters)
    }

    @Test
    fun `dialogue becomes speech and narration sentences with clean text for the voice`() {
        val book = build(pageLines(0, "— Não vou — disse ele. — Nunca." to SHORT))

        assertEquals(listOf("Não vou", "disse ele.", "Nunca."), book.sentences.map { it.ttsText })
        assertEquals(listOf("— Não vou", "— disse ele.", "— Nunca."), book.displayTexts())
        assertEquals(
            listOf(SegmentKind.SPEECH, SegmentKind.NARRATION, SegmentKind.SPEECH),
            book.sentences.map { it.kind },
        )
        assertEquals(GenderHint.MALE, book.sentences.first().genderHint)
    }

    @Test
    fun `sentence offsets are consistent with the paragraph text`() {
        val book = build(
            pageLines(0, "Primeira frase. Segunda frase do mesmo parágrafo." to SHORT) +
                pageLines(1, "— Uma fala! — gritou ela. — Outra fala." to SHORT) +
                pageLines(2, "Última frase do livro." to SHORT),
        )

        var paragraphStart = 0
        val starts = book.paragraphs.map { p -> paragraphStart.also { paragraphStart += p.text.length + 1 } }
        for (s in book.sentences) {
            val paragraph = book.paragraphs[s.paragraphIndex]
            assertTrue(s.startInParagraph < s.endInParagraph)
            assertTrue(s.endInParagraph <= paragraph.text.length)
            assertEquals(starts[s.paragraphIndex] + s.startInParagraph, s.charOffset)
            assertEquals(s.index, book.sentenceAtOffset(s.charOffset))
            assertEquals(s.index, book.sentenceAtOffset(s.charOffset + 1))
        }
        assertEquals(book.sentences.map { it.charOffset }.sorted().distinct(), book.sentences.map { it.charOffset })
        assertEquals(0, book.sentenceAtOffset(-5))
        assertEquals(book.sentences.lastIndex, book.sentenceAtOffset(Int.MAX_VALUE))
    }

    @Test
    fun `legacy page positions map to the matching sentence`() {
        val book = build(
            pageLines(0, "Primeira. Segunda." to SHORT) +
                pageLines(1, "Terceira. Quarta. Quinta." to SHORT) +
                pageLines(3, "Sexta." to SHORT),
        )

        assertEquals(3, book.sentenceForLegacyPosition(page = 1, indexInPage = 1))
        assertEquals(4, book.sentenceForLegacyPosition(page = 1, indexInPage = 9))
        assertEquals(0, book.sentenceForLegacyPosition(page = 0, indexInPage = 0))
        assertEquals(5, book.sentenceForLegacyPosition(page = 2, indexInPage = 0))
        assertEquals(5, book.sentenceForLegacyPosition(page = 7, indexInPage = 0))
    }

    @Test
    fun `chapter of a sentence follows its paragraph`() {
        val book = build(
            listOf(line(0, "CAPÍTULO I", y = 100f, x = 240f, right = 330f)) +
                pageLines(0, "Texto do primeiro capítulo." to SHORT, startY = 150f) +
                listOf(line(1, "CAPÍTULO II", y = 100f, x = 240f, right = 330f)) +
                pageLines(1, "Texto do segundo capítulo." to SHORT, startY = 150f),
        )

        assertEquals(listOf(0, 0, 1, 1), book.sentences.indices.map(book::chapterOf))
    }
}
