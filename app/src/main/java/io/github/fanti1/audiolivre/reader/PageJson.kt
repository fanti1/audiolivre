package io.github.fanti1.audiolivre.reader

import org.json.JSONArray
import org.json.JSONObject

object PageJson {

    fun encode(pages: List<Page>): String {
        val array = JSONArray()
        for (page in pages) {
            val slices = JSONArray()
            for (s in page.slices) {
                slices.put(s.paragraph).put(s.firstLine).put(s.lastLine).put(s.y.toDouble())
            }
            array.put(
                JSONObject()
                    .put("c", page.chapter)
                    .put("s", page.startOffset)
                    .put("e", page.endOffset)
                    .put("o", page.opensChapter)
                    .put("sl", slices),
            )
        }
        return array.toString()
    }

    fun decode(json: String): List<Page> {
        val array = JSONArray(json)
        return List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            val flat = obj.getJSONArray("sl")
            val slices = List(flat.length() / 4) { k ->
                PageSlice(
                    paragraph = flat.getInt(k * 4),
                    firstLine = flat.getInt(k * 4 + 1),
                    lastLine = flat.getInt(k * 4 + 2),
                    y = flat.getDouble(k * 4 + 3).toFloat(),
                )
            }
            Page(
                chapter = obj.getInt("c"),
                slices = slices,
                startOffset = obj.getInt("s"),
                endOffset = obj.getInt("e"),
                opensChapter = obj.getBoolean("o"),
            )
        }
    }
}
