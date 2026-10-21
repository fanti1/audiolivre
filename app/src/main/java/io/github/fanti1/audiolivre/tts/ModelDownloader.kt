package io.github.fanti1.audiolivre.tts

import android.content.Context
import io.github.fanti1.audiolivre.data.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class ModelDownloader(private val context: Context) {

    fun voiceDir(voice: VoiceModel): File =
        File(context.filesDir, "tts/${voice.id}")

    // The espeak-ng pronunciation data ships inside every voice archive and is identical, so the first voice
    // installed keeps it for all of them: 18 MB once instead of 18 MB per voice.
    fun sharedDataDir(): File = File(context.filesDir, "tts/${VoiceModels.SHARED_DATA_DIR}")

    val voicesDir: File get() = File(context.filesDir, "tts")

    fun downloadedVoices(): Set<String> = VoiceModels.ALL.filter { isDownloaded(it) }.map { it.id }.toSet()

    // A model split across several files is only usable once every file is extracted, so a marker
    // written last (not the presence of any one file) decides whether the download finished.
    fun isDownloaded(voice: VoiceModel): Boolean = File(voiceDir(voice), COMPLETE_MARKER).exists()

    fun sizeOnDiskMb(voice: VoiceModel): Int =
        (voiceDir(voice).walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1_000_000).toInt()

    fun deleteLegacyVoices() {
        VoiceModels.LEGACY_IDS.forEach { File(context.filesDir, "tts/$it").deleteRecursively() }
    }

    // onProgress is only called when the stage or the whole-number percent changes.
    suspend fun ensureDownloaded(
        voice: VoiceModel,
        onProgress: (InstallProgress) -> Unit = {},
    ): File = runInterruptible(Dispatchers.IO) {
        val dir = voiceDir(voice)
        if (isDownloaded(voice)) return@runInterruptible dir

        var last: InstallProgress? = null
        fun report(stage: InstallStage, done: Long, total: Long) {
            val progress = InstallProgress(stage, if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else null)
            if (progress != last) {
                last = progress
                onProgress(progress)
            }
        }

        dir.deleteRecursively()
        dir.mkdirs()
        val tempArchive = File(context.cacheDir, voice.archiveName)
        try {
            downloadWithProgress(voice.downloadUrl, tempArchive) { done, total -> report(InstallStage.DOWNLOADING, done, total) }
            // Decompressing ~130 MB of bzip2 takes a while on a phone; without progress it looks frozen at 100%.
            extractTarBz2(tempArchive, dir, stripRootDir = voice.archiveRootDirName) { done, total ->
                report(InstallStage.INSTALLING, done, total)
            }
            shareEspeakData(dir)
            File(dir, COMPLETE_MARKER).createNewFile()
            EventLog.record("voz ${voice.id} baixada")
        } catch (e: Exception) {
            dir.deleteRecursively()
            EventLog.record("download da voz ${voice.id} falhou: ${e.javaClass.simpleName}")
            throw e
        } finally {
            tempArchive.delete()
        }
        dir
    }

    private fun shareEspeakData(voiceDir: File) {
        val bundled = File(voiceDir, VoiceModels.SHARED_DATA_DIR)
        if (!bundled.isDirectory) return
        val shared = sharedDataDir()
        if (shared.isDirectory && (shared.listFiles()?.isNotEmpty() == true)) {
            bundled.deleteRecursively()
        } else {
            shared.parentFile?.mkdirs()
            shared.deleteRecursively()
            if (!bundled.renameTo(shared)) bundled.copyRecursively(shared, overwrite = true).also { bundled.deleteRecursively() }
        }
    }

    fun deleteVoice(voice: VoiceModel) {
        voiceDir(voice).deleteRecursively()
    }

    private fun downloadWithProgress(
        url: String,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Servidor respondeu $responseCode ao baixar a voz")
            }
            val totalBytes = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    onProgress(0, totalBytes)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, totalBytes)
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            throw IOException("Conexão muito lenta ou instável — tente novamente", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTarBz2(
        archive: File,
        destDir: File,
        stripRootDir: String,
        onProgress: (compressedRead: Long, compressedTotal: Long) -> Unit,
    ) {
        val total = archive.length()
        val counting = CountingInputStream(archive.inputStream())
        BufferedInputStream(counting).use { fileIn ->
            BZip2CompressorInputStream(fileIn).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    var entry = tarIn.nextEntry
                    while (entry != null) {
                        val relativeName = entry.name.removePrefix("$stripRootDir/")
                        if (relativeName.isNotBlank()) {
                            val outFile = File(destDir, relativeName)
                            if (!outFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                                throw IOException("Arquivo do modelo com caminho inválido: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val read = tarIn.read(buffer)
                                        if (read == -1) break
                                        out.write(buffer, 0, read)
                                        onProgress(counting.count, total)
                                    }
                                }
                            }
                        }
                        entry = tarIn.nextEntry
                    }
                }
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val COMPLETE_MARKER = ".complete"
    }
}

enum class InstallStage { DOWNLOADING, INSTALLING }

data class InstallProgress(val stage: InstallStage, val percent: Int?)

private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it != -1) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}
