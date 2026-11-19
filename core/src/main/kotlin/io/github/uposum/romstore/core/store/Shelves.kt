package io.github.uposum.romstore.core.store

import io.github.uposum.romstore.core.model.matchTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ShelfItem(val key: String, val title: String, val system: String, val cover: String? = null, val at: Long = 0)

@Serializable
data class ShelfData(val favorites: List<ShelfItem> = emptyList(), val recent: List<ShelfItem> = emptyList())

/** Favourites and recently viewed games (backend/shelves.py). */
class Shelves(private val file: File?) {
    private val _state = MutableStateFlow(readJson(file, ShelfData.serializer()) ?: ShelfData())
    val state: StateFlow<ShelfData> = _state.asStateFlow()

    companion object {
        const val MAX_RECENT = 24
        const val MAX_FAVORITES = 200
        fun key(title: String, system: String) = "$system|${matchTitle(title)}"
    }

    private fun item(title: String, system: String, cover: String?): ShelfItem? {
        if (title.isBlank() || system.isBlank()) return null
        return ShelfItem(key(title, system), title.trim(), system.trim(), cover, System.currentTimeMillis())
    }

    fun isFavorite(title: String, system: String): Boolean = _state.value.favorites.any { it.key == key(title, system) }

    /** Returns whether the game is a favourite now. */
    @Synchronized
    fun toggleFavorite(title: String, system: String, cover: String?): Boolean {
        val item = item(title, system, cover) ?: return false
        val data = _state.value
        val exists = data.favorites.any { it.key == item.key }
        val favorites = if (exists) data.favorites.filterNot { it.key == item.key } else (listOf(item) + data.favorites).take(MAX_FAVORITES)
        set(data.copy(favorites = favorites))
        return !exists
    }

    @Synchronized
    fun addRecent(title: String, system: String, cover: String?) {
        val item = item(title, system, cover) ?: return
        val data = _state.value
        set(data.copy(recent = (listOf(item) + data.recent.filterNot { it.key == item.key }).take(MAX_RECENT)))
    }

    @Synchronized
    fun clearRecent() = set(_state.value.copy(recent = emptyList()))

    private fun set(data: ShelfData) {
        _state.value = data
        writeJson(file, ShelfData.serializer(), data)
    }
}
