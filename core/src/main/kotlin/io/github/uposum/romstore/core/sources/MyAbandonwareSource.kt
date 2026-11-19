package io.github.uposum.romstore.core.sources

import io.github.uposum.romstore.core.net.findAllFast

import io.github.uposum.romstore.core.model.GameMedia
import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.RomFile
import io.github.uposum.romstore.core.model.Screenshot
import io.github.uposum.romstore.core.model.Systems
import io.github.uposum.romstore.core.model.putFlag
import io.github.uposum.romstore.core.model.putStr
import io.github.uposum.romstore.core.net.Html
import io.github.uposum.romstore.core.net.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * MyAbandonware (port of backend/sources/myabandonware.py).
 *
 * The site is backed by a TypeSense index ("games"). The search page ships a
 * short lived, scoped search key inside its HTML, which is exactly what we
 * need to query the index directly - far cheaper and more stable than
 * scraping the rendered result list.
 *
 * * search -> POST https://ts.myabandonware.com/multi_search
 * * detail -> /game/<slug>/           (a.game-downloads__platform-download)
 * * file   -> /download/<id>-<slug>   (302 -> the real archive)
 */
class MyAbandonwareSource(http: HttpClient, options: SourceOptions) : Source(http, options) {
    override val supportsBrowse: Boolean = false

    @Volatile private var tsHost: String = DEFAULT_TS_HOST
    @Volatile private var apiKey: String? = null
    @Volatile private var apiKeyExpiry: Double = 0.0
    private val pages = ConcurrentHashMap<String, String>()

