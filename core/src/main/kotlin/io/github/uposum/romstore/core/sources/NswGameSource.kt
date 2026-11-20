package io.github.uposum.romstore.core.sources

import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.RomFile
import io.github.uposum.romstore.core.model.extractRegion
import io.github.uposum.romstore.core.net.FileHosts
import io.github.uposum.romstore.core.net.Html
import io.github.uposum.romstore.core.net.HttpClient
import io.github.uposum.romstore.core.net.findAllFast
import java.net.URLDecoder

/**
 * NSWGame: Switch, Wii U, Wii, 3DS and DS games, each post listing the base
 * game, its latest update and its DLC, every one on several file hosts.
 *
 *     <address>/?s=<words>                    search (WordPress), /page/N/?s= for more
 *     <address>/category/switch/              a console's catalogue
 *     <address>/<slug>-nintendo-switch-...    a game's post
 *
 * A post's download block is a set of spoilers, one per version of the game
 * ("Link Download ver - NSP - 0100152000022000 (USA)"), each holding lines
 * like `Update 4.0.1 (v1507328): Megaup – 1fichier – Send`. Every host link
 * becomes a file; multi-part archives are left out (the parts would have to be
 * fetched and joined). Hosts that only serve a browser are listed after those
 * the app can fetch (`FileHosts`).
 */
class NswGameSource(http: HttpClient, options: SourceOptions) : Source(http, options) {
    override val supportsBrowse: Boolean = true

    override fun browseSystems(): Set<String> = CATEGORIES.keys

    override suspend fun search(query: String, limit: Int, page: Int): List<RomEntry> {
        val url = if (page > 1) "$base/page/$page/?s=${Html.quotePlus(query)}" else "$base/?s=${Html.quotePlus(query)}"
        val html = http.getText(url)
        lastPageCount = PAGE_LINK.findAllFast(html).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 1
        return parseCards(html).take(limit)
    }

    override suspend fun browse(system: String, limit: Int): List<RomEntry> {
        val category = CATEGORIES[system] ?: return emptyList()
        val entries = mutableListOf<RomEntry>()
        var page = 1
        while (entries.size < limit && page <= 3) {
            val url = if (page > 1) "$base/category/$category/page/$page/" else "$base/category/$category/"
            // The first page failing is the source failing (said so in the
            // page's errors); a later one only ends the list early.
            val html = try {
                http.getText(url)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (page == 1) throw e
                break
            }
            val found = parseCards(html).filter { it.system == system }
            if (found.isEmpty()) break
            entries += found.filter { f -> entries.none { it.url == f.url } }
            page++
        }
        return entries.take(limit)
    }

    fun parseCards(html: String): List<RomEntry> {
        val entries = mutableListOf<RomEntry>()
        for (match in CARD.findAllFast(html)) {
            val url = match.groupValues[1]
            val slug = url.trimEnd('/').substringAfterLast('/')
            val system = systemOf(slug) ?: continue
            val title = Html.unescape(match.groupValues[2]).trim()
            if (title.isEmpty() || entries.any { it.url == url }) continue
            val after = html.substring(match.range.last, minOf(html.length, match.range.last + 1500))
            val cover = IMG.find(after)?.groupValues?.get(1)
            entries += RomEntry(
                source = id, sourceId = slug, title = title, system = system, url = url,
                cover = cover, coverFull = cover, region = extractRegion(title),
            )
        }
        return entries
    }

    override suspend fun resolve(entry: RomEntry): List<RomFile> = parseFiles(http.getText(entry.url), entry)

