package io.github.fanti1.audiolivre.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnomatopoeiaTest {

    private fun spoken(text: String) = PtBrNormalizer.normalize(text)

    @Test
    fun `hushes, hums and buzzes are said, never spelled`() {
        // Measured on the app's voice: "Shhhh" came out "esse agá agá agá" and "Zzzzz" "zê zê zê zê".
        assertEquals("Psiu.", spoken("Shhhh."))
        assertEquals("Psiu.", spoken("SHHHH."))
        assertEquals("Psiu, ele está ouvindo.", spoken("Psst, ele está ouvindo."))
        assertEquals("Hum.", spoken("Hmmmm."))
        assertEquals("Hum, deixe-me pensar.", spoken("Hmm, deixe-me pensar."))
        assertEquals("Zum.", spoken("Zzzzz."))
    }

    @Test
    fun `growls, shivers and hisses have a spoken form`() {
        assertEquals("Grrrom, rosnou o cão.", spoken("Grrrrr, rosnou o cão."))
        assertEquals("Brrru, que frio.", spoken("Brrr, que frio."))
        assertEquals("Chiii, fez a cobra.", spoken("Sssss, fez a cobra."))
    }

    @Test
    fun `a laugh is read as beats of a laugh`() {
        // "Hahaha" collapsed into a single "ah"; "rá rá rá" is heard as a laugh.
        assertEquals("Rá rá rá!", spoken("Hahaha!"))
        assertEquals("Rê, rê, rê.", spoken("Hehehe."))
        assertEquals("Uá uá uá!", spoken("HUAHUAHUA!"))
        assertEquals("Ká ká.", spoken("Kkkk."))
        assertEquals("Risos.", spoken("Rsrs."))
    }

    @Test
    fun `doors, shots, knocks and machines`() {
        assertEquals("A porta fez nhéc.", spoken("A porta fez nhééééc."))
        assertEquals("Bangue!", spoken("BANG!"))
        assertEquals("Tóque, tóque, tóque.", spoken("Toc-toc-toc."))
        assertEquals("Tique-taque.", spoken("Tic-tac."))
        assertEquals("Dim dom.", spoken("Ding-dong."))
        assertEquals("Brum!", spoken("Buuuuum!"))
        assertEquals("Clic.", spoken("Click."))
    }

    @Test
    fun `the body and the animals`() {
        assertEquals("Cófi, cófi.", spoken("Cof-cof."))
        assertEquals("Árgui!", spoken("Argh!"))
        assertEquals("Au-au!", spoken("Au-au!"))
        assertEquals("Auu!", spoken("Auuuuuuuu!"))
        assertEquals("Óinque.", spoken("Oinc."))
    }

    @Test
    fun `a scream stays long, a stretched nasal vowel is squeezed`() {
        assertEquals("Aaaaaah!", spoken("Aaaaaaaaaaah!"))
        assertEquals("Socorrooooo!", spoken("Socorrooooo!"))
        assertEquals("Não!", spoken("Nããããão!"))
        assertTrue(Onomatopoeia.isDrawnOut("Aaaaaaah!"))
        assertFalse(Onomatopoeia.isDrawnOut("Ele gritou aaaaah e saiu correndo pela rua escura."))
    }

    @Test
    fun `each sound keeps the pace chosen for it`() {
        // Laughs were marked "mais rápido"; the howl "um instante mais longo".
        assertEquals(1.3f, Onomatopoeia.speedOf("Hahaha!")!!, 1e-4f)
        assertEquals(0.8f, Onomatopoeia.speedOf("Auuuuuu!")!!, 1e-4f)
        assertEquals(null, Onomatopoeia.speedOf("Hahaha, disse ele."))
        assertEquals(null, Onomatopoeia.speedOf("Tum."))
    }

    @Test
    fun `stretched cries take the form chosen by ear`() {
        assertEquals("Aaai!", spoken("Aiiii!"))
        assertEquals("Ooooh.", spoken("Ohhh."))
        assertEquals("Fiiiu!", spoken("Fiuuu!"))
        assertEquals("Mééé.", spoken("Mééééé."))
    }

    @Test
    fun `a fearful stutter repeats the first syllable`() {
        assertEquals("Eu, eu vi.", spoken("E-eu vi."))
        assertEquals("Nã... nã... não sei.", spoken("N-n-não sei."))
        assertEquals("Si... si... sim.", spoken("S-s-sim."))
        assertEquals("Que... quero ir.", spoken("Q-quero ir."))
        assertEquals("Ai, ai, ai.", spoken("Ai-ai-ai."))
    }

    @Test
    fun `ordinary words that look like sounds are left alone`() {
        // The catalogue is matched against every word of the book, so these are the ones that matter most.
        assertEquals("E aí, tudo bem?", spoken("E aí, tudo bem?"))
        assertEquals("Ele me disse que viria.", spoken("Ele me disse que viria."))
        assertEquals("O rim estava bem.", spoken("O rim estava bem."))
        assertEquals("Ah, entendi.", spoken("Ah, entendi."))
        assertEquals("Ai, que dor.", spoken("Ai, que dor."))
        assertEquals("Han Solo apareceu.", spoken("Han Solo apareceu."))
        assertEquals("Dom Casmurro chegou.", spoken("Dom Casmurro chegou."))
        assertEquals("Seja bem-vindo, pegue o guarda-chuva.", spoken("Seja bem-vindo, pegue o guarda-chuva."))
        assertEquals("Mande um e-mail.", spoken("Mande um e-mail."))
        assertEquals("Senhor Almeida chegou.", spoken("Sr. Almeida chegou."))
        assertTrue(spoken("Mediu 5 mm.").contains("milímetros"))
        assertEquals("Hum, que delícia.", spoken("Mmmm, que delícia."))
        assertTrue(spoken("Pesou 200 gr.").contains("gramas") || spoken("Pesou 200 gr.").contains("gr"))
        assertTrue(spoken("Veja em site.com.br").contains("bê érre"))
        assertEquals("Henrique oitavo", spoken("Henrique VIII"))
        assertFalse(spoken("Belém, PA.").contains("Pá"))
    }
}
