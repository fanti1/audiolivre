package io.github.fanti1.audiolivre.data

import org.json.JSONArray
import org.json.JSONObject

// The shelf is stored as one JSON array in DataStore: a handful of books, read and written whole. Unknown or
// broken content is treated as an empty shelf rather than crashing the app on start.
object LibraryJson {

    fun decode(raw: String?): List<LibraryBook> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                LibraryBook(
                    uri = o.getString("uri"),
                    title = o.optString("title", "Livro"),
                    addedAt = o.optLong("addedAt"),
                    lastOpenedAt = o.optLong("lastOpenedAt"),
                    sizeBytes = o.optLong("sizeBytes"),
                    totalChars = o.optInt("totalChars"),
                    charOffset = o.optInt("charOffset"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun encode(books: List<LibraryBook>): String {
        val array = JSONArray()
        for (b in books) {
            array.put(
                JSONObject()
                    .put("uri", b.uri)
                    .put("title", b.title)
                    .put("addedAt", b.addedAt)
                    .put("lastOpenedAt", b.lastOpenedAt)
                    .put("sizeBytes", b.sizeBytes)
                    .put("totalChars", b.totalChars)
                    .put("charOffset", b.charOffset)
            )
        }
        return array.toString()
    }
}
