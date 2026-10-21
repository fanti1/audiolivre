package io.github.fanti1.audiolivre.book

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class SourceStamp(val size: Long, val lastModified: Long)

object BookJson {

    // Bump whenever BookBuilder's output changes, so books cached by an older version get re-extracted.
    const val FORMAT_VERSION = 2

    fun encode(book: BookDocument, source: SourceStamp): String {
        val chapters = JSONArray()
        for (chapter in book.chapters) chapters.put(JSONArray().put(chapter.title).put(chapter.firstParagraph))

        val paragraphs = JSONArray()
        for (p in book.paragraphs) {
            val segments = JSONArray()
            for (s in p.segments) {
                segments.put(JSONArray().put(s.kind.ordinal).put(s.start).put(s.end).put(s.genderHint.ordinal))
            }
            paragraphs.put(
                JSONObject()
                    .put("t", p.text)
                    .put("c", p.chapterIndex)
                    .put("h", p.isHeading)
                    .put("p", p.sourcePage)
                    .put("s", segments),
            )
        }

        val sentences = JSONArray()
        for (s in book.sentences) {
            sentences.put(
                JSONArray()
                    .put(s.paragraphIndex)
                    .put(s.startInParagraph)
                    .put(s.endInParagraph)
                    .put(s.ttsText)
                    .put(s.kind.ordinal)
                    .put(s.genderHint.ordinal)
                    .put(s.sourcePage)
                    .put(s.charOffset),
            )
        }

        return JSONObject()
            .put("version", FORMAT_VERSION)
            .put("size", source.size)
            .put("modified", source.lastModified)
            .put("title", book.title)
            .put("chapters", chapters)
            .put("paragraphs", paragraphs)
            .put("sentences", sentences)
            .toString()
    }

    fun decode(json: String, expected: SourceStamp): BookDocument? = try {
        val root = JSONObject(json)
        if (root.getInt("version") != FORMAT_VERSION ||
            root.getLong("size") != expected.size ||
            root.getLong("modified") != expected.lastModified
        ) {
            null
        } else {
            decodeBook(root)
        }
    } catch (e: JSONException) {
        null
    }

    fun decodeUnchecked(json: String): BookDocument? = try {
        decodeBook(JSONObject(json))
    } catch (e: JSONException) {
        null
    }

    private fun decodeBook(root: JSONObject): BookDocument {
        val kinds = SegmentKind.entries
        val genders = GenderHint.entries

        val chaptersJson = root.getJSONArray("chapters")
        val chapters = (0 until chaptersJson.length()).map {
            val c = chaptersJson.getJSONArray(it)
            Chapter(c.getString(0), c.getInt(1))
        }

        val paragraphsJson = root.getJSONArray("paragraphs")
        val paragraphs = (0 until paragraphsJson.length()).map {
            val p = paragraphsJson.getJSONObject(it)
            val segmentsJson = p.getJSONArray("s")
            val segments = (0 until segmentsJson.length()).map { k ->
                val s = segmentsJson.getJSONArray(k)
                Segment(kinds[s.getInt(0)], s.getInt(1), s.getInt(2), genders[s.getInt(3)])
            }
            Paragraph(p.getString("t"), p.getInt("c"), p.getBoolean("h"), p.getInt("p"), segments)
        }

        val sentencesJson = root.getJSONArray("sentences")
        val sentences = (0 until sentencesJson.length()).map {
            val s = sentencesJson.getJSONArray(it)
            // The spoken text saved here (index 3) is worked out again from the paragraph, as BookBuilder does.
            BookSentence(
                index = it,
                paragraphIndex = s.getInt(0),
                startInParagraph = s.getInt(1),
                endInParagraph = s.getInt(2),
                kind = kinds[s.getInt(4)],
                genderHint = genders[s.getInt(5)],
                sourcePage = s.getInt(6),
                charOffset = s.getInt(7),
                paragraphText = paragraphs[s.getInt(0)].text,
            )
        }

        return BookDocument(root.getString("title"), chapters, paragraphs, sentences)
    }
}
