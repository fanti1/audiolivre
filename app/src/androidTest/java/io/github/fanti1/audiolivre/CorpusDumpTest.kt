package io.github.fanti1.audiolivre

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fanti1.audiolivre.pdf.PdfTextExtractor
import io.github.fanti1.audiolivre.tts.SpeakerAttribution
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Extracts every PDF of the test corpus with the code that ships, so qa/check_extraction.py can compare the
// result against the text those PDFs were built from. Tuning the parser against one book only proves it
// learned that book; this is what says whether it reads a novel, a popular-science chapter and a two-column
// article. Skipped unless run with `-e qaDump true`.
@RunWith(AndroidJUnit4::class)
class CorpusDumpTest {

    @Test
    fun dumpCorpusExtraction() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("qaDump") == "true")
        val context = instrumentation.targetContext
        PDFBoxResourceLoader.init(context)
        val assets = instrumentation.context.assets
        val names = assets.list("corpus").orEmpty().filter { it.endsWith(".pdf") }
        assumeTrue("no corpus in assets", names.isNotEmpty())

        val out = File(context.getExternalFilesDir(null), "corpus_extraction.jsonl")
        out.bufferedWriter().use { writer ->
            for (name in names) {
                val pdf = File(context.cacheDir, name)
                assets.open("corpus/$name").use { input -> pdf.outputStream().use { input.copyTo(it) } }
                val title = name.removeSuffix(".pdf")
                val book = PdfTextExtractor().extractBook(Uri.fromFile(pdf), context, title) { _, _ -> }
                val roles = SpeakerAttribution.assign(book)
                val roleOfParagraph = HashMap<Int, String>()
                book.sentences.forEach { roleOfParagraph.putIfAbsent(it.paragraphIndex, roles[it.index].name) }
                book.paragraphs.forEachIndexed { index, paragraph ->
                    writer.write(
                        JSONObject()
                            .put("doc", title)
                            .put("index", index)
                            .put("heading", paragraph.isHeading)
                            .put("page", paragraph.sourcePage)
                            .put("role", roleOfParagraph[index] ?: "NARRATOR")
                            .put("text", paragraph.text)
                            .toString()
                    )
                    writer.newLine()
                }
            }
        }
    }
}
