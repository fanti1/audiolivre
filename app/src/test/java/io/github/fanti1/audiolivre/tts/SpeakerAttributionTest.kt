package io.github.fanti1.audiolivre.tts

import io.github.fanti1.audiolivre.book.BookDocument
import io.github.fanti1.audiolivre.book.BookSentence
import io.github.fanti1.audiolivre.book.Chapter
import io.github.fanti1.audiolivre.book.GenderHint
import io.github.fanti1.audiolivre.book.GenderHint.FEMALE
import io.github.fanti1.audiolivre.book.GenderHint.MALE
import io.github.fanti1.audiolivre.book.GenderHint.UNKNOWN
import io.github.fanti1.audiolivre.book.Paragraph
import io.github.fanti1.audiolivre.book.SegmentKind
import io.github.fanti1.audiolivre.book.SegmentKind.NARRATION
import io.github.fanti1.audiolivre.book.SegmentKind.SPEECH
import io.github.fanti1.audiolivre.tts.VoiceRole.NARRATOR
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerAttributionTest {

    private fun book(vararg paragraphs: List<Pair<SegmentKind, GenderHint>>): BookDocument {
        val paras = mutableListOf<Paragraph>()
        val sentences = mutableListOf<BookSentence>()
        var offset = 0
        paragraphs.forEachIndexed { p, pieces ->
            val text = pieces.joinToString(" ") { "xx." }
            paras += Paragraph(text, chapterIndex = 0, isHeading = false, sourcePage = 0, segments = emptyList())
            pieces.forEachIndexed { k, (kind, hint) ->
                sentences += BookSentence(
                    index = sentences.size,
                    paragraphIndex = p,
                    startInParagraph = k * 4,
                    endInParagraph = k * 4 + 3,
                    kind = kind,
                    genderHint = hint,
                    sourcePage = 0,
                    charOffset = offset + k * 4,
                    paragraphText = text,
                )
            }
            offset += text.length + 1
        }
        return BookDocument("livro", listOf(Chapter("livro", 0)), paras, sentences)
    }

    private fun speech(hint: GenderHint = UNKNOWN) = listOf(SPEECH to hint)
    private val narration = listOf(NARRATION to UNKNOWN)

    @Test
    fun `narration is always the narrator`() {
        assertEquals(listOf(NARRATOR, NARRATOR), SpeakerAttribution.assign(book(narration, narration)))
    }

    @Test
    fun `hinted speech gets its gender and the narration tag stays with the narrator`() {
        val roles = SpeakerAttribution.assign(book(listOf(SPEECH to MALE, NARRATION to UNKNOWN)))
        assertEquals(listOf(VoiceRole.MALE, NARRATOR), roles)
    }

    @Test
    fun `a hint anywhere in the paragraph covers all of its speech`() {
        val roles = SpeakerAttribution.assign(book(listOf(SPEECH to UNKNOWN, NARRATION to UNKNOWN, SPEECH to FEMALE)))
        assertEquals(listOf(VoiceRole.FEMALE, NARRATOR, VoiceRole.FEMALE), roles)
    }

    @Test
    fun `a conversation with both genders known alternates the untagged lines`() {
        val roles = SpeakerAttribution.assign(book(speech(MALE), speech(FEMALE), speech(), speech()))
        assertEquals(listOf(VoiceRole.MALE, VoiceRole.FEMALE, VoiceRole.MALE, VoiceRole.FEMALE), roles)
    }

    @Test
    fun `a conversation with only one known gender leaves the rest to the narrator`() {
        val roles = SpeakerAttribution.assign(book(speech(MALE), speech(), speech(MALE)))
        assertEquals(listOf(VoiceRole.MALE, NARRATOR, VoiceRole.MALE), roles)
    }

    @Test
    fun `anchors that disagree about who is talking leave the line to the narrator`() {
        val roles = SpeakerAttribution.assign(book(speech(MALE), speech(FEMALE), speech(), speech(MALE)))
        assertEquals(listOf(VoiceRole.MALE, VoiceRole.FEMALE, NARRATOR, VoiceRole.MALE), roles)
    }

    @Test
    fun `untagged speech outside a conversation stays with the narrator`() {
        val roles = SpeakerAttribution.assign(book(narration, speech(), narration))
        assertEquals(listOf(NARRATOR, NARRATOR, NARRATOR), roles)
    }

    @Test
    fun `narration between dialogue ends the conversation`() {
        val roles = SpeakerAttribution.assign(book(speech(MALE), speech(FEMALE), narration, speech()))
        assertEquals(listOf(VoiceRole.MALE, VoiceRole.FEMALE, NARRATOR, NARRATOR), roles)
    }
}
