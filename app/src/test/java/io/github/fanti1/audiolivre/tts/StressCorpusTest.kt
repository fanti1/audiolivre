package io.github.fanti1.audiolivre.tts

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Runs the normalizer over qa/stress_corpus.txt (the sentences the listening QA synthesizes) and exports the
// result for qa/listen_check.py. Whatever the voice would still have to guess at is a failure here.
class StressCorpusTest {

    private val corpus = File("../qa/stress_corpus.txt")
    private val leftovers = Regex("""[\d@&%$€£§°²³/\\*#_\[\]{}<>|~^=+«»“”„‘’\u00AD\u00A0\u200B\uFB00-\uFB06]""")

    @Test
    fun `stress corpus normalizes to plain speakable text`() {
        val lines = corpus.readLines().filter { it.isNotBlank() }
        val normalized = lines.map { PtBrNormalizer.normalize(it) }

        val out = File("build/qa/stress_normalized.jsonl").apply { parentFile.mkdirs() }
        out.bufferedWriter().use { writer ->
            lines.zip(normalized).forEachIndexed { i, (display, tts) ->
                writer.write(JSONObject().put("index", i).put("role", "NARRATOR").put("display", display).put("tts", tts).toString())
                writer.newLine()
            }
        }

        val problems = lines.zip(normalized).filter { (_, tts) -> leftovers.containsMatchIn(tts) }
        assertTrue(problems.joinToString("\n") { (display, tts) -> "$display\n  -> $tts" }, problems.isEmpty())
    }
}
