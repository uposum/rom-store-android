package io.github.uposum.romstore.core.store

import io.github.uposum.romstore.core.model.Dedupe
import io.github.uposum.romstore.core.model.ContentKind
import io.github.uposum.romstore.core.model.Content
import io.github.uposum.romstore.core.model.str
import io.github.uposum.romstore.core.model.extractRegion
import io.github.uposum.romstore.core.model.Relevance
import io.github.uposum.romstore.core.model.TitleClean
import io.github.uposum.romstore.core.model.FileNames
import io.github.uposum.romstore.core.model.flag
import io.github.uposum.romstore.core.model.GameMedia
import io.github.uposum.romstore.core.model.LibraryGame
import io.github.uposum.romstore.core.model.MergedRom
import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.RomFile
import io.github.uposum.romstore.core.model.Systems
import io.github.uposum.romstore.core.model.normalizeTitle
import io.github.uposum.romstore.core.model.putStr
import io.github.uposum.romstore.core.net.HttpClient
import io.github.uposum.romstore.core.net.HttpError
import io.github.uposum.romstore.core.net.FileHosts
import io.github.uposum.romstore.core.net.LinkCheck
import io.github.uposum.romstore.core.net.Html
import io.github.uposum.romstore.core.sources.Source
import io.github.uposum.romstore.core.sources.SourceDefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Artwork and metadata lookups the service uses (implemented in core.art). */
interface Artwork {
    /** Real box scans (libretro-thumbnails, GameTDB) before any other art. */
    suspend fun applyBoxart(entries: List<RomEntry>) {}
    /** Portrait box art for Switch games (GameTDB, SteamGridDB, IGDB). */
    suspend fun switchCovers(entries: List<RomEntry>) {}
    /** SteamGridDB covers and IGDB ratings/covers for search results. */
    suspend fun enrich(roms: List<MergedRom>) {}
    /** A console's catalogue: SteamGridDB for the games still without a cover. */
    suspend fun fillMissing(entries: List<RomEntry>) {}
    /** Covers and real names for installed games; `knownCovers` maps "system|matchTitle" to covers downloads had. */
    suspend fun library(games: List<LibraryGame>, knownCovers: Map<String, String>) {}
    /** The game page's gallery: the site's own media, IGDB, a YouTube trailer. */
    suspend fun media(entry: RomEntry, own: GameMedia?): GameMedia? = own
    /** Other addresses to try when a cover fails to load (GameTDB regions). */
    fun alternatives(url: String): List<String> = emptyList()
    /** Start background work (title lists). */
    fun prefetch() {}
    /** The game a Switch title ID belongs to, from the shipped title list. */
    fun titleName(titleId: String): String? = null
}

@Serializable
data class SourceHealth(val okAt: Long? = null, val error: String? = null, val errorAt: Long? = null, val failures: Int = 0)

data class SourceInfo(
    val id: String,
    val label: String,
    val address: String,
    val enabled: Boolean,
    val configured: Boolean,
    val supportsBrowse: Boolean,
    val error: String?,
    val health: SourceHealth,
    val restingUntil: Long?,
    val speed: SourceSpeed?,
)

@Serializable
data class SearchResult(
    val query: String = "",
    val system: String? = null,
    val results: List<MergedRom> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    val systems: List<String> = emptyList(),
    val cached: Boolean = false,
)

/** One file a game can be downloaded as, with what the store knows about it. */
data class DownloadOption(
    val entry: RomEntry,
    val file: RomFile,
    val eta: Double?,
    val sourceLabel: String,
    /** Base game, update, DLC or a bundle of them. */
    val content: io.github.uposum.romstore.core.model.ContentInfo = io.github.uposum.romstore.core.model.Content.classifyFile(file.filename, file.label, entry.title),
    /** Set when the file's Switch title ID belongs to another game: that game's name. */
    val otherGame: String? = null,
)

sealed class StartResult {
    data class Started(val job: DownloadJob) : StartResult()
    /** `pageUrl`: the site's page, when getting the file through the in-app browser may work. */
    data class Failed(val error: String, val pageUrl: String? = null) : StartResult()
}

/**
 * Everything the UI needs, in one place (backend/service.py): source fan-out,
 * merging, caching, details, downloads and the library.
 */
