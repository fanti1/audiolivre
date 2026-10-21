package io.github.fanti1.audiolivre.tts

enum class VoiceGender { MALE, FEMALE }

// A Piper voice: one small model per voice, each trained on a Brazilian speaker, so the accent is Brazilian by
// construction. The pronunciation data (espeak-ng) is the same for all of them and is kept once, shared.
data class VoiceModel(
    val id: String,
    val displayName: String,
    val description: String,
    val archiveName: String,
    val approxSizeMb: Int,
    val sampleRate: Int,
    val gender: VoiceGender,
    // Each voice has its own natural tempo; this multiplier brings them all to the same reading pace, so
    // switching voices doesn't change how fast the book is read (measured with qa/make_voice_samples.py).
    val pace: Float = 1.0f,
    // How much the model varies the timbre and the phoneme durations when it samples a sentence. Piper ships
    // 0.667 and 0.8; qa/tune_piper.py measured the whole grid on real book sentences and a calmer timbre came
    // out both more natural and easier to understand. See VoiceModels.NOISE_SCALE.
    val noiseScale: Float = VoiceModels.NOISE_SCALE,
    val noiseScaleW: Float = VoiceModels.NOISE_SCALE_W,
) {
    val downloadUrl: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$archiveName"

    // Archives unpack to a single top-level dir matching the archive's base name.
    val archiveRootDirName: String
        get() = archiveName.removeSuffix(".tar.bz2")
}

object VoiceModels {

    val FABER = VoiceModel(
        id = "faber", displayName = "Faber", description = "Masculina, clara e firme",
        archiveName = "vits-piper-pt_BR-faber-medium.tar.bz2", approxSizeMb = 63, sampleRate = 22050,
        gender = VoiceGender.MALE,
        pace = 1.0f,
    )
    val CADU = VoiceModel(
        id = "cadu", displayName = "Cadu", description = "Masculina, mais grave",
        archiveName = "vits-piper-pt_BR-cadu-medium.tar.bz2", approxSizeMb = 63, sampleRate = 22050,
        gender = VoiceGender.MALE,
        pace = 1.23f,
    )
    val JEFF = VoiceModel(
        id = "jeff", displayName = "Jeff", description = "Masculina, jovem",
        archiveName = "vits-piper-pt_BR-jeff-medium.tar.bz2", approxSizeMb = 63, sampleRate = 22050,
        gender = VoiceGender.MALE,
        pace = 1.23f,
    )
    val MIRO = VoiceModel(
        id = "miro", displayName = "Miro", description = "Masculina, grave e pausada",
        archiveName = "vits-piper-pt_BR-miro-high.tar.bz2", approxSizeMb = 64, sampleRate = 22050,
        gender = VoiceGender.MALE,
        pace = 1.17f,
    )
    val EDRESSON = VoiceModel(
        id = "edresson", displayName = "Edresson", description = "Masculina, gravação mais simples",
        archiveName = "vits-piper-pt_BR-edresson-low.tar.bz2", approxSizeMb = 63, sampleRate = 16000,
        gender = VoiceGender.MALE,
        pace = 1.4f,
    )
    val DII = VoiceModel(
        id = "dii", displayName = "Dii", description = "Feminina",
        archiveName = "vits-piper-pt_BR-dii-high.tar.bz2", approxSizeMb = 64, sampleRate = 22050,
        gender = VoiceGender.FEMALE,
        pace = 1.16f,
    )

    val ALL = listOf(FABER, CADU, JEFF, MIRO, DII, EDRESSON)

    val DEFAULT_NARRATOR = FABER
    val DEFAULT_MALE_CHARACTER = CADU
    val DEFAULT_FEMALE_CHARACTER = DII

    // Every voice speaks at this rate; Edresson's 16 kHz output is resampled so one audio stream fits them all.
    const val PLAYBACK_SAMPLE_RATE = 22050

    // Piper's own defaults are 0.667 and 0.8. Sweeping both over 24 Dom Casmurro sentences (qa/tune_piper.py)
    // showed the timbre temperature is what hurts: the lower it goes the steadier the voice reads, up to the
    // point where it starts to sound flat. These are the measured best.
    const val NOISE_SCALE = 0.25f
    const val NOISE_SCALE_W = 0.65f

    // espeak-ng data ships inside every voice archive; one copy serves all of them.
    const val SHARED_DATA_DIR = "espeak-ng-data"

    fun byId(id: String): VoiceModel? = ALL.firstOrNull { it.id == id }

    // The Supertonic model downloaded by earlier versions, no longer used and safe to delete.
    val LEGACY_IDS = listOf("supertonic3")
}
