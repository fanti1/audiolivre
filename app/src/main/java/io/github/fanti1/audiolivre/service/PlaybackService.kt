package io.github.fanti1.audiolivre.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import io.github.fanti1.audiolivre.R
import io.github.fanti1.audiolivre.book.BookDocument
import io.github.fanti1.audiolivre.data.ResumePositionStore
import io.github.fanti1.audiolivre.data.VoiceSettings
import io.github.fanti1.audiolivre.data.VoiceSettingsStore
import io.github.fanti1.audiolivre.playback.PlaybackController
import io.github.fanti1.audiolivre.playback.PlaybackState
import io.github.fanti1.audiolivre.playback.SynthesisPace
import io.github.fanti1.audiolivre.data.EventLog
import io.github.fanti1.audiolivre.tts.VoiceMemoryMode
import io.github.fanti1.audiolivre.tts.SpeakerAttribution
import io.github.fanti1.audiolivre.tts.ModelDownloader
import io.github.fanti1.audiolivre.tts.PiperEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PlaybackService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: PlaybackController? = null
    private var currentEngine: PiperEngine? = null
    private var voiceSettings = VoiceSettings()
    private var bookTitle: String = ""
    private var bookUri: String? = null
    private var currentBook: BookDocument? = null

    private val _sleepTimer = MutableStateFlow<SleepTimer?>(null)
    val sleepTimer: StateFlow<SleepTimer?> = _sleepTimer.asStateFlow()
    private var sleepJob: Job? = null

    private lateinit var mediaSession: MediaSessionCompat
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        mediaSession = MediaSessionCompat(this, "EbookReaderSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                // Headset, watch and car buttons: a sentence back or forward, like the reader's own arrows.
                override fun onSkipToNext() = skip(1)
                override fun onSkipToPrevious() = skip(-1)
            })
            isActive = true
        }
        // The state changes with every sentence read; the notification only shows the book and play/pause.
        lifecycleScope.launch {
            state.map { Triple(it.isPlaying, it.isFinished, bookTitle) }
                .distinctUntilChanged()
                .collect { updateNotificationAndSession(state.value) }
        }
        lifecycleScope.launch {
            VoiceSettingsStore(this@PlaybackService).settings.collect { settings ->
                voiceSettings = settings
                controller?.updateSettings(settings)
            }
        }
    }

    // Loading the model takes seconds and ~150 MB, so one engine is kept for the service's lifetime
    // and shared by every book opened.
    // One engine for the service's life; it loads each Brazilian voice the book actually needs.
    // Called early by the reader, in parallel with reading the book, and again before playing; the lock makes the
    // second call wait for the first instead of loading the voice twice.
    suspend fun ensureEngine(narratorVoiceId: String): PiperEngine = engineLock.withLock {
        val engine = currentEngine ?: withContext(Dispatchers.Default) {
            val downloader = ModelDownloader(applicationContext)
            val threads = SynthesisPace.threadsFor(Runtime.getRuntime().availableProcessors())
            val activities = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val memory = ActivityManager.MemoryInfo().also(activities::getMemoryInfo)
            val pace = SynthesisPace.of(applicationContext)
            val mode = VoiceMemoryMode.choose(memory.totalMem, activities.isLowRamDevice, pace)
            pace.useLean(mode == VoiceMemoryMode.LEAN, VoiceMemoryMode.LEAN_SLOWDOWN)
            EventLog.record("motor de voz: $threads threads, memória ${mode.name.lowercase()} (${memory.totalMem / 1_000_000} MB de RAM)")
            downloader.ensureSharedData()
            PiperEngine(
                downloader.voicesDir,
                downloader.sharedDataDir(),
                applicationContext.assets,
                numThreads = threads,
                sessionConfig = mode.configFile(downloader.voicesDir),
            )
        }.also { currentEngine = it }
        engine.warmUp(narratorVoiceId)
        engine
    }

    private val engineLock = Mutex()

    fun loadBook(
        uriString: String,
        title: String,
        book: BookDocument,
        resumeStore: ResumePositionStore,
        initialIndex: Int,
    ) {
        val engine = checkNotNull(currentEngine) { "ensureEngine() must run before loadBook()" }
        controller?.release()
        bookTitle = title
        bookUri = uriString
        currentBook = book
        _state.value = PlaybackState(currentIndex = initialIndex)
        controller = PlaybackController(
            book = book,
            engine = engine,
            roles = SpeakerAttribution.assign(book),
            initialSettings = voiceSettings,
            resumeStore = resumeStore,
            uriString = uriString,
            scope = lifecycleScope,
            state = _state,
            pace = SynthesisPace.of(this),
        )
    }

    fun play() {
        if (controller == null) return
        if (!requestAudioFocus()) return
        idleRelease?.cancel()
        controller?.play()
    }

    fun pause() {
        controller?.pause()
        abandonAudioFocus()
        releaseVoicesWhenIdle()
    }

    // A loaded voice holds hundreds of megabytes (the speech engine keeps the memory of its longest sentence).
    // Paused for a couple of minutes, the reader is probably not coming straight back: the memory goes back to
    // the phone, and the voice loads again in under a second on the next play.
    private var idleRelease: Job? = null

    private fun releaseVoicesWhenIdle() {
        idleRelease?.cancel()
        idleRelease = lifecycleScope.launch {
            delay(IDLE_RELEASE_MS)
            if (!_state.value.isPlaying) currentEngine?.releaseVoices(keep = emptySet())
        }
    }

    fun seekTo(index: Int) {
        controller?.seekTo(index)
    }

    fun skip(delta: Int) {
        controller?.seekTo(_state.value.currentIndex + delta)
    }

    // Stops the reading after some minutes, or when the chapter being heard ends; null switches it off.
    fun setSleepTimer(timer: SleepTimer?) {
        sleepJob?.cancel()
        _sleepTimer.value = timer
        if (timer == null) return
        sleepJob = lifecycleScope.launch {
            if (timer.endsAt != null) {
                delay((timer.endsAt - System.currentTimeMillis()).coerceAtLeast(0))
            } else {
                val book = currentBook ?: return@launch
                val chapter = book.chapterOf(_state.value.currentIndex)
                state.first { it.isFinished || book.chapterOf(it.currentIndex) != chapter }
            }
            pause()
            _sleepTimer.value = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_PREVIOUS -> skip(-1)
            ACTION_NEXT -> skip(1)
            // The book was removed or reset from the library: stop reading it, but leave another book alone.
            ACTION_FORGET_BOOK -> {
                if (intent.getStringExtra(EXTRA_URI) == bookUri) forgetBook()
            }

            ACTION_STOP -> {
                pause()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun forgetBook() {
        controller?.release()
        controller = null
        bookUri = null
        abandonAudioFocus()
        _state.value = PlaybackState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                // Pausing (not ducking) on any loss is the simplest correct behavior for
                // long-form narration — a phone call or another app's audio should stop reading,
                // not talk over it quietly. Resuming automatically on regain is intentionally
                // skipped: the user asked for playback to always be under full manual control.
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    pause()
                }
            }
            .build()
        focusRequest = request
        return am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Leitura em voz alta", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun updateNotificationAndSession(playback: PlaybackState) {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, bookTitle)
                .build()
        )
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (playback.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    playback.currentIndex.toLong(),
                    1f,
                )
                .build()
        )

        val playPauseAction = if (playback.isPlaying) {
            NotificationCompat.Action.Builder(android.R.drawable.ic_media_pause, "Pausar", actionIntent(ACTION_PAUSE)).build()
        } else {
            NotificationCompat.Action.Builder(android.R.drawable.ic_media_play, "Tocar", actionIntent(ACTION_PLAY)).build()
        }

        val contentText = when {
            playback.isFinished -> "Leitura concluída"
            playback.isPlaying -> "Lendo…"
            else -> "Pausado"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(bookTitle)
            .setContentText(contentText)
            .addAction(NotificationCompat.Action.Builder(android.R.drawable.ic_media_previous, "Frase anterior", actionIntent(ACTION_PREVIOUS)).build())
            .addAction(playPauseAction)
            .addAction(NotificationCompat.Action.Builder(android.R.drawable.ic_media_next, "Próxima frase", actionIntent(ACTION_NEXT)).build())
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .setOngoing(playback.isPlaying)
            .setOnlyAlertOnce(true)
            .setDeleteIntent(actionIntent(ACTION_STOP))
            .build()

        if (playback.isPlaying) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            // Detach (not remove) so the notification stays visible and dismissible while
            // paused, instead of disappearing or blocking as a non-dismissible foreground one.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    // Android is short of memory: voices not in use go first (each one is tens of megabytes), and all of them
    // when nothing is playing and the app is in the background. They load again when needed.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val engine = currentEngine ?: return
        val playing = _state.value.isPlaying
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && playing) return
        if (level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && !playing) return
        lifecycleScope.launch { engine.releaseVoices(keep = if (playing) setOf(voiceSettings.narratorVoice) else emptySet()) }
    }

    override fun onDestroy() {
        controller?.release()
        currentEngine?.release()
        mediaSession.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "io.github.fanti1.audiolivre.action.PLAY"
        const val ACTION_PAUSE = "io.github.fanti1.audiolivre.action.PAUSE"
        const val ACTION_STOP = "io.github.fanti1.audiolivre.action.STOP"
        const val ACTION_PREVIOUS = "io.github.fanti1.audiolivre.action.PREVIOUS"
        const val ACTION_NEXT = "io.github.fanti1.audiolivre.action.NEXT"
        private const val ACTION_FORGET_BOOK = "io.github.fanti1.audiolivre.action.FORGET_BOOK"
        private const val IDLE_RELEASE_MS = 2 * 60 * 1000L
        private const val EXTRA_URI = "uri"

        // Called by the library when a book is removed or sent back to the start.
        fun stopReading(context: Context, uriString: String) {
            context.startService(
                Intent(context, PlaybackService::class.java).setAction(ACTION_FORGET_BOOK).putExtra(EXTRA_URI, uriString)
            )
        }
    }
}

// When reading stops by itself: at a moment (endsAt, in milliseconds) or, when that is null, at the end of the
// chapter being heard.
data class SleepTimer(val endsAt: Long?)
