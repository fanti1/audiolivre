package io.github.fanti1.audiolivre.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.fanti1.audiolivre.ui.theme.EbookTheme
import io.github.fanti1.audiolivre.ui.theme.enableEdgeToEdgeBars

private const val MOZILLA_PRIVACY = "https://www.mozilla.org/pt-BR/privacy/websites/"
private const val GITHUB_PRIVACY = "https://docs.github.com/pt/site-policy/privacy-policies/github-general-privacy-statement"

// The privacy notice, written for the LGPD (Lei 13.709/2018). Every claim here is about what the code does:
// change the text when the app starts storing, sending or asking for something new, and the README with it.
class PrivacyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdgeBars()
        super.onCreate(savedInstanceState)
        setContent {
            EbookTheme {
                InfoScaffold(title = "Privacidade", onBack = ::finish) {
                    PrivacyContent(onAppSettings = ::openAppSettings)
                }
            }
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }
}

@Composable
private fun ColumnScope.PrivacyContent(onAppSettings: () -> Unit) {
    val open = rememberLinkOpener()

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Em resumo",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "O Audiolivre não coleta, não envia e não vende nenhum dado seu. Não tem conta, anúncios nem " +
                    "rastreadores, e quem fez o app não recebe nada do que você lê.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }

    PolicySection("O que fica guardado no seu celular") {
        Paragraph("Para funcionar, o app guarda no próprio aparelho:")
        Bullet("A sua biblioteca: o nome de cada livro, onde o arquivo está e até onde você leu.")
        Bullet("O texto e a capa de cada livro, já preparados, para abrir mais rápido. Para um livro que não estava em português, o texto guardado é o traduzido.")
        Bullet("Suas escolhas de leitura e de voz.")
        Bullet("As vozes e os tradutores que você baixou.")
        Bullet(
            "Se o app fechar por um erro, um relatório técnico: o modelo do celular, a versão do Android e o ponto " +
                "do app onde o erro aconteceu.",
        )
        Bullet(
            "Um diário técnico do que o app fez, como “tradução terminou em 40 s” ou “voz baixada”, para o relatório " +
                "de suporte. Ele não tem títulos nem texto de livros.",
        )
        Paragraph(
            "Nada disso sai do celular. O app fica fora do backup automático do Android, e os relatórios de erro e de " +
                "suporte só vão para algum lugar se você mesmo copiar ou compartilhar. Antes, o app mostra o texto inteiro.",
        )
    }

    PolicySection("Quando o app usa a internet") {
        Paragraph(
            "Só para baixar uma voz ou um tradutor, e só quando você pede. As vozes vêm do GitHub, onde fica o " +
                "projeto sherpa-onnx. Os tradutores vêm da Mozilla, dos mesmos servidores que o Firefox usa. Como em " +
                "qualquer download, esses servidores recebem o endereço IP do seu celular. Nenhuma informação sobre " +
                "você ou sobre os seus livros vai junto: a tradução é feita no próprio celular.",
        )
        Paragraph("Os links das telas Sobre e Privacidade abrem no seu navegador.")
    }

    PolicySection("Permissões") {
        Bullet("Internet: para baixar as vozes e os tradutores.")
        Bullet("Notificações: para mostrar os controles enquanto o livro toca.")
        Bullet("Serviço em primeiro plano e manter o processador ativo: para continuar lendo, e traduzindo, com a tela desligada.")
        Bullet(
            "Pedir para ignorar a economia de bateria: só é usada se você tocar em “Permitir leitura em segundo plano”, " +
                "na tela Voz, e quem decide é você, na própria janela do Android.",
        )
        Paragraph(
            "O app não pede acesso aos seus arquivos. Ele só abre os livros que você escolhe no seletor do próprio " +
                "Android.",
        )
    }

    PolicySection("Seus direitos (LGPD)") {
        Paragraph(
            "A Lei Geral de Proteção de Dados (Lei nº 13.709/2018) garante que você pode saber quais dados seus são " +
                "usados, corrigi-los e pedir que sejam apagados. Como o Audiolivre não coleta nada, não existe dado " +
                "seu guardado fora do seu celular. O que fica no aparelho está sob o seu controle:",
        )
        Bullet("Remover um livro da biblioteca apaga o texto (inclusive a tradução), a capa e a posição de leitura dele.")
        Bullet("Na tela Voz, cada voz e cada tradutor baixado pode ser apagado.")
        Bullet("Limpar o armazenamento do app, ou desinstalá-lo, apaga tudo.")
        FilledTonalButton(onClick = onAppSettings, modifier = Modifier.padding(top = 4.dp)) {
            Text("Abrir as configurações do app")
        }
    }

    InfoGroup("Dúvidas") {
        InfoRow("Perguntar no GitHub", "As issues são públicas: não coloque dados pessoais nelas", external = true) {
            open(AppLinks.ISSUES)
        }
        InfoDivider()
        InfoRow("Política de privacidade do GitHub", "Vale para o download das vozes", external = true) {
            open(GITHUB_PRIVACY)
        }
        InfoDivider()
        InfoRow("Política de privacidade da Mozilla", "Vale para o download dos tradutores", external = true) {
            open(MOZILLA_PRIVACY)
        }
    }

    Text(
        "Se algo mudar, esta tela muda junto, numa versão nova do app. Atualizada em 16 de outubro de 2026.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
internal fun PolicySection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
internal fun Paragraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Bullet(text: String) {
    Row {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