    fun parseFiles(html: String, entry: RomEntry): List<RomFile> {
        val start = html.indexOf(">Download</h2>").takeIf { it >= 0 } ?: html.indexOf("su-spoiler").takeIf { it >= 0 } ?: return emptyList()
        // Readers' comments (wpDiscuz) come after the post and carry links of their own.
        val end = listOf("wpdiscuz", "id=\"comments\"", "id=\"respond\"", "class=\"comments")
            .mapNotNull { marker -> html.indexOf(marker, start).takeIf { it > 0 } }.minOrNull() ?: html.length
        val block = html.substring(start, end)
        // One section per spoiler; a post without spoilers is one section.
        val titles = SPOILER_TITLE.findAllFast(block).toList()
        val sections = if (titles.isEmpty()) listOf("" to block) else titles.mapIndexed { i, m ->
            val to = titles.getOrNull(i + 1)?.range?.first ?: block.length
            Html.unescape(Html.stripTags(m.groupValues[1])).trim() to block.substring(m.range.last, to)
        }
        val files = mutableListOf<RomFile>()
        val own = FileHosts.host(base)
        sections.forEachIndexed { si, (heading, body) ->
            val format = FORMAT.find(heading)?.groupValues?.get(1)?.uppercase()
            val region = REGION.find(heading)?.groupValues?.get(1)?.let { regionName(it) } ?: entry.region
            PARAGRAPH.findAllFast(body).forEachIndexed { pi, p ->
                val inner = p.groupValues[1]
                val text = Html.unescape(Html.stripTags(inner)).replace(Regex("\\s+"), " ").trim()
                if (':' !in text) return@forEachIndexed
                var label = text.substringBefore(':').trim()
                if (label.isEmpty() || label.length > 80) return@forEachIndexed
                val links = LINK.findAllFast(inner).map { Html.unescape(it.groupValues[1]) to Html.unescape(Html.stripTags(it.groupValues[2])).trim() }
                    .filter { (href, _) -> href.startsWith("http") && FileHosts.host(href).let { h -> h.isNotEmpty() && h != own && !h.endsWith(".$own") && "youtu" !in h } }
                    // The app never offers torrents or magnet links.
                    .filterNot { (href, text) -> TORRENT.containsMatchIn(href) || TORRENT.containsMatchIn(text) }
                    .toList()
                if (links.isEmpty()) return@forEachIndexed
                // Parts of one archive ("Part 1", "Part 2") are not offered.
                val single = links.filterNot { (_, text) -> PART.containsMatchIn(text) }
                if (single.isEmpty()) return@forEachIndexed
                if (label.equals("game", true) || label.equals("only game", true)) label = "Base game"
                // The name of the file, from any host that puts it in its link.
                val name = single.firstNotNullOfOrNull { (href, _) -> filenameOf(href) }
                    ?: links.firstNotNullOfOrNull { (href, _) -> filenameOf(href)?.replace(PART_SUFFIX, "") }
                    ?: "${entry.title} - $label${format?.let { " [$it]" } ?: ""}.rar"
                single.sortedBy { (href, _) -> if (FileHosts.needsBrowser(href)) 1 else 0 }.forEachIndexed { hi, (href, hostText) ->
                    val host = hostText.ifEmpty { FileHosts.label(href) }
                    files += RomFile(
                        id = "${entry.sourceId}-$si-$pi-$hi",
                        filename = name,
                        url = href.replace("&amp;", "&"),
                        region = region,
                        label = listOfNotNull(label, format, region, host).joinToString(" · "),
                        sourceUrl = entry.url,
                    )
                }
            }
        }
        return files
    }

    companion object {
        /** Console → category slug on the site. */
        val CATEGORIES = mapOf("switch" to "switch", "wiiu" to "game-wii-u", "wii" to "game-wii", "3ds" to "3ds", "nds" to "ds")

        private val CARD = Regex("""<h2 class="post-title entry-title">\s*<a href="([^"]+)">([^<]+)</a>""")
        private val IMG = Regex("""<img[^>]+src="([^"]+)"""")
        private val PAGE_LINK = Regex("""/page/(\d+)/""")
        private val SPOILER_TITLE = Regex("""<div class="su-spoiler-title"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        private val PARAGRAPH = Regex("""<p>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        private val LINK = Regex("""<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        private val TORRENT = Regex("""torrent|magnet|mgnet""", RegexOption.IGNORE_CASE)
        private val PART = Regex("""\bpart\s*\d""", RegexOption.IGNORE_CASE)
        private val PART_SUFFIX = Regex("""\.part\d+(?=\.rar$)""", RegexOption.IGNORE_CASE)
        private val FORMAT = Regex("""\b(NSP|XCI|NSZ|XCZ|WUA|WUX|CIA|3DS|NDS|ISO|WBFS|RVZ)\b""", RegexOption.IGNORE_CASE)
        private val REGION = Regex("""\(([A-Za-z ]+)\)\s*$""")
        private val FILE_EXT = Regex("""\.(rar|zip|7z|nsp|xci|nsz|xcz|wua|cia|3ds|nds|iso|wbfs|rvz)$""", RegexOption.IGNORE_CASE)

        /** The console a post is for, from its slug ("…-nintendo-switch-download-free"). */
        fun systemOf(slug: String): String? {
            val s = "-$slug-"
            return when {
                "-nintendo-switch-" in s || "-switch-nsp-" in s || "-nsp-xci-" in s -> "switch"
                "-wii-u-" in s || "-wiiu-" in s -> "wiiu"
                "-3ds-" in s -> "3ds"
                "-wii-" in s -> "wii"
                "-ds-" in s || "-nds-" in s -> "nds"
                else -> null
            }
        }

        private fun regionName(raw: String): String = when (raw.trim().lowercase()) {
            "usa", "us" -> "USA"
            "eur", "europe", "eu" -> "Europe"
            "jpn", "japan", "jp" -> "Japan"
            "chinese", "china", "ch" -> "China"
            "korea", "kor" -> "Korea"
            else -> raw.trim()
        }

        /** "https://megaup.net/xyz/Name_[0100…][v0].nsp.rar" → "Name_[0100…][v0].nsp.rar". */
        fun filenameOf(url: String): String? {
            val path = url.substringBefore('?').trimEnd('/').substringAfterLast('/').removeSuffix(".html")
            val name = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
            return name.takeIf { FILE_EXT.containsMatchIn(it) }
        }
    }
}
