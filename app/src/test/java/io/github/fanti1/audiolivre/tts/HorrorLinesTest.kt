package io.github.fanti1.audiolivre.tts

import org.json.JSONObject
import org.junit.Test
import java.io.File

// Writes what the voice is sent for each line of qa/horror_lines.txt, so qa/horror_page.py can record the
// before and after for the listening page. The sounds of horror (screams, hushes, stutters) are exactly
// what speech recognition mishears, so these are judged by ear rather than by a score.
class HorrorLinesTest {

    @Test
    fun writeHorrorLines() {
        val source = File("../qa/horror_lines.txt").takeIf { it.isFile } ?: return
        val out = File("build/qa/horror_normalized.jsonl").apply { parentFile.mkdirs() }
        out.bufferedWriter().use { w ->
            source.readLines().filter { it.isNotBlank() }.forEach { line ->
                w.write(
                    JSONObject()
                        .put("text", line)
                        .put("spoken", PtBrNormalizer.normalize(line))
                        .put("stretched", Onomatopoeia.isDrawnOut(line))
                        .toString()
                )
                w.newLine()
            }
        }
    }
}
