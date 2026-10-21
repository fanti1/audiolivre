package io.github.fanti1.audiolivre.translate

import android.content.Context
import io.github.fanti1.audiolivre.tts.InstallProgress
import io.github.fanti1.audiolivre.tts.InstallStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest

// Downloads translation models from Mozilla (the same registry and files Firefox uses) and keeps them in
// filesDir/translation/<from>-<to>/, one folder per direction.
class TranslationModelStore(private val context: Context) {

    private val root get() = File(context.filesDir, "translation")
    private val catalogFile get() = File(root, "catalog.json")

    fun pairDir(pair: ModelPair): File = File(root, pair.id)

    fun fileFor(pair: ModelPair, file: ModelFile): File = File(pairDir(pair), file.name)

    // Written last, with the version, so a half-finished download or an outdated model is never used.
    fun isInstalled(pair: ModelPair): Boolean =
        File(pairDir(pair), COMPLETE_MARKER).takeIf { it.isFile }?.readText()?.trim() == pair.version

    fun missingBytes(route: List<ModelPair>): Long = route.filterNot(::isInstalled).sumOf { it.sizeBytes }

    // The registry changes when Mozilla ships better models; a week-old copy is fresh enough, and without
    // internet any copy will do.
    suspend fun catalog(): TranslationCatalog = runInterruptible(Dispatchers.IO) {
        val cached = catalogFile.takeIf { it.isFile }
        val fresh = cached != null && System.currentTimeMillis() - cached.lastModified() < CATALOG_MAX_AGE_MS
        if (!fresh) {
            try {
                val json = fetchText(CATALOG_URL)
                val catalog = TranslationCatalog.parse(json)
                root.mkdirs()
                catalogFile.writeText(json)
                return@runInterruptible catalog.also { remember(catalogFile.lastModified(), it) }
            } catch (e: Exception) {
                if (cached == null) throw IOException("Sem internet para buscar o tradutor. Conecte-se e tente de novo.", e)
            }
        }
        // The registry is 400 KB of JSON; parsed once, it serves every book until the file changes.
        val stamp = catalogFile.lastModified()
        parsed?.takeIf { it.first == stamp }?.second
            ?: TranslationCatalog.parse(catalogFile.readText()).also { remember(stamp, it) }
    }

    // Downloads every model the route still lacks. onProgress is only called when the percent changes.
    suspend fun install(route: List<ModelPair>, onProgress: (InstallProgress) -> Unit = {}) = runInterruptible(Dispatchers.IO) {
        val missing = route.filterNot(::isInstalled)
        val total = missing.sumOf { it.sizeBytes }
        var done = 0L
        var lastPercent = -1
        fun report(extra: Long) {
            val percent = if (total > 0) ((done + extra) * 100 / total).toInt().coerceIn(0, 100) else 0
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(InstallProgress(InstallStage.DOWNLOADING, percent))
            }
        }
        for (pair in missing) {
            val dir = pairDir(pair)
            dir.deleteRecursively()
            dir.mkdirs()
            try {
                for (file in pair.files) {
                    download(file, fileFor(pair, file)) { report(it) }
                    done += file.size
                }
                File(dir, COMPLETE_MARKER).writeText(pair.version)
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
        }
    }

    fun delete(pair: ModelPair) {
        pairDir(pair).deleteRecursively()
    }

    // Folders of models already on the phone, by direction ("en-pt"), with their size.
    fun installed(): Map<String, Long> =
        root.listFiles { f -> f.isDirectory && File(f, COMPLETE_MARKER).isFile }.orEmpty()
            .associate { dir -> dir.name to dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    fun deleteInstalled(id: String) {
        File(root, id).takeIf { it.isDirectory && it.parentFile == root }?.deleteRecursively()
    }

    private fun download(file: ModelFile, target: File, onBytes: (Long) -> Unit) {
        val temp = File(target.path + ".part")
        val connection = open(ATTACHMENTS_URL + file.location)
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Servidor respondeu ${connection.responseCode} ao baixar o tradutor")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        onBytes(read)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (file.sha256.isNotEmpty() && !hash.equals(file.sha256, ignoreCase = true)) {
                throw IOException("O arquivo do tradutor chegou corrompido. Tente de novo.")
            }
            if (!temp.renameTo(target)) throw IOException("Não consegui salvar o tradutor no aparelho.")
        } catch (e: SocketTimeoutException) {
            throw IOException("Conexão muito lenta ou instável — tente novamente", e)
        } finally {
            connection.disconnect()
            temp.delete()
        }
    }

    private fun fetchText(url: String): String {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) throw IOException("Servidor respondeu ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
    }

    private companion object {
        @Volatile
        var parsed: Pair<Long, TranslationCatalog>? = null

        fun remember(stamp: Long, catalog: TranslationCatalog) {
            parsed = stamp to catalog
        }

        const val CATALOG_URL =
            "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/records"
        const val ATTACHMENTS_URL = "https://firefox-settings-attachments.cdn.mozilla.net/"
        const val CATALOG_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val COMPLETE_MARKER = ".complete"
    }
}
