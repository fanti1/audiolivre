package io.github.fanti1.audiolivre.tts

import io.github.fanti1.audiolivre.book.BookDocument
import io.github.fanti1.audiolivre.book.DialogueSplitter
import io.github.fanti1.audiolivre.book.GenderHint
import io.github.fanti1.audiolivre.book.SegmentKind

enum class VoiceRole { NARRATOR, MALE, FEMALE }

// Names alone ("disse Maria") carry no gender hint here; those lines stay with the narrator unless the
// conversation around them pins the gender down.
object SpeakerAttribution {

    fun assign(book: BookDocument): List<VoiceRole> {
        val sentences = book.sentences
        val knownGender = mutableMapOf<Int, GenderHint>()
        val firstKindOfParagraph = mutableMapOf<Int, SegmentKind>()
        for (sentence in sentences) {
            firstKindOfParagraph.putIfAbsent(sentence.paragraphIndex, sentence.kind)
            if (sentence.kind == SegmentKind.SPEECH && sentence.genderHint != GenderHint.UNKNOWN) {
                // One dialogue paragraph is one speaker, so a hint anywhere in it covers all its lines.
                knownGender.putIfAbsent(sentence.paragraphIndex, sentence.genderHint)
            }
        }

        // "Capitu perguntou:" sits in the paragraph before the reply, so the line itself says nothing about
        // who is speaking. Only a paragraph that actually introduces speech counts; one that merely mentions
        // someone would hand the line to the wrong voice.
        for ((paragraph, kind) in firstKindOfParagraph) {
            if (kind != SegmentKind.SPEECH || paragraph in knownGender) continue
            // Only a paragraph that opens with a dialogue dash is certainly someone talking. A quoted phrase
            // inside narration also reads as speech, and handing that to a character voice is a mistake.
            val self = book.paragraphs.getOrNull(paragraph) ?: continue
            if (!DialogueSplitter.startsWithDialogueDash(self.text)) continue
            val before = book.paragraphs.getOrNull(paragraph - 1) ?: continue
            if (before.isHeading || before.segments.any { it.kind == SegmentKind.SPEECH }) continue
            val gender = DialogueSplitter.leadInGender(before.text)
            if (gender != GenderHint.UNKNOWN) knownGender[paragraph] = gender
        }

        val inferred = mutableMapOf<Int, GenderHint>()
        for (exchange in exchanges(firstKindOfParagraph)) {
            inferred += fillByAlternation(exchange, knownGender)
        }

        return sentences.map { sentence ->
            if (sentence.kind != SegmentKind.SPEECH) return@map VoiceRole.NARRATOR
            when (knownGender[sentence.paragraphIndex] ?: inferred[sentence.paragraphIndex]) {
                GenderHint.MALE -> VoiceRole.MALE
                GenderHint.FEMALE -> VoiceRole.FEMALE
                else -> VoiceRole.NARRATOR
            }
        }
    }

    // Consecutive paragraphs that open with speech form one back-and-forth conversation.
    private fun exchanges(firstKindOfParagraph: Map<Int, SegmentKind>): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        for (paragraph in firstKindOfParagraph.keys.sorted()) {
            val opensWithSpeech = firstKindOfParagraph[paragraph] == SegmentKind.SPEECH
            val continues = current.isNotEmpty() && paragraph == current.last() + 1
            if (opensWithSpeech && continues) {
                current += paragraph
            } else {
                if (current.isNotEmpty()) result += current
                current = if (opensWithSpeech) mutableListOf(paragraph) else mutableListOf()
            }
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    // A wrong-gender voice is worse than the narrator's, so guess only when both speakers' genders are
    // known and every anchor around the line agrees on who is talking.
    private fun fillByAlternation(exchange: List<Int>, known: Map<Int, GenderHint>): Map<Int, GenderHint> {
        val anchors = exchange.withIndex().mapNotNull { (position, paragraph) -> known[paragraph]?.let { position to it } }
        if (anchors.none { it.second == GenderHint.MALE } || anchors.none { it.second == GenderHint.FEMALE }) return emptyMap()

        val result = mutableMapOf<Int, GenderHint>()
        exchange.forEachIndexed { position, paragraph ->
            if (paragraph in known) return@forEachIndexed
            val before = anchors.lastOrNull { it.first < position }?.let { atDistance(it.second, position - it.first) }
            val after = anchors.firstOrNull { it.first > position }?.let { atDistance(it.second, it.first - position) }
            val guess = if (before != null && after != null) before.takeIf { it == after } else before ?: after
            if (guess != null) result[paragraph] = guess
        }
        return result
    }

    private fun atDistance(gender: GenderHint, distance: Int): GenderHint =
        if (distance % 2 == 0) gender else if (gender == GenderHint.MALE) GenderHint.FEMALE else GenderHint.MALE
}
