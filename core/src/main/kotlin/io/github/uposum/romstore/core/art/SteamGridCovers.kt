package io.github.uposum.romstore.core.art

import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.matchTitle
import io.github.uposum.romstore.core.model.putFlag
import io.github.uposum.romstore.core.net.Html
import io.github.uposum.romstore.core.net.HttpClient
import io.github.uposum.romstore.core.net.HttpError
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Covers from SteamGridDB for games no box-art library has, a port of the
 * plugin's sgdb.py.
 *
 *     GET /api/v2/search/autocomplete/<title>            the game's id
 *     GET /api/v2/grids/game/<id>?dimensions=600x900     its grids, best first
 *
 * A game is accepted only when SteamGridDB's name is the same title
 * (matchTitle) or the start of it. Answers, found or not, are kept for two
 * weeks; a search waits a moment for the first few and looks the rest up in
 * the background. `key` gives the API key each time (the user's, else
 * [ArtKeys.STEAMGRIDDB_KEY]; see [ArtKeys.steamGridDbKey]).
 */
class SteamGridCovers(
    private val http: HttpClient,
    private val key: () -> String,
    cacheDir: File? = null,
    private val scope: CoroutineScope = defaultArtScope(),
) {
    private data class Hit(val thumb: String, val full: String, val at: Double)

    private val path = cacheDir?.let { File(it, "sgdb_covers.json") }
    @Volatile private var cache: ConcurrentHashMap<String, Hit>? = null
    private val running: MutableSet<Job> = ConcurrentHashMap.newKeySet()
    private val asked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    internal var clock: () -> Double = ::nowSeconds

    val enabled: Boolean get() = key().trim().isNotEmpty()

    // ------------------------------------------------------------------ cache
    private fun entries(): ConcurrentHashMap<String, Hit> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val map = ConcurrentHashMap<String, Hit>()
            JsonFiles.read(path).obj()?.forEach { (k, v) ->
                val item = v.arr()
                if (item != null && item.size == 3) {
                    val thumb = (item[0] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                    val full = (item[1] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                    map[k] = Hit(thumb, full, (item[2] as? JsonPrimitive)?.doubleOrNull ?: 0.0)
                }
            }
            cache = map
            return map
        }
    }

    private fun save() {
        val snapshot = entries().entries.sortedBy { it.key }.associate { (k, hit) ->
            k to JsonArray(listOf(JsonPrimitive(hit.thumb), JsonPrimitive(hit.full), JsonPrimitive(hit.at)))
        }
        JsonFiles.write(path, JsonObject(snapshot))
    }

    /** [thumb, full] when found, [] when SteamGridDB has none, null if not asked yet. */
    fun cached(title: String): List<String>? {
        val item = entries()[matchTitle(cleanTitle(title))] ?: return null
        if (clock() - item.at > TTL) return null
        return if (item.thumb.isNotEmpty()) listOf(item.thumb, item.full) else emptyList()
    }

    // ------------------------------------------------------------------ lookup
    private suspend fun api(path: String): JsonElement? {
        val resp = http.request(
            "$API/$path",
            headers = mapOf("Authorization" to "Bearer ${key().trim()}", "Accept" to "application/json"),
            polite = false,
            retries = 1,
        )
        val data = resp.json() as? JsonObject ?: throw HttpError("SteamGridDB: bad answer")
        if (!truthy(data["success"])) throw HttpError("SteamGridDB: ${data["errors"]}")
        return data["data"]
    }

    /** [thumb, full] for this title, [] for none; null on a network or key error. */
    suspend fun lookup(title: String): List<String>? {
        val search = cleanTitle(title)
        val key = matchTitle(search)
        if (key.isEmpty()) return emptyList()
        val found = orNull {
            val games = api("search/autocomplete/${Html.quote(search, safe = "")}").arr()
                ?.mapNotNull { it as? JsonObject } ?: emptyList()
            val game = pick(games, title)
            var hit: List<String> = emptyList()
            if (game != null) {
                val grids = api("grids/game/${game.text("id")}?$GRIDS").arr() ?: emptyList()
                for (grid in grids.mapNotNull { it as? JsonObject }) {
                    val url = grid.string("url")
                    if (url != null && grid.string("mime") in MIMES) {
                        hit = listOf(grid.string("thumb") ?: url, url)
                        break
                    }
                }
            }
            hit
        } ?: return null
        val entries = withContext(Dispatchers.IO) { entries() }
        entries[key] = if (found.isNotEmpty()) Hit(found[0], found[1], clock()) else Hit("", "", clock())
        return found
    }

    // ------------------------------------------------------------------ apply
    /**
     * Give entries without a cover SteamGridDB's, where it has one. Known
     * answers are instant; up to `budget` new titles are looked up (four at a
     * time), the search waiting `wait` seconds for them at most.
     */
    suspend fun apply(entries: List<RomEntry>, wait: Double = 3.0, budget: Int = 32) {
        if (!enabled) return
        val bare = entries.filter { it.cover.isNullOrEmpty() }
        if (bare.isEmpty()) return
        withContext(Dispatchers.IO) { entries() }
        val wanted = mutableListOf<String>()
        for (entry in bare) {
            val hit = cached(entry.title)
            if (!hit.isNullOrEmpty()) {
                set(entry, hit)
            } else if (hit == null) {
                val key = matchTitle(cleanTitle(entry.title))
                if (key.isNotEmpty() && key !in asked && entry.title !in wanted && wanted.size < budget) {
                    wanted += entry.title
                }
            }
        }
        if (wanted.isEmpty()) return
        for (title in wanted) asked += matchTitle(cleanTitle(title))
        val job = scope.launch(start = CoroutineStart.LAZY) { lookupMany(wanted) }
        running += job
        job.invokeOnCompletion { running.remove(job) }
        job.start()
        waitFor(listOf(job), wait)
        for (entry in bare) {
            if (entry.cover.isNullOrEmpty()) {
                val hit = cached(entry.title)
                if (!hit.isNullOrEmpty()) set(entry, hit)
            }
        }
    }

    private suspend fun lookupMany(titles: List<String>) {
        val semaphore = Semaphore(4)
        try {
            supervisorScope {
                for (title in titles) {
                    launch {
                        val found = semaphore.withPermit { lookup(title) }
                        // Not an answer (network, key): may be asked again later.
                        if (found == null) asked.remove(matchTitle(cleanTitle(title)))
                    }
                }
            }
        } finally {
            withContext(Dispatchers.IO) { save() }
        }
    }

    /** Wait for background lookups (tests). */
    suspend fun settle() {
        while (true) {
            val jobs = running.filter { !it.isCompleted }
            if (jobs.isEmpty()) return
            jobs.joinAll()
        }
    }

    private fun set(entry: RomEntry, hit: List<String>) {
        entry.cover = hit[0]
        entry.coverFull = hit[1]
        entry.extra.putFlag("sgdb")
    }

    companion object {
        const val API = "https://www.steamgriddb.com/api/v2"
        const val GRIDS = "dimensions=600x900&types=static&nsfw=false&humor=false&epilepsy=false"
        const val TTL = 14 * 24 * 60 * 60.0
        private val MIMES = setOf("image/png", "image/jpeg", "image/webp")

        // Region and dump tags, and what the catalogues add ("Download … NSP").
        private val TAGS_RE = Regex("""\s*[(\[][^)\]]*[)\]]""")
        private val NOISE_RE = Regex(
            """^\s*download\s+|\b(?:nsp|xci|nsz|xcz|full\s+game|free|eshop|roms?|iso)\b|\+\s*(?:all\s+)?(?:updates?|dlcs?)\b.*$""",
            RegexOption.IGNORE_CASE,
        )
        private val SPACES = Regex("""\s+""")

        /** The title SteamGridDB is searched with: no tags, no catalogue noise. */
        fun cleanTitle(title: String?): String =
            SPACES.replace(NOISE_RE.replace(TAGS_RE.replace(title ?: "", " "), " "), " ").trim(' ', '-', ':')

        /** The search hit that is this game: the same title, or the start of it. */
        fun pick(games: List<JsonObject>, title: String): JsonObject? {
            val want = matchTitle(cleanTitle(title))
            if (want.isEmpty()) return null
            val named = games.filter { truthy(it["id"]) }.map { it to matchTitle(it.string("name") ?: "") }
            named.firstOrNull { it.second == want }?.let { return it.first }
            // A cut-off catalogue title, or SteamGridDB's shorter one.
            return named.firstOrNull { (_, key) ->
                minOf(key.length, want.length) >= 10 && (key.startsWith(want) || want.startsWith(key))
            }?.first
        }
    }
}
