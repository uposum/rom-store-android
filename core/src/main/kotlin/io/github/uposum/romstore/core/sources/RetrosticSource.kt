package io.github.uposum.romstore.core.sources

import io.github.uposum.romstore.core.net.findAllFast

import io.github.uposum.romstore.core.net.UNI
import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.RomFile
import io.github.uposum.romstore.core.model.Systems
import io.github.uposum.romstore.core.model.extractRegion
import io.github.uposum.romstore.core.model.putDetails
import io.github.uposum.romstore.core.model.putStr
import io.github.uposum.romstore.core.net.Html
import io.github.uposum.romstore.core.net.HttpClient
import kotlin.coroutines.cancellation.CancellationException

/**
 * Retrostic / RomsDL adapter (port of backend/sources/retrostic.py).
 *
 * Both sites run the same software, so one adapter serves both ids
 * ("retrostic" and "romsdl"; only the console slugs differ):
 *
 * * search   -> /search?search_term_string=<term>  (table#romstable or rom-box cards)
 * * browse   -> /roms/<console>                     (same table)
 * * detail   -> /roms/<console>/<slug>              (metadata table + download form)
 * * download -> POST /roms/<console>/<slug>/download with the form's session
 *               token; the response page then contains the real file url.
 */
class RetrosticSource(http: HttpClient, options: SourceOptions) : Source(http, options) {
    override val supportsBrowse: Boolean = true

    // ------------------------------------------------------------------ search
    override suspend fun search(query: String, limit: Int, page: Int): List<RomEntry> {
        val url = "$base/search?search_term_string=${Html.quotePlus(query)}"
        val html = http.getText(url)
        return parseRows(html).take(limit)
    }

    override suspend fun browse(system: String, limit: Int): List<RomEntry> {
        val slug = slugFor(system) ?: return emptyList()
        return pagedCatalogue({ n -> if (n == 1) "$base/roms/$slug" else "$base/roms/$slug/page/$n" }, limit, maxPages = 6) { parseRows(it) }
    }

    override fun browseSystems(): Set<String> =
        Systems.ALL.keys.filterTo(LinkedHashSet()) { slugFor(it) != null }

    /** Map a canonical system onto the console slug this site uses. */
    private fun slugFor(system: String): String? =
        (if (id == "romsdl") ROMSDL_SLUGS else RETROSTIC_SLUGS)[system]?.takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------------ parsing
    private fun parseRows(html: String): List<RomEntry> {
        val entries = mutableListOf<RomEntry>()
        val seen = HashSet<String>()
        // Some deployments of the same engine use a card layout instead of a
        // table - handle both.
        for (cardMatch in CARD_RE.findAllFast(html)) {
            parseCard(cardMatch.value, cardMatch.groupValues[1], seen)?.let { entries += it }
        }
        if (entries.isNotEmpty()) return entries
        for (rowMatch in ROW_RE.findAllFast(html)) {
            val row = rowMatch.groupValues[1]
            val hrefMatch = HREF_RE.find(row) ?: continue
            val href = hrefMatch.groupValues[1]
            if (href in seen) continue
            seen += href

            val cells = CELL_RE.findAllFast(row).map { it.groupValues[1] }.toList()
            var title = ""
            val nameMatch = NAME_RE.find(row)
            if (nameMatch != null) {
                title = Html.collapseWs(Html.stripTags(nameMatch.groupValues[1]))
            } else if (cells.size > 2) {
                title = Html.collapseWs(Html.stripTags(cells[2]))
            }
            if (title.isEmpty()) continue

            var consoleName = ""
            if (cells.size > 3) consoleName = Html.collapseWs(Html.stripTags(cells[3]))
            var downloads: Long? = null
            if (cells.size > 4) {
                // int(text.replace(",", "") or 0) or None
                val text = Html.collapseWs(Html.stripTags(cells[4])).replace(",", "")
                downloads = (if (text.isEmpty()) 0L else pyInt(text))?.takeIf { it != 0L }
            }

            entries += RomEntry(
                source = id,
                sourceId = href.trim('/'),
                title = title,
                system = systemFor(consoleName, href),
                url = Html.absolute(base, href),
                cover = rowImage(row),
                downloads = downloads,
                region = extractRegion(title),
            )
        }
        return entries
    }

