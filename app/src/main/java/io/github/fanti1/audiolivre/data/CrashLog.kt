package io.github.fanti1.audiolivre.data

import android.content.Context
import android.os.Build
import java.io.File
import java.util.Date

// Most people who hit a crash have no adb, so its stack trace is kept on disk and shown
// (with a copy button) the next time the app opens.
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(describe(error, "thread ${thread.name}")) }
            EventLog.record("o app fechou por um erro: ${error.javaClass.simpleName}")
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    fun describe(error: Throwable, where: String): String = buildString {
        appendLine("${Date()} · $where")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        appendLine()
        append(error.stackTraceToString())
    }
}
