package io.github.fanti1.audiolivre.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A short diary of what the app did (a book opened, a translation finished, audio running dry), kept on the
// phone for the support report. It never holds a book's title or text: only formats, sizes, counts and timings.
object EventLog {
    private const val FILE_NAME = "events.log"
    private const val MAX_LINES = 300
    private const val TRIM_AT_BYTES = 64 * 1024

    private val lock = Any()
    private var file: File? = null
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    fun install(context: Context) {
        file = File(context.filesDir, FILE_NAME)
    }

    fun record(message: String) {
        val target = file ?: return
        synchronized(lock) {
            runCatching {
                target.appendText("${stamp.format(Date())}  $message\n")
                if (target.length() > TRIM_AT_BYTES) target.writeText(target.readLines().takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
            }
        }
    }

    fun read(): String = synchronized(lock) { file?.takeIf { it.isFile }?.readText().orEmpty() }
}
