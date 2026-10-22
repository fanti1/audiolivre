package io.github.fanti1.audiolivre.ui

import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.fanti1.audiolivre.R
import io.github.fanti1.audiolivre.data.BatteryOptimization
import io.github.fanti1.audiolivre.data.VoiceSettings
import io.github.fanti1.audiolivre.data.VoiceSettingsStore
import io.github.fanti1.audiolivre.tts.InstallProgress
import io.github.fanti1.audiolivre.tts.ModelDownloader
import io.github.fanti1.audiolivre.tts.VoiceGender
import io.github.fanti1.audiolivre.tts.VoiceModel
import io.github.fanti1.audiolivre.tts.VoiceModels
import io.github.fanti1.audiolivre.translate.TranslationModelStore
import io.github.fanti1.audiolivre.translate.languageName
import io.github.fanti1.audiolivre.ui.theme.EbookTheme
import io.github.fanti1.audiolivre.ui.theme.enableEdgeToEdgeBars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private sealed interface VoiceState {
    data class Ready(val sizeMb: Int) : VoiceState
    data object Missing : VoiceState
    data class Downloading(val progress: InstallProgress?) : VoiceState
    data class Failed(val message: String) : VoiceState
}

// Which role a voice is being chosen for.
private enum class Role { NARRATOR, MALE, FEMALE }

class VoiceSettingsActivity : ComponentActivity() {

    // Checked again on every return: the answer is given in Android's own dialog or settings screen.
    private var batteryUnrestricted by mutableStateOf(true)

    override fun onResume() {
        super.onResume()
        batteryUnrestricted = BatteryOptimization.isUnrestricted(this)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdgeBars()
        super.onCreate(savedInstanceState)

        val store = VoiceSettingsStore(applicationContext)
        val downloader = ModelDownloader(applicationContext)
        val translators = TranslationModelStore(applicationContext)

        setContent {
            val settings by store.settings.collectAsState(initial = null)
            val scope = rememberCoroutineScope()
            val states = remember {
                mutableStateMapOf<String, VoiceState>().apply {
                    VoiceModels.ALL.forEach { voice ->
                        put(voice.id, if (downloader.isDownloaded(voice)) VoiceState.Ready(downloader.sizeOnDiskMb(voice)) else VoiceState.Missing)
                    }
                }
            }
            val jobs = remember { mutableStateMapOf<String, Job>() }
            var role by remember { mutableStateOf(Role.NARRATOR) }
            var confirmDelete by remember { mutableStateOf<VoiceModel?>(null) }
            val installedTranslators = remember { mutableStateMapOf<String, Long>().apply { putAll(translators.installed()) } }

            fun download(voice: VoiceModel, onReady: () -> Unit) {
                if (jobs[voice.id]?.isActive == true) return
                jobs[voice.id] = scope.launch {
                    states[voice.id] = VoiceState.Downloading(null)
                    states[voice.id] = try {
                        downloader.ensureDownloaded(voice) { states[voice.id] = VoiceState.Downloading(it) }
                        downloader.deleteLegacyVoices()
                        onReady()
                        VoiceState.Ready(downloader.sizeOnDiskMb(voice))
                    } catch (e: CancellationException) {
                        VoiceState.Missing
                    } catch (e: Exception) {
                        VoiceState.Failed(e.message ?: "Erro desconhecido")
                    }
                }
            }

            fun choose(voice: VoiceModel) {
                scope.launch {
                    when (role) {
                        Role.NARRATOR -> store.setNarratorVoice(voice.id)
                        Role.MALE -> store.setMaleCharacterVoice(voice.id)
                        Role.FEMALE -> store.setFemaleCharacterVoice(voice.id)
                    }
                }
                if (states[voice.id] !is VoiceState.Ready) download(voice) {}
            }

            EbookTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Voz") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Voltar")
                                }
                            },
                        )
                    },
                ) { padding ->
                    val current = settings ?: return@Scaffold
                    VoiceScreen(
                        settings = current,
                        role = role,
                        states = states,
                        modifier = Modifier.padding(padding),
                        onRole = { role = it },
                        onChoose = ::choose,
                        onDownload = { download(it) {} },
                        onCancel = { jobs[it.id]?.cancel() },
                        onDelete = { confirmDelete = it },
                        onCharacterVoices = { scope.launch { store.setCharacterVoices(it) } },
                        onSpeed = { scope.launch { store.setSpeed(it) } },
                        batteryUnrestricted = batteryUnrestricted,
                        onBattery = { BatteryOptimization.ask(this@VoiceSettingsActivity) },
                        translators = installedTranslators,
                        onDeleteTranslator = {
                            translators.deleteInstalled(it)
                            installedTranslators.remove(it)
                        },
                    )
                }
            }

            confirmDelete?.let { voice ->
                AlertDialog(
                    onDismissRequest = { confirmDelete = null },
                    title = { Text("Apagar a voz ${voice.displayName}?") },
                    text = { Text("Libera ${voice.approxSizeMb} MB. Dá para baixar de novo quando quiser.") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDelete = null
                            downloader.deleteVoice(voice)
                            states[voice.id] = VoiceState.Missing
                        }) { Text("Apagar") }
                    },
                    dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
                )
            }
        }
    }
}

