package io.github.fanti1.audiolivre

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.github.fanti1.audiolivre.book.BookDocument
import io.github.fanti1.audiolivre.data.ResumePositionStore
import io.github.fanti1.audiolivre.pdf.PdfTextExtractor
import io.github.fanti1.audiolivre.service.PlaybackService
import io.github.fanti1.audiolivre.tts.VoiceModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

// Measurements, not pass/fail checks: how far behind the voice the position on disk is (what a dead battery or
// Android ending the app leaves behind), and whether a position saved as the service goes away reaches the disk.
// Each prints its numbers to logcat under the tag PositionExperiment. Only runs when asked for:
//
//     adb shell am instrument -w -e experiment true -e class io.github.fanti1.audiolivre.PositionExperimentTest \
//         io.github.fanti1.audiolivre.test/androidx.test.runner.AndroidJUnitRunner
//
// The tests use only what both the old and the new code have, so the same file measures either build.
@RunWith(AndroidJUnit4::class)
class PositionExperimentTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val uri = "file:///experiment/dom_casmurro.pdf"

    private fun log(message: String) {
        Log.i(TAG, message)
        println("$TAG: $message")
    }

    private fun requested() = assumeTrue(InstrumentationRegistry.getArguments().getString("experiment") == "true")

    private fun book(): BookDocument = runBlocking {
        PDFBoxResourceLoader.init(context)
        val pdf = File(context.cacheDir, "dom_casmurro.pdf")
        testContext.assets.open("dom_casmurro.pdf").use { input -> pdf.outputStream().use { input.copyTo(it) } }
        PdfTextExtractor().extractBook(Uri.fromFile(pdf), context, "Dom Casmurro") { _, _ -> }
    }

    // The position as it is in the file: what survives the process dying this instant.
    private fun offsetOnDisk(): Int? {
        val file = File(context.filesDir, "datastore/resume_position.preferences_pb")
        if (!file.exists()) return null
        return Proto.intEndingWith(file.readBytes(), "_offset")
    }

    private fun clearDisk() {
        File(context.filesDir, "datastore/resume_position.preferences_pb").delete()
    }

    private fun bind(): Pair<PlaybackService, ServiceConnection> {
        var service: PlaybackService? = null
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as PlaybackService.LocalBinder).getService()
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        // Android 15 refuses audio focus to an app with nothing on screen; in real use the reader is open.
        context.startActivity(
            Intent(context, io.github.fanti1.audiolivre.ui.LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Thread.sleep(3_000)
        val intent = Intent(context, PlaybackService::class.java)
        context.startService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        check(latch.await(20, TimeUnit.SECONDS)) { "service did not bind" }
        return service!! to connection
    }

    private fun start(service: PlaybackService, book: BookDocument, from: Int) = runBlocking {
        service.ensureEngine(VoiceModels.DEFAULT_NARRATOR.id)
        withContext(Dispatchers.Main) {
            service.loadBook(uri, book.title, book, ResumePositionStore(context), from)
            service.play()
        }
    }

    private fun stop(service: PlaybackService, connection: ServiceConnection) = runBlocking {
        withContext(Dispatchers.Main) {
            context.unbindService(connection)
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    // 1. A dead battery at a random moment: the reading resumes from the start of the sentence on disk. How much
    // already heard would be heard again, against the least possible (the start of the sentence being heard)?
    @Test
    fun positionOnDiskWhilePlaying() {
        requested()
        val book = book()
        clearDisk()
        val (service, connection) = bind()
        val from = book.sentences.indexOfFirst { it.ttsText.startsWith("Uma noite destas") }
        start(service, book, from)
        val startedAt = HashMap<Int, Long>()
        val watcher = CoroutineScope(Dispatchers.Default).launch {
            service.state.collect { s -> if (s.isPlaying) startedAt.putIfAbsent(s.currentIndex, System.currentTimeMillis()) }
        }
        runBlocking { delay(8_000) }
        val random = Random(SEED)
        val rows = ArrayList<String>()
        var exact = 0
        var lagSentences = 0
        var repeatMs = 0L
        var minimumMs = 0L
        repeat(SAMPLES) {
            runBlocking { delay(random.nextLong(700, 2_500)) }
            val now = System.currentTimeMillis()
            val hearing = service.state.value.currentIndex
            val disk = offsetOnDisk()?.let(book::sentenceAtOffset) ?: -1
            val repeat = startedAt[disk]?.let { now - it } ?: -1
            val least = startedAt[hearing]?.let { now - it } ?: 0
            if (disk == hearing) exact++
            lagSentences += hearing - disk
            repeatMs += repeat
            minimumMs += least
            rows += "hearing=$hearing disk=$disk behind=${hearing - disk} reheard=${repeat}ms least=${least}ms"
        }
        watcher.cancel()
        stop(service, connection)
        rows.forEach(::log)
        log(
            "DISK-WHILE-PLAYING samples=$SAMPLES exact=$exact meanBehindSentences=%.2f meanReheardMs=%d meanLeastMs=%d"
                .format(lagSentences.toDouble() / SAMPLES, repeatMs / SAMPLES, minimumMs / SAMPLES),
        )
    }

    // 2. The service ended while it reads (Android stopping it): does the position at that moment reach the disk?
    @Test
    fun positionWhenTheServiceEnds() {
        requested()
        val book = book()
        val random = Random(SEED)
        val base = book.sentences.indexOfFirst { it.ttsText.startsWith("Uma noite destas") }
        var reached = 0
        repeat(SERVICE_RUNS) { run ->
            clearDisk()
            val (service, connection) = bind()
            start(service, book, base + run * 25)
            runBlocking { delay(random.nextLong(6_000, 12_000)) }
            val hearing = service.state.value.currentIndex
            stop(service, connection)
            runBlocking { delay(2_000) }
            val disk = offsetOnDisk()?.let(book::sentenceAtOffset) ?: -1
            if (disk == hearing) reached++
            log("run=$run hearing=$hearing disk=$disk")
        }
        log("SERVICE-END runs=$SERVICE_RUNS positionReachedDisk=$reached")
    }

    // 3. The mechanism under 2, alone: a DataStore write started in a scope that is cancelled right after, the way
    // the service's own scope is cancelled as it is destroyed.
    @Test
    fun writeFromACancelledScope() {
        requested()
        val store = ResumePositionStore(context)
        var kept = 0
        repeat(WRITES) { i ->
            clearDisk()
            runBlocking { store.save(uri, 1) }
            val value = 1_000 + i
            runBlocking(Dispatchers.Main) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                scope.launch { store.save(uri, value) }
                scope.cancel()
            }
            runBlocking { delay(300) }
            if (offsetOnDisk() == value) kept++
        }
        log("CANCELLED-SCOPE writes=$WRITES kept=$kept")
    }

    private companion object {
        const val TAG = "PositionExperiment"
        const val SEED = 20261101
        const val SAMPLES = 40
        const val SERVICE_RUNS = 8
        const val WRITES = 50
    }
}

// Just enough protobuf to read one int out of a Preferences DataStore file.
private object Proto {
    fun intEndingWith(data: ByteArray, suffix: String): Int? {
        for ((field, entry) in fields(data)) {
            if (field != 1 || entry !is ByteArray) continue
            var key: String? = null
            var value: ByteArray? = null
            for ((f, v) in fields(entry)) {
                if (f == 1 && v is ByteArray) key = String(v)
                if (f == 2 && v is ByteArray) value = v
            }
            if (key?.endsWith(suffix) == true && value != null) {
                for ((f, v) in fields(value)) if (f == 3 && v is Long) return v.toInt()
            }
        }
        return null
    }

    private fun fields(data: ByteArray): List<Pair<Int, Any>> {
        val out = ArrayList<Pair<Int, Any>>()
        var i = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = data[i++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return result
                shift += 7
            }
        }
        while (i < data.size) {
            val key = varint().toInt()
            when (key and 7) {
                0 -> out += (key ushr 3) to varint()
                2 -> {
                    val length = varint().toInt()
                    out += (key ushr 3) to data.copyOfRange(i, i + length)
                    i += length
                }
                5 -> i += 4
                1 -> i += 8
                else -> error("wire type ${key and 7}")
            }
        }
        return out
    }
}
