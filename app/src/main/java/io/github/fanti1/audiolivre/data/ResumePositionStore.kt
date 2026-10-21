package io.github.fanti1.audiolivre.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.resumePositionDataStore by preferencesDataStore(name = "resume_position")

sealed interface SavedPosition {
    data class Offset(val charOffset: Int) : SavedPosition

    // Written by versions before the book model: a PDF page plus the index among that page's sentences.
    data class Legacy(val page: Int, val indexInPage: Int) : SavedPosition
}

class ResumePositionStore(private val context: Context) {

    suspend fun save(uriString: String, charOffset: Int) {
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

    suspend fun clear(uriString: String) {
        val hash = sha256Hex(uriString)
        context.resumePositionDataStore.edit { prefs ->
            prefs.remove(offsetKey(hash))
            prefs.remove(pageKey(hash))
            prefs.remove(indexKey(hash))
        }
    }

    private fun offsetKey(hash: String) = intPreferencesKey("${hash}_offset")
    private fun pageKey(hash: String) = intPreferencesKey("${hash}_page")
    private fun indexKey(hash: String) = intPreferencesKey("${hash}_idx")
}
