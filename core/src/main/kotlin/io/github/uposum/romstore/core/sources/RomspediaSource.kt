package io.github.uposum.romstore.core.sources

import io.github.uposum.romstore.core.net.findAllFast

import io.github.uposum.romstore.core.net.UNI
import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.RomFile
import io.github.uposum.romstore.core.model.Systems
import io.github.uposum.romstore.core.model.extractRegion
import io.github.uposum.romstore.core.model.putDetails
import io.github.uposum.romstore.core.net.Html
import io.github.uposum.romstore.core.net.HttpClient
import kotlin.coroutines.cancellation.CancellationException

/**
 * Romspedia adapter (port of backend/sources/romspedia.py).
 *
 * Romspedia renders everything server side:
 *
 * * search    -> /search?search_term_string=<q>   (cards: div.single-rom)
 * * browse    -> /roms/<console>
 * * detail    -> /roms/<console>/<slug>            (div.view-emulator-detail pairs)
 * * file      -> /roms/<console>/<slug>/download   (links to downloads.romspedia.com)
 */
class RomspediaSource(http: HttpClient, options: SourceOptions) : Source(http, options) {
    override val supportsBrowse: Boolean = true
    override val supportsRegions: Boolean = true

    // ------------------------------------------------------------------ search
    override suspend fun search(query: String, limit: Int, page: Int): List<RomEntry> {
        val html = http.getText(searchUrl(query, page))
        val entries = parseCards(html)
        lastPageCount = pageCount(html)
        return entries.take(limit)
    }

    private fun searchUrl(query: String, page: Int = 1): String {
        val term = Html.quotePlus(query)
        if (page <= 1) return "$base/search?search_term_string=$term"
        return "$base/search.php?currentpage=$page&search_term_string=$term"
    }

    override suspend fun browse(system: String, limit: Int): List<RomEntry> {
        val slug = slugFor(system) ?: return emptyList()
        val html = http.getText("$base/roms/$slug")
        return parseCards(html).take(limit)
    }

    /** The console sections this site actually has, straight from /roms:
     * (slug, label) pairs. Sections the index does not link (Switch, PS3)
     * are added from the known map, with the console key as their label. */
    suspend fun consoleSections(): List<Pair<String, String>> {
        val html = http.getText("$base/roms")
        val sections = mutableListOf<Pair<String, String>>()
        val seen = HashSet<String>()
        for (match in SECTION_RE.findAllFast(html)) {
            val slug = match.groupValues[1].trim('/')
            if (slug.isEmpty() || slug in seen) continue
            var label = Html.collapseWs(Html.stripTags(match.groupValues[2]))
            label = SECTION_SUFFIX_RE.replace(label, "").trim()
            if (label.isEmpty()) continue
            seen += slug
            sections += slug to label
        }
        for ((slug, key) in CONSOLE_MAP) {
            if (slug !in seen) {
                seen += slug
                sections += slug to key
            }
        }
        return sections
    }

    /** All games listed on a console page (what the browse view shows). */
    suspend fun consoleEntries(slug: String): List<RomEntry> {
        val html = http.getText("$base/roms/$slug")
        return parseCards(html)
    }

    override fun browseSystems(): Set<String> = CONSOLE_MAP.values.toSet()

    private fun slugFor(system: String): String? =
        CONSOLE_MAP.entries.firstOrNull { it.value == system }?.key

    // ------------------------------------------------------------------ parsing
    private fun parseCards(html: String): List<RomEntry> {
        val entries = mutableListOf<RomEntry>()
        val seen = HashSet<String>()

        fun build(detailHref: String, block: String): RomEntry? {
            val parts = detailHref.split("/").filter { it.isNotEmpty() }
            if (parts.size != 3 || parts[0] != "roms") return null
            val consoleSlug = parts[1]
            val slug = parts[2]
            if (slug in seen) return null
            val title = cardTitle(block)?.takeIf { it.isNotEmpty() } ?: cardTitleFromLink(block, detailHref)
            if (title.isNullOrEmpty()) return null
            seen += slug
            val system = CONSOLE_MAP[consoleSlug] ?: Systems.canonical(consoleSlug)
            return RomEntry(
                source = id,
                sourceId = "$consoleSlug/$slug",
                title = title,
                system = system,
                url = Html.absolute(base, "/roms/$consoleSlug/$slug"),
                cover = cardCover(block),
                coverFull = cardCover(block),
                downloads = cardDownloads(block),
                rating = cardRating(block),
                region = extractRegion(title),
            )
        }

        // Pass 1 - search results wrap the whole card in an <a>.
        for (anchor in Html.anchors(html)) {
            if ("single-rom" !in anchor.inner) continue
            val entry = build(anchor.href.split("?")[0].trimEnd('/'), anchor.inner)
            if (entry != null) {
                entry.rank = entries.size
                entries += entry
            }
        }

        // Pass 2 - console listings keep the card in a div with the link inside.
        if (entries.isEmpty()) {
            for ((_, block) in Html.blocks(html, "div", "class=\"single-rom\"")) {
                val m = CARD_LINK_RE.find(block) ?: continue
                val entry = build(m.groupValues[1], block)
                if (entry != null) {
                    entry.rank = entries.size
                    entries += entry
                }
            }
        }
        return entries
    }

