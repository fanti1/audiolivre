package io.github.fanti1.audiolivre.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.fanti1.audiolivre.BuildConfig
import io.github.fanti1.audiolivre.R
import io.github.fanti1.audiolivre.data.SupportReport
import io.github.fanti1.audiolivre.ui.theme.EbookTheme
import io.github.fanti1.audiolivre.ui.theme.LiterataFamily
import io.github.fanti1.audiolivre.ui.theme.enableEdgeToEdgeBars
import kotlinx.coroutines.launch

internal object AppLinks {
    const val REPO = "https://github.com/fanti1/audiolivre"
    const val ISSUES = "$REPO/issues"
    const val RELEASES = "$REPO/releases"
    const val LICENSE = "$REPO/blob/main/LICENSE"
    const val AUTHOR = "https://github.com/fanti1"
}

private class Credit(val name: String, val use: String, val license: String, val url: String)

// The same projects as the README's credits table; keep the two in step.
private val CREDITS = listOf(
    Credit("sherpa-onnx", "Motor de voz", "Apache 2.0", "https://github.com/k2-fsa/sherpa-onnx"),
    Credit("ONNX Runtime", "Execução dos modelos de voz", "MIT", "https://github.com/microsoft/onnxruntime"),
    Credit("Piper", "Vozes em português", "CC0, CC BY 4.0 e outras (veja cada voz)", "https://huggingface.co/rhasspy/piper-voices"),
    Credit("TTS-Portuguese Corpus", "Gravações da voz Edresson, de Edresson Casanova", "CC BY 4.0", "https://github.com/Edresson/TTS-Portuguese-Corpus"),
    Credit("eSpeak NG", "Pronúncia", "GPL-3.0", "https://github.com/espeak-ng/espeak-ng"),
    Credit("Firefox Translations (Mozilla)", "Modelos de tradução", "MPL 2.0", "https://github.com/mozilla/translations"),
    Credit("slimt", "Motor de tradução, na versão de David Ventura", "GPL-2.0 ou posterior", "https://github.com/DavidVentura/slimt"),
    Credit("SentencePiece · ruy · cpuinfo", "Base do motor de tradução", "Apache 2.0 · Apache 2.0 · BSD", "https://github.com/google/sentencepiece"),
    Credit("PdfBox-Android", "Leitura de PDF", "Apache 2.0", "https://github.com/TomRoush/PdfBox-Android"),
    Credit("Apache Commons Compress", "Descompactar as vozes", "Apache 2.0", "https://commons.apache.org/proper/commons-compress/"),
    Credit("XZ for Java", "Descompactar as vozes", "Domínio público", "https://tukaani.org/xz/java.html"),
    Credit("Literata e Inter", "Fontes", "SIL Open Font License 1.1", "https://github.com/googlefonts/literata"),
    Credit("Atkinson Hyperlegible", "Fonte para baixa visão, do Braille Institute", "SIL Open Font License 1.1", "https://www.brailleinstitute.org/freefont/"),
    Credit("OpenDyslexic", "Fonte para dislexia, de Abbie Gonzalez", "SIL Open Font License 1.1", "https://opendyslexic.org"),
    Credit("Jetpack Compose, AndroidX e Kotlin", "Base do app", "Apache 2.0", "https://developer.android.com/jetpack"),
)

class AboutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdgeBars()
        super.onCreate(savedInstanceState)
        setContent {
            EbookTheme {
                InfoScaffold(title = "Sobre", onBack = ::finish) {
                    AboutContent(
                        onManual = { startActivity(Intent(this@AboutActivity, ManualActivity::class.java)) },
                        onPrivacy = { startActivity(Intent(this@AboutActivity, PrivacyActivity::class.java)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.AboutContent(onManual: () -> Unit, onPrivacy: () -> Unit) {
    val open = rememberLinkOpener()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(16.dp))
        Text("Audiolivre", style = MaterialTheme.typography.headlineMedium, fontFamily = LiterataFamily)
        Text(
            "Versão ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Text("Todo livro vira audiolivro.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Lê seus livros em voz alta com vozes brasileiras feitas por IA, direto no celular e sem internet. " +
                "Grátis, sem anúncios e de código aberto.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }

    InfoGroup("Ajuda") {
        InfoRow("Como usar o app", "Passo a passo, do primeiro livro às vozes", onClick = onManual)
        InfoDivider()
        InfoRow("Relatório de suporte", "Informações técnicas para mandar junto com um problema") {
            scope.launch { report = SupportReport.build(context) }
        }
    }

    report?.let { text ->
        SupportReportDialog(
            report = text,
            onShare = { shareText(context, text) },
            onDismiss = { report = null },
        )
    }

    InfoGroup("Projeto") {
        InfoRow("Código-fonte", "github.com/fanti1/audiolivre", external = true) { open(AppLinks.REPO) }
        InfoDivider()
        InfoRow("Relatar um problema ou sugerir algo", "Pelas issues do GitHub", external = true) { open(AppLinks.ISSUES) }
        InfoDivider()
        InfoRow("Versões novas", "Baixe a mais recente em Releases", external = true) { open(AppLinks.RELEASES) }
        InfoDivider()
        InfoRow("Feito por", "fanti1", external = true) { open(AppLinks.AUTHOR) }
    }

    InfoGroup("Privacidade") {
        InfoRow("Privacidade e LGPD", "O app não coleta nenhum dado seu", onClick = onPrivacy)
    }

    InfoGroup("Licença") {
        Text(
            "O Audiolivre é software livre, sob a licença GPL-3.0: você pode usar, estudar, modificar e compartilhar " +
                "o app. Quem distribuir uma versão modificada precisa manter a mesma licença e publicar o código. " +
                "O nome vem daí: audiolivro + livre.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        InfoDivider()
        InfoRow("Ler a licença completa", "GNU General Public License v3.0", external = true) { open(AppLinks.LICENSE) }
    }

    InfoGroup("Feito com") {
        CREDITS.forEachIndexed { i, credit ->
            if (i > 0) InfoDivider()
            InfoRow(credit.name, "${credit.use} · ${credit.license}", external = true) { open(credit.url) }
        }
    }
}

// The scaffold shared by the About and Privacy screens: a back arrow, a title and a scrolling column.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InfoScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun InfoGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
    )
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(content = content)
    }
}

@Composable
internal fun InfoRow(title: String, subtitle: String? = null, external: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                painterResource(if (external) R.drawable.ic_open_in_new else R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (external) 18.dp else 24.dp),
            )
        }
    }
}

@Composable
internal fun InfoDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
}

// A phone without a browser has nothing to open the link with; the tap then does nothing instead of crashing.
@Composable
internal fun rememberLinkOpener(): (String) -> Unit {
    val handler = LocalUriHandler.current
    return remember(handler) { { url: String -> runCatching { handler.openUri(url) } } }
}
