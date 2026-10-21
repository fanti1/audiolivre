package io.github.fanti1.audiolivre.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The support report, shown in full before it leaves the phone: the person decides where it goes.
@Composable
fun SupportReportDialog(report: String, onShare: () -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Relatório de suporte") },
        text = {
            Column {
                Text(
                    "Isto é tudo o que vai no relatório. Ele só sai do celular se você copiar ou compartilhar. " +
                        "Mande junto com a descrição do problema numa issue do GitHub.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ReportText(report)
            }
        },
        confirmButton = {
            TextButton(onClick = onShare) { Text("Compartilhar") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(report))
                    onDismiss()
                }) { Text("Copiar") }
                TextButton(onClick = onDismiss) { Text("Fechar") }
            }
        },
    )
}

fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Relatório de suporte do Audiolivre")
        .putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "Enviar relatório")) }
}

@Composable
private fun ReportText(report: String) {
    Text(
        report,
        modifier = Modifier
            .padding(top = 12.dp)
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState()),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun ErrorReportDialog(title: String, intro: String, report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(intro, style = MaterialTheme.typography.bodyMedium)
                ReportText(report)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(report))
                onDismiss()
            }) { Text("Copiar erro") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        },
    )
}
