package io.github.uposum.romstore.core.sources

import io.github.uposum.romstore.core.model.GameMedia
import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.RomFile
import io.github.uposum.romstore.core.net.HttpClient

class SourceError(message: String) : Exception(message)

/** What a source is built with: its id, its label and the address the user entered. */
data class SourceOptions(
    val sourceId: String,
    val label: String,
    /** The site's address, normalised: scheme included, no trailing slash. */
    val baseUrl: String,
)

/** A site the app can search and download from (backend/sources/base.py). */
abstract class Source(val http: HttpClient, val options: SourceOptions) {
    open val id: String get() = options.sourceId
    open val label: String get() = options.label
    val base: String get() = options.baseUrl.trimEnd('/')
    open val homepage: String get() = base
    open val supportsBrowse: Boolean = false
    open val supportsRegions: Boolean = false

    /** Pages the last search reported (for "load more"). */
    open var lastPageCount: Int = 1

    abstract suspend fun search(query: String, limit: Int = 40, page: Int = 1): List<RomEntry>

    open suspend fun browse(system: String, limit: Int = 40): List<RomEntry> = emptyList()

    /** Console keys this site has a catalogue for (empty: no browsing). */
    open fun browseSystems(): Set<String> = emptySet()

    /** The downloadable files for an entry (may be empty). May fill in the
     * entry's details (size, year, summary, ...) in place. */
    open suspend fun resolve(entry: RomEntry): List<RomFile> = entry.files

    /** Screenshots/artwork this site has for a game (optional). */
    open suspend fun media(entry: RomEntry): GameMedia? = null

    /** The URL that actually serves the bytes (default: as is). */
    open suspend fun resolveDownload(file: RomFile): String = file.url
}
