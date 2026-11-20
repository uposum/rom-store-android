package io.github.uposum.romstore.core.sources

import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.RomFile
import io.github.uposum.romstore.core.model.extractRegion
import io.github.uposum.romstore.core.model.normalizeTitle
import io.github.uposum.romstore.core.model.putFlag
import io.github.uposum.romstore.core.net.Html
import io.github.uposum.romstore.core.net.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The Internet Archive: items people uploaded, found through its public
 * search API and read through its metadata API, downloaded straight from its
 * file servers (fast, no checks, resumable).
 *
 *     <address>/advancedsearch.php?q=title:(<words>) AND mediatype:(software OR data)&fl[]=identifier&...&output=json
 *     <address>/metadata/<identifier>     files with name, size, private flag
 *     <address>/download/<identifier>/<file>
 *
 * Items are whatever someone uploaded: a single game, a game with its update
 * and DLC, an update on its own, or a collection. The console comes from the
 * file type (`.nsp` is Switch) or, for disc images, from the item's title,
 * subjects and collections; an item whose console cannot be told is left out.
 * Items or files the Archive restricts (lending, dark, private) are never
 * offered.
 */
class ArchiveOrgSource(http: HttpClient, options: SourceOptions) : Source(http, options) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun search(query: String, limit: Int, page: Int): List<RomEntry> {
        val words = queryWords(query)
        if (words.isEmpty()) return emptyList()
        val rows = limit.coerceIn(10, 50)
        val q = "title:(${words.joinToString(" AND ")}) AND mediatype:(software OR data)"
        val url = "$base/advancedsearch.php?q=${Html.quotePlus(q)}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=downloads&rows=$rows&page=${maxOf(1, page)}&output=json"
        val data = json.parseToJsonElement(http.request(url, polite = false).text.ifEmpty { "{}" }) as? JsonObject ?: return emptyList()
        val response = data["response"] as? JsonObject ?: return emptyList()
        val found = (response["numFound"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        lastPageCount = maxOf(1, (found + rows - 1) / rows)
        val docs = (response["docs"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return emptyList()
        // The item's files say what it is; read the first ones (the API is quick).
        val gate = Semaphore(6)
        val items = coroutineScope {
            docs.take(MAX_ITEMS).map { doc ->
                async {
                    val id = str(doc["identifier"]) ?: return@async emptyList()
                    gate.withPermit {
                        withTimeoutOrNull(8_000) { runCatching { item(id, str(doc["title"]) ?: id, num(doc["downloads"]), words) }.getOrNull() }
                    } ?: emptyList()
                }
            }.awaitAll()
        }
        return items.flatten().take(limit)
    }

    override suspend fun resolve(entry: RomEntry): List<RomFile> = entry.files

    /** One item: its game files, as one entry (an upload of one game) or one per file (a collection). */
    private suspend fun item(id: String, title: String, downloads: Long?, words: List<String>): List<RomEntry> {
        val text = http.request("$base/metadata/${Html.quote(id, safe = "")}", polite = false, maxBytes = 6 * 1024 * 1024).text
        val data = json.parseToJsonElement(text.ifEmpty { "{}" }) as? JsonObject ?: return emptyList()
        val meta = data["metadata"] as? JsonObject ?: return emptyList()
        if (truthy(meta["access-restricted-item"]) || truthy(data["is_dark"])) return emptyList()
        if (NOT_A_GAME.containsMatchIn(title) || all(meta["collection"]).contains("thingiverse")) return emptyList()
        val files = (data["files"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return emptyList()
        val games = files.mapNotNull { f ->
            val name = str(f["name"]) ?: return@mapNotNull null
            if (truthy(f["private"])) return@mapNotNull null
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in GAME_EXTENSIONS || name.contains("/__ia_thumb") || NOT_A_GAME_FILE.containsMatchIn(name)) return@mapNotNull null
            Triple(name, ext, str(f["size"])?.toLongOrNull())
        }
        if (games.isEmpty()) return emptyList()
        val context = listOf(title, all(meta["subject"]), all(meta["collection"]), str(meta["description"])?.take(400) ?: "").joinToString(" ")
        val year = (str(meta["year"]) ?: str(meta["date"]))?.take(4)?.toIntOrNull()
        val page = "$base/details/$id"

        fun file(name: String, size: Long?) = RomFile(
            id = "$id/$name",
            filename = name.substringAfterLast('/'),
            url = "$base/download/${Html.quote(id, safe = "")}/${Html.quote(name)}",
            size = size,
            region = extractRegion(name),
            label = name.substringAfterLast('/'),
            sourceUrl = page,
        )

        if (games.size <= SINGLE_UPLOAD) {
            val system = games.firstNotNullOfOrNull { systemFor(it.second, it.first + " " + context) } ?: return emptyList()
            // One file whose name is the item's title minus noise ("Mario Kart 8 Deluxe abc" → its "Mario Kart 8 Deluxe.nsp"): the file's name.
            val only = games.singleOrNull()?.first?.substringAfterLast('/')?.substringBeforeLast('.')
            val titleWords = normalizeTitle(cleanTitle(title)).split(' ').filter { it.isNotEmpty() }.toSet()
            val onlyWords = only?.let { normalizeTitle(cleanTitle(it)).split(' ').filter { w -> w.isNotEmpty() } }.orEmpty()
            val name = if (only != null && onlyWords.size >= 2 && titleWords.containsAll(onlyWords)) cleanTitle(only) else cleanTitle(title)
            val entry = RomEntry(
                source = id(), sourceId = id, title = name, system = system, url = page,
                downloads = downloads, year = year, region = extractRegion(title),
            )
            entry.extra.putFlag("fileNamed")
            entry.files = games.filter { systemFor(it.second, it.first + " " + context) == system }.map { file(it.first, it.third) }
            entry.size = entry.files.firstNotNullOfOrNull { it.size }
            return listOf(entry)
        }
        // A collection: only the files that are the game asked for.
        return games.filter { (name, _, _) ->
            val n = normalizeTitle(name.substringAfterLast('/'))
            words.all { it.lowercase() in n }
        }.take(30).mapNotNull { (name, ext, size) ->
            val system = systemFor(ext, name + " " + context) ?: return@mapNotNull null
            val bare = name.substringAfterLast('/').substringBeforeLast('.')
            RomEntry(
                source = id(), sourceId = "$id/$name", title = cleanTitle(bare), system = system, url = page,
                year = year, region = extractRegion(bare), size = size,
            ).also { it.files = listOf(file(name, size)); it.extra.putFlag("fileNamed") }
        }
    }

    private fun id() = options.sourceId

    companion object {
        private const val MAX_ITEMS = 16
        /** More game files than this is a collection, not one game's upload. */
        private const val SINGLE_UPLOAD = 12

        /** File types that are games (archives count: most uploads are zipped). */
        val GAME_EXTENSIONS = setOf(
            "nsp", "nsz", "xci", "xcz", "wua", "wux", "rpx", "rvz", "wbfs", "gcm", "gcz", "ciso",
            "cia", "3ds", "nds", "gba", "gbc", "gb", "nes", "sfc", "smc", "n64", "z64", "v64",
            "cso", "pbp", "iso", "chd", "cue", "gdi", "cdi", "pkg", "vpk", "gen", "sms", "gg", "pce",
            "zip", "7z", "rar",
        )

        /** Uploads that are not a game: mods, patches, demos, saves, art. */
        private val NOT_A_GAME = Regex(
            """\b(mods?|modded|patch(es)?|cheats?|saves?|save ?data|shaders?|textures?|logo|soundtrack|ost|manual|wallpapers?|fake|leak|demo|kiosk|trainer|translation|romhack|hack)\b""",
            RegexOption.IGNORE_CASE,
        )

        /** Files that ride along in game uploads and are never offered: firmware, keys, emulators, fonts. */
        private val NOT_A_GAME_FILE = Regex(
            """(firmware|\bkeys?\b|prod\.keys|title\.keys|\bbios\b|shared.?fonts|flatpak|appimage|\b(yuzu|ryujinx|eden|citron|sudachi|suyu|torzu|sumi)\b)""",
            RegexOption.IGNORE_CASE,
        )

        /** The extensions that name their console. */
        private val BY_EXTENSION = mapOf(
            "nsp" to "switch", "nsz" to "switch", "xci" to "switch", "xcz" to "switch",
            "wua" to "wiiu", "wux" to "wiiu", "rpx" to "wiiu",
            "wbfs" to "wii", "gcm" to "gc", "gcz" to "gc",
            "cia" to "3ds", "3ds" to "3ds", "nds" to "nds",
            "gba" to "gba", "gbc" to "gbc", "gb" to "gb", "nes" to "nes", "sfc" to "snes", "smc" to "snes",
            "n64" to "n64", "z64" to "n64", "v64" to "n64", "cso" to "psp", "pbp" to "psp", "vpk" to "psvita",
            "gdi" to "dreamcast", "cdi" to "dreamcast", "gen" to "genesis",
            "sms" to "mastersystem", "gg" to "gamegear", "pce" to "pcengine",
        )

        /** Words that name a console, most specific first ("wii u" before "wii"). */
        private val KEYWORDS: List<Pair<Regex, String>> = listOf(
            """\b(nintendo switch|switch|nsp|xci)\b""" to "switch",
            """\b(wii ?u)\b""" to "wiiu",
            """\b(wii)\b""" to "wii",
            """\b(gamecube|game cube|ngc)\b""" to "gc",
            """\b(3ds)\b""" to "3ds",
            """\b(nintendo ds|nds)\b""" to "nds",
            """\b(ps ?vita|playstation vita)\b""" to "psvita",
            """\b(ps3|playstation 3)\b""" to "ps3",
            """\b(ps2|playstation 2)\b""" to "ps2",
            """\b(psp|playstation portable)\b""" to "psp",
            """\b(ps1|psx|psone|playstation 1|playstation)\b""" to "psx",
            """\b(dreamcast)\b""" to "dreamcast",
            """\b(sega saturn|saturn)\b""" to "saturn",
            """\b(game boy advance|gba)\b""" to "gba",
            """\b(nintendo 64|n64)\b""" to "n64",
            """\b(super nintendo|snes|super famicom)\b""" to "snes",
            """\b(genesis|mega drive)\b""" to "genesis",
        ).map { (pattern, system) -> Regex(pattern, RegexOption.IGNORE_CASE) to system }

        fun systemFor(ext: String, text: String): String? {
            BY_EXTENSION[ext]?.let { return it }
            val spaced = text.replace('_', ' ').replace('-', ' ')
            return KEYWORDS.firstOrNull { it.first.containsMatchIn(spaced) }?.second
        }

        /** The words a title must contain; "update"/"dlc" are dropped (uploads say "UPD", "v3.0.4"). */
        fun queryWords(query: String): List<String> =
            normalizeTitle(query).split(' ')
                .filter { it.isNotEmpty() && it !in setOf("update", "updates", "dlc", "the", "and", "of") }
                .map { it.filter { c -> c.isLetterOrDigit() } }
                .filter { it.isNotEmpty() }
                .take(8)

        /** "[ PC Game ] Name V 3.0.4[ 0100…][ UPD]" keeps its tags (they say what it is) but loses the spacing noise. */
        private val DOTTED = Regex("""(\w\.\s){2,}""")

        fun cleanTitle(title: String): String =
            (if (DOTTED.containsMatchIn(title)) title.replace(". ", " ") else title).replace('_', ' ').replace(Regex("""\[\s+"""), "[").replace(Regex("""\s+\]"""), "]")
                .replace(Regex("""\s{2,}"""), " ").trim()

        private fun str(e: JsonElement?): String? = when (e) {
            is JsonPrimitive -> e.contentOrNull
            is JsonArray -> (e.firstOrNull() as? JsonPrimitive)?.contentOrNull
            else -> null
        }

        private fun all(e: JsonElement?): String = when (e) {
            is JsonPrimitive -> e.contentOrNull ?: ""
            is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(" ")
            else -> ""
        }

        private fun num(e: JsonElement?): Long? = str(e)?.toDoubleOrNull()?.toLong()

        private fun truthy(e: JsonElement?): Boolean = str(e)?.lowercase() in setOf("true", "1", "yes")
    }
}
