package io.github.uposum.romstore.core.sources

import io.github.uposum.romstore.core.model.GameMedia
import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.RomFile
import io.github.uposum.romstore.core.model.Screenshot
import io.github.uposum.romstore.core.model.putFlag
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

/**
 * Homebrew Hub (port of backend/sources/homebrewhub.py): new games for old
 * consoles, free to download.
 *
 * The gbdev community's archive of homebrew for Game Boy, Game Boy Color, Game
 * Boy Advance and NES: games made for those consoles today, published by their
 * authors to be played. Everything comes from its JSON API:
 *
 *     https://hh3.gbdev.io/api/search?q=<words>&typetag=game&results=40
 *     https://hh3.gbdev.io/api/search?platform=GB&typetag=game&results=100&page=2
 *
 * Each entry lists its files (`files/orion-gb-v1.2.gb`, the playable one
 * marked `default`) and screenshots, served from
 *
 *     https://hh3.gbdev.io/static/<basepath>/entries/<slug>/<file>
 */
class HomebrewHubSource(http: HttpClient, options: SourceOptions) : Source(http, options) {
    override val supportsBrowse: Boolean = true

    /** Homebrew has no Final Fantasy; words its catalogue is full of instead. */
    val speedTestTerms: List<String> = listOf("space", "dragon", "quest")

    override fun browseSystems(): Set<String> = SYSTEM_PLATFORMS.keys

    private suspend fun api(query: String): JsonObject {
        val resp = http.request("$base/api/search?$query", polite = false)
        val data = Json.parseToJsonElement(resp.text.ifEmpty { "{}" })
        return data as? JsonObject ?: JsonObject(emptyMap())
    }

    // ------------------------------------------------------------------ search
    override suspend fun search(query: String, limit: Int, page: Int): List<RomEntry> {
        val data = api("q=${Html.quotePlus(query)}&typetag=game&results=${limit.coerceIn(1, PAGE)}&page=${maxOf(1, page)}")
        return entries(data).take(limit)
    }

    /** Python's default limit here is 200; Kotlin keeps the contract's 40. */
    override suspend fun browse(system: String, limit: Int): List<RomEntry> {
        val platform = SYSTEM_PLATFORMS[system] ?: return emptyList()
        val entries = mutableListOf<RomEntry>()
        var page = 1
        while (entries.size < limit) {
            val data = api("platform=$platform&typetag=game&results=$PAGE&page=$page")
            val found = entries(data)
            entries += found
            val total = data["page_total"].let { if (truthy(it)) pyStr(it).toDoubleOrNull()?.toInt() ?: 1 else 1 }
            if (found.isEmpty() || page >= total) break
            page += 1
        }
        return entries.take(limit)
    }

    // ------------------------------------------------------------------ parse
    private fun static(item: JsonObject, path: String): String {
        val basepath = if (truthy(item["basepath"])) pyStr(item["basepath"]) else "database-gb"
        // Slugs and file names can hold spaces, "#" or "?": quoted, so the URL names the file.
        return "$base/static/$basepath/entries/${Html.quote(pyStr(item["slug"]), safe = "")}/${Html.quote(path)}"
    }

    private fun entries(data: JsonObject): List<RomEntry> {
        val entries = mutableListOf<RomEntry>()
        val items = data["entries"].takeIf { truthy(it) } as? JsonArray ?: return entries
        for (element in items) {
            val item = element as? JsonObject ?: continue
            if (!truthy(item["slug"]) || !truthy(item["title"])) continue
            val platform = if (truthy(item["platform"])) pyStr(item["platform"]) else ""
            val system = PLATFORMS[platform.uppercase()] ?: continue
            val files = files(item)
            if (files.isEmpty()) continue // nothing to play (a tool, a demo without a ROM)
            val shots = (item["screenshots"] as? JsonArray).orEmpty()
                .filter { it is JsonPrimitive && it.isString }
                .map { static(item, (it as JsonPrimitive).content) }
            val date = if (truthy(item["date"])) pyStr(item["date"]) else ""
            val head = date.take(4)
            val year = if (head.isNotEmpty() && head.all { it.isDigit() }) head.toInt() else null
            val slug = pyStr(item["slug"])
            val description = item["description"]
            val tags = (item["tags"] as? JsonArray).orEmpty().take(3)
            val entry = RomEntry(
                source = id,
                sourceId = slug,
                title = pyStr(item["title"]).trim(),
                system = system,
                url = gamePage(slug),
                cover = shots.firstOrNull(),
                coverFull = shots.firstOrNull(),
                summary = if (truthy(description)) pyStr(description) else null,
                year = year,
                developer = developer(item["developer"]),
                genres = tags.filter { it is JsonPrimitive && it.isString }
                    .map { pyTitle((it as JsonPrimitive).content.replace("-", " ")) },
                files = files,
            )
            entry.extra.putFlag("homebrew", true)
            entry.extra["shots"] = JsonArray(shots.take(12).map { JsonPrimitive(it) })
            entries += entry
        }
        return entries
    }

    private fun files(item: JsonObject): List<RomFile> {
        val playable = (item["files"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { truthy(it["playable"]) && truthy(it["filename"]) }
            // The author's default build first, then the others (older versions).
            .sortedBy { !truthy(it["default"]) }
        val slug = pyStr(item["slug"])
        return playable.mapIndexed { index, file ->
            val path = pyStr(file["filename"])
            RomFile(
                id = "$slug-$index",
                filename = path.substringAfterLast('/'),
                url = static(item, path),
                label = if (truthy(file["version"])) "v${pyStr(file["version"])}" else null,
                sourceUrl = gamePage(slug),
            )
        }
    }

    // The API answer already carries the files.
    override suspend fun resolve(entry: RomEntry): List<RomFile> = entry.files.toList()

    override suspend fun media(entry: RomEntry): GameMedia? {
        val shots = (entry.extra["shots"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content }
        if (shots.isEmpty()) return null
        // homebrewhub.py also returns "caption": entry.title; GameMedia has none.
        return GameMedia(shots = shots.map { Screenshot(thumb = it, full = it) })
    }

    companion object {
        const val GAME_PAGE = "https://hh.gbdev.io/game/"
        const val PAGE = 100

        /** Homebrew Hub platform -> store console key. */
        val PLATFORMS: Map<String, String> = linkedMapOf("GB" to "gb", "GBC" to "gbc", "GBA" to "gba", "NES" to "nes")
        val SYSTEM_PLATFORMS: Map<String, String> = LinkedHashMap<String, String>().also { map ->
            for ((platform, key) in PLATFORMS) map[key] = platform
        }

        fun gamePage(slug: String): String = GAME_PAGE + slug

        private fun developer(value: JsonElement?): String? = when (value) {
            is JsonArray -> value
                .map { if (it is JsonObject) it["name"] else it }
                .filter { truthy(it) }
                .joinToString(", ") { pyStr(it) }
                .ifEmpty { null }
            is JsonObject -> if (truthy(value["name"])) pyStr(value["name"]) else null
            else -> if (truthy(value)) pyStr(value) else null
        }

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

        private fun isCased(c: Char) = Character.isUpperCase(c) || Character.isLowerCase(c) || Character.isTitleCase(c)

        /** Python's str.title(). */
        private fun pyTitle(text: String): String = buildString {
            var prevCased = false
            for (c in text) {
                if (isCased(c)) {
                    append(if (prevCased) c.lowercaseChar() else c.titlecaseChar())
                    prevCased = true
                } else {
                    append(c)
                    prevCased = false
                }
            }
        }
    }
}