@Composable
private fun VoiceScreen(
    settings: VoiceSettings,
    role: Role,
    states: Map<String, VoiceState>,
    modifier: Modifier = Modifier,
    onRole: (Role) -> Unit,
    onChoose: (VoiceModel) -> Unit,
    onDownload: (VoiceModel) -> Unit,
    onCancel: (VoiceModel) -> Unit,
    onDelete: (VoiceModel) -> Unit,
    onCharacterVoices: (Boolean) -> Unit,
    onSpeed: (Float) -> Unit,
    batteryUnrestricted: Boolean,
    onBattery: () -> Unit,
    translators: Map<String, Long>,
    onDeleteTranslator: (String) -> Unit,
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }

    fun stopSample() {
        player?.release()
        player = null
        playing = null
    }

    fun toggleSample(voice: VoiceModel) {
        if (playing == voice.id) {
            stopSample()
            return
        }
        stopSample()
        runCatching {
            context.assets.openFd("voice_samples/${voice.id}.ogg").use { afd ->
                player = MediaPlayer().apply {
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    setOnCompletionListener { stopSample() }
                    prepare()
                    start()
                }
            }
            playing = voice.id
        }
    }

    DisposableEffect(Unit) { onDispose { player?.release() } }

    val selected = when (role) {
        Role.NARRATOR -> settings.narratorVoice
        Role.MALE -> settings.maleCharacterVoice
        Role.FEMALE -> settings.femaleCharacterVoice
    }
    val voices = if (role == Role.FEMALE) {
        VoiceModels.ALL.sortedBy { it.gender != VoiceGender.FEMALE }
    } else {
        VoiceModels.ALL
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Todas as vozes são brasileiras e leem no aparelho, sem internet. O Faber já vem com o app; as outras " +
                "ocupam cerca de 63 MB cada e só são baixadas quando você escolhe.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        SectionTitle("Escolher voz para")
        RoleTabs(role = role, settings = settings, onRole = onRole)

        voices.forEach { voice ->
            VoiceCard(
                voice = voice,
                state = states[voice.id] ?: VoiceState.Missing,
                selected = voice.id == selected,
                playing = playing == voice.id,
                onSelect = { onChoose(voice) },
                onSample = { toggleSample(voice) },
                onDownload = { onDownload(voice) },
                onCancel = { onCancel(voice) },
                onDelete = { onDelete(voice) },
            )
        }

        SectionTitle("Vozes de personagens")
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Personagens com voz própria", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Falas com travessão ganham a voz de homem ou de mulher escolhida acima. Sem elas, o " +
                            "narrador lê tudo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = settings.characterVoices, onCheckedChange = onCharacterVoices)
            }
        }

        SectionTitle("Velocidade")
        SpeedControl(settings.speed, onSpeed)

        SectionTitle("Ler com a tela desligada")
        BatteryCard(batteryUnrestricted, onBattery)

        // Downloaded when a book in another language was translated; listed so the space can be taken back.
        if (translators.isNotEmpty()) {
            SectionTitle("Tradutores baixados")
            translators.toSortedMap().forEach { (id, bytes) -> TranslatorCard(id, bytes, onDelete = { onDeleteTranslator(id) }) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
    )
}

