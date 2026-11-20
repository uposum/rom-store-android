package io.github.uposum.romstore.core.sources

import io.github.uposum.romstore.core.model.GameMedia
import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.RomFile
import io.github.uposum.romstore.core.net.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

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

    /**
     * A catalogue spread over numbered pages: fetched a few at a time, in
     * order, until `limit` games, an empty page, a page that adds nothing or
     * one that lands on another site (a mirror without paging redirects).
     */
    protected suspend fun pagedCatalogue(
        pageUrl: (Int) -> String, limit: Int, maxPages: Int, parallel: Int = 3,
        parse: (String) -> List<RomEntry>,
    ): List<RomEntry> {
        val entries = mutableListOf<RomEntry>()
        val seen = HashSet<String>()
        val host = base.substringAfter("://").substringBefore('/')
        var page = 1
        while (page <= maxPages && entries.size < limit) {
            val batch = (page until minOf(page + parallel, maxPages + 1)).toList()
            val pages = kotlinx.coroutines.coroutineScope {
                batch.map { n ->
                    async {
                        runCatching {
                            val r = http.request(pageUrl(n))
                            if (n > 1 && r.url.substringAfter("://").substringBefore('/') != host) emptyList() else parse(r.text)
                        }.getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e; if (n == 1) throw e; emptyList() }
                    }
                }.awaitAll()
            }
            var stop = false
            for (found in pages) {
                var added = 0
                for (e in found) if (seen.add(e.url.ifEmpty { e.sourceId })) { e.rank = entries.size; entries += e; added++ }
                if (added == 0) { stop = true; break }
            }
            if (stop) break
            page += parallel
        }
        return entries.take(limit)
    }
}
