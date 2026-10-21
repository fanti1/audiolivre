package io.github.fanti1.audiolivre.translate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.fanti1.audiolivre.R
import io.github.fanti1.audiolivre.data.BookCache
import io.github.fanti1.audiolivre.data.CrashLog
import io.github.fanti1.audiolivre.data.LibraryStore
import io.github.fanti1.audiolivre.data.sha256Hex
import io.github.fanti1.audiolivre.ui.ReaderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Translates books with the app in the background: a novel takes minutes, and nobody should have to keep the
// screen on and the app open for it. Shows the progress in a notification, keeps the CPU awake while it works,
// and saves the result where the reader looks for it.
class TranslationService : LifecycleService() {

    private val jobs = HashMap<String, Job>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastPercent = -1

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, "Tradução em andamento", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "Tradução pronta", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val uri = intent?.getStringExtra(EXTRA_URI)
        when (intent?.action) {
            ACTION_START -> if (uri != null) start(uri) else stopIfIdle()
            ACTION_CANCEL -> if (uri != null) {
                jobs.remove(uri)?.cancel()
                stopIfIdle()
            }
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun start(uri: String) {
        // A foreground service has to show its notification at once, even when there turns out to be nothing to do.
        showProgress(uri, "Preparando a tradução…", null)
        if (jobs[uri]?.isActive == true) return
        val pending = TranslationJobs.take(uri) ?: return stopIfIdle()
        acquireWakeLock()
        jobs[uri] = lifecycleScope.launch {
            try {
                val book = BookTranslationFlow(this@TranslationService).translate(pending.book, sha256Hex(uri), pending.language) { step ->
                    TranslationJobs.set(uri, TranslationJobs.Status.Running(step))
                    when (step) {
                        is TranslationStep.Downloading -> showProgress(uri, "Baixando o tradutor de ${languageName(step.language)}", step.percent)
                        is TranslationStep.Translating -> showProgress(uri, "Traduzindo “${pending.title}”", step.percent)
                        else -> Unit
                    }
                }
                BookCache(applicationContext).save(Uri.parse(uri), book)
                LibraryStore(applicationContext).renameIfDifferent(uri, book.title)
                TranslationJobs.set(uri, TranslationJobs.Status.Done)
                notifyFinished(uri, "Tradução pronta", "“${book.title}” já pode ser lido e ouvido.")
            } catch (e: CancellationException) {
                TranslationJobs.clear(uri)
                throw e
            } catch (e: Throwable) {
                TranslationJobs.set(uri, TranslationJobs.Status.Failed(e.message ?: "Erro na tradução", CrashLog.describe(e, "traduzindo")))
                notifyFinished(uri, "A tradução não terminou", "Abra o livro para ver o que aconteceu e tentar de novo.")
            } finally {
                jobs.remove(uri)
                stopIfIdle()
            }
        }
    }

    private fun stopIfIdle() {
        if (jobs.values.any { it.isActive }) return
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // The phone may sleep with the screen off; translating needs the processor until it is done.
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Audiolivre:translation")
            .apply { acquire(WAKE_LOCK_LIMIT_MS) }
    }

    private fun showProgress(uri: String, text: String, percent: Int?) {
        if (percent != null && percent == lastPercent) return
        lastPercent = percent ?: -1
        val notification = NotificationCompat.Builder(this, PROGRESS_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Traduzindo para o português")
            .setContentText(text)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openBook(uri))
            .addAction(0, "Cancelar", cancelIntent(uri))
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, PROGRESS_ID, notification, type)
    }

    private fun notifyFinished(uri: String, title: String, text: String) {
        val notification = NotificationCompat.Builder(this, DONE_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openBook(uri))
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(uri.hashCode(), notification) }
    }

    private fun openBook(uri: String): PendingIntent = PendingIntent.getActivity(
        this,
        uri.hashCode(),
        ReaderActivity.intent(this, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(uri: String): PendingIntent = PendingIntent.getService(
        this,
        uri.hashCode() + 1,
        Intent(this, TranslationService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_URI, uri),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "io.github.fanti1.audiolivre.action.TRANSLATE"
        const val ACTION_CANCEL = "io.github.fanti1.audiolivre.action.CANCEL_TRANSLATION"
        const val EXTRA_URI = "uri"
        private const val PROGRESS_CHANNEL = "translation"
        private const val DONE_CHANNEL = "translation_done"
        private const val PROGRESS_ID = 2
        private const val WAKE_LOCK_LIMIT_MS = 3L * 60 * 60 * 1000
    }
}
