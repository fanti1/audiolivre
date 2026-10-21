package io.github.fanti1.audiolivre

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fanti1.audiolivre.book.BookBuilder
import io.github.fanti1.audiolivre.book.DialogueSplitter
import io.github.fanti1.audiolivre.book.PdfLine
import io.github.fanti1.audiolivre.data.ReadingFont
import io.github.fanti1.audiolivre.data.TextAlignment
import io.github.fanti1.audiolivre.pdf.PdfTextExtractor
import io.github.fanti1.audiolivre.reader.Paginator
import io.github.fanti1.audiolivre.reader.ReadingSpec
import io.github.fanti1.audiolivre.tts.PtBrNormalizer
import io.github.fanti1.audiolivre.tts.SpeakerAttribution
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Runs the text pipeline on Android itself: its ICU regex engine, PdfBox port and text layout differ from the
// desktop JVM that runs the unit tests (a JVM-only regex flag once passed every unit test and crashed the app).
@RunWith(AndroidJUnit4::class)
class OnDevicePipelineTest {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun textRulesRunOnAndroid() {
        val segments = DialogueSplitter.split("— Não vou — disse ela, a avó. — Nunca.")
        assertTrue(segments.size >= 3)

        assertEquals(
            "Capítulo quatro, o senhor Almeida pagou mil e quinhentos reais às vinte e três horas.",
            PtBrNormalizer.normalize("Capítulo IV — o Sr. Almeida pagou R$ 1.500,00 às 23h."),
        )
        assertEquals("Às sete horas e trinta minutos", PtBrNormalizer.normalize("Às 7:30"))

        val lines = listOf(
            line(0, "CAPÍTULO I", fontSize = 16f, paragraphStart = true),
            line(0, "— Quem está aí? — perguntou ele.", paragraphStart = true),
            line(0, "— Você não devia ter voltado — respondeu uma mulher.", paragraphStart = true),
        )
        val book = BookBuilder.build(lines, emptyList(), pageCount = 1, title = "Teste")
        assertTrue(book.sentences.isNotEmpty())
        assertEquals(book.sentences.size, SpeakerAttribution.assign(book).size)
    }

    // Android's ICU regex engine differs from the JVM's (lookbehinds, Unicode case folding), so the spoken forms
    // the unit tests pin down are checked again here, plus the whole QA stress corpus for leftover symbols.
    @Test
    fun spokenFormsMatchOnAndroid() {
        val n = PtBrNormalizer::normalize
        assertEquals("capítulo doze", n("CAPÍTULO XII"))
        assertEquals("A empresa faturou dois vírgula cinco milhões de reais.", n("A empresa faturou R$ 2,5 milhões."))
        assertEquals("Nasceu em doze de março de mil novecentos e oitenta e sete.", n("Nasceu em 12/03/1987."))
        assertEquals("Entre mil novecentos e quatorze a mil novecentos e dezoito.", n("Entre 1914-1918."))
        assertEquals("O placar terminou três a um para o time da casa.", n("O placar terminou 3 x 1 para o time da casa."))
        assertEquals("Caiu para menos três graus Celsius.", n("Caiu para -3 °C."))
        assertEquals("A sala tem vinte e cinco metros quadrados.", n("A sala tem 25 m²."))
        assertEquals("O cê pê éfe e o érre gê.", n("O CPF e o RG."))
        assertEquals("Escreva para contato arroba editora ponto com ponto bê érre.", n("Escreva para contato@editora.com.br."))
        assertEquals("E dona Glória chorou.", n("E D. Glória chorou."))
        assertEquals("Quis chamá-la, sacudi-la, mas faltou-me animo.", n("Quiz chamal-a, sacudil-a, mas faltou-me animo."))
        assertEquals("Os resultados foram surpreendentes, segundo o autor.", n("Os resultados foram surpreendentes1, segundo o autor."))

        val leftovers = Regex("""[\d@&%$€£§°²³/\\*#_\[\]{}<>|~^=+«»“”„‘’]""")
        val corpus = testContext.assets.open("stress_corpus.txt").bufferedReader().readLines().filter { it.isNotBlank() }
        val unspeakable = corpus.map { it to n(it) }.filter { (_, spoken) -> leftovers.containsMatchIn(spoken) }
        assertTrue(unspeakable.joinToString("\n"), unspeakable.isEmpty())
    }

    @Test
    fun realBookExtractsNormalizesAndPaginates() = runBlocking {
        PDFBoxResourceLoader.init(targetContext)
        val pdf = File(targetContext.cacheDir, "dom_casmurro.pdf")
        testContext.assets.open("dom_casmurro.pdf").use { input -> pdf.outputStream().use { input.copyTo(it) } }

        val extractionStart = System.currentTimeMillis()
        val book = PdfTextExtractor().extractBook(Uri.fromFile(pdf), targetContext, "Dom Casmurro") { _, _ -> }
        val extractionMs = System.currentTimeMillis() - extractionStart
        assertTrue("chapters: ${book.chapters.size}", book.chapters.size >= 140)
        assertTrue("sentences: ${book.sentences.size}", book.sentences.size > 3000)
        assertTrue(book.paragraphs.none { it.text.contains("Dom Casmurro — Machado de Assis") })

        // The source has 1425 body paragraphs plus a heading and a title line per chapter (1721 in all). A PdfBox
        // paragraph threshold below the line leading once made every printed line a paragraph (~10x more).
        assertTrue("paragraphs: ${book.paragraphs.size}", book.paragraphs.size in 1690..1750)
        val chapterOne = book.chapters[0].firstParagraph
        assertEquals("CAPÍTULO I", book.paragraphs[chapterOne].text)
        assertTrue(book.paragraphs[chapterOne + 1].isHeading)
        val opening = book.paragraphs[chapterOne + 2].text
        assertTrue(opening, opening.startsWith("Uma noite destas, vindo da cidade para o Engenho Novo, encontrei no trem da Central"))
        assertTrue(opening, opening.endsWith("."))

        book.sentences.forEach { PtBrNormalizer.normalize(it.ttsText) }
        val roles = SpeakerAttribution.assign(book)
        assertEquals(book.sentences.size, roles.size)

        val spec = ReadingSpec(
            columnWidthPx = 954,
            pageHeightPx = 1900,
            density = 2.625f,
            fontScale = 1f,
            fontSizeSp = 18,
            lineSpacing = 1.5f,
            font = ReadingFont.LITERATA,
            alignment = TextAlignment.JUSTIFY,
        )
        val paginationStart = System.currentTimeMillis()
        val pages = Paginator(targetContext).paginate(book, spec) { _, _ -> }
        val paginationMs = System.currentTimeMillis() - paginationStart
        android.util.Log.i("BookTiming", "extraction ${extractionMs}ms, pagination ${paginationMs}ms, " +
            "${book.paragraphs.size} paragraphs, ${pages.size} pages")
        assertTrue("pages: ${pages.size}", pages.size > 150)
        pages.zipWithNext().forEach { (a, b) -> assertTrue(a.startOffset <= b.startOffset) }
        assertEquals(book.paragraphs.lastIndex, pages.last().slices.last().paragraph)
    }

    private fun line(page: Int, text: String, fontSize: Float = 11f, paragraphStart: Boolean = false) =
        PdfLine(page, text, x = 50f, right = 400f, y = 100f, height = fontSize, fontSize = fontSize, paragraphStart = paragraphStart)
}