    private fun parseCard(card: String, body: String, seen: MutableSet<String>): RomEntry? {
        val hrefMatch = HREF_RE.find(card) ?: return null
        val href = hrefMatch.groupValues[1]
        if (href in seen) return null
        seen += href

        var title = ""
        val nameMatch = NAME_RE.find(body)
        if (nameMatch != null) title = Html.collapseWs(Html.stripTags(nameMatch.groupValues[1]))
        if (title.lowercase().startsWith("name:")) title = title.substring(5).trim()
        if (title.isEmpty()) {
            val head = H3_RE.find(body)
            if (head != null) {
                title = Html.collapseWs(Html.stripTags(head.groupValues[1]))
                if (title.lowercase().startsWith("name:")) title = title.substring(5).trim()
            }
        }
        if (title.isEmpty()) return null

        var consoleName = ""
        var downloads: Long? = null
        for (head in H4_RE.findAllFast(body)) {
            val text = Html.collapseWs(Html.stripTags(head.groupValues[1]))
            if (text.lowercase().startsWith("console:")) {
                consoleName = text.substring(8).trim()
            } else {
                val number = NUMBER_RE.find(text)
                if (number != null) downloads = pyInt(number.groupValues[1].replace(",", ""))?.takeIf { it != 0L }
            }
        }

        return RomEntry(
            source = id,
            sourceId = href.trim('/'),
            title = title,
            system = systemFor(consoleName, href),
            url = Html.absolute(base, href),
            cover = rowImage(body),
            downloads = downloads,
            region = extractRegion(title),
        )
    }

    private fun systemFor(consoleName: String, href: String): String {
        var system = CONSOLE_ALIASES[consoleName.lowercase().trim()] ?: Systems.canonical(consoleName)
        if (system == "unknown") {
            val parts = href.split("/").filter { it.isNotEmpty() }
            system = Systems.canonical(if (parts.size > 2) parts[1] else consoleName)
        }
        return system
    }

    private fun rowImage(row: String): String? {
        for (match in IMG_RE.findAllFast(row)) {
            val path = match.groupValues[1]
            if ("spinner" in path || "no-image" in path || "flags/" in path || "logo" in path) continue
            // The listings use site-relative paths (/img/screenshots/...);
            // the client can only load an absolute URL.
            return Html.absolute(base, path)
        }
        return null
    }

