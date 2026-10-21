package io.github.fanti1.audiolivre.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.fanti1.audiolivre.tts.VoiceModels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.voiceSettingsDataStore by preferencesDataStore(name = "voice_settings")

data class VoiceSettings(
    val narratorVoice: String = VoiceModels.DEFAULT_NARRATOR.id,
    val maleCharacterVoice: String = VoiceModels.DEFAULT_MALE_CHARACTER.id,
    val femaleCharacterVoice: String = VoiceModels.DEFAULT_FEMALE_CHARACTER.id,
    val characterVoices: Boolean = true,
    // Multiplier over the calibrated audiobook pace; 1.0 = ~140 words per minute.
    val speed: Float = 1.0f,
) {
    companion object {
        const val MIN_SPEED = 0.7f
        const val MAX_SPEED = 2.0f
        // The quick choices offered by the speed button in the reader's player.
        val SPEED_PRESETS = listOf(0.8f, 1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f)
    }
}

class VoiceSettingsStore(private val context: Context) {

    val settings: Flow<VoiceSettings> = context.voiceSettingsDataStore.data.map { prefs ->
        VoiceSettings(
            narratorVoice = voice(prefs[NARRATOR_VOICE_KEY], migratedNarrator(prefs[LEGACY_NARRATOR_KEY])),
            maleCharacterVoice = voice(prefs[MALE_VOICE_KEY], VoiceModels.DEFAULT_MALE_CHARACTER.id),
            femaleCharacterVoice = voice(prefs[FEMALE_VOICE_KEY], VoiceModels.DEFAULT_FEMALE_CHARACTER.id),
            characterVoices = prefs[CHARACTERS_KEY] ?: true,
            speed = (prefs[SPEED_KEY] ?: 1.0f).coerceIn(VoiceSettings.MIN_SPEED, VoiceSettings.MAX_SPEED),
        )
    }

    suspend fun setNarratorVoice(id: String) = edit(NARRATOR_VOICE_KEY, id)

    suspend fun setMaleCharacterVoice(id: String) = edit(MALE_VOICE_KEY, id)

    suspend fun setFemaleCharacterVoice(id: String) = edit(FEMALE_VOICE_KEY, id)

    suspend fun setCharacterVoices(value: Boolean) {
        context.voiceSettingsDataStore.edit { it[CHARACTERS_KEY] = value }
    }

    suspend fun setSpeed(value: Float) {
        context.voiceSettingsDataStore.edit { it[SPEED_KEY] = value }
    }

    private suspend fun edit(key: androidx.datastore.preferences.core.Preferences.Key<String>, id: String) {
        context.voiceSettingsDataStore.edit { it[key] = id }
    }

    // A voice the app no longer ships (or a typo in storage) must never leave the reader mute.
    private fun voice(stored: String?, fallback: String): String =
        stored?.takeIf { VoiceModels.byId(it) != null } ?: fallback

    // Before the Brazilian voices there were only two options, stored as MALE or FEMALE.
    private fun migratedNarrator(legacy: String?): String = when (legacy) {
        "FEMALE" -> VoiceModels.DEFAULT_FEMALE_CHARACTER.id
        else -> VoiceModels.DEFAULT_NARRATOR.id
    }

    private companion object {
        val NARRATOR_VOICE_KEY = stringPreferencesKey("narrator_voice")
        val MALE_VOICE_KEY = stringPreferencesKey("male_character_voice")
        val FEMALE_VOICE_KEY = stringPreferencesKey("female_character_voice")
        val LEGACY_NARRATOR_KEY = stringPreferencesKey("narrator")
        val CHARACTERS_KEY = booleanPreferencesKey("character_voices")
        val SPEED_KEY = floatPreferencesKey("speed")
    }
}
