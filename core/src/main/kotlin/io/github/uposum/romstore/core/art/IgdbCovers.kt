package io.github.uposum.romstore.core.art

import io.github.uposum.romstore.core.model.Screenshot
import io.github.uposum.romstore.core.model.Trailer
import io.github.uposum.romstore.core.net.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

/** An IGDB image: its id and size (wide artworks make Steam heroes). */
@Serializable
data class IgdbImage(val id: String, val width: Int = 0, val height: Int = 0)

/** What IGDB knows about a game (the plugin's lookup() dict). */
@Serializable
data class IgdbInfo(
    /** t_cover_big poster URL. */
    val cover: String? = null,
    /** Community score on a 0-5 scale. */
    val rating: Double? = null,
    @SerialName("rating_count") val ratingCount: Int? = null,
    /** Screenshots for the game page (t_screenshot_huge; ImageUrls.bestVariant picks the small one). */
    val shots: List<Screenshot> = emptyList(),
    @SerialName("cover_id") val coverId: String? = null,
    val artworks: List<IgdbImage> = emptyList(),
    val screenshots: List<IgdbImage> = emptyList(),
    val trailer: Trailer? = null,
    val summary: String? = null,
    val genres: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val developers: List<String> = emptyList(),
    /** First release, Unix seconds. */
    val released: Long? = null,
)

/**
 * IGDB (Twitch) lookups for cover art, rating, screenshots, trailer, summary,
 * genres and developers, with a local cache. A port of the plugin's covers.py.
 */
