package io.github.fanti1.audiolivre

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fanti1.audiolivre.tts.ModelDownloader
import io.github.fanti1.audiolivre.tts.PiperEngine
import io.github.fanti1.audiolivre.tts.VoiceModels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

// Speaks on the device with the voices that are installed: the narrator's always is, since it ships in the APK.
// The test that needs a second voice is skipped when none was downloaded.
@RunWith(AndroidJUnit4::class)
class PiperEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val downloader = ModelDownloader(context).apply { ensureSharedData() }
    private val engine = PiperEngine(downloader.voicesDir, downloader.sharedDataDir(), context.assets)
    private val sentence = "Naquela noite, a cidade parecia diferente."

    @Test
    fun speaksWithAnInstalledVoice() = runBlocking {
        val voice = VoiceModels.ALL.firstOrNull { downloader.isDownloaded(it) }
        assumeTrue("no voice installed", voice != null)

        val audio = engine.synthesize(sentence, voice!!.id, voice.id, speed = 0.7f)
        // Every voice is played through one stream, so even Edresson's 16 kHz output comes back resampled.
        assertEquals(VoiceModels.PLAYBACK_SAMPLE_RATE, engine.sampleRate())
        assertTrue("samples: ${audio.size}", audio.size > engine.sampleRate())
        assertTrue("silent output", audio.any { kotlin.math.abs(it) > 0.01f })
    }

    @Test
    fun anotherVoiceSoundsDifferentAndAMissingOneFallsBack() = runBlocking {
        val installed = VoiceModels.ALL.filter { downloader.isDownloaded(it) }
        assumeTrue("needs two installed voices", installed.size >= 2)
        val (first, second) = installed

        val one = engine.synthesize(sentence, first.id, first.id, speed = 0.7f)
        val other = engine.synthesize(sentence, second.id, second.id, speed = 0.7f)
        assertTrue("silent output", one.any { kotlin.math.abs(it) > 0.01f } && other.any { kotlin.math.abs(it) > 0.01f })

        // A character whose voice was never downloaded speaks with the narrator's voice instead of going silent.
        // The model samples its own durations, so the same voice never repeats a take exactly; the length is
        // what tells the voices apart.
        val missing = VoiceModels.ALL.first { !downloader.isDownloaded(it) }
        val fallback = engine.synthesize(sentence, missing.id, first.id, speed = 0.7f)
        val difference = kotlin.math.abs(fallback.size - one.size).toFloat() / one.size
        assertTrue("fallback length differs by ${(difference * 100).toInt()}%", difference < 0.25f)

        engine.release()
    }
}
