package io.github.fanti1.audiolivre.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.recentFilesDataStore by preferencesDataStore(name = "recent_files")

class RecentFilesStore(private val context: Context) {

    val recentUris: Flow<List<String>> = context.recentFilesDataStore.data.map { prefs ->
        prefs[URIS_KEY]?.split(SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun addOrPromote(uri: String) {
        context.recentFilesDataStore.edit { prefs ->
            val current = prefs[URIS_KEY]?.split(SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()
            val updated = listOf(uri) + current.filterNot { it == uri }
            prefs[URIS_KEY] = updated.joinToString(SEPARATOR)
        }
    }

    // Cleared once LibraryStore has taken over, so an empty shelf stays empty.
    suspend fun clear() {
        context.recentFilesDataStore.edit { it.remove(URIS_KEY) }
    }

    private companion object {
        val URIS_KEY = stringPreferencesKey("recent_pdf_uris")
        const val SEPARATOR = "\n"
    }
}