class IgdbCovers(
    private val http: HttpClient,
    clientId: String? = ArtKeys.IGDB_CLIENT_ID,
    clientSecret: String? = ArtKeys.IGDB_CLIENT_SECRET,
    private val cacheDir: File? = null,
) {
    /** A remembered answer; `persist` is false for a network error (asked again next session). */
    private class Cached(val info: IgdbInfo?, val persist: Boolean = true)

    private val clientId = (clientId ?: "").trim()
    private val clientSecret = (clientSecret ?: "").trim()
    private var token: String? = null
    private var tokenExpiry = 0.0
    private val tokenLock = Mutex()
    private val memory = ConcurrentHashMap<String, Cached>()
    @Volatile private var cacheLoaded = false
    private val saveLock = Any()

    internal var clock: () -> Double = ::nowSeconds

    val enabled: Boolean get() = clientId.isNotEmpty() && clientSecret.isNotEmpty()

    // ------------------------------------------------------------------ cache
    private fun cachePath(): File? = cacheDir?.let { File(it, "igdb_covers.json") }

    /** Read the file into memory once; entries already in memory win. */
    private fun loadCache() {
        if (cacheLoaded) return
        synchronized(saveLock) {
            if (cacheLoaded) return
            cacheLoaded = true
            JsonFiles.read(cachePath()).obj()?.forEach { (key, value) ->
                val info = if (value is JsonNull) null else orNull { JsonFiles.json.decodeFromJsonElement(IgdbInfo.serializer(), value) }
                if (value is JsonNull || info != null) memory.putIfAbsent(key, Cached(info))
            }
        }
    }

    private fun saveCache() {
        val path = cachePath() ?: return
        synchronized(saveLock) {
            val out = LinkedHashMap<String, JsonElement>()
            for ((key, item) in memory) {
                if (!item.persist) continue
                out[key] = item.info?.let { JsonFiles.json.encodeToJsonElement(IgdbInfo.serializer(), it) } ?: JsonNull
            }
            JsonFiles.write(path, JsonObject(out))
        }
    }

    // ------------------------------------------------------------------ token
    private suspend fun accessToken(): String = tokenLock.withLock {
        token?.takeIf { it.isNotEmpty() && clock() < tokenExpiry }?.let { return it }
        val body = "client_id=$clientId&client_secret=$clientSecret&grant_type=client_credentials".toByteArray()
        val response = http.request(
            TOKEN_URL, "POST", body,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        )
        val data = response.json().obj() ?: JsonObject(emptyMap())
        val fresh = data.string("access_token") ?: ""
        token = fresh
        tokenExpiry = clock() + (data.number("expires_in") ?: 3600.0) - 60
        fresh
    }

    // ------------------------------------------------------------------ lookup
    /** What is already known about a title, without any network call (null: nothing, or "no such game"). */
    fun cached(title: String): IgdbInfo? {
        // lookup() stores under the lower-cased title; the cleaned title is
        // tried too (the plugin only tried that one and so missed most).
        val keys = listOf(title.trim().lowercase(), clean(title)).filter { it.isNotEmpty() }.distinct()
        if (keys.isEmpty()) return null
        keys.firstNotNullOfOrNull { memory[it] }?.let { return it.info }
        loadCache()
        return keys.firstNotNullOfOrNull { memory[it] }?.info
    }

    /** The IGDB art, rating and facts for a game title, if known. */
    suspend fun lookup(title: String): IgdbInfo? {
        if (!enabled || title.isEmpty()) return null
        val key = title.trim().lowercase()
        memory[key]?.let { return it.info }
        withContext(Dispatchers.IO) { loadCache() }
        memory[key]?.let { return it.info }

        var queryTitle = clean(title)
        for (noise in NOISE) {
            if (queryTitle.startsWith(noise.trim())) {
                queryTitle = queryTitle.substring(noise.trim().length)
                break
            }
        }
        if (queryTitle.length < 3) {
            memory[key] = Cached(null)
            return null
        }

        val body = (
            "search \"${title.trim()}\"; fields name,cover.image_id,screenshots.image_id,screenshots.width,screenshots.height," +
                "artworks.image_id,artworks.width,artworks.height," +
                "videos.video_id,rating,aggregated_rating,rating_count,total_rating,summary," +
                "first_release_date,genres.name,platforms.name,involved_companies.company.name; " +
                "limit 6;"
            ).toByteArray()
        val games = orNull {
            val token = accessToken()
            val response = http.request(
                API_URL, "POST", body,
                headers = mapOf("Client-ID" to clientId, "Authorization" to "Bearer $token"),
            )
            (response.json() as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        }
        if (games == null) {
            memory[key] = Cached(null, persist = false)
            return null
        }

        val best = bestGame(games, queryTitle)
        if (best == null) {
            memory[key] = Cached(null)
            return null
        }
        val result = info(best)
        memory[key] = Cached(result)
        withContext(Dispatchers.IO) { saveCache() }
        return result
    }

    /** Look several titles up in parallel (two at a time, inside IGDB's rate limit). */
    suspend fun lookupMany(titles: List<String>, limit: Int = 12): Map<String, IgdbInfo?> {
        val unique = titles.filter { it.isNotEmpty() }.distinct().take(limit)
        if (unique.isEmpty()) return emptyMap()
        val semaphore = Semaphore(2)
        val results = coroutineScope {
            unique.map { title ->
                async {
                    orNull {
                        semaphore.withPermit {
                            delay(120) // stay inside the IGDB rate limit
                            title to lookup(title)
                        }
                    }
                }
            }.awaitAll()
        }
        val found = LinkedHashMap<String, IgdbInfo?>()
        for (item in results) if (item != null) found[item.first] = item.second
        return found
    }

    companion object {
        const val TOKEN_URL = "https://id.twitch.tv/oauth2/token"
        const val API_URL = "https://api.igdb.com/v4/games"
        const val IMAGE_BASE = "https://images.igdb.com/igdb/image/upload/t_cover_big/"

        /** IGDB size names start with "t_"; without it the image is a 404. */
        const val SCREENSHOT_BASE = "https://images.igdb.com/igdb/image/upload/t_screenshot_huge/"

        // Words that make a title unsearchable on IGDB.
        private val NOISE = listOf("the ", "a ", "an ")
        private val NON_ALNUM = Regex("[^a-z0-9]+")

        /** "Super Mario Bros. Wonder" and "Super Mario Bros Wonder" are the same game. */
        internal fun clean(title: String?): String = NON_ALNUM.replace((title ?: "").lowercase(), " ").trim()

        private fun JsonObject.list(key: String): List<JsonObject> =
            (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

        /** Python's round(value, 1). */
        private fun round1(value: Double): Double = BigDecimal(value).setScale(1, RoundingMode.HALF_EVEN).toDouble()

        /** IGDB stores the community score out of 100, the store shows 0-5. */
        fun rating(game: JsonObject): Double? {
            for (field in listOf("rating", "total_rating", "aggregated_rating")) {
                val raw = game[field] as? JsonPrimitive ?: continue
                if (raw is JsonNull) continue
                val value = raw.doubleOrNull ?: continue
                // Every IGDB score is out of 100 (the plugin halved the critics' one by mistake).
                return round1(value / 20.0)
            }
            return null
        }

        /**
         * The entry that is really the game we are looking for. How well the
         * name matches decides first (the same name, then a name that starts
         * with it, contains it, is part of it, shares most words; whole words
         * only, so "art" is not in "Mario Kart"). IGDB often has several
         * entries with the same name, a bare one and a richer one that carries
         * the rating, the trailer and more screenshots: within one match level
         * the entry with the most data wins.
         */
        fun bestGame(games: List<JsonObject>, queryTitle: String): JsonObject? {
            val wanted = clean(queryTitle)
            if (wanted.isEmpty()) return null
            val wantedWords = wanted.split(" ").filter { it.isNotEmpty() }
            var best: JsonObject? = null
            var bestTier = 0.0
            var bestData = 0.0
            for (game in games) {
                val name = clean(game.string("name"))
                if (name.isEmpty()) continue
                val tier = when {
                    name == wanted -> 5.0
                    name.startsWith("$wanted ") -> 4.0
                    " $wanted " in " $name " -> 3.0
                    " $name " in " $wanted " -> 2.0
                    else -> {
                        val words = wantedWords.toSet()
                        val overlap = (words intersect name.split(" ").filter { it.isNotEmpty() }.toSet()).size
                        if (overlap == 0 || words.size < 2) continue
                        val ratio = overlap.toDouble() / words.size
                        if (ratio < 0.75) continue
                        ratio // below every tier above
                    }
                }
                // Within a tier: the entry that actually carries the metadata.
                var data = 0.0
                if (truthy(game["rating"]) || truthy(game["total_rating"]) || truthy(game["aggregated_rating"])) data += 1.2
                if (truthy(game["videos"])) data += 1.2
                if (truthy(game["screenshots"])) data += 0.3
                if (truthy(game["cover"])) data += 0.2
                if (truthy(game["summary"])) data += 0.2
                // Prefer the plain game over a bundle or a DLC.
                if (truthy(game["involved_companies"])) data += 0.1
                if (tier > bestTier || (tier == bestTier && data > bestData)) {
                    bestTier = tier
                    bestData = data
                    best = game
                }
            }
            return best
        }

        internal fun shots(game: JsonObject): List<Screenshot> =
            game.list("screenshots").take(8).mapNotNull { shot ->
                shot.text("image_id")?.let { Screenshot(thumb = "$SCREENSHOT_BASE$it.jpg", full = "$SCREENSHOT_BASE$it.jpg") }
            }

        /** The game's trailer (IGDB knows which video is the trailer). */
        internal fun trailer(game: JsonObject): Trailer? {
            val videos = game.list("videos").filter { it.text("video_id") != null }
            if (videos.isEmpty()) return null
            val pick = videos.firstOrNull { "trailer" in (it.string("name") ?: "").lowercase() } ?: videos[0]
            return YouTube.trailer(pick.text("video_id")!!, pick.string("name") ?: "Trailer")
        }

        private fun images(game: JsonObject, key: String): List<IgdbImage> =
            game.list(key).mapNotNull { item ->
                item.text("image_id")?.let {
                    IgdbImage(it, (item["width"] as? JsonPrimitive)?.intOrNull ?: 0, (item["height"] as? JsonPrimitive)?.intOrNull ?: 0)
                }
            }.take(8)

        /** The lookup result for the chosen game, or null when it has no cover, rating or screenshots. */
        internal fun info(best: JsonObject): IgdbInfo? {
            val cover = best["cover"].obj()?.text("image_id")
            val url = cover?.let { "$IMAGE_BASE$it.jpg" }
            val rating = rating(best)
            val shots = shots(best)
            if (url == null && (rating == null || rating == 0.0) && shots.isEmpty()) return null
            return IgdbInfo(
                cover = url,
                rating = rating,
                ratingCount = (best["rating_count"] as? JsonPrimitive)?.intOrNull,
                shots = shots,
                coverId = cover,
                artworks = images(best, "artworks"),
                screenshots = images(best, "screenshots"),
                trailer = trailer(best),
                summary = best.string("summary")?.take(600)?.ifEmpty { null },
                genres = best.list("genres").mapNotNull { it.string("name") }.take(4),
                platforms = best.list("platforms").mapNotNull { it.string("name") }.take(4),
                developers = best.list("involved_companies").mapNotNull { it["company"].obj()?.string("name") }.take(2),
                released = (best["first_release_date"] as? JsonPrimitive)?.longOrNull,
            )
        }
    }
}
