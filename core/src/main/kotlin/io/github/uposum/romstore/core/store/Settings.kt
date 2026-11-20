package io.github.uposum.romstore.core.store

import io.github.uposum.romstore.core.sources.SourceDefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Everything the user can change (backend/settings.py, without the Steam
 * parts). The app ships no source addresses: the user enters each one.
 */
@Serializable
data class StoreSettings(
    /** Source id -> the address the user entered ("" = not set up). */
    val sourceAddresses: Map<String, String> = emptyMap(),
    val enabledSources: List<String> = SourceDefs.IDS,
    val sourcePriority: List<String> = SourceDefs.IDS,
    /** The ROM library: a folder tree the user picked (content:// URI). */
    val libraryUri: String? = null,
    /** Console key -> a folder the user picked for that console instead. */
    val consoleFolders: Map<String, String> = emptyMap(),
    /** Console key -> folder name inside the library, when it differs from the default. */
    val folderNames: Map<String, String> = emptyMap(),
    val extractArchives: Boolean = false,
    val keepArchive: Boolean = true,
    /** Unpack disc images that their emulators cannot read from an archive. */
    val autoUnpackDiscs: Boolean = true,
    val maxConcurrent: Int = 2,
    /** Updates and DLC go into updates/ and dlc/ inside the console folder, not next to the games. */
    val addonFolders: Boolean = true,
    val resultsPerSource: Int = 30,
    val searchCacheHours: Double = 72.0,
    /** IGDB crops every cover to 3:4, so its art only fills in games without one unless this is on. */
    val igdbReplaceCovers: Boolean = false,
    val igdbEnabled: Boolean = true,
    val preferredRegion: String = "",
    val speedTestAuto: Boolean = true,
    val libretroBoxart: Boolean = true,
    val boxartResize: Boolean = true,
    val steamgriddbApiKey: String = "",
    val showTitles: Boolean = false,
) {
    fun address(id: String): String = SourceDefs.normalizeAddress(sourceAddresses[id])

    /** Sources that are switched on and have an address. */
    val configuredSources: List<String>
        get() = SourceDefs.IDS.filter { it in enabledSources && address(it).isNotEmpty() }
}

class SettingsStore(private val file: File?) {
    private val _state = MutableStateFlow(readJson(file, StoreSettings.serializer()) ?: StoreSettings().also {
        // A new install writes its file at once.
        writeJson(file, StoreSettings.serializer(), it)
    })
    val state: StateFlow<StoreSettings> = _state.asStateFlow()
    val value: StoreSettings get() = _state.value

    @Synchronized
    fun update(change: (StoreSettings) -> StoreSettings) {
        val next = change(_state.value)
        if (next == _state.value) return
        _state.value = next
        writeJson(file, StoreSettings.serializer(), next)
    }
}