    // ------------------------------------------------------------------ details
    override suspend fun resolve(entry: RomEntry): List<RomFile> {
        val html = http.getText(entry.url)
        val details = parseDetails(html)

        // The detail page links to a /download page that in turn points at the
        // real file on downloads.romspedia.com.
        val m = BTN_DOWNLOAD_RE.find(html) ?: DOWNLOAD_HREF_RE.find(html)
        val downloadPage: String? = m?.let { Html.absolute(base, it.groupValues[1]) }

        val filename = details["File Name"]
        val size = Html.parseSize(details["Size"])

        val url: String = if (downloadPage != null) {
            try {
                val page = http.getText(downloadPage)
                val fm = DOWNLOAD_RE.find(page)
                if (fm != null) WS_RE.replace(fm.groupValues[1], " ").replace(" ", "%20") else downloadPage
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                downloadPage
            }
        } else {
            entry.url
        }

        val name = filename?.takeIf { it.isNotEmpty() }
            ?: url.substringAfterLast('/').ifEmpty { "${entry.title}.zip" }
        val region = details["Region"]?.takeIf { it.isNotEmpty() }
        val files = listOf(
            RomFile(
                id = "${entry.sourceId}-0",
                filename = name,
                url = url,
                size = size,
                region = region ?: entry.region,
                label = details["Region"],
                sourceUrl = entry.url,
            ),
        )

        // Python's `a or b`: 0 and "" count as missing too.
        entry.rating = entry.rating?.takeIf { it != 0.0 } ?: number(details["Rating"])
        entry.year = entry.year?.takeIf { it != 0 } ?: toLong(details["Release Year"])?.toInt()
        entry.downloads = entry.downloads?.takeIf { it != 0L } ?: toLong(details["Downloads"])
        entry.size = entry.size?.takeIf { it != 0L } ?: size
        entry.region = region ?: entry.region
        entry.summary = details["Category"]
        if ("details" !in entry.extra) entry.extra.putDetails(details)
        return files
    }

