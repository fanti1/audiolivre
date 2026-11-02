package io.github.fanti1.audiolivre

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.github.fanti1.audiolivre.data.ResumePositionStore
import io.github.fanti1.audiolivre.pdf.PdfTextExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// The new code's side of PositionExperimentTest, plus what a touch on real books selects. Same switch:
//     adb shell am instrument -w -e experiment true -e class io.github.fanti1.audiolivre.TouchAndSaveExperimentTest ...
@RunWith(AndroidJUnit4::class)
class TouchAndSaveExperimentTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private fun log(message: String) {
        Log.i("PositionExperiment", message)
        println("PositionExperiment: $message")
    }

    private fun requested() = assumeTrue(InstrumentationRegistry.getArguments().getString("experiment") == "true")

    // The write the reading now uses, in the same cancelled scope as PositionExperimentTest.writeFromACancelledScope.
    @Test
    fun saveSoonFromACancelledScope() {
        requested()
        val store = ResumePositionStore(context)
        val uri = "file:///experiment/dom_casmurro.pdf"
        val file = File(context.filesDir, "datastore/resume_position.preferences_pb")
        var kept = 0
        repeat(50) { i ->
            runBlocking { store.save(uri, 1) }
            val value = 1_000 + i
            runBlocking(Dispatchers.Main) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                store.saveSoon(uri, value)
                scope.cancel()
            }
            runBlocking { delay(300) }
            if (runBlocking { store.offsets(listOf(uri)) }[uri] == value && file.exists()) kept++
        }
        log("SAVESOON-CANCELLED-SCOPE writes=50 kept=$kept")
    }

    // Every character of every paragraph of real books, touched: does the reading start in the paragraph touched?
    @Test
    fun touchOnRealBooks() {
        requested()
        PDFBoxResourceLoader.init(context)
        for (name in listOf("dom_casmurro.pdf", "corpus/romance.pdf", "corpus/ciencia.pdf", "corpus/artigo.pdf")) {
            val pdf = File(context.cacheDir, name.substringAfterLast('/'))
            testContext.assets.open(name).use { input -> pdf.outputStream().use { input.copyTo(it) } }
            val book = runBlocking { PdfTextExtractor().extractBook(Uri.fromFile(pdf), context, name) { _, _ -> } }
            val readable = book.sentences.map { it.paragraphIndex }.toSet()
            var positions = 0
            var oldEarlier = 0
            var newEarlier = 0
            var paragraphStarts = 0
            var oldStartEarlier = 0
            var newStartEarlier = 0
            val examples = ArrayList<String>()
            for (p in book.paragraphs.indices) {
                if (p !in readable) continue
                val start = book.paragraphStarts[p]
                for (i in 0..book.paragraphs[p].text.length) {
                    val offset = start + i
                    val old = book.sentences[book.sentenceAtOffset(offset)].paragraphIndex
                    val new = book.sentences[book.sentenceForTouch(offset)].paragraphIndex
                    positions++
                    if (old < p) oldEarlier++
                    if (new < p) newEarlier++
                    if (i == 0) {
                        paragraphStarts++
                        if (old < p) {
                            oldStartEarlier++
                            if (examples.size < 3) examples += "¶$p \"${book.paragraphs[p].text.take(40)}\""
                        }
                        if (new < p) newStartEarlier++
                    }
                }
            }
            log(
                "TOUCH $name positions=$positions oldStartsInEarlierParagraph=$oldEarlier new=$newEarlier | " +
                    "paragraphFirstChar=$paragraphStarts old=$oldStartEarlier new=$newStartEarlier e.g. $examples",
            )
        }
    }
}
