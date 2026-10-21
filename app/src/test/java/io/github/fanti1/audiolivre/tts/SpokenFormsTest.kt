package io.github.fanti1.audiolivre.tts

import org.junit.Assert.assertEquals
import org.junit.Test

// The reading errors the listening QA (qa/listen_check.py) found, pinned down one by one.
class SpokenFormsTest {

    private fun n(text: String) = PtBrNormalizer.normalize(text)

    @Test
    fun `money with a scale word`() {
        assertEquals("A empresa faturou dois vírgula cinco milhões de reais.", n("A empresa faturou R$ 2,5 milhões."))
        assertEquals("Custou três mil reais.", n("Custou R$ 3 mil."))
        assertEquals("Um prêmio de um milhão de dólares.", n("Um prêmio de US$ 1 milhão."))
    }

    @Test
    fun `a number before a preposition stays masculine`() {
        assertEquals(
            "A guerra durou de mil novecentos e trinta e nove a mil novecentos e quarenta e cinco.",
            n("A guerra durou de 1939 a 1945."),
        )
        assertEquals("O placar terminou três a um para o time da casa.", n("O placar terminou 3 x 1 para o time da casa."))
        assertEquals("Foi a primeira de duas pessoas, e uma vez só.", n("Foi a primeira de 2 pessoas, e 1 vez só."))
    }

    @Test
    fun dates() {
        assertEquals("Nasceu em doze de março de mil novecentos e oitenta e sete.", n("Nasceu em 12/03/1987."))
        assertEquals("Prazo: primeiro de janeiro de dois mil e vinte e cinco.", n("Prazo: 01/01/2025."))
        assertEquals("Em trinta e um de dezembro de noventa e nove.", n("Em 31-12-99."))
    }

    @Test
    fun ranges() {
        assertEquals(
            "Entre mil novecentos e quatorze a mil novecentos e dezoito.",
            n("Entre 1914-1918."),
        )
        assertEquals("Leia as páginas quarenta e cinco a cinquenta e dois.", n("Leia as páginas 45–52."))
    }

    @Test
    fun `phone numbers are read digit by digit`() {
        assertEquals(
            "Ligue para onze, nove oito sete seis cinco, quatro três dois um agora.",
            n("Ligue para (11) 98765-4321 agora."),
        )
    }

    @Test
    fun `temperatures and units`() {
        assertEquals("Caiu para menos três graus Celsius.", n("Caiu para -3 °C."))
        assertEquals("Fazia vinte e cinco graus.", n("Fazia 25°."))
        assertEquals("Atingiu cento e oitenta quilômetros por hora.", n("Atingiu 180 km/h."))
        assertEquals("A sala tem vinte e cinco metros quadrados.", n("A sala tem 25 m²."))
        assertEquals("Tomou quinhentos miligramas.", n("Tomou 500 mg."))
        assertEquals("Mediu um metro.", n("Mediu 1 m."))
        assertEquals("Uma tábua de dois por três metros.", n("Uma tábua de 2 x 3 m."))
    }

    @Test
    fun `fractions, numbered laws and versions`() {
        assertEquals("Ainda faltam três quartos do caminho.", n("Ainda faltam 3/4 do caminho."))
        assertEquals("Meio litro.", n("1/2 litro."))
        assertEquals("A Lei número oito mil e sessenta e nove de mil novecentos e noventa.", n("A Lei nº 8.069/1990."))
        assertEquals("Aberto vinte e quatro por sete.", n("Aberto 24/7."))
        assertEquals("A versão dois ponto zero saiu.", n("A versão 2.0 saiu."))
    }

    @Test
    fun acronyms() {
        assertEquals("O cê pê éfe e o érre gê.", n("O CPF e o RG."))
        assertEquals("A onu e os Estados Unidos.", n("A ONU e os EUA."))
        assertEquals("O i bê gê é divulgou o pib.", n("O IBGE divulgou o PIB."))
        assertEquals("A unesco e a fifa.", n("A UNESCO e a FIFA."))
        assertEquals("Está okei.", n("Está OK."))
        assertEquals("Capítulo um, A casa do fim.", n("CAPÍTULO I — A CASA DO FIM."))
        assertEquals("Leia as instruções.", n("Leia as INSTRUÇÕES."))
        assertEquals("cê pê éfe E érre gê", n("CPF E RG"))
    }

    @Test
    fun `roman numerals outside chapter headings`() {
        assertEquals("quarenta e quatro O primeiro filho quarenta e cinco As pazes", n("XLIV O primeiro filho XLV As pazes"))
        assertEquals("No ato três, o rei entra.", n("No ato III, o rei entra."))
        assertEquals("Grave um cê dê.", n("Grave um CD."))
    }

    @Test
    fun `web addresses`() {
        assertEquals(
            "Escreva para contato arroba editora ponto com ponto bê érre.",
            n("Escreva para contato@editora.com.br."),
        )
        assertEquals("Visite dáblio dáblio dáblio ponto editora ponto com.", n("Visite www.editora.com."))
    }

    @Test
    fun `dona and dom`() {
        assertEquals("E dona Glória chorou.", n("E D. Glória chorou."))
        assertEquals("O reinado de dom Pedro segundo.", n("O reinado de D. Pedro II."))
    }

    @Test
    fun `pdf artifacts`() {
        assertEquals("O filho do florista ficou aflito.", n("O \uFB01lho do \uFB02orista \uFB01cou a\uFB02ito."))
        assertEquals("Era uma proposta irrecusável.", n("Era uma pro\u00ADposta irrecusável."))
        assertEquals("Os resultados foram surpreendentes, segundo o autor.", n("Os resultados foram surpreendentes1, segundo o autor."))
        assertEquals("Ele comeu maçã.", n("Ele comeu mac\u0327a\u0303."))
        assertEquals("\"Venha cá\", gritou.", n("«Venha cá», gritou."))
        assertEquals("Um dado, veja a tabela, confirma.", n("Um dado (veja a tabela) confirma."))
    }

    @Test
    fun `archaic spelling is spoken the modern way`() {
        assertEquals("Perguntou ele.", n("Perguntou elle."))
        assertEquals("Vinte e seis anos de intervalo.", n("Vinte e seis annos de intervallo."))
        assertEquals("Quis chamá-la, sacudi-la, mas faltou-me animo.", n("Quiz chamal-a, sacudil-a, mas faltou-me animo."))
        assertEquals("Iremos vê-la, e daí a pouco tais coisas.", n("Iremos vel-a, e d'ahi a pouco taes cousas."))
        assertEquals("Vai sabado, disse o pai.", n("Vae sabbado, disse o pae."))
        assertEquals("Criado na ortografia de meus pais.", n("Criado na orthographia de meus paes."))
        assertEquals("Mostrei-lho.", n("Mostrei-lh'o."))
        assertEquals("O carro passou assim mesmo.", n("O carro passou assim mesmo."))
    }
}
