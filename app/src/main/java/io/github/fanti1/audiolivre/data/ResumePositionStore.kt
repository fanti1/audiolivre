package io.github.fanti1.audiolivre.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

private val Context.resumePositionDataStore by preferencesDataStore(name = "resume_position")

sealed interface SavedPosition {
    data class Offset(val charOffset: Int) : SavedPosition

    // Written by versions before the book model: a PDF page plus the index among that page's sentences.
    data class Legacy(val page: Int, val indexInPage: Int) : SavedPosition
}

class ResumePositionStore(private val context: Context) {

    suspend fun save(uriString: String, charOffset: Int) {
        pending.remove(uriString)
        lock.withLock { write(uriString, charOffset) }
    }

    // For the reading itself, which saves as it goes and as it stops. The write runs in a scope of its own: a
    // position saved as the service or the screen goes away must still reach the disk, and DataStore drops an
    // edit whose caller was cancelled. Writes queue behind one lock and each takes the latest position asked for,
    // so an older position can never land after a newer one.
    fun saveSoon(uriString: String, charOffset: Int) {
        pending[uriString] = charOffset
        writer.launch {
            lock.withLock {
                val offset = pending.remove(uriString) ?: return@withLock
                write(uriString, offset)
            }
        }
    }

    private suspend fun write(uriString: String, charOffset: Int) {
        val hash = sha256Hex(uriString)
        context.resumePositionDataStore.edit { prefs ->
            prefs[offsetKey(hash)] = charOffset
            prefs.remove(pageKey(hash))
            prefs.remove(indexKey(hash))
        }
    }

    suspend fun load(uriString: String): SavedPosition? {
        val hash = sha256Hex(uriString)
        val prefs = context.resumePositionDataStore.data.first()
        prefs[offsetKey(hash)]?.let { return SavedPosition.Offset(it) }
        val page = prefs[pageKey(hash)] ?: return null
        val indexInPage = prefs[indexKey(hash)] ?: return null
        return SavedPosition.Legacy(page, indexInPage)
    }

    // The library shows how far each book got; one read of the store answers for the whole shelf.
    suspend fun offsets(uris: List<String>): Map<String, Int> {
        val prefs = context.resumePositionDataStore.data.first()
        return uris.mapNotNull { uri -> prefs[offsetKey(sha256Hex(uri))]?.let { uri to it } }.toMap()
    }

    // A position still waiting to be written would otherwise bring the book back from the start it was sent to.
    suspend fun clear(uriString: String) {
        pending.remove(uriString)
        val hash = sha256Hex(uriString)
        lock.withLock {
            context.resumePositionDataStore.edit { prefs ->
                prefs.remove(offsetKey(hash))
                prefs.remove(pageKey(hash))
                prefs.remove(indexKey(hash))
            }
        }
    }

    private fun offsetKey(hash: String) = intPreferencesKey("${hash}_offset")
    private fun pageKey(hash: String) = intPreferencesKey("${hash}_page")
    private fun indexKey(hash: String) = intPreferencesKey("${hash}_idx")

    // Shared by every instance: the reader, the service and the library each make their own store.
    private companion object {
        val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pending = ConcurrentHashMap<String, Int>()
        val lock = Mutex()
    }
}
