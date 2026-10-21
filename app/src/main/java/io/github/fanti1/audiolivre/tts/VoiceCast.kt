package io.github.fanti1.audiolivre.tts

import io.github.fanti1.audiolivre.data.VoiceSettings

// Which Brazilian voice reads what, and how fast. The narrator always has a voice; a character falls back to the
// narrator when its voice hasn't been downloaded.
object VoiceCast {

    // Piper speaks around 203 words/min at its natural rate; 0.70 lands near 140, the audiobook pace.
    const val BASE_SPEED = 0.70f

    // Above this the voice starts slurring, so faster reading is finished by TimeStretch, which only shortens
    // the audio and leaves the pronunciation alone.
    private const val MAX_MODEL_SPEED = 1.25f

    fun voiceId(role: VoiceRole, settings: VoiceSettings): String = when {
        role == VoiceRole.NARRATOR || !settings.characterVoices -> settings.narratorVoice
        role == VoiceRole.MALE -> settings.maleCharacterVoice
        else -> settings.femaleCharacterVoice
    }

    fun modelSpeed(voiceId: String, settings: VoiceSettings): Float =
        minOf(wantedSpeed(voiceId, settings), MAX_MODEL_SPEED)

    fun stretch(voiceId: String, settings: VoiceSettings): Float =
        wantedSpeed(voiceId, settings) / modelSpeed(voiceId, settings)

    private fun wantedSpeed(voiceId: String, settings: VoiceSettings): Float {
        val pace = VoiceModels.byId(voiceId)?.pace ?: 1f
        return BASE_SPEED * settings.speed * pace
    }
}
