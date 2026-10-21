package io.github.fanti1.audiolivre.tts

import org.json.JSONObject
import org.junit.Test
import java.io.File

// Writes what the voice is sent for every example in qa/onomatopoeia_examples.txt, so
// qa/onomatopoeia_page.py can record the whole catalogue and check nothing comes out spelled.
class OnomatopoeiaExamplesTest {

    @Test
    fun writeExamples() {
        val source = File("../qa/onomatopoeia_examples.txt").takeIf { it.isFile } ?: return
        val out = File("build/qa/onomatopoeia.jsonl").apply { parentFile.mkdirs() }
        var group = ""
        out.bufferedWriter().use { w ->
            for (line in source.readLines().map { it.trim() }.filter { it.isNotEmpty() }) {
                if (line.startsWith("#")) {
                    group = line.removePrefix("#").trim()
                    continue
                }
                w.write(
                    JSONObject()
                        .put("group", group)
                        .put("written", line)
                        .put("spoken", PtBrNormalizer.normalize(line))
                        .put("drawnOut", Onomatopoeia.isDrawnOut(line))
                        .toString()
                )
                w.newLine()
            }
        }
    }
}
