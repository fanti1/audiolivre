package io.github.fanti1.audiolivre

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fanti1.audiolivre.tts.ModelDownloader
import io.github.fanti1.audiolivre.tts.PiperEngine
import io.github.fanti1.audiolivre.tts.SpeechChunker
import io.github.fanti1.audiolivre.tts.VoiceModels
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Speaks the same sentences with ONNX Runtime's memory pool on (the default) and off, and logs under
// "VoiceMemory" how much memory each way keeps and how long it takes. Needs an installed voice.
@RunWith(AndroidJUnit4::class)
class VoiceMemoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val downloader = ModelDownloader(context)

    private val sentences = listOf(
        "Era uma vez um livro.",
        "Naquela noite, a cidade parecia diferente, e ninguém sabia dizer por quê.",
        // As long as one piece of speech gets (SpeechChunker.MAX_CHARS): the case that grows the pool most.
        ("Ele caminhou devagar pela rua antiga, olhando as janelas acesas, pensando em tudo o que tinha deixado " +
            "para trás, nos amigos, na casa onde crescera, nas tardes de domingo em que o pai lia o jornal em voz " +
            "alta e a mãe fingia não ouvir, e percebeu, de repente, que nada daquilo voltaria, que a cidade tinha " +
            "mudado tanto quanto ele, e que talvez fosse melhor assim, porque quem volta procurando o passado " +
            "encontra apenas a própria saudade, e ela não cabe em lugar nenhum.").take(SpeechChunker.MAX_CHARS),
        "— Você vem? — perguntou ela, já na porta.",
    )

    @Test
    fun comparesMemoryPoolOnAndOff() = runBlocking {
        val voice = VoiceModels.ALL.firstOrNull { downloader.isDownloaded(it) }
        assumeTrue("no voice installed", voice != null)
        fun config(name: String, text: String) = File(context.cacheDir, name).apply { writeText(text) }
        val modes = listOf(
            "A default (arena on, pattern on)" to null,
            "B arena off, pattern off" to config("b.config", "EnableCpuMemArena=0\nEnableMemPattern=0\n"),
            "C arena off, pattern on" to config("c.config", "EnableCpuMemArena=0\n"),
            "D arena on, pattern off" to config("d.config", "EnableMemPattern=0\n"),
        )
        val audioByMode = LinkedHashMap<String, List<FloatArray>>()
        val workByMode = LinkedHashMap<String, MutableList<Double>>()
        // Each mode twice, interleaved, so none profits from caches another warmed up.
        for ((label, config) in modes + modes) {
            repeat(2) { System.gc() }
            val baseline = rssMb()
            val engine = PiperEngine(downloader.voicesDir, downloader.sharedDataDir(), numThreads = 4, sessionConfig = config)
            engine.warmUp(voice!!.id)
            val loaded = rssMb()
            var peak = loaded
            val started = System.nanoTime()
            var audioSeconds = 0.0
            val audios = ArrayList<FloatArray>()
            repeat(3) {
                for (sentence in sentences) {
                    val audio = engine.synthesize(sentence, voice.id, voice.id, speed = 1f)
                    audioSeconds += audio.size.toDouble() / engine.sampleRate()
                    audios += audio
                    peak = maxOf(peak, rssMb())
                }
            }
            val work = (System.nanoTime() - started) / 1e9 / audioSeconds
            workByMode.getOrPut(label) { ArrayList() } += work
            val after = rssMb()
            // The same voice from a fresh engine: is the sound the same, sample for sample, both ways?
            val reference = audioByMode.values.firstOrNull()
            val same = reference?.let { ref -> ref.size == audios.size && ref.indices.all { ref[it].contentEquals(audios[it]) } }
            audioByMode.putIfAbsent(label, audios)
            Log.i(
                TAG,
                "$label: loaded ${loaded - baseline} MB, highest ${peak - baseline} MB, kept ${after - baseline} MB; " +
                    "${"%.4f".format(work)} s of work per s of speech; audio identical to the first run: ${same ?: "(first)"}",
            )
            engine.release()
        }
        for ((label, works) in workByMode) Log.i(TAG, "$label: mean ${"%.4f".format(works.average())} s of work per s of speech")
    }

    // Resident memory of the whole process: what the system counts against the app.
    private fun rssMb(): Long =
        File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.filter(Char::isDigit).toLong() / 1024

    private companion object {
        const val TAG = "VoiceMemory"
    }
}
