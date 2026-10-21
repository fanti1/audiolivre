package io.github.fanti1.audiolivre.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.libraryDataStore by preferencesDataStore(name = "library")

// What the library knows about a book without opening it. The title and size are kept here because the document
// provider can stop answering (file moved, permission lost) and a book must never vanish silently from the shelf.
data class LibraryBook(
    val uri: String,
    val title: String,
    val addedAt: Long,
    val lastOpenedAt: Long,
    val sizeBytes: Long = 0,
    val totalChars: Int = 0,
    val charOffset: Int = 0,
) {
    val progress: Float get() = if (totalChars > 0) (charOffset.toFloat() / totalChars).coerceIn(0f, 1f) else 0f
    val started: Boolean get() = charOffset > 0
}

class LibraryStore(private val context: Context) {

    val books: Flow<List<LibraryBook>> = context.libraryDataStore.data.map { prefs ->
        LibraryJson.decode(prefs[BOOKS_KEY]).sortedByDescending { it.lastOpenedAt }
    }

    suspend fun addOrPromote(uri: String, title: String, sizeBytes: Long) = update { books ->
        val now = System.currentTimeMillis()
        val existing = books.firstOrNull { it.uri == uri }
        val book = existing?.copy(title = title, lastOpenedAt = now, sizeBytes = sizeBytes)
            ?: LibraryBook(uri = uri, title = title, addedAt = now, lastOpenedAt = now, sizeBytes = sizeBytes)
        listOf(book) + books.filterNot { it.uri == uri }
    }

    suspend fun markOpened(uri: String) = update { books ->
        books.map { if (it.uri == uri) it.copy(lastOpenedAt = System.currentTimeMillis()) else it }
    }

    suspend fun updateReadingState(uri: String, totalChars: Int, charOffset: Int) = update { books ->
        books.map { if (it.uri == uri) it.copy(totalChars = totalChars, charOffset = charOffset) else it }
    }

    // The reader finds the real title inside the PDF; the shelf shows it instead of the file name.
    suspend fun renameIfDifferent(uri: String, title: String) = update { books ->
        books.map { if (it.uri == uri && it.title != title && title.isNotBlank()) it.copy(title = title) else it }
    }

    suspend fun resetProgress(uri: String) = update { books ->
        books.map { if (it.uri == uri) it.copy(charOffset = 0) else it }
    }

    suspend fun remove(uri: String) = update { books -> books.filterNot { it.uri == uri } }

    // Books added by earlier versions, which stored only the URIs.
    suspend fun migrate(uris: List<String>, titleOf: (String) -> String?) = update { books ->
        if (books.isNotEmpty()) return@update books
        val now = System.currentTimeMillis()
        uris.mapIndexed { i, uri ->
            LibraryBook(uri = uri, title = titleOf(uri) ?: "Livro", addedAt = now - i, lastOpenedAt = now - i)
        }
    }

    private suspend fun update(transform: (List<LibraryBook>) -> List<LibraryBook>) {
        context.libraryDataStore.edit { prefs ->
            prefs[BOOKS_KEY] = LibraryJson.encode(transform(LibraryJson.decode(prefs[BOOKS_KEY])))
        }
    }

    private companion object {
        val BOOKS_KEY = stringPreferencesKey("books")
    }
}
