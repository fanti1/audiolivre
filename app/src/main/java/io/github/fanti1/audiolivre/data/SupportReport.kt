package io.github.fanti1.audiolivre.data

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import androidx.core.content.ContextCompat
import io.github.fanti1.audiolivre.BuildConfig
import io.github.fanti1.audiolivre.playback.SynthesisPace
import io.github.fanti1.audiolivre.translate.SlimtTranslator
import io.github.fanti1.audiolivre.translate.TranslationModelStore
import io.github.fanti1.audiolivre.tts.ModelDownloader
import io.github.fanti1.audiolivre.tts.VoiceModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// What someone asking for help can send without adb: the phone, the app's settings, what it has downloaded,
// how fast it speaks, the last crash and the recent events. The person sees the whole text before sharing it,
// and it never carries a book's title or text.
object SupportReport {

    suspend fun build(context: Context): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val voice = VoiceSettingsStore(app).settings.first()
        val reading = ReaderPreferencesStore(app).preferences.first()
        val books = LibraryStore(app).books.first()
        val downloader = ModelDownloader(app)
        val pace = SynthesisPace.of(app)
        val memory = ActivityManager.MemoryInfo().also { (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val storage = StatFs(app.filesDir.path)
        val cores = Runtime.getRuntime().availableProcessors()

        buildString {
            appendLine("Relatório de suporte do Audiolivre")
            appendLine("Gerado em ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())}")
            appendLine("Sem títulos nem texto de livros.")

            section("App")
            appendLine("Versão: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), ${BuildConfig.BUILD_TYPE}")
            appendLine("Tradutor embutido: ${if (SlimtTranslator.isAvailable) "sim" else "não"}")

            section("Aparelho")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Processador: $cores núcleos, ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Memória: ${memory.totalMem / MB} MB no total, ${memory.availMem / MB} MB livres")
            appendLine("Espaço livre: ${storage.availableBytes / MB} MB")
            appendLine("Economia de bateria: ${if (BatteryOptimization.isUnrestricted(app)) "app liberado" else "app restrito"}")
            val notifications = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            appendLine("Notificações: ${if (notifications) "permitidas" else "bloqueadas"}")

            section("Voz")
            appendLine("Narrador: ${voice.narratorVoice}; personagens: ${if (voice.characterVoices) "${voice.maleCharacterVoice} e ${voice.femaleCharacterVoice}" else "desligado"}")
            appendLine("Velocidade: ${voice.speed}×")
            val voices = VoiceModels.ALL.filter { downloader.isDownloaded(it) }
            appendLine("Vozes baixadas: ${voices.joinToString { "${it.id} (${downloader.sizeOnDiskMb(it)} MB)" }.ifEmpty { "nenhuma" }}")
            appendLine("Ritmo de síntese: %.2f ms de trabalho por ms de fala; %d threads".format(pace.ratio, SynthesisPace.threadsFor(cores)))
            appendLine("Áudio preparado à frente: ${pace.aheadMs / 1000} s; antes de começar: ${pace.startBufferMs / 1000} s")
            appendLine("Vezes que o áudio esvaziou: ${pace.underruns}")
            appendLine("Memória do motor de voz: ${if (pace.lean) "enxuta (aparelho com pouca RAM)" else "rápida"}")

            section("Leitura")
            appendLine(
                "Fonte ${reading.font}, tamanho ${reading.fontSizeSp ?: "automático"}, entrelinha ${reading.lineSpacing}, " +
                    "margens ${reading.margin}, alinhamento ${reading.alignment}, tema ${reading.theme}",
            )

            section("Tradutores")
            appendLine(TranslationModelStore(app).installed().entries.joinToString { "${it.key} (${it.value / MB} MB)" }.ifEmpty { "nenhum" })

            section("Biblioteca")
            appendLine("${books.size} livros; ${books.count { it.started }} começados")

            section("Último erro")
            appendLine(CrashLog.read(app)?.trim() ?: "nenhum")

            section("Eventos recentes")
            append(EventLog.read().ifEmpty { "nenhum\n" })
        }
    }

    private const val MB = 1_000_000L

    private fun StringBuilder.section(title: String) {
        appendLine()
        appendLine("== $title ==")
    }
}
