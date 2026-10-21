package io.github.fanti1.audiolivre

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fanti1.audiolivre.translate.SlimtTranslator
import io.github.fanti1.audiolivre.translate.TranslationModelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

// Downloads Mozilla's models (once; about 36 MB per direction) and translates real book openings on the device.
// Prints each translation and the speed to logcat under "TranslationEngineTest", for reading the quality.
// Needs internet the first time.
@RunWith(AndroidJUnit4::class)
class TranslationEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = TranslationModelStore(context)

    private val english = listOf(
        "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.",
        "“My dear Mr. Bennet,” said his lady to him one day, “have you heard that Netherfield Park is let at last?”",
        "Mr. Bennet replied that he had not. “But it is,” returned she; “for Mrs. Long has just been here, and she told me all about it.”",
        "The train was late, so she took the bus to the city centre and bought a cell phone, a jacket and a cup of coffee at the station.",
    )

    private val spanish = listOf(
        "En un lugar de la Mancha, de cuyo nombre no quiero acordarme, no ha mucho tiempo que vivía un hidalgo de los de lanza en astillero.",
        "—¿Qué gigantes? —dijo Sancho Panza.",
    )

    @Test
    fun translatesEnglishToPortuguese() = runBlocking {
        assumeTrue("engine not built", SlimtTranslator.isAvailable)
        val route = store.catalog().route("en")!!
        assertEquals(listOf("en-pt"), route.map { it.id })
        store.install(route)

        val output = SlimtTranslator.open(store, route).use { translator ->
            translator.translate(listOf("Warm-up sentence.")) // model loading is not part of the speed
            timed("en→pt", english) { translator.translate(english) }
        }
        assertEquals(english.size, output.size)
        assertTrue(output.joinToString(), output.all { it.isNotBlank() })
        assertTrue("does not look like Portuguese: ${output[0]}", Regex("""\b(um|uma|que|de)\b""").containsMatchIn(output[0]))
    }

    @Test
    fun translatesSpanishThroughEnglish() = runBlocking {
        assumeTrue("engine not built", SlimtTranslator.isAvailable)
        val route = store.catalog().route("es")!!
        assertEquals(listOf("es-en", "en-pt"), route.map { it.id })
        store.install(route)

        val output = SlimtTranslator.open(store, route).use { translator ->
            translator.translate(listOf("Hola."))
            timed("es→en→pt", spanish) { translator.translate(spanish) }
        }
        assertEquals(spanish.size, output.size)
        assertTrue(output.joinToString(), output.all { it.isNotBlank() })
    }

    private fun timed(label: String, input: List<String>, block: () -> List<String>): List<String> {
        val start = System.nanoTime()
        val output = block()
        val seconds = (System.nanoTime() - start) / 1e9
        val words = input.sumOf { it.split(' ').size }
        Log.i(TAG, "$label: $words words in ${"%.2f".format(seconds)} s (${(words / seconds).toInt()} words/s)")
        input.zip(output).forEach { (source, target) -> Log.i(TAG, "  $source\n  → $target") }
        return output
    }

    private companion object {
        const val TAG = "TranslationEngineTest"
    }
}
