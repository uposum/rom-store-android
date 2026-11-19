package io.github.uposum.romstore.core.art

import io.github.uposum.romstore.core.model.LibraryGame
import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.extractRegion
import io.github.uposum.romstore.core.model.matchTitle
import io.github.uposum.romstore.core.model.putStr
import io.github.uposum.romstore.core.net.Html
import io.github.uposum.romstore.core.net.HttpClient
import io.github.uposum.romstore.core.net.HttpError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Box art from GameTDB (the cover database homebrew loaders use), a port of
 * the plugin's gametdb.py.
 *
 *     https://www.gametdb.com/switchtdb.txt?LANG=EN     "AABPA = Mario Kart 8 Deluxe"
 *     https://art.gametdb.com/switch/cover/US/AABPA.jpg     160 x 260 (grid)
 *     https://art.gametdb.com/switch/coverM/US/AABPA.jpg    352 x 570 (poster)
 *
 * The title list is fetched once and kept for two weeks; games are matched by
 * matchTitle(). A game has covers for some regions only, so the first region
 * that has one is looked up in the background and remembered per game, found
 * or not. A search probes a page's worth of new games at most.
 */
class GameTdb(
    private val http: HttpClient,
    cacheDir: File? = null,
    private val scope: CoroutineScope = defaultArtScope(),
) {
    private data class Found(val region: String, val at: Double)

    private val dir = cacheDir?.let { File(it, "gametdb") }
    private val index = ConcurrentHashMap<String, Map<String, List<String>>>()
    private val sortedKeys = ConcurrentHashMap<String, List<String>>()
    private val titles = ConcurrentHashMap<String, Map<String, String>>()
    private val loadedAt = ConcurrentHashMap<String, Double>()
    private val building = HashMap<String, Job>()

    // "switch/AABPA" -> region folder or "" (none), and when it was looked up
    private val found = ConcurrentHashMap<String, Found>()
    @Volatile private var foundLoaded = false
    private val probing: MutableSet<Job> = ConcurrentHashMap.newKeySet()
    private val timeouts = AtomicInteger(0)
    @Volatile private var pausedUntil = 0.0

    // When a title list could not be fetched: nothing waits on it again (and it
    // is not asked for again) for LIST_RETRY seconds.
    private val listFailed = ConcurrentHashMap<String, Double>()

    // When each running title-list fetch started: the wait for one fetch is
    // shared by every search, not served again to each of them.
    private val listStarted = ConcurrentHashMap<String, Double>()

    /** Through the resizing proxy (the "boxart_resize" setting). */
    @Volatile var proxy: Boolean = true

    /** Switch IDs whose GameTDB cover is a real box (retail games); loaded on first use. */
    @Volatile internal var retail: Set<String>? = null

    internal var listWait: Double = LIST_WAIT
    internal var clock: () -> Double = ::nowSeconds

    // ------------------------------------------------------------------ files
    private fun path(name: String): File? = dir?.let { File(it, name) }

    // ------------------------------------------------------------------ index
    private fun load(platform: String): Boolean {
        if (index.containsKey(platform)) return true
        val data = JsonFiles.read(path("$platform.json")).obj() ?: return false
        val list = data["titles"].obj()?.takeIf { it.isNotEmpty() } ?: return false
        val map = LinkedHashMap<String, String>()
        for ((id, title) in list) (title as? JsonPrimitive)?.contentOrNull?.let { map[id] = it }
        setTitles(platform, map, (data["at"] as? JsonPrimitive)?.doubleOrNull ?: 0.0)
        return true
    }

    /** Use this title list for a platform (tests, or a list kept elsewhere). */
    internal fun setTitles(platform: String, list: Map<String, String>, at: Double = clock()) {
        index[platform] = group(list)
        titles[platform] = list
        sortedKeys.remove(platform)
        loadedAt[platform] = at
    }

    private suspend fun fetch(platform: String) {
        val list = orNull {
            val resp = http.request(TITLES.format(platform), polite = false, maxBytes = 16 * 1024 * 1024)
            parse(resp.text)
        } ?: emptyMap()
        if (list.size < 100) { // unreachable, or an error page rather than the list
            listFailed[platform] = clock()
            return
        }
        listFailed.remove(platform)
        val now = clock()
        setTitles(platform, list, now)
        JsonFiles.write(
            path("$platform.json"),
            JsonObject(mapOf("at" to JsonPrimitive(now), "titles" to JsonObject(list.mapValues { JsonPrimitive(it.value) }))),
        )
    }

    /** Start fetching a platform's title list if it is missing or old. */
    private fun ensure(platform: String): Job? {
        val loaded = load(platform)
        if (loaded && clock() - (loadedAt[platform] ?: 0.0) < INDEX_TTL) return null
        if (clock() - (listFailed[platform] ?: 0.0) < LIST_RETRY) return null
        synchronized(building) {
            val running = building[platform]
            if (running != null && !running.isCompleted) return running
            val job = scope.launch { fetch(platform) }
            building[platform] = job
            listStarted[platform] = clock()
            return job
        }
    }

    /** GameTDB IDs for a title, the plain game first ([] for none or a console GameTDB lacks). */
    fun ids(title: String, system: String): List<String> {
        val platform = PLATFORMS[system] ?: return emptyList()
        val idx = index[platform] ?: emptyMap()
        val key = matchTitle(title)
        idx[key]?.takeIf { it.isNotEmpty() }?.let { return it }
        val cleaned = matchTitle(NOISE_RE.replace(title, " "))
        if (cleaned != key) idx[cleaned]?.takeIf { it.isNotEmpty() }?.let { return it }
        return prefix(platform, cleaned.ifEmpty { key })
    }

    /** GameTDB's name for this game (its title list must be loaded). */
    fun name(title: String, system: String): String? {
        val platform = PLATFORMS[system] ?: return null
        val found = ids(title, system)
        return if (found.isEmpty()) null else titles[platform]?.get(found[0])
    }

    /** A cut-off title: the shortest full title it is the start of. */
    private fun prefix(platform: String, key: String): List<String> {
        if (key.length < PREFIX_MIN) return emptyList()
        val idx = index[platform] ?: return emptyList()
        val keys = sortedKeys.getOrPut(platform) { idx.keys.sorted() }
        var at = keys.binarySearch(key).let { if (it < 0) -it - 1 else it }
        val candidates = mutableListOf<String>()
        while (at < keys.size && keys[at].startsWith(key)) {
            candidates += keys[at]
            at++
        }
        val shortest = candidates.minByOrNull { it.length } ?: return emptyList()
        return idx[shortest] ?: emptyList()
    }

    /**
     * Fetch title lists in the background (Switch's at app start), so the first
     * search does not have to wait for them. The others are fetched the first
     * time their console shows up.
     */
    fun prefetch(systems: Iterable<String> = listOf("switch")) {
        val wanted = systems.mapNotNull { PLATFORMS[it] }
        if (wanted.isEmpty()) return
        scope.launch { wanted.forEach { ensure(it) } }
    }

    // ------------------------------------------------------------------ covers
    private fun foundMap(): ConcurrentHashMap<String, Found> {
        if (!foundLoaded) {
            synchronized(found) {
                if (!foundLoaded) {
                    JsonFiles.read(path("covers.json")).obj()?.forEach { (key, value) ->
                        val item = value.arr()
                        if (item != null && item.size == 2) {
                            val region = (item[0] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                            val at = (item[1] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                            found.putIfAbsent(key, Found(region, at))
                        }
                    }
                    foundLoaded = true
                }
            }
        }
        return found
    }

    /** Record what a lookup found (tests, or answers known elsewhere). */
    internal fun setKnown(platform: String, gameId: String, region: String, at: Double = clock()) {
        foundMap()["$platform/$gameId"] = Found(region, at)
    }

    /** The region folder with this game's cover, "" for none, null if not looked up. */
    fun known(platform: String, gameId: String): String? {
        val item = foundMap()["$platform/$gameId"] ?: return null
        if (item.region.isEmpty() && clock() - item.at > MISSING_TTL) return null
        return item.region
    }

    /**
     * The first region folder that has this game's cover. "" when GameTDB has
     * none (every region a 404); null when the answer is unknown (no network),
     * so nothing is remembered.
     */
    private suspend fun probe(platform: String, gameId: String, order: List<String>): String? {
        if (clock() < pausedUntil) return null
        for (region in order) {
            // A tiny copy through the proxy: cheap, and GameTDB is asked once.
            var url = coverUrl(platform, gameId, region, kinds(platform).first, proxy)
            if (proxy) url += "&w=8&h=8"
            val resp = try {
                http.request(url, method = if (proxy) "GET" else "HEAD", polite = false, retries = 0, maxBytes = 65536)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpError) {
                if (e.status == 404 || e.status == 400) {
                    timeouts.set(0)
                    continue
                }
                if (timeouts.incrementAndGet() >= 3) {
                    // GameTDB (or the proxy) is not answering: stop asking for a while.
                    pausedUntil = clock() + 30 * 60
                }
                return null
            } catch (_: Exception) {
                return null
            }
            if (resp.status == 200) {
                timeouts.set(0)
                return region
            }
        }
        return ""
    }

    private fun order(region: String?): List<String> {
        val first = REGION_FOLDERS[region ?: ""]
        return listOfNotNull(first) + ORDER.filter { it != first }
    }

    /**
     * Give entries their GameTDB box art where there is one: `cover` (the small
     * front, for the grid) and `coverFull` (the larger one, for the game page).
     * Nothing here waits on the art server: a game whose cover region is known
     * gets that region; one not looked up yet gets its likeliest region at once
     * (the image cache tries the other regions if that one is missing, see
     * [alternatives]) and is looked up in the background, `budget` games at a
     * time. A game known to have no cover is left to the other sources. Only
     * the title list itself is waited for, the first time.
     */
    suspend fun apply(entries: List<RomEntry>, wait: Double = 4.0, budget: Int = 48) {
        val wanted = entries.filter { it.system in PLATFORMS }
        if (wanted.isEmpty()) return
        val platforms = wanted.map { PLATFORMS.getValue(it.system) }.toSet()
        val missing = platforms.any { !index.containsKey(it) }
        val pending = withContext(Dispatchers.IO) { platforms.mapNotNull { ensure(it) } }
        if (pending.isNotEmpty() && wait > 0) {
            // Without any list there is nothing to match against (and the search
            // result is cached for days): wait for it a little longer, counted
            // from when the fetch started.
            val now = clock()
            val started = platforms.minOf { listStarted[it]?.takeIf { at -> at != 0.0 } ?: now }
            val waitList = (if (missing) maxOf(wait, listWait) else wait) - (now - started)
            if (waitList > 0) waitFor(pending, waitList)
        }

        if (wanted.any { it.system == "switch" } && retail == null) {
            retail = withContext(Dispatchers.IO) { retailIds() }
        }
        withContext(Dispatchers.IO) { foundMap() } // read covers.json off the caller's thread

        val toProbe = LinkedHashMap<Pair<String, String>, List<String>>()
        for (entry in wanted) {
            val platform = PLATFORMS.getValue(entry.system)
            var gameIds = ids(entry.title, entry.system)
            if (platform == "switch") {
                val shipped = retail ?: emptySet()
                gameIds = gameIds.filter { it in shipped }
            }
            gameIds = gameIds.take(3)
            if (gameIds.isEmpty()) continue
            val order = order(entry.region?.takeIf { it.isNotEmpty() } ?: extractRegion(entry.title))
            val (grid, full) = kinds(platform)
            var chosen: Pair<String, String>? = null
            for (gameId in gameIds) {
                val region = known(platform, gameId)
                if (!region.isNullOrEmpty()) {
                    chosen = gameId to region
                    break
                }
                if (region == null) {
                    if (toProbe.size < budget) toProbe.putIfAbsent(platform to gameId, order)
                    // Not looked up yet: the likeliest region for now.
                    if (chosen == null) chosen = gameId to order[0]
                }
            }
            if (chosen != null) {
                entry.cover = coverUrl(platform, chosen.first, chosen.second, grid, proxy)
                entry.coverFull = coverUrl(platform, chosen.first, chosen.second, full, proxy)
                entry.extra.putStr("gametdb", chosen.first)
            }
        }
        if (toProbe.isNotEmpty()) probeInBackground(toProbe)
    }

    private fun probeInBackground(toProbe: Map<Pair<String, String>, List<String>>) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val semaphore = Semaphore(6)
            supervisorScope {
                for ((key, order) in toProbe) {
                    launch {
                        val region = semaphore.withPermit { probe(key.first, key.second, order) }
                        if (region != null) foundMap()["${key.first}/${key.second}"] = Found(region, clock())
                    }
                }
            }
            writeFound()
        }
        probing += job
        job.invokeOnCompletion { probing.remove(job) }
        job.start()
    }

    private fun writeFound() {
        val snapshot = foundMap().entries.sortedBy { it.key }.associate { (key, item) ->
            key to JsonArray(listOf(JsonPrimitive(item.region), JsonPrimitive(item.at)))
        }
        JsonFiles.write(path("covers.json"), JsonObject(snapshot))
    }

    /** Wait for background lookups (for tests and the speed of the next search). */
    suspend fun settle() {
        while (true) {
            val running = probing.filter { !it.isCompleted }
            if (running.isEmpty()) return
            running.joinAll()
        }
    }

    /** The same for installed games: sets `cover` where GameTDB has one. */
    suspend fun covers(games: List<LibraryGame>, wait: Double = 2.0) {
        val entries = games.mapIndexed { index, game ->
            RomEntry(source = "library", sourceId = index.toString(), title = game.title, system = game.system)
        }
        apply(entries, wait = wait)
        for ((game, entry) in games.zip(entries)) {
            entry.cover?.let { game.cover = it }
        }
    }

    companion object {
        const val ART = "https://art.gametdb.com"
        const val TITLES = "https://www.gametdb.com/%stdb.txt?LANG=EN"
        const val PROXY = "https://wsrv.nl/"
        const val LIST_RETRY = 30 * 60.0

        /** How long searches wait, all together, for a missing title list. */
        const val LIST_WAIT = 10.0
        const val INDEX_TTL = 14 * 24 * 60 * 60.0

        /** How long "this game has no cover" is believed before asking again. */
        const val MISSING_TTL = 7 * 24 * 60 * 60.0

        /** A cut-off title matches the full one when its key is at least this long. */
        const val PREFIX_MIN = 12

        /** Store console key -> GameTDB platform. */
        val PLATFORMS: Map<String, String> = linkedMapOf(
            "switch" to "switch", "wiiu" to "wiiu", "wii" to "wii", "nds" to "ds", "3ds" to "3ds", "ps3" to "ps3",
        )

        /** Per platform: the art for the grid, the art for the game page, file type. Wii has the small front only. */
        private val KINDS: Map<String, Triple<String, String, String>> = mapOf("wii" to Triple("cover", "cover", "png"))
        private val DEFAULT_KINDS = Triple("cover", "coverM", "jpg")

        // Cover folders by region, in the order they are tried after the game's own.
        private val REGION_FOLDERS = mapOf("USA" to "US", "Europe" to "EN", "Japan" to "JA")
        private val ORDER = listOf("US", "EN", "JA")

        private val LINE_RE = Regex("""([0-9A-Z]{4,10})\s*=\s*(.+?)\s*""")

        // What the Switch catalogues add to a title ("Download Super Mario Odyssey NSP
        // Full Game", "Mario Kart 8 Deluxe Switch (NSP)"): tried without it.
        private val NOISE_RE = Regex(
            """^\s*download\s+|\b(?:nsp|xci|nsz|xcz|full\s+game|free|eshop|roms?|switch)\b""" +
                """|\+\s*(?:all\s+)?(?:updates?|dlcs?)\b.*$|\bv\d+(?:\.\d+)*\b""",
            RegexOption.IGNORE_CASE,
        )

        // Bundles and editions sort after the plain game when several IDs share a title.
        private val EXTRA_RE = Regex("""[\[(]|bundle|starter pack|demo""", RegexOption.IGNORE_CASE)

        private val PROXIED_RE = Regex("""[?&]url=([^&]+)(.*)$""")
        private val ART_URL_RE = Regex("""^(https://art\.gametdb\.com/[^/]+/[^/]+/)([A-Za-z]+)(/[^/]+)$""")

        fun directUrl(platform: String, gameId: String, region: String, kind: String = "cover"): String {
            val ext = (KINDS[platform] ?: DEFAULT_KINDS).third
            return "$ART/$platform/$kind/$region/$gameId.$ext"
        }

        /**
         * A cover's URL, through the resizing proxy (wsrv.nl) by default:
         * GameTDB's art server slows down and stops answering when many clients
         * fetch from it at once; the proxy fetches each picture once.
         */
        fun coverUrl(platform: String, gameId: String, region: String, kind: String = "cover", proxy: Boolean = true): String {
            val url = directUrl(platform, gameId, region, kind)
            if (!proxy) return url
            return "$PROXY?url=${Html.quote(url.substringAfter("://"), safe = "")}&output=webp&q=85"
        }

        /** The grid and the game-page art kinds of a platform. */
        fun kinds(platform: String): Pair<String, String> =
            (KINDS[platform] ?: DEFAULT_KINDS).let { it.first to it.second }

        /** The title list: one "ID = Title" per line after a header line. */
        fun parse(text: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (line in text.lines()) {
                val m = LINE_RE.matchEntire(line) ?: continue
                if (m.groupValues[1] != "TITLES") out[m.groupValues[1]] = m.groupValues[2]
            }
            return out
        }

        /** IDs by matchTitle(), the plain game before bundles and editions. */
        fun group(titles: Map<String, String>): Map<String, List<String>> {
            val grouped = LinkedHashMap<String, MutableList<Pair<Boolean, String>>>()
            for ((gameId, title) in titles) {
                val key = matchTitle(title)
                if (key.isNotEmpty()) grouped.getOrPut(key) { mutableListOf() } += EXTRA_RE.containsMatchIn(title) to gameId
            }
            return grouped.mapValues { (_, ids) ->
                ids.sortedWith(compareBy<Pair<Boolean, String>> { it.first }.thenBy { it.second }).map { it.second }
            }
        }

        /**
         * The same cover in the other regions, for when this one is missing
         * (direct or through the proxy, the way it was asked for).
         */
        fun alternatives(url: String?): List<String> {
            val text = url ?: ""
            val proxied = text.startsWith(PROXY)
            var direct = text
            var tail = ""
            if (proxied) {
                val m = PROXIED_RE.find(text) ?: return emptyList()
                direct = "https://" + Html.unquote(m.groupValues[1])
                tail = m.groupValues[2]
            }
            val m = ART_URL_RE.find(direct) ?: return emptyList()
            val others = (ORDER + "other").filter { it != m.groupValues[2] }
                .map { "${m.groupValues[1]}$it${m.groupValues[3]}" }
            if (!proxied) return others
            return others.map { "$PROXY?url=${Html.quote(it.substringAfter("://"), safe = "")}$tail" }
        }

        /** GameTDB IDs of retail Switch games (the shipped list); empty if it cannot be read. */
        fun retailIds(): Set<String> =
            readResourceJson("/data/gametdb_switch_retail.json.gz").arr()
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toHashSet() ?: emptySet()
    }
}