    companion object {
        /** romspedia console slug -> canonical system key */
        val CONSOLE_MAP: Map<String, String> = linkedMapOf(
            "gameboy" to "gb",
            "gameboy-advance" to "gba",
            "gameboy-color" to "gbc",
            "mame" to "mame",
            "nintendo" to "nes",
            "nintendo-3ds" to "3ds",
            "nintendo-64" to "n64",
            "nintendo-ds" to "nds",
            "nintendo-wii" to "wii",
            "nintendo-wii-u" to "wiiu",
            "nintendo-switch" to "switch",
            "playstation-1" to "psx",
            "playstation-2" to "ps2",
            "playstation-3" to "ps3",
            "playstation-portable" to "psp",
            "playstation-vita" to "psvita",
            "super-nintendo" to "snes",
            "sega-genesis" to "genesis",
            "sega-master-system" to "mastersystem",
            "sega-game-gear" to "gamegear",
            "sega-saturn" to "saturn",
            "sega-dreamcast" to "dreamcast",
            "gamecube" to "gc",
        )

        private val I = RegexOption.IGNORE_CASE
        private val S = RegexOption.DOT_MATCHES_ALL

        // "(?U)" makes \s and \d Unicode-aware, like Python 3's str patterns.
        private val DOWNLOAD_RE = Regex("""href="(https://downloads\.romspedia\.com/[^"]+)"""", I)
        private val PAGE_RE = Regex("""${UNI}currentpage=(\d+)""")
        private val SECTION_RE = Regex("""<a[^>]*href="/roms/([a-z0-9-]+)"[^>]*>(.*?)</a>""", setOf(I, S))
        private val SECTION_SUFFIX_RE = Regex("""${UNI}\s+(ROMs?|ISOs?|ROMS)$""", I)
        private val CARD_LINK_RE = Regex("""href="(/roms/[^"/]+/[^"/]+)"""", I)
        private val TITLE_RE = Regex("""<h2[^>]*class="roms-title"[^>]*>(.*?)</h2>""", setOf(I, S))
        private val DOWN_NUMBER_RE = Regex("""${UNI}<span[^>]*class="down-number"[^>]*>([\d,.]+)</span>""", I)
        private val LIST_RATING_RE = Regex("""${UNI}class="list-rom-rating"[^>]*data-rating="([\d.]+)"""", I)
        private val RATING_RE = Regex("""${UNI}data-rating="([\d.]+)"""", I)
        private val COVER_BOX_RE = Regex("""src="(https://static\.romspedia\.com[^"]*?-cover\.webp)"""", I)
        private val COVER_ANY_RE = Regex("""src="(https://static\.romspedia\.com[^"]*?\.webp)"""", I)
        private val COVER_SRCSET_RE = Regex("""srcset="(https://static\.romspedia\.com[^",]*?\.webp)""", I)
        private val WS_RE = Regex("""${UNI}\s+""")
        private val BTN_DOWNLOAD_RE = Regex("""id="btnDownload"[^>]*href="([^"]+)"""", I)
        private val DOWNLOAD_HREF_RE = Regex("""href="([^"]*/download[^"]*)"""", I)
        private val DETAIL_RE = Regex(
            """${UNI}<div[^>]*class="view-emulator-detail-name"[^>]*>(.*?)</div>\s*""" +
                """<div[^>]*class="view-emulator-detail-value"[^>]*>(.*?)</div>""",
            setOf(I, S),
        )

        /** Highest page number the search result pager links to (1 if none). */
        private fun pageCount(html: String): Int =
            PAGE_RE.findAllFast(html).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 1

        private fun cardTitleFromLink(block: String, detailHref: String): String? {
            val m = Regex("""<a[^>]*href="${Regex.escape(detailHref)}"[^>]*title="([^"]+)"""", I).find(block)
                ?: return null
            return Html.collapseWs(Html.stripTags(m.groupValues[1])).replace(" ROM", "").trim()
        }

        private fun cardTitle(inner: String): String? {
            val m = TITLE_RE.find(inner) ?: return null
            return Html.collapseWs(Html.stripTags(m.groupValues[1]))
        }

        private fun cardDownloads(inner: String): Long? {
            val m = DOWN_NUMBER_RE.find(inner) ?: return null
            return toLong(m.groupValues[1])
        }

        private fun cardRating(inner: String): Double? {
            val m = LIST_RATING_RE.find(inner) ?: RATING_RE.find(inner) ?: return null
            return pyFloat(m.groupValues[1])
        }

        private fun cardCover(block: String): String? {
            // Box art first, fall back to the wide thumbnail.
            val m = COVER_BOX_RE.find(block) ?: COVER_ANY_RE.find(block) ?: COVER_SRCSET_RE.find(block)
                ?: return null
            return WS_RE.replace(m.groupValues[1], "")
        }

        private fun parseDetails(html: String): Map<String, String> {
            val details = LinkedHashMap<String, String>()
            for (m in DETAIL_RE.findAllFast(html)) {
                val key = Html.collapseWs(Html.stripTags(m.groupValues[1])).trimEnd(':').trim()
                val value = Html.collapseWs(Html.stripTags(m.groupValues[2]))
                if (key.isNotEmpty()) details[key] = value
            }
            // Rating is rendered as a data attribute.
            if ("Rating" !in details) {
                RATING_RE.find(html)?.let { details["Rating"] = it.groupValues[1] }
            }
            return details
        }

        private fun number(value: String?): Double? = value?.let { pyFloat(it.trim()) }

        /** int(str(value).replace(",", "").strip()), None on failure. */
        private fun toLong(value: String?): Long? = value?.replace(",", "")?.trim()?.toLongOrNull()

        /** float() of a plain decimal; Kotlin's parser alone would also take "1f" or hex. */
        private val FLOAT_RE = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

        private fun pyFloat(text: String): Double? = if (FLOAT_RE.matches(text)) text.toDoubleOrNull() else null
    }
}
