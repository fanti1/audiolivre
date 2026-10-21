package io.github.fanti1.audiolivre.translate

import io.github.fanti1.audiolivre.book.BookBuilder
import io.github.fanti1.audiolivre.book.BookDocument
import io.github.fanti1.audiolivre.data.sha256Hex
import io.github.fanti1.audiolivre.formats.TextBlock
import io.github.fanti1.audiolivre.formats.TocEntry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.util.Locale

const val TRANSLATED_SUFFIX = " (pt-BR)"

// Translates a whole book, paragraph by paragraph, and builds it again from the Portuguese text, so sentence
// splitting, dialogue and character voices, chapters and pronunciation all work on what is read aloud.
// Paragraph i of the result is paragraph i of the original, and chapters start where they started.
object BookTranslator {

    // The engine's worker threads share a chunk's sentences and idle at its end, waiting for the slowest one, so
    // bigger chunks go faster: measured on Pride and Prejudice, 2,500 chars gave 2,000 words/s, 40,000 gave 5,700,
    // with the translation identical word for word. Past that the gain is small, while cancelling and the
    // progress bar wait for the chunk in hand (TranslationSpeedTest).
    private const val CHUNK_CHARS = 40_000

    suspend fun translate(
        book: BookDocument,
        translator: MachineTranslator,
        routeId: String,
        journal: TranslationJournal? = null,
        chunkChars: Int = CHUNK_CHARS,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BookDocument {
        // Everything to translate in one list: the paragraphs, then each chapter's title, then the book's.
        val originalTitle = book.title.removeSuffix(TRANSLATED_SUFFIX)
        val items = book.paragraphs.map { it.text } + book.chapters.map { it.title } + originalTitle
        val fingerprint = sha256Hex(routeId + "\n" + items.joinToString("\n"))
        val translated = journal?.load(fingerprint) ?: HashMap()
        journal?.begin(fingerprint, resumed = translated.isNotEmpty())

        // Scene breaks ("* * *"), page numbers and chapter numbers have nothing to translate; a model reads the
        // roman "I." as the English pronoun and writes "Eu.".
        val wanted = items.indices.filter { items[it].any(Char::isLetter) && !CHAPTER_NUMBER.matches(items[it].trim()) }
        val total = wanted.sumOf { items[it].length }.coerceAtLeast(1)
        var done = wanted.filter { it in translated }.sumOf { items[it].length }
        onProgress(done, total)

        val pending = wanted.filterNot { it in translated }
        var start = 0
        while (start < pending.size) {
            currentCoroutineContext().ensureActive()
            var end = start
            var chars = 0
            while (end < pending.size && end - start < chunkChars / 100 && (chars == 0 || chars + items[pending[end]].length <= chunkChars)) {
                chars += items[pending[end]].length
                end++
            }
            val chunk = pending.subList(start, end)
            // One sentence per input, all of the chunk's sentences in one call, then put back together.
            val sentences = chunk.map { SourceSentences.split(asWritten(items[it])) }
            val output = translator.translate(sentences.flatten())
            var next = 0
            val results = chunk.indices.associate { n ->
                val count = sentences[n].size
                val text = output.subList(next, next + count).joinToString(" ") { it.trim() }
                next += count
                chunk[n] to restoreCase(items[chunk[n]], text)
            }
            translated.putAll(results)
            journal?.append(results)
            done += chars
            onProgress(done.coerceAtMost(total), total)
            start = end
        }

        // An empty answer from the engine would lose the paragraph; the original is better than nothing.
        fun text(i: Int) = translated[i]?.takeIf { it.isNotBlank() } ?: items[i]
        val paragraphs = book.paragraphs.size
        val blocks = book.paragraphs.mapIndexed { i, p -> TextBlock(text(i), p.isHeading) }
        val toc = book.chapters.mapIndexed { c, chapter -> TocEntry(text(paragraphs + c), chapter.firstParagraph) }
        return BookBuilder.fromBlocks(blocks, toc, text(items.lastIndex) + TRANSLATED_SUFFIX)
    }

    private val PT_BR = Locale("pt", "BR")
    private val CHAPTER_NUMBER = Regex("""^([IVXLCDM]{1,7}|\d{1,4})\.?$""")

    // Title pages and headings are often set in capitals, and the models read "DIE VERWANDLUNG" far worse than
    // "Die Verwandlung" ("O TRANSFORMAÇÃO"). Such text goes in with ordinary capitalisation and comes back in
    // capitals again.
    private fun isAllCaps(text: String): Boolean {
        val letters = text.filter(Char::isLetter)
        return letters.length >= 3 && letters.all { it.isUpperCase() }
    }

    internal fun asWritten(text: String): String {
        val quoted = withPlainQuotes(text)
        return if (!isAllCaps(quoted)) quoted
        else quoted.lowercase(PT_BR).split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(PT_BR) } }
    }

    // Guillemets point outward in French («…») and inward in German (»…«); the models, trained mostly on curly
    // quotes, drop one or the other. Which way a paragraph uses them is told by which comes first.
    private fun withPlainQuotes(text: String): String {
        val right = text.indexOf('»')
        val left = text.indexOf('«')
        if (right == -1 && left == -1) return text
        val german = right != -1 && (left == -1 || right < left)
        return if (german) text.replace('»', '“').replace('«', '”') else text.replace('«', '“').replace('»', '”')
    }

    // A word in capitals inside ordinary text sometimes comes back half in capitals ("CAPÍTUlo II."), copied
    // piece by piece from the source's subwords; such a word is put back in capitals whole.
    private val HALF_CAPS = Regex("""(?<![\p{L}])(\p{Lu}{3,})(\p{Ll}{1,3})(?![\p{L}])""")

    internal fun restoreCase(original: String, translated: String): String =
        if (isAllCaps(original)) translated.uppercase(PT_BR)
        else HALF_CAPS.replace(translated) { it.value.uppercase(PT_BR) }
}

// Translating a novel takes minutes, and the app may be closed halfway. Every finished chunk is written here,
// so the next attempt picks up where the last one stopped. The first line names the book and route it belongs
// to; a different book or route starts the file over.
class TranslationJournal(private val file: File) {

    fun load(fingerprint: String): MutableMap<Int, String> {
        val result = HashMap<Int, String>()
        if (!file.isFile) return result
        runCatching {
            file.bufferedReader().useLines { lines ->
                val iterator = lines.iterator()
                if (!iterator.hasNext() || iterator.next() != fingerprint) return result
                for (line in iterator) {
                    // A line cut short by the app being killed mid-write is simply dropped.
                    val entry = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    result[entry.getInt("i")] = entry.getString("t")
                }
            }
        }
        return result
    }

    fun begin(fingerprint: String, resumed: Boolean) {
        if (resumed) return
        file.parentFile?.mkdirs()
        file.writeText(fingerprint + "\n")
    }

    fun append(entries: Map<Int, String>) {
        val text = buildString {
            for ((i, t) in entries) append(JSONObject().put("i", i).put("t", t).toString()).append('\n')
        }
        file.appendText(text)
    }

    fun delete() {
        file.delete()
    }
}
