package io.github.fanti1.audiolivre.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueSplitterTest {

    private fun pieces(text: String) = DialogueSplitter.split(text).map { Triple(it.kind, text.substring(it.start, it.end), it.genderHint) }

    @Test
    fun `splits dash dialogue interrupted by the narrator`() {
        assertEquals(
            listOf(
                Triple(SegmentKind.SPEECH, "— Não vou", GenderHint.MALE),
                Triple(SegmentKind.NARRATION, "— disse ele.", GenderHint.UNKNOWN),
                Triple(SegmentKind.SPEECH, "— Nunca.", GenderHint.MALE),
            ),
            pieces("— Não vou — disse ele. — Nunca."),
        )
    }

    @Test
    fun `a named speaker gets a voice too`() {
        assertEquals(
            listOf(
                Triple(SegmentKind.SPEECH, "— Onde você estava?", GenderHint.FEMALE),
                Triple(SegmentKind.NARRATION, "— perguntou Maria, irritada.", GenderHint.UNKNOWN),
            ),
            pieces("— Onde você estava? — perguntou Maria, irritada."),
        )
        assertEquals(
            listOf(
                Triple(SegmentKind.SPEECH, "— Não fui eu.", GenderHint.MALE),
                Triple(SegmentKind.NARRATION, "— disse o Escobar.", GenderHint.UNKNOWN),
            ),
            pieces("— Não fui eu. — disse o Escobar."),
        )
    }

    @Test
    fun `a word that only looks like a name is not one`() {
        // "disse a verdade" must not turn the speaker into a woman, and a pronoun still wins over a name.
        assertEquals(
            listOf(
                Triple(SegmentKind.SPEECH, "— Está tudo certo", GenderHint.UNKNOWN),
                Triple(SegmentKind.NARRATION, "— disse, sem olhar para trás.", GenderHint.UNKNOWN),
            ),
            pieces("— Está tudo certo — disse, sem olhar para trás."),
        )
        assertEquals(
            listOf(
                Triple(SegmentKind.SPEECH, "— Vamos embora", GenderHint.MALE),
                Triple(SegmentKind.NARRATION, "— disse ele a Maria.", GenderHint.UNKNOWN),
            ),
            pieces("— Vamos embora — disse ele a Maria."),
        )
    }

    @Test
    fun `unspaced dashes and a female pronoun`() {
        assertEquals(
            listOf(
                Triple(SegmentKind.SPEECH, "—Não vou", GenderHint.FEMALE),
                Triple(SegmentKind.NARRATION, "—disse ela.", GenderHint.UNKNOWN),
                Triple(SegmentKind.SPEECH, "—Nunca.", GenderHint.FEMALE),
            ),
            pieces("—Não vou—disse ela.—Nunca."),
        )
    }

    @Test
    fun `a pause inside the speech is not narration`() {
        assertEquals(
            listOf(Triple(SegmentKind.SPEECH, "— Ela era — como dizer — estranha.", GenderHint.UNKNOWN)),
            pieces("— Ela era — como dizer — estranha."),
        )
    }

    @Test
    fun `narration with a descriptive subject is recognized by the speech verb`() {
        val result = pieces("— Vá embora! — a velha gritou da janela.")

        assertEquals(listOf(SegmentKind.SPEECH, SegmentKind.NARRATION), result.map { it.first })
        assertEquals(GenderHint.FEMALE, result[0].third)
    }

    @Test
    fun `quoted speech inside narration`() {
        assertEquals(
            listOf(
                Triple(SegmentKind.NARRATION, "Ele gritou:", GenderHint.UNKNOWN),
                Triple(SegmentKind.SPEECH, "“Corre, agora!”", GenderHint.MALE),
                Triple(SegmentKind.NARRATION, "e fugiu.", GenderHint.UNKNOWN),
            ),
            pieces("Ele gritou: “Corre, agora!” e fugiu."),
        )
    }

    @Test
    fun `quoted titles stay in the narration`() {
        assertEquals(
            listOf(Triple(SegmentKind.NARRATION, "Ela leu “Dom Casmurro” ontem.", GenderHint.UNKNOWN)),
            pieces("Ela leu “Dom Casmurro” ontem."),
        )
    }

    @Test
    fun `plain narration is a single segment`() {
        val text = "  A noite caiu sobre a cidade silenciosa.  "

        assertEquals(
            listOf(Triple(SegmentKind.NARRATION, "A noite caiu sobre a cidade silenciosa.", GenderHint.UNKNOWN)),
            pieces(text),
        )
    }

    @Test
    fun `hyphen at line start is dialogue only when it looks like speech`() {
        assertTrue(DialogueSplitter.startsWithDialogueDash("- Oi, tudo bem?"))
        assertTrue(DialogueSplitter.startsWithDialogueDash("-Oi, tudo bem?"))
        assertTrue(DialogueSplitter.startsWithDialogueDash("— Oi"))
        assertFalse(DialogueSplitter.startsWithDialogueDash("-chuva molhado"))
        assertFalse(DialogueSplitter.startsWithDialogueDash("A noite caiu."))
    }

    @Test
    fun `a speech tag hung off a comma is narration, not part of the line`() {
        // Machado's usual shape: no second dash, the tag rides on a comma. Without this the line has no
        // narration beside it and nobody knows who is speaking.
        assertEquals(
            listOf(
                Triple(SegmentKind.SPEECH, "— Já acabei,", GenderHint.MALE),
                Triple(SegmentKind.NARRATION, "murmurou ele.", GenderHint.UNKNOWN),
            ),
            pieces("— Já acabei, murmurou ele."),
        )
    }

    @Test
    fun `the tag may hang off a question mark, and the verb may carry its pronoun`() {
        assertEquals(
            listOf(
                Triple(SegmentKind.SPEECH, "— Que é?", GenderHint.FEMALE),
                Triple(SegmentKind.NARRATION, "perguntou ela como se ouvira mal.", GenderHint.UNKNOWN),
            ),
            pieces("— Que é? perguntou ela como se ouvira mal."),
        )
        assertEquals(
            listOf(
                Triple(SegmentKind.SPEECH, "— Que é?", GenderHint.FEMALE),
                Triple(SegmentKind.NARRATION, "perguntou-lhe minha mãe.", GenderHint.UNKNOWN),
            ),
            pieces("— Que é? perguntou-lhe minha mãe."),
        )
    }

    @Test
    fun `a capital after the question mark means the character is still talking`() {
        assertEquals(
            listOf(Triple(SegmentKind.SPEECH, "— Saiu? Disse que voltava.", GenderHint.UNKNOWN)),
            pieces("— Saiu? Disse que voltava."),
        )
    }

    @Test
    fun `a pre-1943 pronoun still names the speaker`() {
        // "elle" is how every public-domain classic spells it; the markers are modern.
        assertEquals(
            listOf(
                Triple(SegmentKind.SPEECH, "— Não consinto,", GenderHint.MALE),
                Triple(SegmentKind.NARRATION, "disse elle.", GenderHint.UNKNOWN),
            ),
            pieces("— Não consinto, disse elle."),
        )
    }

    @Test
    fun `a quoted citation keeps the narrator, a quoted line does not`() {
        // A run of words in quotes is a citation unless it is a finished sentence or somebody is speaking.
        assertEquals(
            listOf(Triple(SegmentKind.NARRATION, "Lembrei o verso “na doce e rara mãe”.", GenderHint.UNKNOWN)),
            pieces("Lembrei o verso “na doce e rara mãe”."),
        )
        assertEquals(
            listOf(
                Triple(SegmentKind.NARRATION, "Ela insistiu:", GenderHint.UNKNOWN),
                Triple(SegmentKind.SPEECH, "“na doce e rara mãe”", GenderHint.FEMALE),
            ),
            pieces("Ela insistiu: “na doce e rara mãe”."),
        )
    }
}