    // ------------------------------------------------------------ type sense key
    private suspend fun ensureKey(): String {
        val now = nowSeconds()
        apiKey?.let { if (now < apiKeyExpiry - 60) return it }
        val html = http.getText("$base/search?q=zelda")
        val m = KEY_RE.find(html) ?: throw SourceError("Could not read the MyAbandonware search key")
        val key = m.groupValues[1]
        SERVER_RE.find(html)?.let { server ->
            try {
                val data = Json.parseToJsonElement(server.groupValues[1]) as? JsonObject
                val host = data?.get("host")
                if (truthy(host)) tsHost = pyStr(host)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        apiKey = key
        apiKeyExpiry = keyExpiry(key)
        return key
    }

    // ------------------------------------------------------------------ search
    override suspend fun search(query: String, limit: Int, page: Int): List<RomEntry> {
        val key = ensureKey()
        val search = linkedMapOf<String, Any?>(
            "collection" to "games",
            "q" to query,
            "query_by" to "name,alt_names",
            "query_by_weights" to "2,1",
            "sort_by" to "popularity:asc",
            "prefix" to true,
            "infix" to "fallback",
            "num_typos" to 2,
            "typo_tokens_threshold" to 100,
            "drop_tokens_threshold" to 0,
            "enable_overrides" to true,
            "exact_match_first" to true,
            "per_page" to limit.coerceIn(1, 50),
            "page" to maxOf(1, page),
        )
        val payload = linkedMapOf<String, Any?>("searches" to listOf(search))
        val resp = http.postJson("https://$tsHost/multi_search", pyDumps(payload), mapOf("X-TYPESENSE-API-KEY" to key))
        val data = try {
            resp.json() as? JsonObject ?: throw SourceError("MyAbandonware returned an unexpected payload: not an object")
        } catch (e: SourceError) {
            throw e
        } catch (e: Exception) {
            throw SourceError("MyAbandonware returned an unexpected payload: ${e.message}")
        }
        val resultsList = (data["results"] as? JsonArray)?.takeIf { it.isNotEmpty() }
        val results = resultsList?.get(0) as? JsonObject ?: JsonObject(emptyMap())
        val entries = mutableListOf<RomEntry>()
        for (hit in (results["hits"] as? JsonArray).orEmpty()) {
            val doc = (hit as? JsonObject)?.get("document") as? JsonObject ?: JsonObject(emptyMap())
            val entry = entryFromHit(doc) ?: continue
            entry.rank = entries.size
            entries += entry
        }
        return entries.take(limit)
    }

    /** Pick the webp screenshot advertised by the index. */
    private fun webpFromSources(releases: List<JsonElement>): String? {
        for (release in releases) {
            val raw = (release as? JsonObject)?.get("main_screenshot_sources")
            if (!truthy(raw)) continue
            val sources: JsonElement = if (raw is JsonPrimitive && raw.isString) {
                try {
                    Json.parseToJsonElement(raw.content)
                } catch (_: Exception) {
                    continue
                }
            } else raw!!
            for (source in (sources as? JsonArray).orEmpty()) {
                val obj = source as? JsonObject ?: continue
                val type = obj["type"]?.let { pyStr(it) } ?: ""
                if (type.endsWith("webp") && truthy(obj["srcset"])) {
                    return Html.absolute(base, pyStr(obj["srcset"]))
                }
            }
        }
        return null
    }

    private fun entryFromHit(doc: JsonObject): RomEntry? {
        val name = strOf(doc["name"]).trim()
        val slug = strOf(doc["short_url"]).trim()
        if (name.isEmpty() || slug.isEmpty()) return null
        val platforms = (doc["platforms"] as? JsonArray).orEmpty().filter { truthy(it) }
        val releases = (doc["releases"] as? JsonArray).orEmpty().toList()
        var system = Systems.canonical(if (platforms.isNotEmpty()) pyStr(platforms[0]) else "")
        if (system == "unknown" && releases.isNotEmpty()) {
            system = Systems.canonical(strOf((releases[0] as? JsonObject)?.get("platform")))
        }

        var cover: String? = null
        for (release in releases) {
            val shot = (release as? JsonObject)?.get("main_screenshot")
            if (truthy(shot)) {
                cover = Html.absolute(base, pyStr(shot))
                break
            }
        }
        // The site ships a webp next to every png screenshot - use the exact
        // path from the index instead of guessing it.
        if (cover != null) {
            webpFromSources(releases)?.let { cover = it }
        }

        val genres = (doc["genres"] as? JsonArray).orEmpty().filter { truthy(it) }.map { pyStr(it) }
        val yearValue = doc["year"] as? JsonPrimitive
        val year = yearValue?.takeIf { !it.isString && it.booleanOrNull == null && '.' !in it.content && 'e' !in it.content.lowercase() }
            ?.longOrNull?.toInt()
        var ratingRaw = doc["rating_score"]
        if (!truthy(ratingRaw)) ratingRaw = doc["avg_rate"]
        val rating = (ratingRaw as? JsonPrimitive)?.let { p ->
            if (p is JsonNull) null else p.content.trim().toDoubleOrNull()
        }

        val entry = RomEntry(
            source = id,
            sourceId = slug,
            title = name,
            system = system,
            url = "$base/game/$slug/",
            cover = cover,
            rating = rating,
            ratingCount = (doc["rating_count"] as? JsonPrimitive)?.longOrNull?.toInt(),
            downloads = (doc["popularity"] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() },
            year = year,
            developer = joinNames(doc["developers"]),
            publisher = joinNames(doc["publishers"]),
            genres = genres,
        )
        entry.extra["platforms"] = JsonArray(platforms)
        return entry
    }

    // ------------------------------------------------------------------ details
    /** Fetch a game page (cached, so we only ask once per game). */
    private suspend fun page(url: String): String {
        pages[url]?.let { return it }
        val html = http.getText(url)
        if (pages.size > 24) pages.clear()
        pages[url] = html
        return html
    }

    /** MyAbandonware has a full screenshot gallery for most games. */
    override suspend fun media(entry: RomEntry): GameMedia? {
        val html = try {
            page(entry.url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val shots = mutableListOf<Screenshot>()
        val seen = HashSet<String>()
        for (m in GALLERY_RE.findAllFast(html)) {
            val path = m.groupValues[1]
            val full = Html.absolute(base, path)
            if (full in seen) continue
            seen += full
            // The gallery links the large jpg, the thumbnails live next to it.
            val slash = path.lastIndexOf('/')
            val head = if (slash == -1) "" else path.substring(0, slash)
            val name = if (slash == -1) path else path.substring(slash + 1)
            val thumb = if (head.isNotEmpty() && name.endsWith(".jpg")) "$head/thumbs/${name.dropLast(4)}.png" else path
            shots += Screenshot(thumb = Html.absolute(base, thumb), full = full)
            if (shots.size >= 12) break
        }
        if (shots.isEmpty()) return null
        // myabandonware.py also returns "caption": entry.title; GameMedia has
        // no caption (the game page shows the title already).
        return GameMedia(shots = shots)
    }

    override suspend fun resolve(entry: RomEntry): List<RomFile> {
        val html = page(entry.url)
        val files = parseDownloads(html, entry)
        entry.extra.putFlag("download_html_seen", files.isNotEmpty())
        // The game page carries the real box art ("default" image).
        val boxart = parseBoxart(html)
        if (boxart != null) {
            entry.coverFull = boxart
            entry.cover = entry.cover ?: boxart
            entry.extra.putStr("boxart", boxart)
        }
        return files
    }

    /** Find the cover image (data-source) on a MyAbandonware game page. */
    private fun parseBoxart(html: String): String? {
        for (m in BOXART_RE.findAllFast(html)) {
            val path = m.groupValues[1]
            val lower = path.lowercase()
            if ("/default/" in path && IMAGE_EXTS.any { lower.endsWith(it) }) return Html.absolute(base, path)
        }
        val og = OG_IMAGE_RE.find(html) ?: return null
        return Html.absolute(base, og.groupValues[1])
    }

    private fun parseDownloads(html: String, entry: RomEntry): List<RomFile> {
        val platformTitles = PLATFORM_TITLE_RE.findAllFast(html)
            .map { it.range.first to Html.collapseWs(Html.stripTags(it.groupValues[1])) }
            .toList()
        val files = mutableListOf<RomFile>()
        for ((index, m) in DOWNLOAD_RE.findAllFast(html).withIndex()) {
            val tag = m.value
            val href = HREF_RE.find(tag) ?: continue
            // data-file-size is the archive size in bytes.
            val size = SIZE_RE.find(tag)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()
            var platform = ""
            for ((pos, label) in platformTitles) {
                if (pos < m.range.first) platform = label.replace(" version", "").trim() else break
            }
            files += RomFile(
                id = "${entry.sourceId}-$index",
                filename = "",
                url = Html.absolute(base, href.groupValues[1]),
                size = size,
                label = platform.ifEmpty { null },
                sourceUrl = entry.url,
            )
        }
        return files
    }

    /**
     * Turn /download/<id>-<slug> into the real archive URL.
     *
     * MyAbandonware only hands out the file after a session cookie has been
     * established on the game page, so we fetch that page first and then let
     * the redirect point at the archive.
     */
    override suspend fun resolveDownload(file: RomFile): String {
        val sourceUrl = file.sourceUrl
        if (sourceUrl.isNullOrEmpty()) return file.url
        val headers = mapOf("Referer" to sourceUrl)
        http.getText(sourceUrl, headers)
        return http.resolveRedirect(file.url, headers)
    }

    companion object {
        const val DEFAULT_TS_HOST = "ts.myabandonware.com"

        private val KEY_RE = Regex("""id="tsKey"\s+value="([^"]+)"""")
        private val SERVER_RE = Regex("""tsServer\s*=\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL)
        private val DOWNLOAD_RE = Regex("""<a\b[^>]*class="[^"]*game-downloads__platform-download[^"]*"[^>]*>""", RegexOption.IGNORE_CASE)
        private val PLATFORM_TITLE_RE = Regex(
            """<h3[^>]*class="game-downloads__platforms-title"[^>]*>(.*?)</h3>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val HREF_RE = Regex("""href="([^"]+)"""", RegexOption.IGNORE_CASE)
        private val SIZE_RE = Regex("""data-file-size="([\d.]+)"""", RegexOption.IGNORE_CASE)
        private val GALLERY_RE = Regex("""<a class="game-screenshots__gallery-thumbnail[^"]*" href="([^"]+)"""")
        private val BOXART_RE = Regex("""data-source="(/media/img/[^"]+)"""")
        private val OG_IMAGE_RE = Regex("""<meta[^>]+property="og:image"[^>]+content="([^"]+)"""", RegexOption.IGNORE_CASE)
        private val IMAGE_EXTS = listOf(".jpg", ".png", ".jpeg", ".webp")

        private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

        /** Scoped TypeSense keys embed their expiry in the payload. */
        internal fun keyExpiry(key: String): Double {
            return try {
                val padded = key + "=".repeat((4 - key.length % 4) % 4)
                val raw = String(Base64.getMimeDecoder().decode(padded), Charsets.UTF_8)
                val start = raw.indexOf('{')
                if (start == -1) return nowSeconds() + 900
                val payload = Json.parseToJsonElement(raw.substring(start)) as JsonObject
                val expires = payload["expires_at"]
                if (truthy(expires)) pyStr(expires).toDouble() else nowSeconds() + 900
            } catch (_: Exception) {
                nowSeconds() + 900
            }
        }

        /** ", ".join(names) or None. */
        private fun joinNames(value: JsonElement?): String? {
            val list = (value as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return null
            return list.joinToString(", ") { pyStr(it) }.ifEmpty { null }
        }

        /** (doc.get(k) or "") as text. */
        private fun strOf(value: JsonElement?): String = if (truthy(value)) pyStr(value) else ""

        /** str(value) for a JSON scalar (strings unquoted). */
        private fun pyStr(value: JsonElement?): String = when (value) {
            null, is JsonNull -> "None"
            is JsonPrimitive -> when {
                value.isString -> value.content
                value.booleanOrNull == true -> "True"
                value.booleanOrNull == false -> "False"
                else -> value.content
            }
            else -> value.toString()
        }

        /** Python truthiness of a JSON value. */
        private fun truthy(value: JsonElement?): Boolean = when (value) {
            null, is JsonNull -> false
            is JsonObject -> value.isNotEmpty()
            is JsonArray -> value.isNotEmpty()
            is JsonPrimitive -> when {
                value.isString -> value.content.isNotEmpty()
                value.booleanOrNull != null -> value.booleanOrNull!!
                else -> value.doubleOrNull?.let { it != 0.0 } ?: false
            }
        }

        /** json.dumps(value) with Python's default separators and ensure_ascii. */
        internal fun pyDumps(value: Any?): String = buildString { dump(value, this) }

        private fun dump(value: Any?, out: StringBuilder) {
            when (value) {
                null -> out.append("null")
                is Boolean -> out.append(if (value) "true" else "false")
                is Int, is Long -> out.append(value.toString())
                is String -> {
                    out.append('"')
                    for (c in value) {
                        when {
                            c == '"' -> out.append("\\\"")
                            c == '\\' -> out.append("\\\\")
                            c == '\n' -> out.append("\\n")
                            c == '\r' -> out.append("\\r")
                            c == '\t' -> out.append("\\t")
                            c == '\b' -> out.append("\\b")
                            c == '\u000c' -> out.append("\\f")
                            c.code < 0x20 || c.code > 0x7f -> out.append(String.format("\\u%04x", c.code))
                            else -> out.append(c)
                        }
                    }
                    out.append('"')
                }
                is Map<*, *> -> {
                    out.append('{')
                    var first = true
                    for ((k, v) in value) {
                        if (!first) out.append(", ")
                        first = false
                        dump(k.toString(), out)
                        out.append(": ")
                        dump(v, out)
                    }
                    out.append('}')
                }
                is List<*> -> {
                    out.append('[')
                    value.forEachIndexed { i, v ->
                        if (i > 0) out.append(", ")
                        dump(v, out)
                    }
                    out.append(']')
                }
                else -> dump(value.toString(), out)
            }
        }
    }
}