@Composable
private fun RoleTabs(role: Role, settings: VoiceSettings, onRole: (Role) -> Unit) {
    val options = listOf(
        Role.NARRATOR to "Narrador",
        Role.MALE to "Personagem homem",
        Role.FEMALE to "Personagem mulher",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { (option, label) ->
            val chosen = option == role
            val voiceId = when (option) {
                Role.NARRATOR -> settings.narratorVoice
                Role.MALE -> settings.maleCharacterVoice
                Role.FEMALE -> settings.femaleCharacterVoice
            }
            Surface(
                onClick = { onRole(option) },
                shape = RoundedCornerShape(12.dp),
                color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2)
                    Text(
                        VoiceModels.byId(voiceId)?.displayName ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceCard(
    voice: VoiceModel,
    state: VoiceState,
    selected: Boolean,
    playing: Boolean,
    onSelect: () -> Unit,
    onSample: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    // The whole card is the choice: one item for TalkBack ("Faber, selecionado"), the radio dot only shows it.
    Surface(
        selected = selected,
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().semantics { role = SemanticsRole.RadioButton },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(voice.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        voice.description + stateSuffix(state, voice),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state is VoiceState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = onSample) {
                    Icon(
                        painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (playing) "Parar amostra" else "Ouvir ${voice.displayName}",
                    )
                }
            }
            when (state) {
                is VoiceState.Downloading -> {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val percent = state.progress?.percent
                        if (percent == null) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            Text("$percent%", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = onCancel) { Text("Cancelar") }
                    }
                }

                is VoiceState.Missing, is VoiceState.Failed -> {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = onDownload) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Baixar ${voice.approxSizeMb} MB")
                    }
                }

                is VoiceState.Ready -> if (!selected && !voice.bundled) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onDelete) { Text("Apagar do aparelho") }
                }
            }
        }
    }
}

@Composable
private fun BatteryCard(unrestricted: Boolean, onBattery: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (unrestricted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_check_circle),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Liberado", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    "O Android não vai interromper a leitura para economizar bateria.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Para economizar bateria, o Android pode parar a leitura pouco depois de a tela apagar. " +
                        "Libere o Audiolivre para ele continuar lendo até você pausar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = onBattery) { Text("Permitir leitura em segundo plano") }
            }
        }
    }
}

@Composable
private fun TranslatorCard(id: String, bytes: Long, onDelete: () -> Unit) {
    val (from, to) = id.split('-', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${languageName(from).replaceFirstChar { it.uppercase() }} → ${languageName(to)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${bytes / 1_000_000} MB · baixa de novo quando um livro precisar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) { Text("Apagar") }
        }
    }
}

private fun stateSuffix(state: VoiceState, voice: VoiceModel): String = when (state) {
    is VoiceState.Ready -> if (voice.bundled) " · vem com o app" else " · baixada"
    is VoiceState.Missing -> " · ${voice.approxSizeMb} MB para baixar"
    is VoiceState.Downloading -> " · baixando"
    is VoiceState.Failed -> " · ${state.message}"
}

@Composable
private fun SpeedControl(speed: Float, onSpeed: (Float) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(speed) }
    val shown = if (dragging) dragValue else speed
    val label = "%.2f×".format(Locale("pt", "BR"), shown).replace(",00×", ",0×")

    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if ((shown * 100).roundToInt() == 100) {
                    Text(
                        "ritmo de audiolivro",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Slider(
                value = shown,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    dragging = false
                    onSpeed((dragValue * 20).roundToInt() / 20f)
                },
                valueRange = VoiceSettings.MIN_SPEED..VoiceSettings.MAX_SPEED,
            )
            Row {
                Text(
                    "Mais devagar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Mais rápido",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
