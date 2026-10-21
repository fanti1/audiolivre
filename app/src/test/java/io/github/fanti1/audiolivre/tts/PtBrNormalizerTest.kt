package io.github.fanti1.audiolivre.tts

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class PtBrNormalizerTest {

    private fun n(text: String) = PtBrNormalizer.normalize(text)

    @Test
    fun `voice comparison passage`() {
        assertEquals("Capítulo quatro, A casa no fim da rua.", n("Capítulo IV — A casa no fim da rua."))
        assertEquals(
            "Naquela noite, o senhor Almeida chegou às vinte e três horas com mil e quinhentos reais no bolso e um gosto amargo na boca.",
            n("Naquela noite, o Sr. Almeida chegou às 23h com 1.500 reais no bolso e um gosto amargo na boca."),
        )
        assertEquals("Alguém, ou alguma coisa, respirava no escuro.", n("Alguém — ou alguma coisa — respirava no escuro."))
    }

    @Test
    fun `cardinals`() {
        val expected = mapOf(
            0L to "zero", 1L to "um", 10L to "dez", 14L to "quatorze", 16L to "dezesseis", 19L to "dezenove",
            21L to "vinte e um", 99L to "noventa e nove", 100L to "cem", 101L to "cento e um",
            123L to "cento e vinte e três", 200L to "duzentos", 555L to "quinhentos e cinquenta e cinco",
            1000L to "mil", 1001L to "mil e um", 1500L to "mil e quinhentos", 1230L to "mil duzentos e trinta",
            1998L to "mil novecentos e noventa e oito", 2000L to "dois mil", 2345L to "dois mil trezentos e quarenta e cinco",
            1_000_000L to "um milhão", 2_500_000L to "dois milhões e quinhentos mil",
            1_230_000L to "um milhão duzentos e trinta mil", 1_000_100L to "um milhão e cem", 3_000_000_000L to "três bilhões",
        )
        expected.forEach { (value, words) -> assertEquals("$value", words, PtBrNormalizer.cardinal(value)) }
    }

    @Test
    fun `feminine cardinals`() {
        assertEquals("duas", PtBrNormalizer.cardinal(2, feminine = true))
        assertEquals("duzentas e vinte e uma", PtBrNormalizer.cardinal(221, feminine = true))
        assertEquals("duas mil", PtBrNormalizer.cardinal(2000, feminine = true))
        assertEquals("dois milhões", PtBrNormalizer.cardinal(2_000_000, feminine = true))
    }

    @Test
    fun `numbers agree with the noun they count`() {
        assertEquals("duas páginas", n("2 páginas"))
        assertEquals("duzentas pessoas", n("200 pessoas"))
        assertEquals("uma vez", n("1 vez"))
        assertEquals("uma casa", n("1 casa"))
        assertEquals("dois dias", n("2 dias"))
        assertEquals("dois problemas", n("2 problemas"))
        assertEquals("três livros", n("3 livros"))
    }

    @Test
    fun `plain numbers and decimals`() {
        assertEquals("chegou em mil novecentos e noventa e oito", n("chegou em 1998"))
        assertEquals("um vírgula cinco", n("1,5"))
        assertEquals("três vírgula quatorze", n("3,14"))
        assertEquals("três vírgula zero cinco", n("3,05"))
        assertEquals("dois vírgula sete um oito", n("2,718"))
        assertEquals("zero zero sete", n("007"))
        assertEquals("mil e quinhentos vírgula setenta e cinco", n("1.500,75"))
    }

    @Test
    fun `digits inside codes are left alone`() {
        assertEquals("vitamina B12", n("vitamina B12"))
        assertEquals("arquivo mp3", n("arquivo mp3"))
        assertEquals("covid-dezenove", n("COVID-19"))
    }

    @Test
    fun `money`() {
        assertEquals("mil e quinhentos reais", n("R$ 1.500,00"))
        assertEquals("mil e quinhentos reais", n("R$1.500"))
        assertEquals("dez reais e cinquenta centavos", n("R$ 10,50"))
        assertEquals("dez reais e cinquenta centavos", n("R$ 10,5"))
        assertEquals("noventa e nove centavos", n("R$ 0,99"))
        assertEquals("um real", n("R$ 1,00"))
        assertEquals("um real e um centavo", n("R$ 1,01"))
        assertEquals("vinte dólares", n("US$ 20"))
        assertEquals("vinte dólares", n("U$ 20"))
        assertEquals("vinte dólares", n("$20"))
        assertEquals("um dólar", n("US$ 1"))
        assertEquals("cinco euros", n("€ 5"))
        assertEquals("cinco euros", n("5 €"))
        assertEquals("três libras", n("£ 3"))
        assertEquals("uma libra", n("£ 1"))
        assertEquals("duas libras", n("£ 2"))
        assertEquals("um milhão de reais", n("R$ 1.000.000"))
        assertEquals("dois milhões e quinhentos mil reais", n("R$ 2.500.000"))
        assertEquals("dois dólares", n("2 dólares"))
        assertEquals("mil reais", n("mil reais"))
        assertEquals("Custou mil e quinhentos reais, uma fortuna.", n("Custou R$ 1.500,00, uma fortuna."))
    }

    @Test
    fun `percent`() {
        assertEquals("cinquenta por cento", n("50%"))
        assertEquals("doze vírgula cinco por cento", n("12,5%"))
        assertEquals("cem por cento", n("100 %"))
    }

    @Test
    fun `times`() {
        assertEquals("vinte e três horas", n("23h"))
        assertEquals("uma hora", n("1h"))
        assertEquals("duas horas", n("2h"))
        assertEquals("vinte e uma horas", n("21h"))
        assertEquals("sete horas e trinta minutos", n("7h30"))
        assertEquals("doze horas e cinco minutos", n("12h05"))
        assertEquals("às sete horas e trinta minutos", n("às 7:30"))
        assertEquals("João três:dezesseis", n("João 3:16"))
        assertEquals("25h", n("25h"))
    }

    @Test
    fun `ordinals`() {
        assertEquals("primeiro lugar", n("1º lugar"))
        assertEquals("primeira vez", n("1ª vez"))
        assertEquals("segundo andar", n("2.º andar"))
        assertEquals("vigésimo primeiro", n("21º"))
        assertEquals("terceira", n("3ª"))
    }

    @Test
    fun `roman numerals in context`() {
        assertEquals("capítulo doze", n("CAPÍTULO XII"))
        assertEquals("no século dezenove", n("no século XIX"))
        assertEquals("Parte dois", n("Parte II"))
        assertEquals("Dom Pedro segundo governou", n("Dom Pedro II governou"))
        assertEquals("Henrique oitavo", n("Henrique VIII"))
        assertEquals("Luís quatorze", n("Luís XIV"))
        assertEquals("Papa João Paulo segundo", n("Papa João Paulo II"))
        assertEquals("Diabetes Tipo dois", n("Diabetes Tipo II"))
        assertEquals("quatro", n("IV"))
        assertEquals("Doze.", n("XII."))
    }

    @Test
    fun `letters that only look roman stay untouched`() {
        assertEquals("raio X", n("raio X"))
        assertEquals("vitamina C", n("vitamina C"))
        assertEquals("Vitamina C", n("Vitamina C"))
        assertEquals("a letra I", n("a letra I"))
        assertEquals("Capítulo i i i i", n("Capítulo IIII"))
        assertEquals("Parte vê xis", n("Parte VX"))
    }

    @Test
    fun `abbreviations`() {
        assertEquals("senhora Silva", n("Sra. Silva"))
        assertEquals("senhorita Luz", n("Srta. Luz"))
        assertEquals("doutora Ana", n("Dra. Ana"))
        assertEquals("professor Souza", n("Prof. Souza"))
        assertEquals("Vossa Excelência", n("V. Exa."))
        assertEquals("livros, cadernos etcétera.", n("livros, cadernos etc."))
        assertEquals("livros etcétera, e canetas", n("livros etc., e canetas"))
        assertEquals("por exemplo isto", n("p. ex. isto"))
        assertEquals("avenida Paulista", n("Av. Paulista"))
        assertEquals("número cinco", n("nº 5"))
        assertEquals("número doze", n("n.º 12"))
        assertEquals("página três", n("pág. 3"))
        assertEquals("capítulo quatro", n("cap. IV"))
        assertEquals("século vinte", n("séc. XX"))
    }

    @Test
    fun `units after numbers`() {
        assertEquals("cinco quilômetros", n("5 km"))
        assertEquals("um quilômetro", n("1 km"))
        assertEquals("dois quilos", n("2 kg"))
        assertEquals("dez metros", n("10 m"))
        assertEquals("duzentos gramas", n("200 g"))
        assertEquals("um vírgula cinco quilômetros", n("1,5 km"))
    }

    @Test
    fun `whitespace and dashes`() {
        assertEquals("a b", n("a   b"))
        assertEquals("Não vou, disse ele.", n("Não vou — disse ele."))
        assertEquals("Eu sei", n("— Eu sei"))
    }

    @Test
    fun `normalizing twice changes nothing`() {
        val once = n("Capítulo IV — o Sr. Almeida pagou R$ 1.500,00 às 23h no dia 2 de março.")
        assertEquals(once, n(once))
    }

    @Test
    fun `odd input never throws`() {
        val alphabet = "IVXLCDM0123456789.,:%\$R€£ºªhkmgl—– abcSrDretcUS".toList()
        val random = Random(42)
        repeat(2000) {
            val text = String(CharArray(random.nextInt(0, 40)) { alphabet[random.nextInt(alphabet.size)] })
            n(text)
        }
    }
}
