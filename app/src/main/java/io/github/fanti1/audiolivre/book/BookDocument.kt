package io.github.fanti1.audiolivre.book

data class PdfLine(
    val page: Int,
    val text: String,
    val x: Float,
    val right: Float,
    val y: Float,
    val height: Float,
    val fontSize: Float,
    val paragraphStart: Boolean,
    // Most books set section titles in bold at nearly the body size, where size alone cannot tell a title
    // from a paragraph.
    val bold: Boolean = false,
)

data class OutlineEntry(val title: String, val page: Int, val level: Int = 0)

enum class SegmentKind { NARRATION, SPEECH }

enum class GenderHint { MALE, FEMALE, UNKNOWN }

data class Segment(val kind: SegmentKind, val start: Int, val end: Int, val genderHint: GenderHint)

data class Paragraph(
    val text: String,
    val chapterIndex: Int,
    val isHeading: Boolean,
    val sourcePage: Int,
    val segments: List<Segment>,
)

data class Chapter(val title: String, val firstParagraph: Int)

// One sentence, by its place in its paragraph. What the voice says is worked out from the paragraph's text
// each time it is asked for (once or twice per sentence read), not kept: a long novel would otherwise hold its
// whole text twice, in memory and in the saved book.
class BookSentence(
    val index: Int,
    val paragraphIndex: Int,
    val startInParagraph: Int,
    val endInParagraph: Int,
    val kind: SegmentKind,
    val genderHint: GenderHint,
    val sourcePage: Int,
    val charOffset: Int,
    private val paragraphText: String,
) {
    val ttsText: String
        get() = BookBuilder.ttsTextOf(paragraphText.substring(startInParagraph, endInParagraph))

    override fun equals(other: Any?): Boolean =
        other is BookSentence && index == other.index && paragraphIndex == other.paragraphIndex &&
            startInParagraph == other.startInParagraph && endInParagraph == other.endInParagraph &&
            kind == other.kind && genderHint == other.genderHint && sourcePage == other.sourcePage &&
            charOffset == other.charOffset && ttsText == other.ttsText

    override fun hashCode(): Int = (index * 31 + paragraphIndex) * 31 + startInParagraph

    override fun toString(): String = "BookSentence($index, ¶$paragraphIndex $startInParagraph..$endInParagraph, $kind, \"$ttsText\")"
}

data class BookDocument(
    val title: String,
    val chapters: List<Chapter>,
    val paragraphs: List<Paragraph>,
    val sentences: List<BookSentence>,
) {
    // Global offset of each paragraph's first char; paragraphs are joined with one separator char.
    val paragraphStarts: IntArray by lazy {
        val starts = IntArray(paragraphs.size + 1)
        for (p in paragraphs.indices) starts[p + 1] = starts[p] + paragraphs[p].text.length + 1
        starts
    }

    fun sentenceAtOffset(offset: Int): Int {
        if (sentences.isEmpty()) return 0
        var low = 0
        var high = sentences.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (sentences[mid].charOffset <= offset) low = mid else high = mid - 1
        }
        return low
    }

    fun chapterOf(sentenceIndex: Int): Int = paragraphs[sentences[sentenceIndex].paragraphIndex].chapterIndex

    // Before the book model existed, resume positions were saved as (PDF page, index among that page's sentences).
    fun sentenceForLegacyPosition(page: Int, indexInPage: Int): Int {
        if (sentences.isEmpty()) return 0
        val first = sentences.indexOfFirst { it.sourcePage >= page }
        if (first == -1) return sentences.lastIndex
        if (sentences[first].sourcePage != page) return first
        val lastOnPage = sentences.indexOfLast { it.sourcePage == page }
        return (first + indexInPage.coerceAtLeast(0)).coerceAtMost(lastOnPage)
    }
}
