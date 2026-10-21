package io.github.fanti1.audiolivre.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSentencesTest {

    @Test
    fun `paragraphs are split into sentences`() {
        assertEquals(
            listOf("It was late.", "The train had gone!", "What now?"),
            SourceSentences.split("It was late. The train had gone! What now?"),
        )
    }

    @Test
    fun `titles, initials and numbers do not end a sentence`() {
        assertEquals(
            listOf("Mr. Bennet met Dr. Jones and J. K. Rowling at 3.30 in St. Paul."),
            SourceSentences.split("Mr. Bennet met Dr. Jones and J. K. Rowling at 3.30 in St. Paul."),
        )
        assertEquals(listOf("La Sra. García llegó tarde."), SourceSentences.split("La Sra. García llegó tarde."))
    }

    @Test
    fun `quotes and dialogue stay with their sentence`() {
        assertEquals(
            listOf("“My dear Mr. Bennet,” said his lady.", "“Have you heard?”", "He had not."),
            SourceSentences.split("“My dear Mr. Bennet,” said his lady. “Have you heard?” He had not."),
        )
        assertEquals(listOf("—¿Qué gigantes?", "—dijo Sancho Panza."), SourceSentences.split("—¿Qué gigantes? —dijo Sancho Panza."))
    }

    @Test
    fun `a lowercase word after a period is not a new sentence`() {
        assertEquals(listOf("He bought apples, pears etc. and went home."), SourceSentences.split("He bought apples, pears etc. and went home."))
    }

    @Test
    fun `Chinese and Japanese end sentences without spaces`() {
        assertEquals(listOf("吾輩は猫である。", "名前はまだ無い。"), SourceSentences.split("吾輩は猫である。名前はまだ無い。"))
    }

    @Test
    fun `very long sentences are cut where a clause ends`() {
        val clause = "und er sah, wenn er den Kopf ein wenig hob, seinen gewölbten braunen Bauch"
        val sentence = (1..6).joinToString(", ") { clause } + "."
        val pieces = SourceSentences.split(sentence)
        assertTrue(pieces.toString(), pieces.size > 1)
        assertTrue(pieces.toString(), pieces.all { it.split(' ').size <= 50 })
        assertEquals(sentence.replace(" ", ""), pieces.joinToString("").replace(" ", ""))
    }
}