class StoreService(
    val settings: SettingsStore,
    private val dataDir: File,
    private val cacheDir: File,
    val http: HttpClient = HttpClient(),
    val library: () -> RomLibrary?,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    var artwork: Artwork = object : Artwork {}

    /** Told about every failure a source had (the app logs them). */
    var onError: ((String, Throwable) -> Unit)? = null

    /** Timings of a search's steps (the app logs them). */
    var onTiming: ((String) -> Unit)? = null

    val shelves = Shelves(File(dataDir, "shelves.json"))
    val speeds = SpeedStats(File(dataDir, "source_speeds.json"))
    val speedTest = SpeedTest(scope)
    val libraryIndex = LibraryIndex()
    val downloads = DownloadManager(
        http, library, File(dataDir, "downloads.json"), File(cacheDir, "unpack"), settings.value.maxConcurrent, scope,
    ).also { manager ->
        manager.onMeasure = { job, size, seconds -> speeds.record(job.source, size, seconds, job.prepareSeconds) }
        // Files unpacked from a bundle: updates and DLC into their folders.
        manager.router = { job, folder, name -> addonFolderFor(job.system, folder, Content.classify(name).kind) ?: folder }
        // File hosts whose link works once (1fichier): fetched as the download starts.
        manager.resolver = { job, note -> FileHosts.resolve(http, job.url, note) }
    }

    /** updates/ or dlc/ inside a console folder for an add-on (when that setting is on), else null. */
    private fun addonFolderFor(system: String, folder: FolderNode, kind: ContentKind): FolderNode? {
        if (!settings.value.addonFolders || system !in Content.ADDON_SYSTEMS) return null
        val name = when (kind) {
            ContentKind.UPDATE -> Content.UPDATES_FOLDER
            ContentKind.DLC -> Content.DLC_FOLDER
            else -> return null
        }
        return runCatching { folder.createFolder(name) }.getOrNull()
    }

    private val healthFile = File(dataDir, "source_health.json")
    private val healthSerializer = MapSerializer(String.serializer(), SourceHealth.serializer())
    private val health: MutableMap<String, SourceHealth> = ConcurrentHashMap(readJson(healthFile, healthSerializer) ?: emptyMap())
    private val errors = ConcurrentHashMap<String, String>()
    private val unreachable = ConcurrentHashMap<String, Long>()
    private val browseCache = ConcurrentHashMap<String, Pair<Long, List<RomEntry>>>()
    private var lastPageCount = 1

    companion object {
        const val MAX_CACHED = 300
        const val FAILURES_BEFORE_REST = 3
        const val REST_MS = 15 * 60 * 1000L
        const val SPEED_TEST_EVERY = 7 * 24 * 3600 * 1000L
        /** Bumped when cached results would show outdated covers. */
        const val CACHE_REVISION = 6

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    init {
        dropOldCaches()
    }

    // ------------------------------------------------------------------ cache
    // One small file per query and per detail view, pruned to the most recent
    // MAX_CACHED: never one growing document (that froze the plugin once).

    @Serializable
    private data class CachedSearch(val at: Long, val payload: SearchResult)

    @Serializable
    private data class CachedDetails(val at: Long, val entries: List<RomEntry>, val media: GameMedia? = null)

    private fun cacheFile(kind: String, key: String) = File(File(cacheDir, "store/$kind"), "$key.json")

    private fun cachePrune(kind: String) {
        val files = File(cacheDir, "store/$kind").listFiles() ?: return
        if (files.size <= MAX_CACHED) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_CACHED).forEach { it.delete() }
    }

    private fun dropOldCaches() {
        val marker = File(cacheDir, "store/revision")
        val current = runCatching { marker.readText().trim().toInt() }.getOrDefault(0)
        if (current != CACHE_REVISION) {
            File(cacheDir, "store/search").deleteRecursively()
            File(cacheDir, "store/details").deleteRecursively()
            marker.parentFile?.mkdirs()
            runCatching { marker.writeText(CACHE_REVISION.toString()) }
        }
    }

    fun clearCache(): Int {
        var removed = 0
        for (kind in listOf("search", "details")) {
            File(cacheDir, "store/$kind").listFiles()?.forEach { if (it.delete()) removed++ }
        }
        browseCache.clear()
        return removed
    }

    fun cacheSize(): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        for (kind in listOf("search", "details")) {
            File(cacheDir, "store/$kind").listFiles()?.forEach { files++; bytes += it.length() }
        }
        return files to bytes
    }

    private val ttlMs: Long get() = (settings.value.searchCacheHours * 3600_000).toLong()

    // ---------------------------------------------------------------- sources

    fun buildSources(): List<Source> {
        val s = settings.value
        return SourceDefs.IDS.filter { it in s.enabledSources }.mapNotNull { SourceDefs.build(it, http, s.address(it)) }
    }

    private fun record(id: String, error: String?) {
        val old = health[id] ?: SourceHealth()
        health[id] = if (error == null) old.copy(okAt = System.currentTimeMillis(), failures = 0)
        else old.copy(error = error.take(240), errorAt = System.currentTimeMillis(), failures = old.failures + 1)
    }

    private fun writeHealth() = writeJson(healthFile, healthSerializer, health.toMap())

    /** When a source that keeps failing gets tried again, or null. */
    fun restingUntil(id: String): Long? {
        val h = health[id] ?: return null
        if (h.failures < FAILURES_BEFORE_REST) return null
        val until = (h.errorAt ?: 0L) + REST_MS
        return until.takeIf { it > System.currentTimeMillis() }
    }

    fun describeSources(): List<SourceInfo> {
        val s = settings.value
        val stats = speeds.all()
        return SourceDefs.ALL.map { def ->
            val address = s.address(def.id)
            SourceInfo(
                id = def.id, label = def.label, address = address, enabled = def.id in s.enabledSources,
                configured = address.isNotEmpty(), supportsBrowse = def.supportsBrowse, error = errors[def.id],
                health = health[def.id] ?: SourceHealth(), restingUntil = restingUntil(def.id), speed = stats[def.id],
            )
        }
    }

    /** Forget a source's failures (after its address was changed). */
    fun resetHealth(id: String) {
        health.remove(id)
        unreachable.remove(id)
        errors.remove(id)
        writeHealth()
    }

    // ----------------------------------------------------------------- search

    suspend fun search(
        rawQuery: String,
        sourceIds: List<String>? = null,
        system: String? = null,
        sort: String = "relevance",
        limit: Int = 60,
        page: Int = 1,
    ): SearchResult = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        var sources = buildSources()
        if (!sourceIds.isNullOrEmpty()) sources = sources.filter { it.id in sourceIds }
        val perSource = settings.value.resultsPerSource
        val pageNo = maxOf(1, page)
        if (pageNo == 1) lastPageCount = 1

        val cacheKey = sha1(
            "${query.lowercase()}|${system ?: "all"}|${sources.map { "${it.id}@${it.base}" }.sorted().joinToString(",").ifEmpty { "all" }}|$sort|$pageNo|${settings.value.coverStyle}",
        )
        readJson(cacheFile("search", cacheKey), CachedSearch.serializer())?.let { cached ->
            if (System.currentTimeMillis() - cached.at < ttlMs) {
                val payload = cached.payload.copy(cached = true)
                markInstalled(payload.results)
                return@withContext payload
            }
        }

        val now = System.currentTimeMillis()
        val blocked = sources.count { (unreachable[it.id] ?: 0) > now }
        // If that would silence every source, the network is probably just not up yet.
        val ignoreBreaker = blocked == sources.size && sources.isNotEmpty()
        val roundErrors = ConcurrentHashMap<String, String>()

        val tStart = System.nanoTime()
        val results = coroutineScope {
            sources.map { source ->
                async {
                    if ((unreachable[source.id] ?: 0) > System.currentTimeMillis() && !ignoreBreaker) {
                        roundErrors[source.id] = "unreachable"
                        return@async emptyList<RomEntry>()
                    }
                    if (restingUntil(source.id) != null && !ignoreBreaker) {
                        roundErrors[source.id] = "skipped after repeated failures: ${health[source.id]?.error}"
                        return@async emptyList<RomEntry>()
                    }
                    try {
                        val found = source.search(query, perSource, pageNo)
                        record(source.id, null)
                        errors.remove(source.id)
                        if (source.lastPageCount > lastPageCount) lastPageCount = source.lastPageCount
                        found
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        onError?.invoke("search ${source.id}", e)
                        // "Load more" past a site's last page answers 404/500: no more results, not a failing source.
                        if (pageNo > 1) return@async emptyList<RomEntry>()
                        val message = e.message ?: e.javaClass.simpleName
                        roundErrors[source.id] = message
                        errors[source.id] = message
                        record(source.id, message)
                        if ("host not found" in message.lowercase()) unreachable[source.id] = System.currentTimeMillis() + 120_000
                        emptyList()
                    }
                }
            }.awaitAll()
        }
        writeHealth()
        val tSources = System.nanoTime()
        var entries = results.flatten()
        if (system != null && system != "all") {
            entries = entries.filter { it.system == system }
            // The sites search every console at once and return one page, so
            // the console's own catalogue (the browse list, cached) fills in.
            val known = entries.map { it.key }.toSet()
            entries = entries + catalogueMatches(system, query, known)
        }

        dropPlaceholderCovers(entries)
        nameByTitleId(entries)
        cleanTitles(entries)
        // Sites that search whole pages or match loosely: only what the title is about.
        if (query.isNotEmpty()) {
            val relevant = entries.filter { Relevance.matches(query, it.title) }
            if (relevant.isNotEmpty()) entries = relevant
        }
        val t0 = System.nanoTime()
        runCatching { artwork.applyBoxart(entries) }
        val t1 = System.nanoTime()
        runCatching { artwork.switchCovers(entries) }
        val t2 = System.nanoTime()
        var merged = Dedupe.merge(entries, settings.value.sourcePriority)
        if (sort != "relevance") merged = Dedupe.sort(merged, sort)
        merged = merged.take(limit)
        merged.forEachIndexed { index, rom -> rom.id = "rom-p$pageNo-$index-${rom.title}".replace(" ", "-").take(80) }
        runCatching { artwork.enrich(merged) }
        val t3 = System.nanoTime()
        onTiming?.invoke("search '$query': boxart ${(t1 - t0) / 1_000_000} ms, switch ${(t2 - t1) / 1_000_000} ms, enrich ${(t3 - t2) / 1_000_000} ms, sources ${(tSources - tStart) / 1_000_000} ms")
        markInstalled(merged)

        val payload = SearchResult(
            query = query, system = system, results = merged, page = pageNo,
            hasMore = pageNo < lastPageCount, errors = roundErrors.toMap(),
            systems = entries.map { it.system }.distinct().sorted(),
        )
        // Only a complete answer is kept: one missing a failed source would hide it for days.
        if (roundErrors.isEmpty()) {
            writeJson(cacheFile("search", cacheKey), CachedSearch.serializer(), CachedSearch(System.currentTimeMillis(), payload))
            cachePrune("search")
        }
        payload
    }

    /** A console's catalogue, merged (the console pages). */
    suspend fun browse(system: String, limit: Int = 200): SearchResult = withContext(Dispatchers.IO) {
        val tBrowse = System.nanoTime()
        val cached = browseCache["$system|${settings.value.coverStyle}"]
        val roundErrors = HashMap<String, String>()
        val entries: List<RomEntry> = if (cached != null && System.currentTimeMillis() - cached.first < ttlMs) {
            cached.second.also {
                runCatching { artwork.switchCovers(it) }
                runCatching { artwork.fillMissing(it) }
            }
        } else {
            val found = coroutineScope {
                val able = buildSources().filter { it.supportsBrowse && system in it.browseSystems() }
                // A source resting after failures is said so, not silently left out (an empty console page).
                for (source in able.filter { restingUntil(it.id) != null }) {
                    roundErrors[source.id] = "paused after ${health[source.id]?.failures ?: 3} failed tries (${health[source.id]?.error ?: "error"}); tried again in a few minutes"
                }
                able.filter { restingUntil(it.id) == null }.map { source ->
                    async {
                        try {
                            source.browse(system, limit).also { record(source.id, null) }
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            onError?.invoke("browse ${source.id}", e)
                            val message = e.message ?: e.javaClass.simpleName
                            synchronized(roundErrors) { roundErrors[source.id] = message }
                            errors[source.id] = message
                            record(source.id, message)
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
            writeHealth()
            val tSources = System.nanoTime()
            dropPlaceholderCovers(found)
            nameByTitleId(found)
            cleanTitles(found)
            runCatching { artwork.applyBoxart(found) }
            val t1 = System.nanoTime()
            runCatching { artwork.switchCovers(found) }
            val t2 = System.nanoTime()
            runCatching { artwork.fillMissing(found) }
            val t3 = System.nanoTime()
            onTiming?.invoke("browse $system: sources ${(tSources - tBrowse) / 1_000_000} ms, boxart ${(t1 - tSources) / 1_000_000} ms, switch ${(t2 - t1) / 1_000_000} ms, fill ${(t3 - t2) / 1_000_000} ms")
            if (found.isNotEmpty()) browseCache["$system|${settings.value.coverStyle}"] = System.currentTimeMillis() to found
            found
        }
        val merged = Dedupe.merge(entries, settings.value.sourcePriority).take(limit)
        merged.forEachIndexed { index, rom -> rom.id = "rom-browse-$system-$index".take(80) }
        markInstalled(merged)
        SearchResult(query = "", system = system, results = merged, errors = roundErrors, systems = listOf(system))
    }

    /** Entries of a console's catalogue whose title has every query word. */
    private suspend fun catalogueMatches(system: String, query: String, known: Set<String>): List<RomEntry> {
        val words = normalizeTitle(query).split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        if (browseCache["$system|${settings.value.coverStyle}"] == null) withTimeoutOrNull(12_000) { runCatching { browse(system) } }
        val cached = browseCache["$system|${settings.value.coverStyle}"] ?: return emptyList()
        fun matches(title: String): Boolean {
            val titleWords = normalizeTitle(title).split(" ")
            return words.all { word -> titleWords.any { it.startsWith(word) } }
        }
        return cached.second.filter { it.system == system && it.key !in known && matches(it.title) }
    }

    /** Every console an enabled source can browse, the most played first. */
    fun consoles(): List<String> {
        val keys = buildSources().filter { it.supportsBrowse }.flatMap { runCatching { it.browseSystems() }.getOrDefault(emptySet()) }
            .filter { it in Systems.ALL && it != "unknown" }.toSet()
        val rank = Systems.POPULAR.withIndex().associate { it.value to it.index }
        return keys.sortedWith(compareBy({ rank[it] ?: rank.size }, { Systems.name(it).lowercase() }))
    }

    private fun nameByTitleId(entries: List<RomEntry>) = nameByTitleId(entries) { artwork.titleName(it) }

    /** Game names without tags, serials and site names (the site's own stays in `extra.rawTitle`). */
    private fun cleanTitles(entries: List<RomEntry>) {
        for (e in entries) {
            if (e.extra.str("rawTitle") != null) continue
            val raw = e.title
            e.extra.putStr("rawTitle", raw)
            if (e.region == null) e.region = extractRegion(raw)
            e.title = TitleClean.display(raw)
        }
    }

    /**
     * Covers that arrived after a page was shown (SteamGridDB and IGDB keep
     * looking in the background): the same rules again, from what is cached
     * now. True when a cover changed.
     */
    suspend fun refreshCovers(roms: List<MergedRom>): Boolean = withContext(Dispatchers.IO) {
        val entries = roms.flatMap { it.entries }
        val before = entries.map { it.cover }
        runCatching { artwork.applyBoxart(entries) }
        runCatching { artwork.switchCovers(entries) }
        runCatching { artwork.fillMissing(entries) }
        entries.map { it.cover } != before
    }

    /** Clear a source's failures so it is asked again now (Settings, "Try again"). */
    fun wakeSource(id: String) {
        health[id]?.let { health[id] = it.copy(failures = 0) }
        unreachable.remove(id)
        writeHealth()
    }

    fun wakeAll() = health.keys.toList().forEach(::wakeSource)

    // ---------------------------------------------------------------- details

    /** Resolve a game's download links and gallery; cached for the TTL. */
    suspend fun details(rom: MergedRom): MergedRom = withContext(Dispatchers.IO) {
        val key = sha1("${rom.title.trim().lowercase()}|${rom.system}")
        val cacheFile = cacheFile("details", key)
        readJson(cacheFile, CachedDetails.serializer())?.let { cached ->
            if (System.currentTimeMillis() - cached.at < ttlMs && cached.entries.any { it.files.isNotEmpty() } &&
                cached.entries.none { e -> e.files.any { LinkCheck.expired(it.url) } }
            ) {
                val copy = rom.copy(entries = cached.entries, media = cached.media ?: rom.media)
                markInstalled(listOf(copy))
                return@withContext copy
            }
        }
        val entries = rom.entries.map { it.copy(extra = it.extra.toMutableMap()) }
        val pending = entries.filter { it.files.isEmpty() }
        val sources = buildSources().associateBy { it.id }
        coroutineScope {
            pending.map { entry ->
                async {
                    val source = sources[entry.source] ?: return@async
                    var lastError = ""
                    for (attempt in 0 until 3) {
                        try {
                            val files = source.resolve(entry)
                            if (files.isNotEmpty()) {
                                entry.files = files
                                if (entry.size == null) entry.size = files.firstNotNullOfOrNull { it.size }
                                entry.extra.remove("error")
                                return@async
                            }
                            lastError = "no download link found"
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            lastError = e.message ?: e.javaClass.simpleName
                            if (e is HttpError && !e.retry) break // a dead link stays dead
                        }
                        if (attempt < 2) delay(700L * (attempt + 1))
                    }
                    entry.extra.putStr("error", lastError.ifEmpty { "could not resolve the download link" })
                }
            }.awaitAll()
        }
        val primary = entries.firstOrNull()
        val media = primary?.let { entry ->
            val own = sources[entry.source]?.let { runCatching { it.media(entry) }.getOrNull() }
            runCatching { artwork.media(entry, own) }.getOrNull() ?: own
        } ?: rom.media
        val result = rom.copy(entries = entries, media = media)
        if (entries.any { it.files.isNotEmpty() }) {
            writeJson(cacheFile, CachedDetails.serializer(), CachedDetails(System.currentTimeMillis(), entries, media))
            cachePrune("details")
        }
        markInstalled(listOf(result))
        result
    }

    /**
     * The files a game can be downloaded as: preferred region first (when one
     * is set), then by estimated time (start delay + size / measured speed;
     * unmeasured sources count as the median); within one source its order stays.
     */
    fun downloadOptions(rom: MergedRom): List<DownloadOption> {
        val stats = speeds.all()
        val speedsKnown = stats.values.mapNotNull { it.speed }.sorted()
        val median = speedsKnown.getOrNull(speedsKnown.size / 2)
        val region = settings.value.preferredRegion
        val sizes = rom.entries.flatMap { e -> e.files.mapNotNull { it.size } + listOfNotNull(e.size) }.sorted()
        val medianSize = sizes.getOrNull(sizes.size / 2)
        val options = mutableListOf<Triple<DownloadOption, Int, Int>>()
        rom.entries.forEachIndexed { ei, entry ->
            entry.files.forEachIndexed { fi, file ->
                // Sites list a console's BIOS next to its games (RomsFun's "BIOS: neogeo");
                // the store never offers BIOS files.
                if (isBiosFile(file)) return@forEachIndexed
                val stat = stats[entry.source] ?: median?.let { SourceSpeed(speed = it) }
                val eta = SpeedStats.estimate(stat, file.size ?: entry.size ?: medianSize)
                options += Triple(DownloadOption(entry, file, eta, SourceDefs.label(entry.source)), ei, fi)
            }
        }
        val testFailed = stats.filterValues { it.testError != null }.keys
        return options.map { (option, ei, fi) -> Triple(withOtherGame(rom, option), ei, fi) }.sortedWith(
            compareBy<Triple<DownloadOption, Int, Int>>(
                // The game first; updates and DLC after it.
                { if (it.first.content.isAddon) 1 else 0 },
                // Files the app can fetch before those that need a browser.
                { if (FileHosts.needsBrowser(it.first.file.url)) 1 else 0 },
                { if (region.isNotEmpty()) (if (Regions.matches(it.first.file.region ?: it.first.entry.region ?: it.first.entry.title, region)) 0 else 1) else 0 },
                { if (it.first.entry.source in testFailed) 1 else 0 },
                { it.first.eta ?: Double.MAX_VALUE },
                { it.second },
                { it.third },
            ),
        ).map { it.first }
    }

    private fun isBiosFile(file: RomFile): Boolean {
        val label = file.label ?: ""
        return label.startsWith("BIOS", ignoreCase = true) || label.contains("· BIOS") ||
            Dedupe.isFirmware(file.filename) || Dedupe.isFirmware(label)
    }

    /** A file whose Switch title ID names a different game gets that game's name (sites mislabel posts). */
    private fun withOtherGame(rom: MergedRom, option: DownloadOption): DownloadOption {
        val tid = option.content.titleId ?: return option
        val base = Content.baseTitleId(tid) ?: return option
        val name = runCatching { artwork.titleName(base) }.getOrNull() ?: return option
        val ours = normalizeTitle(Content.baseTitle(rom.title)).split(" ").filter { it.length > 2 }.toSet()
        val theirs = normalizeTitle(name).split(" ").filter { it.length > 2 }.toSet()
        return if (ours.isNotEmpty() && theirs.isNotEmpty() && ours.intersect(theirs).isEmpty()) option.copy(otherGame = name) else option
    }

    // ------------------------------------------------------------ add-ons

    private val addonCache = ConcurrentHashMap<String, Pair<Long, List<DownloadOption>>>()

    /**
     * Updates and DLC for a game that the sources publish as posts of their
     * own: each source is asked for "<game> update" and "<game> dlc", and
     * posts about the same game that are add-ons (or bundles) are resolved.
     * Only for consoles whose games get add-ons; kept for the session.
     */
    suspend fun findAddons(rom: MergedRom): List<DownloadOption> = withContext(Dispatchers.IO) {
        if (rom.system !in Content.ADDON_SYSTEMS) return@withContext emptyList()
        val base = Content.baseTitle(rom.title)
        val key = "${rom.system}|${Content.baseKey(rom.title)}"
        addonCache[key]?.let { if (System.currentTimeMillis() - it.first < ttlMs) return@withContext it.second }
        val known = rom.entries.map { it.key }.toSet()
        val sources = buildSources().filter { restingUntil(it.id) == null }
        val found = withTimeoutOrNull(25_000) {
            coroutineScope {
                sources.flatMap { source ->
                    listOf("$base update", "$base dlc").map { query ->
                        async { runCatching { source.search(query, 20) }.getOrDefault(emptyList()).map { source to it } }
                    }
                }.awaitAll().flatten()
            }
        } ?: emptyList()
        val wanted = found
            .filter { (_, e) -> e.system == rom.system && e.key !in known && Content.baseKey(e.title) == Content.baseKey(rom.title) }
            .distinctBy { it.second.key }
            .take(8)
        val options = withTimeoutOrNull(25_000) {
            coroutineScope {
                wanted.map { (source, entry) ->
                    async {
                        val files = runCatching { entry.files.ifEmpty { source.resolve(entry) } }.getOrDefault(emptyList())
                        entry.files = files
                        files.map { DownloadOption(entry, it, null, SourceDefs.label(entry.source)) }
                    }
                }.awaitAll().flatten()
            }
        } ?: emptyList()
        val addons = options.filter { (it.content.isAddon || it.content.kind == ContentKind.BUNDLE) && !isBiosFile(it.file) }
            .map { withOtherGame(rom, it) }
            .sortedWith(compareBy({ it.content.kind.ordinal }, { it.file.filename }))
        addonCache[key] = System.currentTimeMillis() to addons
        addons
    }

    // --------------------------------------------------------------- download

    /** Start downloading one file of a game into its console's folder. */
    suspend fun startDownload(rom: MergedRom, entry: RomEntry, file: RomFile, system: String? = null): StartResult = withContext(Dispatchers.IO) {
        val lib = library() ?: return@withContext StartResult.Failed("Choose a ROM folder in Settings first.")
        val target = system ?: entry.system
        val consoleFolder = runCatching { lib.consoleFolder(target, create = true) }.getOrNull()
            ?: return@withContext StartResult.Failed("Cannot open the ${Systems.label(target)} folder. Check the ROM folder in Settings.")
        val content = Content.classifyFile(file.filename, file.label, entry.title)
        // A file host that only hands files to a browser: the site's page is the way.
        if (FileHosts.needsBrowser(file.url)) {
            return@withContext StartResult.Failed(FileHosts.browserMessage(file.url) + ".", file.url)
        }
        // Updates and DLC go into updates/ and dlc/, so frontends do not list them as games.
        val folder = addonFolderFor(target, consoleFolder, content.kind) ?: consoleFolder
        val source = buildSources().firstOrNull { it.id == entry.source }
        val preparing = System.nanoTime()
        var file = file
        if (LinkCheck.expired(file.url)) {
            // A link from an earlier look at the game: ask the site for the current one.
            val fresh = source?.let { runCatching { it.resolve(entry) }.getOrNull() }
                ?.let { files -> files.firstOrNull { it.id == file.id } ?: files.firstOrNull { it.filename == file.filename } }
            if (fresh == null || LinkCheck.expired(fresh.url)) {
                return@withContext StartResult.Failed(LinkCheck.expiredMessage(file.url) + ".")
            }
            file = fresh
        }
        var url = file.url
        if (source != null) {
            for (attempt in 0 until 3) {
                try {
                    url = source.resolveDownload(file)
                    break
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (e is HttpError && e.message?.contains("Cloudflare") == true) {
                        return@withContext StartResult.Failed(e.message!!, file.url.takeIf { it.startsWith("http") } ?: file.sourceUrl ?: entry.url)
                    }
                    url = file.url
                    if (attempt < 2) delay(700L * (attempt + 1))
                }
            }
        }
        val original = file.filename.ifEmpty { Html.filenameFromUrl(url).ifEmpty { "${entry.title}.zip" } }
        // Saved under the game's clean name, not "Mario_Kart_8_Deluxe_[…]_[taodung.com].rar".
        val cleanBase = if (settings.value.cleanFileNames) FileNames.base(rom.title.ifEmpty { entry.title }, content, file.region ?: entry.region) else null
        val filename = cleanBase?.let { FileNames.rename(original, it, target) } ?: original
        // The page to open in the browser if the link turns out to need one: the
        // site's download page when the source resolved one, else the game's page.
        val page = file.url.takeIf { it != url && it.startsWith("http") } ?: file.sourceUrl ?: entry.url
        return@withContext enqueueInto(
            folder, consoleFolder, target, entry.title, entry.source, filename, url, emptyMap(),
            file.size ?: entry.size ?: 0L, rom.cover ?: entry.cover, page, (System.nanoTime() - preparing) / 1e9,
            resolveLate = FileHosts.resolvesLate(url), cleanBase = cleanBase,
        )
    }

    /**
     * A download the in-app browser started (the user went through the site's
     * page): saved like any other, into the console's folder, with the
     * browser's cookies and user agent so the file server sees the same visitor.
     */
    suspend fun startBrowserDownload(
        title: String, system: String, source: String, cover: String?, pageUrl: String?,
        url: String, filename: String, size: Long, headers: Map<String, String>,
    ): StartResult = withContext(Dispatchers.IO) {
        val lib = library() ?: return@withContext StartResult.Failed("Choose a ROM folder in Settings first.")
        val consoleFolder = runCatching { lib.consoleFolder(system, create = true) }.getOrNull()
            ?: return@withContext StartResult.Failed("Cannot open the ${Systems.label(system)} folder. Check the ROM folder in Settings.")
        val content = Content.classify(filename, title)
        val folder = addonFolderFor(system, consoleFolder, content.kind) ?: consoleFolder
        enqueueInto(folder, consoleFolder, system, title, source, filename, url, headers, size, cover, pageUrl, 0.0)
    }

    private fun enqueueInto(
        folder: FolderNode, consoleFolder: FolderNode, target: String, title: String, sourceId: String,
        filename: String, url: String, headers: Map<String, String>, size: Long, cover: String?, page: String?, prepare: Double,
        resolveLate: Boolean = false, cleanBase: String? = null,
    ): StartResult {
        val s = settings.value
        val needed = s.autoUnpackDiscs && Unpack.needsUnpack(target, filename)
        val extract = s.extractArchives || needed
        if (size > 0) {
            val need = size * (if (extract) 2 else 1)
            val free = folder.freeSpace()
            if (free != null && free > 0 && need > free) {
                return StartResult.Failed("Not enough free space: needs ${io.github.uposum.romstore.core.net.humanSize(need)}, ${io.github.uposum.romstore.core.net.humanSize(free)} free.")
            }
        }
        val keep = if (s.extractArchives) s.keepArchive else false
        val job = downloads.enqueue(
            title = title, system = target, source = sourceId, filename = filename, url = url,
            destination = folder.id, headers = headers, extract = extract, keepArchive = keep,
            prepareSeconds = prepare, cover = cover,
            folderLabel = Systems.label(target) + if (folder !== consoleFolder) " · ${folder.name}" else "",
            pageUrl = page, resolveLate = resolveLate, cleanBase = cleanBase,
        )
        return StartResult.Started(job)
    }

    // ---------------------------------------------------------------- library

    /** Flag results that already have a file in their console's folder. */
    fun markInstalled(roms: List<MergedRom>) {
        val lib = library() ?: run { roms.forEach { it.installed = false }; return }
        val folders = HashMap<String, FolderNode?>()
        for (rom in roms) {
            val folder = folders.getOrPut(rom.system) { runCatching { lib.consoleFolder(rom.system, create = false) }.getOrNull() }
            val titles = listOf(rom.title) + rom.entries.map { it.title }
            rom.installed = titles.any { it.isNotEmpty() && libraryIndex.contains(folder, it) }
        }
    }

    /** Every game in the ROM library, with covers and real names. */
    suspend fun installed(): List<LibraryGame> = withContext(Dispatchers.IO) {
        val lib = library() ?: return@withContext emptyList()
        val games = LibraryScan.installedGames(lib)
        val known = HashMap<String, String>()
        for (job in downloads.state.value) {
            val cover = job.cover ?: continue
            val bare = job.title.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").trim()
            known.putIfAbsent("${job.system}|${io.github.uposum.romstore.core.model.matchTitle(bare)}", cover)
        }
        runCatching { artwork.library(games, known) }
        for (game in games) {
            if (game.name.isNullOrEmpty()) game.name = cleanName(game.title)
            val files = game.files.filterNot { it.folder }
            game.needsUnpack = files.isNotEmpty() && files.all { Unpack.needsUnpack(game.system, it.name) }
        }
        games
    }

    fun uninstall(gameId: String): LibraryScan.UninstallResult {
        val lib = library() ?: return LibraryScan.UninstallResult(false, error = "No ROM folder chosen.")
        return LibraryScan.uninstall(lib, gameId).also { libraryIndex.clear() }
    }

    data class UnpackResult(val ok: Boolean, val files: List<String> = emptyList(), val error: String? = null)

    /** Unpack an installed game's archives for its emulator, then delete them. */
    suspend fun unpackGame(gameId: String): UnpackResult = withContext(Dispatchers.IO) {
        val lib = library() ?: return@withContext UnpackResult(false, error = "No ROM folder chosen.")
        val game = LibraryScan.installedGames(lib).firstOrNull { it.id == gameId }
            ?: return@withContext UnpackResult(false, error = "That game is no longer in the library.")
        val folder = lib.folderById(game.folder) ?: return@withContext UnpackResult(false, error = "The console folder is gone.")
        val produced = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val children = folder.list().associateBy { it.name }
        for (file in game.files) {
            if (file.folder || Unpack.archiveExt(file.name) == null) continue
            val node = children[file.name] ?: continue
            try {
                produced += Unpack.unpack(node, folder, File(cacheDir, "unpack")).map { it.name }
                node.delete()
            } catch (e: Unpack.UnpackError) {
                problems += "${file.name}: ${e.message}"
            }
        }
        libraryIndex.clear()
        UnpackResult(produced.isNotEmpty() && problems.isEmpty(), produced, problems.joinToString("; ").ifEmpty { null })
    }

    // ------------------------------------------------------------- speed test

    fun startSpeedTest(): Boolean {
        val sources = buildSources().filter { restingUntil(it.id) == null }
        return speedTest.start(sources, http) { id, result ->
            speeds.recordTest(id, result.bytes.toDouble(), result.seconds, result.delay, if (result.ok) null else (result.error ?: "failed"))
        }
    }

    fun speedTestDue(): Boolean {
        if (!settings.value.speedTestAuto || speedTest.running) return false
        val ids = buildSources().map { it.id }
        if (ids.isEmpty() || System.currentTimeMillis() - speeds.lastTested(ids) < SPEED_TEST_EVERY) return false
        return !downloads.hasActive
    }

    /** Background work: title lists, the weekly speed test, and resuming downloads. */
    fun start() {
        runCatching { artwork.prefetch() }
        downloads.resumePending()
        scope.launch {
            while (true) {
                delay(120_000)
                runCatching { if (speedTestDue()) startSpeedTest() }
                // A source that was unreachable gets another chance.
                val now = System.currentTimeMillis()
                unreachable.entries.removeIf { it.value <= now }
            }
        }
    }
}

private val PLACEHOLDER_COVER = Regex("""(image|cover)[-_]?(un)?available|no[-_]?image|placeholder|default[-_]cover""", RegexOption.IGNORE_CASE)

/** Some sites hand out a "cover unavailable" picture as the cover: that is no
 * cover, so the other art sources (and the title tile) get their turn. */
/**
 * Switch uploads named by noise ("Mario. Kart. 8. Deluxe. DLC. …", "Name V 3.0.4[0100…][UPD]",
 * "0100152000022800.nsp") get the official name of their title ID, with "Update"/"DLC",
 * so they group with their game. The ID comes from the title, or from the file for
 * sources whose entry is the file itself (`extra.fileNamed`); a site's post keeps its
 * own name (its file can belong to another game, see `otherGame`).
 */
internal fun nameByTitleId(entries: List<RomEntry>, titleName: (String) -> String?) {
    for (e in entries) {
        if (e.system != "switch") continue
        val tid = Content.titleId(e.title)
            ?: (if (e.extra.flag("fileNamed")) e.files.firstNotNullOfOrNull { Content.titleId(it.filename) } else null)
            ?: continue
        val base = Content.baseTitleId(tid) ?: continue
        val name = runCatching { titleName(base) }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
        val info = Content.classifyFile(e.files.firstOrNull()?.filename ?: e.title, e.title)
        e.title = when (info.kind) {
            ContentKind.UPDATE -> "$name Update" + (info.version?.takeIf { !it.startsWith("#") }?.let { " $it" } ?: "")
            ContentKind.DLC -> "$name DLC"
            else -> name
        }
    }
}

fun dropPlaceholderCovers(entries: List<RomEntry>) {
    for (entry in entries) {
        if (entry.cover?.let { PLACEHOLDER_COVER.containsMatchIn(it) } == true) entry.cover = null
        if (entry.coverFull?.let { PLACEHOLDER_COVER.containsMatchIn(it) } == true) entry.coverFull = null
    }
}

/** A file name turned into a readable title: tags, "NSP", "+" and "%20" gone,
 * "Title, The" turned round (backend/titles.py clean_name). */
fun cleanName(title: String): String {
    var t = title.replace("+", " ").replace("%20", " ").replace("_", " ")
    t = t.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), " ")
    t = t.replace(Regex("\\bNSP\\b|\\bXCI\\b", RegexOption.IGNORE_CASE), " ")
    t = Html.collapseWs(t)
    Regex("^(.*), (The|A|An)$", RegexOption.IGNORE_CASE).find(t)?.let { t = "${it.groupValues[2]} ${it.groupValues[1]}" }
    return t.ifEmpty { title }
}
