package io.github.fanti1.audiolivre.tts

import io.github.fanti1.audiolivre.book.BookDocument
import io.github.fanti1.audiolivre.book.BookSentence
import io.github.fanti1.audiolivre.book.SegmentKind

// How a narrator paces a book: where the silences go and how long they last. A voice that pauses the same
// amount everywhere sounds like a machine reading a list; the difference between a full stop, a new
// paragraph, a scene break and a chapter is most of what makes a reading sound narrated.
object Prosody {

    // Between two sentences of the same paragraph.
    const val SENTENCE_PAUSE_MS = 350

    // "…" leaves something hanging; a narrator waits longer there than at a full stop.
    const val ELLIPSIS_PAUSE_MS = 600

    // Between a line of dialogue and the narration around it ("— Não vou — disse ele."), so the reply and
    // the "disse ele" don't run together as one breath.
    const val SPEAKER_PAUSE_MS = 480

    const val PARAGRAPH_PAUSE_MS = 700

    // The blank line or "* * *" between two scenes: longer than a paragraph, shorter than a chapter.
    const val SCENE_PAUSE_MS = 1100

    // Around a heading, so the title doesn't sound like the first line of the chapter.
    const val HEADING_LEAD_MS = 900
    const val HEADING_TAIL_MS = 1000

    // "CAPÍTULO I" and the title under it are one announcement, so they stay closer together than the
    // title and the text that follows.
    const val HEADING_SUBTITLE_MS = 500

    const val CHAPTER_PAUSE_MS = 1400

    // Between the pieces of a long sentence split at a comma or semicolon, close to the voice's own comma pause.
    const val CLAUSE_PAUSE_MS = 180

    // A title is announced, not read at reading speed.
    const val HEADING_SPEED = 0.92f

    // Suspense is written in the rhythm of the silences. A line cut off mid-word ("Eu só queria—") is
    // interrupted: whoever speaks next does not wait, so the gap is short even across paragraphs.
    const val CUT_OFF_PAUSE_MS = 200

    // "Tum. Tum. Tum." / "Silêncio." — a sentence of a word or two is a beat, and a narrator lets it land.
    const val FRAGMENT_PAUSE_MS = 600
    private const val FRAGMENT_MAX_WORDS = 2

    // A stretched scream ("Aaaaaah!") comes out of the voice as a 0.8-second blip; read at this speed it is a
    // 1.5-second cry, still clean (measured with qa/horror_page.py).
    const val SCREAM_SPEED = 0.45f

    private const val ELLIPSIS = "…"

    fun pauseAfterMs(book: BookDocument, index: Int): Int {
        val current = book.sentences[index]
        val next = book.sentences.getOrNull(index + 1) ?: return 0
        val sameParagraph = next.paragraphIndex == current.paragraphIndex
        return when {
            book.chapterOf(next.index) != book.chapterOf(index) -> CHAPTER_PAUSE_MS
            isHeading(book, current.paragraphIndex) && isHeading(book, next.paragraphIndex) -> HEADING_SUBTITLE_MS
            isHeading(book, current.paragraphIndex) && !sameParagraph -> HEADING_TAIL_MS
            isHeading(book, next.paragraphIndex) -> HEADING_LEAD_MS
            crossesSceneBreak(book, current.paragraphIndex, next.paragraphIndex) -> SCENE_PAUSE_MS
            isCutOff(book, current) -> CUT_OFF_PAUSE_MS
            !sameParagraph -> PARAGRAPH_PAUSE_MS
            endsWithEllipsis(current) -> ELLIPSIS_PAUSE_MS
            isFragment(book, current) -> FRAGMENT_PAUSE_MS
            changesSpeaker(current, next) -> SPEAKER_PAUSE_MS
            else -> SENTENCE_PAUSE_MS
        }
    }

    // Titles are read a touch slower and a stretched scream much slower; everything else keeps the pace the
    // reader chose.
    fun speedFactor(book: BookDocument, index: Int): Float {
        val sentence = book.sentences[index]
        return when {
            isHeading(book, sentence.paragraphIndex) -> HEADING_SPEED
            // A sound has its own pace (a laugh runs quicker); a sound the narrator draws out is read slow.
            else -> Onomatopoeia.speedOf(sentence.ttsText)
                ?: if (Onomatopoeia.isDrawnOut(Onomatopoeia.speak(sentence.ttsText))) SCREAM_SPEED else 1f
        }
    }

    // The "* * *" or row of dashes between two scenes has nothing to say, so it never becomes a sentence:
    // the only trace of it is the paragraph sitting between the sentence that ends one scene and the one
    // that opens the next. Reading straight through it loses the break entirely.
    fun crossesSceneBreak(book: BookDocument, from: Int, to: Int): Boolean =
        (from + 1 until to).any { isSceneBreak(book, it) }

    fun isSceneBreak(book: BookDocument, paragraphIndex: Int): Boolean {
        val text = book.paragraphs.getOrNull(paragraphIndex)?.text ?: return false
        return text.isNotBlank() && text.none { it.isLetterOrDigit() }
    }

    private fun isHeading(book: BookDocument, paragraphIndex: Int): Boolean =
        book.paragraphs.getOrNull(paragraphIndex)?.isHeading == true

    // The sentence as printed: the spoken text has had its dashes and quotes trimmed away.
    private fun printed(book: BookDocument, sentence: BookSentence): String =
        book.paragraphs[sentence.paragraphIndex].text.substring(sentence.startInParagraph, sentence.endInParagraph)

    // "queria—" with the dash glued to the word: the line was interrupted. A dash after a space ("queria —")
    // introduces the narrator instead.
    private fun isCutOff(book: BookDocument, sentence: BookSentence): Boolean {
        val text = printed(book, sentence).trimEnd()
        val last = text.lastOrNull() ?: return false
        return (last == '—' || last == '–') && text.length > 1 && !text[text.length - 2].isWhitespace()
    }

    private fun isFragment(book: BookDocument, sentence: BookSentence): Boolean {
        val text = sentence.ttsText.trim()
        if (!text.endsWith('.') || text.endsWith("..")) return false
        return text.split(WHITESPACE).count { word -> word.any { it.isLetter() } } in 1..FRAGMENT_MAX_WORDS
    }

    private val WHITESPACE = Regex("""\s+""")

    private fun endsWithEllipsis(sentence: BookSentence): Boolean =
        sentence.ttsText.trimEnd().endsWith(ELLIPSIS) || sentence.ttsText.trimEnd().endsWith("...")

    // Speech giving way to narration, or one character answering another.
    private fun changesSpeaker(current: BookSentence, next: BookSentence): Boolean =
        current.kind != next.kind ||
            (current.kind == SegmentKind.SPEECH && current.genderHint != next.genderHint)
}
