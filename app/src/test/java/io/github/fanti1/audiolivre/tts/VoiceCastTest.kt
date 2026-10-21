package io.github.fanti1.audiolivre.tts

import io.github.fanti1.audiolivre.data.VoiceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCastTest {

    private val settings = VoiceSettings(
        narratorVoice = VoiceModels.FABER.id,
        maleCharacterVoice = VoiceModels.CADU.id,
        femaleCharacterVoice = VoiceModels.DII.id,
    )

    @Test
    fun `each role gets its own voice, unless characters are off`() {
        assertEquals(VoiceModels.FABER.id, VoiceCast.voiceId(VoiceRole.NARRATOR, settings))
        assertEquals(VoiceModels.CADU.id, VoiceCast.voiceId(VoiceRole.MALE, settings))
        assertEquals(VoiceModels.DII.id, VoiceCast.voiceId(VoiceRole.FEMALE, settings))
        val plain = settings.copy(characterVoices = false)
        assertEquals(VoiceModels.FABER.id, VoiceCast.voiceId(VoiceRole.MALE, plain))
        assertEquals(VoiceModels.FABER.id, VoiceCast.voiceId(VoiceRole.FEMALE, plain))
    }

    @Test
    fun `every voice reads at the same pace`() {
        // Each voice has its own natural tempo, corrected by its pace factor: the speech rate a listener hears
        // (engine speed divided by the voice's tempo) must be the same for all of them.
        val rates = VoiceModels.ALL.map { voice ->
            val total = VoiceCast.modelSpeed(voice.id, settings) * VoiceCast.stretch(voice.id, settings)
            total / voice.pace
        }
        rates.forEach { assertEquals(rates.first(), it, 1e-4f) }
        assertEquals(VoiceCast.BASE_SPEED, rates.first(), 1e-4f)
    }

    @Test
    fun `fast reading keeps the voice within its clear range and compresses the rest`() {
        val faber = VoiceModels.FABER.id
        val normal = settings.copy(speed = 1.0f)
        assertEquals(0.70f, VoiceCast.modelSpeed(faber, normal), 1e-4f)
        assertEquals(1.0f, VoiceCast.stretch(faber, normal), 1e-4f)

        val double = settings.copy(speed = 2.0f)
        // 0.70 x 2 = 1.40 is past the 1.25 the voice reads cleanly, so the rest is audio compression.
        assertEquals(1.25f, VoiceCast.modelSpeed(faber, double), 1e-4f)
        assertTrue(VoiceCast.stretch(faber, double) > 1f)
        assertEquals(
            VoiceCast.BASE_SPEED * 2f,
            VoiceCast.modelSpeed(faber, double) * VoiceCast.stretch(faber, double),
            1e-4f,
        )
    }
}
