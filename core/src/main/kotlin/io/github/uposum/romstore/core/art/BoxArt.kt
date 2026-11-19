package io.github.uposum.romstore.core.art

import io.github.uposum.romstore.core.net.findAllFast

import io.github.uposum.romstore.core.model.LibraryGame
import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.extractRegion
import io.github.uposum.romstore.core.model.matchTitle
import io.github.uposum.romstore.core.model.putStr
import io.github.uposum.romstore.core.net.Html
import io.github.uposum.romstore.core.net.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Box art from libretro-thumbnails (RetroArch's box-art library), a port of
 * the plugin's boxart.py.
 *
 * The server lists each console's Named_Boxarts folder, so one request per
 * console gives the whole index. It is cached for two weeks, built in the
 * background when a console shows up for the first time, and matched against
 * the store's titles by matchTitle(). The grid gets a small WebP from the
 * resizing proxy wsrv.nl; the poster loads the full scan.
 */
class BoxArt(
    private val http: HttpClient,
    cacheDir: File? = null,
    private val scope: CoroutineScope = defaultArtScope(),
) {
    private val dir = cacheDir?.let { File(it, "boxart") }
    private val index = ConcurrentHashMap<String, Map<String, List<String>>>()
    private val loadedAt = ConcurrentHashMap<String, Double>()
    private val building = HashMap<String, Job>()

    internal var clock: () -> Double = ::nowSeconds

    // ------------------------------------------------------------------ index
    private fun path(system: String): File? = dir?.let { File(it, "$system.json") }

    private fun load(system: String): Boolean {
        if (index.containsKey(system)) return true
        val data = JsonFiles.read(path(system)).obj() ?: return false
        if ((data["v"] as? JsonPrimitive)?.intOrNull != INDEX_VERSION) return false // older format: fetched again
        val names = data["names"].arr()?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        index[system] = group(names)
        loadedAt[system] = (data["at"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        return true
    }

    /** Use these scan names as a console's index (tests, or an index kept elsewhere). */
    internal fun setIndex(system: String, names: Iterable<String>, at: Double = clock()) {
        index[system] = group(names)
        loadedAt[system] = at
    }

    private suspend fun fetch(system: String) {
        val playlist = SYSTEMS[system] ?: return
        orNull {
            // One plain directory listing per console (PS1: ~2.5 MB, 9000 names).
            val resp = http.request(
                "$BASE/${Html.quote(playlist)}/Named_Boxarts/", polite = false, maxBytes = 24 * 1024 * 1024,
            )
            val names = scanNames(resp.text).sorted()
            if (names.isEmpty()) return@orNull
            index[system] = group(names)
            val now = clock()
            loadedAt[system] = now
            JsonFiles.write(
                path(system),
                JsonObject(
                    mapOf(
                        "at" to JsonPrimitive(now),
                        "v" to JsonPrimitive(INDEX_VERSION),
                        "names" to JsonArray(names.map { JsonPrimitive(it) }),
                    ),
                ),
            )
        }
    }

    /** Start building a console's index if it is missing or stale. */
    private fun ensure(system: String): Job? {
        if (system !in SYSTEMS) return null
        if (index.containsKey(system) && clock() - (loadedAt[system] ?: 0.0) < INDEX_TTL) return null
        synchronized(building) {
            val running = building[system]
            if (running != null && !running.isCompleted) return running
            // Reading a stored index (thousands of names) is work too: it
            // runs here, inside the search's time limit, not before it.
            val job = scope.launch {
                val loaded = load(system)
                if (!loaded || clock() - (loadedAt[system] ?: 0.0) >= INDEX_TTL) fetch(system)
            }
            building[system] = job
            return job
        }
    }


    /** Whether a console's index is in memory. */
    fun hasIndex(system: String): Boolean = index.containsKey(system)

    // ------------------------------------------------------------------ matching
    /** Full-size scan URL for a game, or null. */
    fun find(title: String, system: String, region: String? = null): String? {
        val names = index[system]?.get(matchTitle(title))
        if (names.isNullOrEmpty()) return null
        val wanted = region?.takeIf { it.isNotEmpty() } ?: extractRegion(title)
        val best = names.minWithOrNull(scoreComparator(wanted)) ?: return null
        return fullUrl(SYSTEMS.getValue(system), best)
    }

    /** The game's name as the matched scan has it, without tags. */
    fun name(title: String, system: String): String? {
        val names = index[system]?.get(matchTitle(title))
        if (names.isNullOrEmpty()) return null
        val best = names.minWithOrNull(scoreComparator(extractRegion(title))) ?: return null
        val bare = TAG_STRIP_RE.replace(stripPng(best), "").trim()
        // libretro writes "Title, The" and ":" as " - ".
        return ARTICLE_RE.replace(bare, "$2 $1").ifEmpty { null }
    }

    /**
     * Box art for installed games: sets `cover` where a scan matches the file's
     * name. A console without an index gets one built; the list waits `wait`
     * seconds for it at most and has the art the next time otherwise.
     */
    suspend fun covers(games: List<LibraryGame>, resize: Boolean = true, wait: Double = 2.0) {
        val systems = games.map { it.system }.filter { it in SYSTEMS }.toSet()
        val pending = withContext(Dispatchers.IO) { systems.mapNotNull { ensure(it) } }
        waitFor(pending, wait)
        for (game in games) {
            if (!index.containsKey(game.system)) continue
            val url = find(game.title, game.system) ?: continue
            game.cover = if (resize) thumbUrl(url) else url
        }
    }

    /**
     * Give entries libretro box art where there is a match. A console seen for
     * the first time has its index fetched; the first search waits up to
     * `wait` seconds for it, after that it finishes in the background and the
     * next search has the art.
     */
    suspend fun apply(entries: List<RomEntry>, resize: Boolean = true, wait: Double = 4.0) {
        val systems = entries.map { it.system }.filter { it in SYSTEMS }.toSet()
        val pending = withContext(Dispatchers.IO) { systems.mapNotNull { ensure(it) } }
        waitFor(pending, wait)
        for (entry in entries) {
            if (!index.containsKey(entry.system)) continue
            val url = find(entry.title, entry.system, entry.region) ?: continue
            entry.extra.putStr("boxart", url)
            entry.coverFull = url
            if (resize) entry.cover = thumbUrl(url)
        }
    }

    companion object {
        const val BASE = "https://thumbnails.libretro.com"
        const val RESIZER = "https://wsrv.nl/"
        const val INDEX_TTL = 14 * 24 * 60 * 60.0

        /** Bumped when the cached index needs building again (2: link files left out). */
        const val INDEX_VERSION = 2

        /** Store console key -> libretro playlist name. Switch is not in libretro (GameTdb). */
        val SYSTEMS: Map<String, String> = linkedMapOf(
            "nes" to "Nintendo - Nintendo Entertainment System",
            "snes" to "Nintendo - Super Nintendo Entertainment System",
            "n64" to "Nintendo - Nintendo 64",
            "gc" to "Nintendo - GameCube",
            "wii" to "Nintendo - Wii",
            "gb" to "Nintendo - Game Boy",
            "gbc" to "Nintendo - Game Boy Color",
            "gba" to "Nintendo - Game Boy Advance",
            "nds" to "Nintendo - Nintendo DS",
            "3ds" to "Nintendo - Nintendo 3DS",
            "virtualboy" to "Nintendo - Virtual Boy",
            "pokemini" to "Nintendo - Pokemon Mini",
            "sg1000" to "Sega - SG-1000",
            "mastersystem" to "Sega - Master System - Mark III",
            "genesis" to "Sega - Mega Drive - Genesis",
            "segacd" to "Sega - Mega-CD - Sega CD",
            "sega32x" to "Sega - 32X",
            "saturn" to "Sega - Saturn",
            "dreamcast" to "Sega - Dreamcast",
            "gamegear" to "Sega - Game Gear",
            "psx" to "Sony - PlayStation",
            "ps2" to "Sony - PlayStation 2",
            "psp" to "Sony - PlayStation Portable",
            "atari2600" to "Atari - 2600",
            "atari5200" to "Atari - 5200",
            "atari7800" to "Atari - 7800",
            "atarilynx" to "Atari - Lynx",
            "jaguar" to "Atari - Jaguar",
            "pcengine" to "NEC - PC Engine - TurboGrafx 16",
            "pcenginecd" to "NEC - PC Engine CD - TurboGrafx-CD",
            "ngp" to "SNK - Neo Geo Pocket",
            "ngpc" to "SNK - Neo Geo Pocket Color",
            "wonderswan" to "Bandai - WonderSwan",
            "wonderswan-color" to "Bandai - WonderSwan Color",
            "3do" to "The 3DO Company - 3DO",
            "colecovision" to "Coleco - ColecoVision",
            "intellivision" to "Mattel - Intellivision",
            "vectrex" to "GCE - Vectrex",
            "msx" to "Microsoft - MSX",
            "c64" to "Commodore - 64",
            "amiga" to "Commodore - Amiga",
            "dos" to "DOS",
            "wiiu" to "Nintendo - Wii U",
            "ps3" to "Sony - PlayStation 3",
            "psvita" to "Sony - PlayStation Vita",
            "atarist" to "Atari - ST",
            "atari800" to "Atari - 8-bit",
            "amstradcpc" to "Amstrad - CPC",
            "zxspectrum" to "Sinclair - ZX Spectrum",
            "vic20" to "Commodore - VIC-20",
            "videopac" to "Magnavox - Odyssey2",
            "x68000" to "Sharp - X68000",
            "sharp-x1" to "Sharp - X1",
            "x1" to "Sharp - X1",
            "pc98" to "NEC - PC-98",
            "pcfx" to "NEC - PC-FX",
            "neogeo" to "SNK - Neo Geo",
            "neogeoaes" to "SNK - Neo Geo",
            "neogeocd" to "SNK - Neo Geo CD",
            "scummvm" to "ScummVM",
            "easyrpg" to "RPG Maker",
            "arcades" to "FBNeo - Arcade Games",
            "fba" to "FBNeo - Arcade Games",
            "mame" to "MAME",
        )

        private val HREF_RE = Regex("""href="([^"?/][^"]*\.png)"""", RegexOption.IGNORE_CASE)

        // A listing row: the file, then its date and size cells.
        private val ROW_RE = Regex(
            """href="([^"?/][^"]*\.png)".*?<td[^>]*>[^<]*</td>\s*<td[^>]*>\s*([0-9.]+[KMG]?)\s*</td>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val TAGS_RE = Regex("""\(([^)]*)\)""")
        private val TAG_STRIP_RE = Regex("""\s*\([^)]*\)|\s*\[[^\]]*]""")
        private val ARTICLE_RE = Regex("""^(.+?), (The|A|An)\b""")

        // Builds nobody wants as the picture for a game.
        private val UNWANTED = listOf("beta", "proto", "demo", "sample", "kiosk", "pirate", "unl", "hack", "virtual console")
        private val REGION_ORDER = listOf("usa", "world", "europe", "japan")

        /**
         * The scans in a Named_Boxarts listing, without link files.
         *
         * libretro-thumbnails keeps a duplicate name as a git symlink, which the
         * server hands out as a 19-byte text file holding the real name. It is
         * not an image, so names under 1 kB are left out; the real scan they
         * point to is in the listing under its own name.
         */
        fun scanNames(html: String): Set<String> {
            val sized = ROW_RE.findAllFast(html).toList()
            if (sized.isNotEmpty()) {
                val names = LinkedHashSet<String>()
                for (m in sized) {
                    val href = m.groupValues[1]
                    val size = m.groupValues[2]
                    val unit = size.takeLast(1).uppercase()
                    if (unit == "K" || unit == "M" || unit == "G" || (size.toDoubleOrNull() ?: 0.0) >= 1024) {
                        names += Html.unescape(Html.unquote(href))
                    }
                }
                return names
            }
            return HREF_RE.findAllFast(html).map { Html.unescape(Html.unquote(it.groupValues[1])) }.toCollection(LinkedHashSet())
        }

        fun fullUrl(systemName: String, filename: String): String =
            "$BASE/${Html.quote(systemName)}/Named_Boxarts/${Html.quote(filename)}"

        /** A small WebP of a scan, through the resizing proxy. */
        fun thumbUrl(url: String, width: Int = 320): String {
            val bare = url.substringAfter("://")
            return "$RESIZER?url=${Html.quote(bare, safe = "")}&w=$width&h=$width&fit=inside&output=webp&q=82"
        }

        private fun stripPng(name: String): String = if (name.lowercase().endsWith(".png")) name.dropLast(4) else name

        internal fun group(names: Iterable<String>): Map<String, List<String>> {
            val grouped = LinkedHashMap<String, MutableList<String>>()
            for (name in names) {
                val key = matchTitle(stripPng(name))
                if (key.isNotEmpty()) grouped.getOrPut(key) { mutableListOf() }.add(name)
            }
            return grouped
        }

        /**
         * Lower is better: the entry's own region, then USA/World/Europe/Japan,
         * then plain releases over revisions and special builds.
         */
        internal fun score(name: String, region: String?): List<Int> {
            val tags = TAGS_RE.findAllFast(name).joinToString(" ") { it.groupValues[1] }.lowercase()
            val unwanted = UNWANTED.any { it in tags }
            val wanted = (region ?: "").lowercase()
            val own = if (wanted.isNotEmpty() && wanted in tags) 0 else 1
            val order = REGION_ORDER.indexOfFirst { it in tags }.let { if (it < 0) REGION_ORDER.size else it }
            return listOf(if (unwanted) 1 else 0, own, order, if ("rev" in tags) 1 else 0, name.length)
        }

        private fun scoreComparator(region: String?): Comparator<String> = Comparator { a, b ->
            val sa = score(a, region)
            val sb = score(b, region)
            sa.indices.firstNotNullOfOrNull { i -> sa[i].compareTo(sb[i]).takeIf { it != 0 } } ?: 0
        }
    }
}
