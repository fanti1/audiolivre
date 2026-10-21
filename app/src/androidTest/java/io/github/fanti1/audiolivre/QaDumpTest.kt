package io.github.fanti1.audiolivre

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fanti1.audiolivre.pdf.PdfTextExtractor
import io.github.fanti1.audiolivre.tts.PtBrNormalizer
import io.github.fanti1.audiolivre.tts.SpeakerAttribution
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Exports what the app would send to the voice for every sentence of the test book, for the listening QA in
// qa/ (synthesis + speech recognition on the PC). Skipped unless run with `-e qaDump true`.
@RunWith(AndroidJUnit4::class)
class QaDumpTest {

    @Test
    fun dumpSpokenText() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("qaDump") == "true")
        val context = instrumentation.targetContext
        PDFBoxResourceLoader.init(context)
        val pdf = File(context.cacheDir, "dom_casmurro.pdf")
        instrumentation.context.assets.open("dom_casmurro.pdf").use { input -> pdf.outputStream().use { input.copyTo(it) } }

        val book = PdfTextExtractor().extractBook(Uri.fromFile(pdf), context, "Dom Casmurro") { _, _ -> }
        val roles = SpeakerAttribution.assign(book)
        val out = File(context.getExternalFilesDir(null), "qa_sentences.jsonl")
        out.bufferedWriter().use { writer ->
            book.sentences.forEach { s ->
                val paragraph = book.paragraphs[s.paragraphIndex]
                writer.write(
                    JSONObject()
                        .put("index", s.index)
                        .put("paragraph", s.paragraphIndex)
                        .put("heading", paragraph.isHeading)
                        .put("role", roles[s.index].name)
                        .put("display", paragraph.text.substring(s.startInParagraph, s.endInParagraph))
                        .put("tts", PtBrNormalizer.normalize(s.ttsText))
                        .toString()
                )
                writer.newLine()
            }
        }
    }
}
