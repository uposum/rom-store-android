package io.github.uposum.romstore.core.sources

import io.github.uposum.romstore.core.net.findAllFast

import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.RomFile
import io.github.uposum.romstore.core.model.extractRegion
import io.github.uposum.romstore.core.model.putStr
import io.github.uposum.romstore.core.model.str
import io.github.uposum.romstore.core.net.Html
import io.github.uposum.romstore.core.net.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * RomsFun (port of backend/sources/romsfun.py).
 *
 * Search:   /?s=<query>, more pages at /page/<n>/?s=<query>
 * Browse:   /roms/<console>/, more pages at /roms/<console>/page/<n>/
 * Games come in one of two layouts, depending on the page: "rf-li" list blocks
 * or grid cards. Both give the game page (/roms/<console>/<slug>.html, which
 * also names the console), a thumbnail, the title, a type chip ("Hack", "Fan
 * Translation") and a download count.
 *
 * Downloading, the way a visitor's browser does it, as a guest:
 *
 * 1. the game page links to /download/<slug>-<id>
 * 2. that page lists the files in a table (name, type, size), each linking to
 *    /download/<slug>-<id>/<n>
 * 3. that page shows a short countdown ("Please wait 7 seconds"). Meanwhile its
 *    script asks the site for the link (POST admin-ajax.php, action
 *    k_get_download) and shows it when the countdown ends. The adapter makes
 *    the same request and waits out the same countdown before using the link.
 *
 * No login, captcha or paywall is involved. If the site ever asks for one (its
 * guest download limit, a bot check), the adapter reports that and stops; it
 * does not try to get around it.
 */
class RomsFunSource(http: HttpClient, options: SourceOptions) : Source(http, options) {
    override val supportsBrowse: Boolean = true

    /** Scales the site's countdown (1.0: wait it out like a visitor; tests may set 0). */
    var countdownScale: Double = 1.0

    private suspend fun fetchPage(url: String): String {
        val html = http.getText(url)
        if (CHALLENGE.any { it in html }) {
            // A bot check: report it, never try to get past it.
            throw SourceError("RomsFun is showing a browser check right now; try again later")
        }
        return html
    }

    // ------------------------------------------------------------------ lists
    private class Block(val link: String, val title: String, val image: String?, val kind: String, val downloads: Long?)

    /** (link, title, image, kind, downloads) for each game, either layout. */
    private fun blocks(html: String): List<Block> {
        val out = mutableListOf<Block>()
        for (block in html.split("class=\"rf-li ").drop(1)) {
            val titleMatch = TITLE_RE.find(block) ?: continue
            val image = IMG_RE.find(block)
            val chip = CHIP_RE.find(block)
            val stats = STATS_RE.find(block)
            val badges = if (stats != null) BADGE_RE.findAllFast(stats.groupValues[1]).map { it.groupValues[1] }.toList() else emptyList()
            out += Block(
                titleMatch.groupValues[1],
                titleMatch.groupValues[2],
                image?.groupValues?.get(1),
                if (chip != null) Html.collapseWs(Html.stripTags(chip.groupValues[1])) else "",
                if (badges.isNotEmpty()) count(Html.stripTags(badges[0])) else null,
            )
        }
        for (block in html.split(GRID_SPLIT).drop(1)) {
            val titleMatch = GRID_TITLE_RE.find(block) ?: continue
            if ("/roms/" !in titleMatch.groupValues[1]) continue
            val image = IMG_RE.find(block)
            val kind = GRID_KIND_RE.find(block)
            val countMatch = GRID_COUNT_RE.find(block)
            out += Block(
                titleMatch.groupValues[1],
                titleMatch.groupValues[2],
                image?.groupValues?.get(1),
                if (kind != null) Html.collapseWs(Html.stripTags(kind.groupValues[1])) else "",
                if (countMatch != null) count(countMatch.groupValues[1]) else null,
            )
        }
        return out
    }

    private fun parseList(html: String): List<RomEntry> {
        val entries = mutableListOf<RomEntry>()
        val seen = HashSet<String>()
        for (b in blocks(html)) {
            val url = Html.absolute(base, b.link)
            val console = GAME_URL_RE.find(url)
            val system = if (console != null) CONSOLES[console.groupValues[1].lowercase()] else null
            if (system == null || url in seen) continue
            seen += url
            val title = tidy(b.title)
            val entry = RomEntry(
                source = id,
                sourceId = urlPath(url).trim('/'),
                title = title,
                system = system,
                url = url,
                cover = if (b.image != null) Html.absolute(base, b.image) else null,
                downloads = b.downloads,
                region = extractRegion(title),
            )
            if (b.kind.isNotEmpty()) entry.extra.putStr("kind", b.kind)
            entries += entry
        }
        // Romhacks and translations fill the first pages of a search ("mario"
        // is mostly hacks); the games themselves come first.
        val ordered = entries.sortedBy { (it.extra.str("kind") ?: "").lowercase() in HACK_KINDS }
        ordered.forEachIndexed { rank, entry -> entry.rank = rank }
        return ordered
    }

    override suspend fun search(query: String, limit: Int, page: Int): List<RomEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val url = if (page <= 1) "$base/?s=${Html.quotePlus(q)}" else "$base/page/$page/?s=${Html.quotePlus(q)}"
        return parseList(fetchPage(url)).take(limit)
    }

    override fun browseSystems(): Set<String> = SLUGS.keys

    override suspend fun browse(system: String, limit: Int): List<RomEntry> {
        val slug = SLUGS[system] ?: return emptyList()
        val entries = mutableListOf<RomEntry>()
        val seen = HashSet<String>()
        for (page in 1 until 9) {
            val url = if (page == 1) "$base/roms/$slug/" else "$base/roms/$slug/page/$page/"
            val found = try {
                parseList(fetchPage(url))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (page == 1) throw e
                break
            }
            val fresh = found.filter { it.url !in seen }
            if (fresh.isEmpty()) break
            for (entry in fresh) {
                seen += entry.url
                entry.rank = entries.size
                entries += entry
            }
            if (entries.size >= limit) break
        }
        return entries.take(limit)
    }

    // ------------------------------------------------------------------ files
    override suspend fun resolve(entry: RomEntry): List<RomFile> {
        val game = fetchPage(entry.url)
        val match = DOWNLOAD_PAGE_RE.find(game) ?: return emptyList()
        val listing = fetchPage(Html.absolute(base, match.groupValues[1]))
        val files = mutableListOf<RomFile>()
        for (row in ROW_RE.findAllFast(listing)) {
            val rowHtml = row.groupValues[1]
            val link = FILE_LINK_RE.find(rowHtml) ?: continue
            val cells = CELL_RE.findAllFast(rowHtml)
                .map { Html.collapseWs(Html.unescape(Html.stripTags(it.groupValues[1]))) }
                .toList()
            val name = cells.getOrElse(0) { "" }
            val kind = cells.getOrElse(1) { "" }
            val size = if (cells.size > 2) size(cells[2]) else null
            files += RomFile(
                id = "${entry.sourceId}-${files.size + 1}",
                // The real file name (with its extension) is only known once
                // the link is ready: fileNameOf(resolveDownload(file)).
                filename = "",
                url = Html.absolute(base, link.groupValues[1]),
                size = size,
                region = extractRegion(name),
                label = if (kind.isEmpty()) name else "$name · $kind",
                sourceUrl = entry.url,
            )
        }
        if (files.isNotEmpty() && (entry.size == null || entry.size == 0L)) {
            entry.size = files.firstOrNull { it.size != null && it.size != 0L }?.size
        }
        return files
    }

    override suspend fun resolveDownload(file: RomFile): String {
        // The site hands out the link for the file page this session opened
        // last, so two downloads must not prepare their links at the same time.
        return LINK_LOCK.withLock {
            val page = fetchPage(file.url)
            val started = System.nanoTime()
            val countdown = COUNTDOWN_RE.find(page)
            val wait = if (countdown != null) minOf(countdown.groupValues[1].toInt(), 30) else 7
            val headers = LinkedHashMap<String, String>()
            headers["Referer"] = file.url
            headers["X-Requested-With"] = "XMLHttpRequest"
            val resp = http.postForm("$base/wp-admin/admin-ajax.php", linkedMapOf("action" to "k_get_download"), headers)
            val link = linkFrom(resp.text) ?: throw SourceError(whyNoLink(page, resp.text))
            // The same countdown a visitor sits through.
            val remaining = wait * countdownScale - (System.nanoTime() - started) / 1e9
            if (remaining > 0) delay((remaining * 1000).toLong())
            link
        }
        // romsfun.py also stores the link's file name on the RomFile; RomFile
        // is immutable here, so the caller takes it with fileNameOf(link).
    }

    companion object {
        /** RomsFun console slug -> store console key. Consoles without an
         * emulator on the Deck (PS4, Xbox, ...) are not listed and their games
         * are skipped. */
        val CONSOLES: Map<String, String> = linkedMapOf(
            "nes" to "nes",
            "super-nintendo" to "snes",
            "nintendo-64" to "n64",
            "gamecube" to "gc",
            "nintendo-wii" to "wii",
            "wii-u" to "wiiu",
            "game-boy" to "gb",
            "game-boy-color" to "gbc",
            "game-boy-advance" to "gba",
            "nintendo-ds" to "nds",
            "nintendo-3ds" to "3ds",
            "nintendo-virtual-boy" to "virtualboy",
            "nintendo-pokemon-mini" to "pokemini",
            "sega-sg-1000" to "sg1000",
            "sega-master-system" to "mastersystem",
            "sega-genesis" to "genesis",
            "sega-cd" to "segacd",
            "32x" to "sega32x",
            "sega-saturn" to "saturn",
            "dreamcast" to "dreamcast",
            "sega-game-gear" to "gamegear",
            "playstation" to "psx",
            "playstation-2" to "ps2",
            "ps3" to "ps3",
            "playstation-portable" to "psp",
            "ps-vita" to "psvita",
            "atari-2600" to "atari2600",
            "atari-7800" to "atari7800",
            "atari-jaguar" to "jaguar",
            "turbografx" to "pcengine",
            "turbografx-cd" to "pcenginecd",
            "nec-pc-fx" to "pcfx",
            "nec-pc-9801" to "pc98",
            "neo-geo" to "neogeo",
            "neo-geo-aes" to "neogeo",
            "neo-geo-cd" to "neogeocd",
            "snk-neo-geo-pocket-color" to "ngpc",
            "3do" to "3do",
            "mame" to "mame",
            "microsoft-msx" to "msx",
            "microsoft-msx2" to "msx",
            "amiga" to "amiga",
            "sharp-x1" to "sharp-x1",
            "sharp-x68000" to "x68000",
            "scummvm" to "scummvm",
            "openbor" to "openbor",
            "windows" to "pc",
        )

        /** The other way round, for browsing (first slug wins). */
        val SLUGS: Map<String, String> = LinkedHashMap<String, String>().also { map ->
            for ((slug, key) in CONSOLES) map.putIfAbsent(key, slug)
        }

        /** The file name a resolved download link carries (its last path
         * segment, unquoted): what romsfun.py stores as RomFile.filename. */
        fun fileNameOf(link: String): String = Html.unquote(urlPath(link).substringAfterLast('/'))

        private val LINK_LOCK = Mutex()
        private val HACK_KINDS = setOf("hack", "homebrew")

        private val I = RegexOption.IGNORE_CASE
        private val IS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

        private val GAME_URL_RE = Regex("""/roms/([a-z0-9-]+)/[^/"]+\.html""", I)
        private val TITLE_RE = Regex("""class="rf-li-title"[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", IS)
        private val IMG_RE = Regex("""<img[^>]+src="([^"]+)"""", I)
        private val CHIP_RE = Regex("""class="rf-type-chip"[^>]*>(.*?)</a>""", IS)
        private val STATS_RE = Regex("""class="rf-li-stats[^"]*"[^>]*>(.*?)</div>""", IS)
        private val BADGE_RE = Regex("""class="badge[^"]*"[^>]*>(.*?)</span>\s*(?=<|$)""", IS)

        // Grid card: <div class="bg-white rounded-lg shadow-md overflow-hidden ...">
        //   <img src=...> <h3 ...><a href=".../roms/<console>/<slug>.html">Title</a></h3>
        //   <a ... href="...types[]=...">Fan Translation</a>  <span class="text-xs">...249</span>
        private const val GRID_SPLIT = "class=\"bg-white rounded-lg shadow-md overflow-hidden"
        private val GRID_TITLE_RE = Regex("""<h3[^>]*>\s*<a[^>]+href="([^"]+\.html)"[^>]*>(.*?)</a>""", IS)
        private val GRID_KIND_RE = Regex("""<a[^>]+href="[^"]*types(?:\[\]|%5B%5D)=[^"]*"[^>]*>(.*?)</a>""", IS)
        private val GRID_COUNT_RE = Regex("""<span class="text-xs">\s*<span[^>]*>.*?</span>\s*([\d.,]+\s*[kKmM]?)\s*</span>""", IS)
        private val DOWNLOAD_PAGE_RE = Regex("""href="([^"]*/download/[a-z0-9-]+-\d+)/?"""", I)
        private val ROW_RE = Regex("""<tr\b[^>]*>(.*?)</tr>""", IS)
        private val CELL_RE = Regex("""<td\b[^>]*>(.*?)</td>""", IS)
        private val FILE_LINK_RE = Regex("""href="([^"]*/download/[a-z0-9-]+-\d+/\d+)"""", I)
        private val COUNTDOWN_RE = Regex("""id="countdown"[^>]*>\s*(\d+)""", I)
        private val LINK_RE = Regex("""<a[^>]+href="([^"]+)"[^>]*id="download-link"|id="download-link"[^>]*href="([^"]+)"""", I)

        // Markers of Cloudflare's challenge page itself. Not "challenge-platform":
        // normal pages load that script too.
        private val CHALLENGE = listOf("<title>Just a moment...</title>", "cf-chl-")

        private val COUNT_RE = Regex("""([\d.,]+)\s*([kKmM]?)""")
        private val SIZE_RE = Regex("""([\d.,]+)\s*([KMGT]?)i?B?\b""", I)

        /** '12', '1.2K', '3 M' -> a number. */
        internal fun count(text: String?): Long? {
            val match = COUNT_RE.find(text ?: "") ?: return null
            val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
            val factor = when (match.groupValues[2].lowercase()) {
                "k" -> 1_000.0
                "m" -> 1_000_000.0
                else -> 1.0
            }
            return (value * factor).toLong()
        }

        /** '624.75 K', '1.2 MB', '3 G' -> bytes. */
        internal fun size(text: String?): Long? {
            val match = SIZE_RE.find(text ?: "") ?: return null
            val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
            val power = "_KMGT".indexOf(match.groupValues[2].uppercase().ifEmpty { "_" })
            var factor = 1.0
            repeat(power) { factor *= 1024 }
            return (value * factor).toLong()
        }

        /** Some titles are in capitals ("SUPER MARIO WORLD"). */
        internal fun tidy(raw: String): String {
            val title = Html.collapseWs(Html.unescape(Html.stripTags(raw)))
            return if (isUpper(title)) pyTitle(title) else title
        }

        /** urlparse(url).path: no scheme, host, query, fragment or ;params. */
        internal fun urlPath(url: String): String {
            var rest = url
            val scheme = rest.indexOf("://")
            if (scheme != -1) {
                rest = rest.substring(scheme + 3)
                val slash = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
                rest = if (slash == -1) "" else rest.substring(slash)
            }
            val cut = rest.indexOfFirst { it == '?' || it == '#' }
            if (cut != -1) rest = rest.substring(0, cut)
            val lastSlash = rest.lastIndexOf('/')
            val semi = rest.indexOf(';', maxOf(lastSlash, 0))
            if (semi != -1) rest = rest.substring(0, semi)
            return rest
        }

        internal fun linkFrom(text: String): String? {
            val parsed: JsonElement = try {
                Json.parseToJsonElement(text)
            } catch (_: Exception) {
                return null
            }
            val data = parsed as? JsonObject ?: return null
            val html = ((data["data"] as? JsonObject)?.get("html") as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            if (!truthy(data["success"]) || html.isNullOrEmpty()) return null
            val match = LINK_RE.find(html) ?: return null
            val href = match.groups[1]?.value ?: match.groups[2]?.value ?: return null
            return Html.unescape(href)
        }

        internal fun whyNoLink(page: String, answer: String): String {
            val text = "${Html.stripTags(page)} $answer".lowercase()
            if ("limit" in text && ("reached" in text || "exceeded" in text)) {
                return "RomsFun's download limit for guests is reached; try again later"
            }
            if (listOf("captcha", "turnstile", "verify you are human").any { it in text }) {
                return "RomsFun wants a human check for this download; get it from romsfun.com in a browser"
            }
            return "RomsFun did not hand out a download link for this file"
        }

        /** Python truthiness of a JSON value. */
        private fun truthy(value: JsonElement?): Boolean = when (value) {
            null -> false
            is JsonObject -> value.isNotEmpty()
            is JsonArray -> value.isNotEmpty()
            is JsonPrimitive -> when {
                value.isString -> value.content.isNotEmpty()
                value.booleanOrNull != null -> value.booleanOrNull!!
                value.doubleOrNull != null -> value.doubleOrNull != 0.0
                else -> false // null
            }
        }

        private fun isCased(c: Char) = Character.isUpperCase(c) || Character.isLowerCase(c) || Character.isTitleCase(c)

        /** Python's str.isupper(): has a cased letter and none in lower case. */
        private fun isUpper(text: String): Boolean =
            text.any { isCased(it) } && text.none { Character.isLowerCase(it) || Character.isTitleCase(it) }

        /** Python's str.title(): each run of letters starts upper case. */
        internal fun pyTitle(text: String): String = buildString {
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