    // ------------------------------------------------------------------ details
    override suspend fun resolve(entry: RomEntry): List<RomFile> {
        val html = http.getText(entry.url)
        val details = parseMeta(html)
        if (!details["File Name"].isNullOrEmpty()) {
            entry.size = entry.size?.takeIf { it != 0L } ?: Html.parseSize(details["File Size"])
            entry.region = entry.region?.takeIf { it.isNotEmpty() } ?: details["Region"]
            entry.year = entry.year?.takeIf { it != 0 } ?: toLong(details["Release Year"])?.toInt()
            entry.downloads = entry.downloads?.takeIf { it != 0L } ?: toLong(details["Downloads"])
            entry.summary = entry.summary?.takeIf { it.isNotEmpty() } ?: details["Genre/s"]
            entry.extra.putDetails(details)
        }

        // The page gives a site-relative path ("/img/..."); the app can only load absolute URLs.
        val cover = detailImage(html)?.let { Html.absolute(base, it) }
        if (!cover.isNullOrEmpty()) {
            entry.coverFull = cover
            entry.cover = entry.cover?.takeIf { it.isNotEmpty() } ?: cover
        }

        val (formAction, fields) = downloadForm(html) ?: return emptyList()
        if (fields.isEmpty()) return emptyList()
        val action = Html.absolute(base, formAction)

        val response = try {
            http.postForm(action, fields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            entry.extra.putStr("error", "${e.javaClass.simpleName}: ${e.message}")
            return emptyList()
        }

        val url = FILE_RE.find(response.text)?.groupValues?.get(1) ?: ""
        val filename = Html.unquote(
            details["File Name"]?.takeIf { it.isNotEmpty() }
                ?: (if (url.isNotEmpty()) url.substringAfterLast('/') else ""),
        )
        if (url.isEmpty()) return emptyList()
        return listOf(
            RomFile(
                id = "${entry.sourceId}-0",
                filename = filename,
                url = url,
                size = Html.parseSize(details["File Size"]),
                region = details["Region"],
                label = details["Region"],
                sourceUrl = entry.url,
            ),
        )
    }

    companion object {
        private val I = RegexOption.IGNORE_CASE
        private val S = RegexOption.DOT_MATCHES_ALL

        // "(?U)" makes \s and \d Unicode-aware, like Python 3's str patterns.
        private val ROW_RE = Regex("""<tr\b[^>]*>(.*?)</tr>""", setOf(I, S))
        private val CARD_RE = Regex("""<a\b[^>]*class="[^"]*rom-box[^"]*"[^>]*>(.*?)</a>""", setOf(I, S))
        private val H3_RE = Regex("""<h3\b[^>]*>(.*?)</h3>""", setOf(I, S))
        private val H4_RE = Regex("""<h4\b[^>]*>(.*?)</h4>""", setOf(I, S))
        private val CELL_RE = Regex("""<td\b[^>]*>(.*?)</td>""", setOf(I, S))
        private val HREF_RE = Regex("""href="(/roms/[^"]+)"""")
        private val NAME_RE = Regex("""<p[^>]*itemprop="name"[^>]*>(.*?)</p>""", setOf(I, S))
        private val IMG_RE = Regex("""(?:data-src|src)="(/img/[^"]+)"""", I)
        private val META_RE = Regex("""${UNI}<td[^>]*>\s*([^<]{2,28}?:)\s*</td>\s*<td[^>]*>(.*?)</td>""", setOf(I, S))
        private val FORM_RE = Regex("""<form\b([^>]*)>(.*?)</form>""", setOf(I, S))
        private val INPUT_RE = Regex("""${UNI}name="([^"]+)"\s+value="([^"]*)"""", I)
        private val FILE_RE = Regex("""${UNI}window\.location\.href\s*=\s*"([^"]+)"""", I)
        private val ID_RE = Regex("""id="([^"]+)"""")
        private val ACTION_RE = Regex("""action="([^"]+)"""", I)
        private val NUMBER_RE = Regex("""${UNI}^([\d,]+)""")
        private val DETAIL_IMG_RE = Regex("""<img[^>]*class="img-fluid"[^>]*src="(/img/[^"]+)"""", I)

        /** Console names as the site spells them -> canonical system key. */
        val CONSOLE_ALIASES: Map<String, String> = linkedMapOf(
            "cps 1" to "arcades",
            "cps 2" to "arcades",
            "cps 3" to "arcades",
            "capcom play system 1" to "arcades",
            "capcom play system 2" to "arcades",
            "capcom play system 3" to "arcades",
            "naomi" to "arcades",
            "famicom" to "nes",
            "megadrive" to "genesis",
            "mega drive" to "genesis",
            "master system" to "mastersystem",
            "game gear" to "gamegear",
            "turbo grafx 16" to "pcengine",
            "turbografx 16" to "pcengine",
            "virtual boy" to "virtualboy",
            "wonder swan" to "wonderswan",
            "wonder swan color" to "wonderswan-color",
            "neo geo pocket" to "ngp",
            "neo geo pocket color" to "ngpc",
            "zx spectrum" to "zxspectrum",
            "bbc micro" to "unknown",
            "cd i" to "unknown",
            "c64 preservation" to "c64",
            "c64 tapes" to "c64",
            "sega 32x" to "sega32x",
            "sharp x68000" to "x68000",
            "atari st" to "atarist",
            "atari jaguar" to "jaguar",
            "atari lynx" to "atarilynx",
            "gamecube" to "gc",
            "nintendo gamecube" to "gc",
            "playstation" to "psx",
            "ps1" to "psx",
            "ps2" to "ps2",
            "ps3" to "ps3",
            "psp" to "psp",
            "nintendo 64" to "n64",
            "nintendo ds" to "nds",
            "game boy" to "gb",
            "game boy color" to "gbc",
            "game boy advance" to "gba",
            "super nintendo" to "snes",
            "nintendo entertainment system" to "nes",
        )

        private val ROMSDL_SLUGS: Map<String, String> = linkedMapOf(
            "nds" to "nintendo-ds",
            "gba" to "gameboy-advance",
            "gbc" to "gameboy-color",
            "gb" to "gameboy",
            "nes" to "nintendo",
            "snes" to "super-nintendo",
            "gc" to "nintendo-gamecube",
            "wii" to "nintendo-wii",
            "n64" to "nintendo-64",
            "psx" to "playstation-1",
            "ps2" to "playstation-2",
            "ps3" to "playstation-3",
            "psp" to "playstation-portable",
            "c64" to "commodore-64-preservation",
            "arcades" to "capcom-play-system-1",
        )

        private val RETROSTIC_SLUGS: Map<String, String> = linkedMapOf(
            "3do" to "3do", "atari2600" to "atari-2600", "atari5200" to "atari-5200",
            "atari7800" to "atari-7800", "atarilynx" to "atari-lynx", "atarist" to "atari-st",
            "jaguar" to "atari-jaguar", "arcades" to "cps-1", "mame" to "mame",
            "c64" to "c64-preservation", "dreamcast" to "dreamcast", "nes" to "nes",
            "famicom" to "famicom", "gamegear" to "game-gear", "gc" to "gamecube",
            "gb" to "gb", "gba" to "gba", "gbc" to "gbc", "mastersystem" to "master-system",
            "genesis" to "megadrive", "n64" to "n64", "nds" to "nds", "neogeo" to "neo-geo",
            "ngp" to "neo-geo-pocket", "psx" to "ps-1", "ps2" to "ps-2", "ps3" to "ps-3",
            "psp" to "psp", "saturn" to "saturn", "sega32x" to "sega-32x",
            "snes" to "snes", "pcengine" to "turbografx16", "virtualboy" to "virtual-boy",
            "wii" to "wii", "wonderswan" to "wonderswan",
            "wonderswan-color" to "wonderswan-color", "x68000" to "sharp-x68000",
            "zxspectrum" to "zx-spectrum",
        )

        /** The download form (id="dl"): its action and hidden fields, in page order. */
        private fun downloadForm(html: String): Pair<String, Map<String, String>>? {
            for (match in FORM_RE.findAllFast(html)) {
                val attrs = match.groupValues[1]
                val body = match.groupValues[2]
                if (ID_RE.findAllFast(attrs).none { it.groupValues[1] == "dl" }) continue
                val actionMatch = ACTION_RE.find(attrs) ?: continue
                // Like a dict comprehension: a repeated name keeps its first
                // position and takes the last value.
                val fields = LinkedHashMap<String, String>()
                for (m in INPUT_RE.findAllFast(body)) fields[m.groupValues[1]] = m.groupValues[2]
                return actionMatch.groupValues[1] to fields
            }
            return null
        }

        private fun parseMeta(html: String): Map<String, String> {
            val details = LinkedHashMap<String, String>()
            for (match in META_RE.findAllFast(html)) {
                val key = Html.collapseWs(Html.stripTags(match.groupValues[1])).trimEnd(':').trim()
                val value = Html.collapseWs(Html.stripTags(match.groupValues[2]))
                if (key.isNotEmpty()) details[key] = value
            }
            return details
        }

        private fun detailImage(html: String): String? {
            val match = DETAIL_IMG_RE.find(html) ?: return null
            val path = match.groupValues[1]
            return if ("no-image" !in path) path else null
        }

        private fun toLong(value: String?): Long? = value?.replace(",", "")?.trim()?.toLongOrNull()

        /** int() of a string of digits (Python also takes surrounding whitespace). */
        private fun pyInt(text: String): Long? = text.trim().toLongOrNull()
    }
}
