package io.github.fanti1.audiolivre.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.fanti1.audiolivre.translate.TARGET_LANGUAGE
import io.github.fanti1.audiolivre.translate.TranslationStep
import io.github.fanti1.audiolivre.translate.languageName
import java.util.Locale

@Composable
internal fun TranslationScreen(step: TranslationStep, title: String, onCancel: () -> Unit, onLeave: () -> Unit) {
    when (step) {
        is TranslationStep.Ask -> AskTranslation(step, title, onCancel)
        is TranslationStep.Starting -> Progress(
            heading = "Preparando a tradução",
            body = "Um instante.",
            percent = null,
            note = null,
            onCancel = onCancel,
            onLeave = onLeave,
        )
        is TranslationStep.Downloading -> Progress(
            heading = "Baixando o tradutor",
            body = "${capitalized(languageName(step.language))} → português. É só desta vez; depois ele funciona sem internet.",
            percent = step.percent,
            note = null,
            onCancel = onCancel,
            onLeave = onLeave,
        )
        is TranslationStep.Translating -> Progress(
            heading = "Traduzindo para o português",
            body = "Do ${languageName(step.language)} para o português do Brasil, aqui no celular.",
            percent = step.percent,
            note = timeLeft(step.secondsLeft) +
                "Pode sair do app ou apagar a tela: a tradução continua e avisa quando o livro estiver pronto.",
            onCancel = onCancel,
            onLeave = onLeave,
        )
    }
}

@Composable
private fun AskTranslation(step: TranslationStep.Ask, title: String, onCancel: () -> Unit) {
    val supported = step.detected != null && step.detected in step.languages
    var choosing by remember { mutableStateOf(!supported) }
    var choice by remember { mutableStateOf(if (supported) step.detected else null) }
    val language = choice

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            when {
                supported -> "Este livro está em ${languageName(step.detected!!)}"
                step.detected != null -> "Este livro parece estar em ${languageName(step.detected)}"
                else -> "Em que idioma está este livro?"
            },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        if (step.detected != null && !supported) {
            Text(
                "Ainda não há tradutor desse idioma para o português. Se o livro estiver em outro idioma, escolha abaixo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "O Audiolivre lê em português. O livro vai ser traduzido para o português do Brasil aqui no celular, " +
                "sem enviar o texto para ninguém, e fica na biblioteca como “$title (pt-BR)”. O arquivo original " +
                "continua no seu celular, do jeito que está.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "A tradução leva alguns minutos e é feita por um programa, então pode ter erros.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val mb = language?.let { step.downloadMb[it] } ?: 0
        if (language != null && language != TARGET_LANGUAGE && mb > 0) {
            Text(
                "Antes, o app baixa o tradutor ${languageName(language)} → português: $mb MB, só desta vez.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (choosing) {
            LanguageList(step.languages, choice) { choice = it }
        }

        Button(
            onClick = { step.answer.complete(language) },
            enabled = language != null,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(if (language == TARGET_LANGUAGE) "Abrir sem traduzir" else "Traduzir")
        }
        if (!choosing) {
            TextButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Não é ${languageName(step.detected!!)}? Escolher o idioma")
            }
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancelar")
        }
    }
}

@Composable
private fun LanguageList(languages: List<String>, selected: String?, onSelect: (String) -> Unit) {
    val options = listOf(TARGET_LANGUAGE) + languages
    Column(modifier = Modifier.selectableGroup()) {
        options.forEachIndexed { i, code ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = code == selected, onClick = { onSelect(code) }, role = Role.RadioButton)
                    .padding(vertical = 4.dp),
            ) {
                RadioButton(selected = code == selected, onClick = null, modifier = Modifier.padding(12.dp))
                Text(
                    if (code == TARGET_LANGUAGE) "Português (já está em português)" else capitalized(languageName(code)),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun Progress(heading: String, body: String, percent: Int?, note: String?, onCancel: () -> Unit, onLeave: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(heading, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (percent == null) {
            LinearProgressIndicator(modifier = Modifier.padding(top = 28.dp).fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.padding(top = 28.dp).fillMaxWidth())
            Text(
                "$percent%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp).announcedPercent(percent),
            )
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        Button(onClick = onLeave, modifier = Modifier.padding(top = 24.dp)) {
            Text("Continuar em segundo plano")
        }
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) {
            Text("Cancelar a tradução")
        }
    }
}

private fun timeLeft(seconds: Long?): String {
    if (seconds == null) return ""
    val minutes = (seconds + 30) / 60
    return when {
        minutes < 1 -> "Falta menos de 1 minuto. "
        minutes == 1L -> "Falta cerca de 1 minuto. "
        minutes < 60 -> "Faltam cerca de $minutes minutos. "
        else -> "Faltam cerca de ${minutes / 60} h ${minutes % 60} min. "
    }
}

private fun capitalized(text: String) = text.replaceFirstChar { it.titlecase(Locale("pt", "BR")) }
