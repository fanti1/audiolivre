package io.github.fanti1.audiolivre.translate

import io.github.fanti1.audiolivre.book.BookBuilder
import io.github.fanti1.audiolivre.formats.TextBlock
import io.github.fanti1.audiolivre.formats.TocEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TranslationTest {

    private val portuguese = listOf(
        "Uma noite destas, vindo da cidade para o Engenho Novo, encontrei no trem da Central um rapaz aqui do bairro, que eu conheço de vista e de chapéu. Cumprimentou-me, sentou-se ao pé de mim, falou da lua e dos ministros, e acabou recitando-me versos.",
        "A viagem era curta, e os versos pode ser que não fossem inteiramente maus. Sucedeu, porém, que, como eu estava cansado, fechei os olhos três ou quatro vezes; tanto bastou para que ele interrompesse a leitura e metesse os versos no bolso.",
        "No dia seguinte entrou a dizer de mim nomes feios, e acabou alcunhando-me Dom Casmurro. Os vizinhos, que não gostam dos meus hábitos reclusos e calados, deram curso à alcunha, que afinal pegou. Nem por isso me zanguei.",
    )

    private val english = listOf(
        "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife. However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well fixed in the minds of the surrounding families.",
        "My dear Mr. Bennet, said his lady to him one day, have you heard that Netherfield Park is let at last? Mr. Bennet replied that he had not. But it is, returned she; for Mrs. Long has just been here, and she told me all about it.",
        "Mr. Bennet made no answer. Do you not want to know who has taken it? cried his wife impatiently. You want to tell me, and I have no objection to hearing it. This was invitation enough.",
    )

    private val spanish = listOf(
        "En un lugar de la Mancha, de cuyo nombre no quiero acordarme, no ha mucho tiempo que vivía un hidalgo de los de lanza en astillero, adarga antigua, rocín flaco y galgo corredor. Una olla de algo más vaca que carnero, salpicón las más noches, duelos y quebrantos los sábados.",
        "El resto della concluían sayo de velarte, calzas de velludo para las fiestas, con sus pantuflos de lo mesmo, y los días de entresemana se honraba con su vellorí de lo más fino. Tenía en su casa una ama que pasaba de los cuarenta.",
        "Frisaba la edad de nuestro hidalgo con los cincuenta años; era de complexión recia, seco de carnes, enjuto de rostro, gran madrugador y amigo de la caza. Quieren decir que tenía el sobrenombre de Quijada, o Quesada, que en esto hay alguna diferencia.",
    )

    private val french = listOf(
        "En 1815, M. Charles-François-Bienvenu Myriel était évêque de Digne. C'était un vieillard d'environ soixante-quinze ans; il occupait le siège de Digne depuis 1806. Quoique ce détail ne touche en aucune manière au fond même de ce que nous avons à raconter, il n'est peut-être pas inutile.",
        "Vrai ou faux, ce qu'on dit des hommes tient souvent autant de place dans leur vie et surtout dans leur destinée que ce qu'ils font. M. Myriel était fils d'un conseiller au parlement d'Aix; noblesse de robe. On contait de lui que son père, le réservant pour hériter de sa charge, l'avait marié de fort bonne heure.",
    )

    private val italian = listOf(
        "C'era una volta... Un re! diranno subito i miei piccoli lettori. No, ragazzi, avete sbagliato. C'era una volta un pezzo di legno. Non era un legno di lusso, ma un semplice pezzo da catasta, di quelli che d'inverno si mettono nelle stufe e nei caminetti per accendere il fuoco.",
        "Non so come andasse, ma il fatto gli è che un bel giorno questo pezzo di legno capitò nella bottega di un vecchio falegname, il quale aveva nome mastr'Antonio, se non che tutti lo chiamavano maestro Ciliegia, per via della punta del suo naso, che era sempre lustra e paonazza.",
    )

    private val german = listOf(
        "Als Gregor Samsa eines Morgens aus unruhigen Träumen erwachte, fand er sich in seinem Bett zu einem ungeheueren Ungeziefer verwandelt. Er lag auf seinem panzerartig harten Rücken und sah, wenn er den Kopf ein wenig hob, seinen gewölbten, braunen Bauch.",
        "Was ist mit mir geschehen? dachte er. Es war kein Traum. Sein Zimmer, ein richtiges, nur etwas zu kleines Menschenzimmer, lag ruhig zwischen den vier wohlbekannten Wänden. Über dem Tisch, auf dem eine auseinandergepackte Musterkollektion von Tuchwaren ausgebreitet war, hing das Bild.",
    )

    @Test
    fun `detects the language a book is written in`() {
        assertEquals("pt", LanguageDetector.detect(portuguese))
        assertEquals("en", LanguageDetector.detect(english))
        assertEquals("es", LanguageDetector.detect(spanish))
        assertEquals("fr", LanguageDetector.detect(french))
        assertEquals("it", LanguageDetector.detect(italian))
        assertEquals("de", LanguageDetector.detect(german))
    }

    @Test
    fun `scripts name their language`() {
        val russian = "Все счастливые семьи похожи друг на друга, каждая несчастливая семья несчастлива по-своему. Всё смешалось в доме Облонских."
        val ukrainian = "Реве та стогне Дніпр широкий, сердитий вітер завива, додолу верби гне високі, горами хвилю підійма. І блідий місяць на ту пору із хмари де-де виглядав."
        val greek = "Άνδρα μοι έννεπε, Μούσα, πολύτροπον, ος μάλα πολλά πλάγχθη, επεί Τροίης ιερόν πτολίεθρον έπερσεν· πολλών δ' ανθρώπων ίδεν άστεα και νόον έγνω."
        val japanese = "吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。"
        assertEquals("ru", LanguageDetector.detect(listOf(russian)))
        assertEquals("uk", LanguageDetector.detect(listOf(ukrainian)))
        assertEquals("el", LanguageDetector.detect(listOf(greek)))
        assertEquals("ja", LanguageDetector.detect(listOf(japanese)))
    }

    @Test
    fun `too little text is left to the reader`() {
        assertNull(LanguageDetector.detect(listOf("Capítulo 1", "* * *", "Fim da primeira parte do livro de contos.")))
    }

    @Test
    fun `catalog keeps the newest usable release of each direction`() {
        val catalog = TranslationCatalog.parse(registry())
        val enPt = catalog.pairs.getValue("en-pt")
        assertEquals("2.1", enPt.version)
        assertEquals("model.enpt.v21", enPt.model.name)
        assertEquals(listOf("model.enpt.v21", "lex.enpt.v21", "vocab.enpt.v21"), enPt.files.map { it.name })
        // 3.0 is a newer architecture, 2.2a1 a pre-release, and the desktop-only build is not for Android.
        assertEquals("2.0", catalog.pairs.getValue("es-en").version)
        assertEquals("1.0", catalog.pairs.getValue("ja-en").version)
        assertEquals("trgvocab.jaen", catalog.pairs.getValue("ja-en").targetVocabulary?.name)
    }

    @Test
    fun `other languages reach Portuguese through English`() {
        val catalog = TranslationCatalog.parse(registry())
        assertEquals(listOf("en-pt"), catalog.route("en")?.map { it.id })
        assertEquals(listOf("es-en", "en-pt"), catalog.route("es")?.map { it.id })
        assertEquals(emptyList<ModelPair>(), catalog.route("pt"))
        assertNull(catalog.route("ko"))
        assertEquals(listOf("en", "es", "ja"), catalog.sourceLanguages().sorted())
    }

    @Test
    fun `translates every paragraph and keeps chapters where they were`() = runBlocking<Unit> {
        val book = BookBuilder.fromBlocks(
            listOf(
                TextBlock("Chapter One", isHeading = true),
                TextBlock("It was a dark night. The wind howled."),
                TextBlock("* * *"),
                TextBlock("Chapter Two", isHeading = true),
                TextBlock("Morning came at last."),
            ),
            listOf(TocEntry("Chapter One", 0), TocEntry("Chapter Two", 3)),
            "The Night",
        )
        val translator = FakeTranslator()
        val result = BookTranslator.translate(book, translator, "en-pt@2.1")

        assertEquals("[pt] The Night (pt-BR)", result.title)
        assertEquals(
            listOf("[pt] Chapter One", "[pt] It was a dark night. [pt] The wind howled.", "* * *", "[pt] Chapter Two", "[pt] Morning came at last."),
            result.paragraphs.map { it.text },
        )
        assertEquals(listOf("[pt] Chapter One" to 0, "[pt] Chapter Two" to 3), result.chapters.map { it.title to it.firstParagraph })
        assertTrue("scene breaks are not sent to the engine", translator.seen.none { it == "* * *" })
    }

    @Test
    fun `text in capitals is translated as ordinary text and comes back in capitals`() = runBlocking<Unit> {
        val book = BookBuilder.fromBlocks(
            listOf(TextBlock("DIE VERWANDLUNG", isHeading = true), TextBlock("Als Gregor Samsa eines Morgens erwachte.")),
            emptyList(),
            "Die Verwandlung",
        )
        val translator = FakeTranslator()
        val result = BookTranslator.translate(book, translator, "de-en+en-pt")
        assertTrue(translator.seen.toString(), "Die Verwandlung" in translator.seen)
        assertEquals("[PT] DIE VERWANDLUNG", result.paragraphs[0].text)
        assertEquals("[pt] Als Gregor Samsa eines Morgens erwachte.", result.paragraphs[1].text)
    }

    @Test
    fun `chapter numbers are kept and guillemets become plain quotes`() = runBlocking<Unit> {
        val book = BookBuilder.fromBlocks(
            listOf(
                TextBlock("I.", isHeading = true),
                TextBlock("»Wie wäre es«, dachte er."),
                TextBlock("« Bonjour », dit-il."),
            ),
            emptyList(),
            "Mix",
        )
        val translator = FakeTranslator()
        val result = BookTranslator.translate(book, translator, "de-en+en-pt")
        assertEquals("I.", result.paragraphs[0].text)
        assertEquals("Um cavalo preto. CAPÍTULO III.", BookTranslator.restoreCase("A black horse. CHAPTER III.", "Um cavalo preto. CAPÍTUlo III."))
        assertTrue(translator.seen.toString(), "“Wie wäre es”, dachte er." in translator.seen)
        assertTrue(translator.seen.toString(), "“ Bonjour ”, dit-il." in translator.seen)
    }

    @Test
    fun `an interrupted translation picks up where it stopped`() = runBlocking<Unit> {
        val blocks = (1..60).map { TextBlock("Paragraph number $it of a long book, with enough words to need translating.") }
        val book = BookBuilder.fromBlocks(blocks, emptyList(), "Long Book")
        val dir = Files.createTempDirectory("journal").toFile()
        val journalFile = File(dir, "book.jsonl")

        val failing = FakeTranslator(failAfter = 2)
        // Small chunks, so the run breaks off partway: the production size would take this book in one go.
        runCatching { BookTranslator.translate(book, failing, "en-pt@2.1", TranslationJournal(journalFile), chunkChars = 2_500) }
        val firstRun = failing.seen.size
        assertTrue(firstRun in 1 until 60)

        val second = FakeTranslator()
        val result = BookTranslator.translate(book, second, "en-pt@2.1", TranslationJournal(journalFile), chunkChars = 2_500)
        assertTrue("already translated paragraphs are not sent again", second.seen.size <= 60 + 2 - firstRun)
        assertTrue(result.paragraphs.all { it.text.startsWith("[pt] ") })

        // Another route means another translation: the journal starts over.
        val other = FakeTranslator()
        BookTranslator.translate(book, other, "es-en@2.0+en-pt@2.1", TranslationJournal(journalFile))
        assertEquals(62, other.seen.size)
        dir.deleteRecursively()
    }

    private class FakeTranslator(private val failAfter: Int = Int.MAX_VALUE) : MachineTranslator {
        val seen = mutableListOf<String>()
        private var calls = 0

        override fun translate(texts: List<String>): List<String> {
            if (calls++ >= failAfter) throw IllegalStateException("app closed")
            seen += texts
            return texts.map { "[pt] $it" }
        }

        override fun close() = Unit
    }

    private fun registry(): String {
        fun record(from: String, to: String, type: String, version: String, name: String, filter: String = "") =
            """{"fromLang":"$from","toLang":"$to","fileType":"$type","version":"$version","name":"$name",
               "filter_expression":"$filter","attachment":{"location":"x/$name","size":10,"hash":"ab"}}"""
        val records = listOf(
            record("en", "pt", "model", "2.0", "model.enpt.v20"),
            record("en", "pt", "vocab", "2.0", "vocab.enpt.v20"),
            record("en", "pt", "model", "2.1", "model.enpt.v21"),
            record("en", "pt", "lex", "2.1", "lex.enpt.v21"),
            record("en", "pt", "vocab", "2.1", "vocab.enpt.v21"),
            record("en", "pt", "model", "3.0", "model.enpt.v30"),
            record("en", "pt", "vocab", "3.0", "vocab.enpt.v30"),
            record("es", "en", "model", "2.0", "model.esen"),
            record("es", "en", "vocab", "2.0", "vocab.esen"),
            record("es", "en", "model", "2.2a1", "model.esen.alpha"),
            record("es", "en", "vocab", "2.2a1", "vocab.esen.alpha"),
            record("ja", "en", "model", "1.0", "model.jaen", "env.appinfo.OS == 'Android' "),
            record("ja", "en", "srcvocab", "1.0", "srcvocab.jaen", "env.appinfo.OS == 'Android' "),
            record("ja", "en", "trgvocab", "1.0", "trgvocab.jaen", "env.appinfo.OS == 'Android' "),
            record("ja", "en", "model", "2.0", "model.jaen.desktop", "env.appinfo.OS != 'Android' || env.channel != 'release'"),
            record("ja", "en", "vocab", "2.0", "vocab.jaen.desktop", "env.appinfo.OS != 'Android' || env.channel != 'release'"),
            record("ko", "en", "vocab", "2.0", "vocab.koen"),
        )
        return """{"data":[${records.joinToString(",")}]}"""
    }
}
