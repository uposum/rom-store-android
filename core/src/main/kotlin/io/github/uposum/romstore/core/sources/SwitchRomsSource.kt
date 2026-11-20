package io.github.uposum.romstore.core.sources

import io.github.uposum.romstore.core.net.findAllFast

import io.github.uposum.romstore.core.net.UNI
import io.github.uposum.romstore.core.model.GameMedia
import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.RomFile
import io.github.uposum.romstore.core.model.Screenshot
import io.github.uposum.romstore.core.model.extractRegion
import io.github.uposum.romstore.core.net.Html
import io.github.uposum.romstore.core.net.HttpClient
import io.github.uposum.romstore.core.net.HttpError
import io.github.uposum.romstore.core.net.LinkCheck
import kotlin.coroutines.cancellation.CancellationException

/**
 * Nintendo Switch source (port of backend/sources/switchroms.py).
 *
 * A Switch-only catalogue. The game pages are server rendered and link
 * straight to the .nsp/.xci file, so no captcha or script is involved. The
 * address is the user's, which also makes it easy to point the adapter at a
 * mirror of the same site.
 */
class SwitchRomsSource(http: HttpClient, options: SourceOptions) : Source(http, options) {
    override val supportsBrowse: Boolean = true

    /** Game pages already fetched (media() and resolve() read the same page). */
    private val pages = HashMap<String, String>()

    // ------------------------------------------------------------------ search
    override suspend fun search(query: String, limit: Int, page: Int): List<RomEntry> {
        val term = Html.quotePlus(query)
        val url = if (page <= 1) "$base/?s=$term" else "$base/page/$page/?s=$term"
        val html = http.getText(url)
        return parseResults(html).take(limit)
    }

    override fun browseSystems(): Set<String> = setOf("switch")

    /** Walk the catalogue pages so the browse view shows the whole library.
     * (The plugin's default limit here is 200; pass it explicitly.) */
    override suspend fun browse(system: String, limit: Int): List<RomEntry> {
        // A Switch-only catalogue: browsing any other console would fetch a
        // dozen pages only for the store to filter every result out again.
        if (system != "switch") return emptyList()
        // The site is slow (about 1.7 s a page): four pages at a time.
        return pagedCatalogue({ n -> if (n == 1) "$base/" else "$base/page/$n/" }, limit, maxPages = 12, parallel = 4) { parseCards(it) }
    }


    /** Parse the card grid used on the catalogue page. */
    private fun parseCards(html: String): List<RomEntry> {
        val entries = mutableListOf<RomEntry>()
        val seen = HashSet<String>()
        val host = urlParse(base).netloc
        val pattern = Regex(
            """<a[^>]+href="(https?://""" + Regex.escape(host) + """/[^"#?]+/)"[^>]*>(.*?)</a>""",
            setOf(I, S),
        )
        for (match in pattern.findAllFast(html)) {
            val href = match.groupValues[1]
            val inner = match.groupValues[2]
            if (href in seen) continue
            val path = urlParse(href).path
            if (DATE_PATH_RE.containsMatchIn(path)) continue
            if (SKIP_PREFIXES.any { path.startsWith(it) }) continue
            if (path.trim('/') in setOf("", "nintendo-switch-roms")) continue
            var title = Html.collapseWs(Html.stripTags(inner))
            if (title.isEmpty() || title.codePointCount(0, title.length) < 6) continue
            val low = title.lowercase()
            if (low == "read more" || low == "download" || low.startsWith("by ")) continue
            title = cleanTitle(title)
            if (title.isEmpty()) continue
            seen += href
            val cover = nearbyImage(html, match.range.first)
            entries += RomEntry(
                source = id,
                sourceId = href,
                title = title,
                system = "switch",
                url = href,
                cover = cover,
                region = extractRegion(title),
                rank = entries.size,
            )
        }
        return entries
    }

    // ------------------------------------------------------------------ parsing
    private fun parseResults(html: String): List<RomEntry> {
        val entries = mutableListOf<RomEntry>()
        val seen = HashSet<String>()
        for (match in RESULT_RE.findAllFast(html)) {
            val imageHtml = match.groupValues[2]
            val titleHref = match.groupValues[3]
            val titleHtml = match.groupValues[4]
            val url = titleHref.trim()
            if (url in seen) continue
            seen += url
            val title = cleanTitle(Html.collapseWs(Html.stripTags(titleHtml)))
            if (title.isEmpty()) continue
            val cover = IMG_RE.find(imageHtml)?.let { Html.absolute(base, it.groupValues[1]) }
            entries += RomEntry(
                source = id,
                sourceId = url,
                title = title,
                system = "switch",
                url = Html.absolute(base, url),
                cover = cover,
                region = extractRegion(title),
                rank = entries.size,
            )
        }
        return entries
    }

    // ------------------------------------------------------------------ details
    private suspend fun page(url: String): String {
        synchronized(pages) { pages[url] }?.let { return it }
        val html = http.getText(url)
        synchronized(pages) {
            if (pages.size > 24) pages.clear()
            pages[url] = html
        }
        return html
    }

    /** The site publishes wallpapers/box art for every game. */
    override suspend fun media(entry: RomEntry): GameMedia? {
        val html = try {
            page(entry.url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val urls = mutableListOf<String>()
        // Only the images of the game itself - the page also links the
        // artwork of related and popular games.
        val body = ownContent(html)
        for (match in UPLOAD_SRC_RE.findAllFast(body)) {
            val url = match.groupValues[1].split("?")[0]
            if ("logo" in url.lowercase() || url in urls) continue
            urls += url
            if (urls.size >= 5) break
        }
        if (urls.isEmpty()) return null
        return GameMedia(shots = urls.map { Screenshot(thumb = it, full = it) })
    }

    override suspend fun resolve(entry: RomEntry): List<RomFile> {
        val html = page(entry.url)
        // Some pages only expose the link through the download button.
        val match = FILE_RE.find(html) ?: BUTTON_RE.find(html) ?: return emptyList()
        val url = match.groupValues[1].replace("&amp;", "&")
        // Some posts carry a signed link that has long expired; the site never renews it.
        if (LinkCheck.expired(url)) throw HttpError(LinkCheck.expiredMessage(url), 410, url, retry = false)
        val filename = filenameFrom(url)
        val size = remoteSize(url, entry.url)
        return listOf(
            RomFile(
                id = "${entry.sourceId}-0",
                filename = filename,
                url = url,
                size = size,
                region = entry.region,
                label = entry.region,
                sourceUrl = entry.url,
            ),
        )
    }

    /** Ask the file host how big the file is (the pages do not say). */
    private suspend fun remoteSize(url: String, referer: String): Long? = try {
        val response = http.request(url, "HEAD", headers = mapOf("Referer" to referer), maxBytes = 1024, retries = 1)
        val length = response.header("content-length")
        if (length.isNotEmpty() && length.all { it in '0'..'9' }) length.toLongOrNull() else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpError) {
        // A file that is gone (vikingfile and co. answer 404, signed links 410) is not offered.
        if (e.status == 404 || e.status == 410) throw HttpError(LinkCheck.statusMessage(e.status, runCatching { java.net.URI(url.replace(" ", "%20")).host }.getOrNull() ?: "the file host"), e.status, url, retry = false)
        null
    } catch (_: Exception) {
        null
    }

    companion object {
        private val I = RegexOption.IGNORE_CASE
        private val S = RegexOption.DOT_MATCHES_ALL

        // "(?U)" makes \s, \d and \b Unicode-aware, like Python 3's str patterns.
        private val FILE_RE = Regex("""href="(https?://[^"]+?\.(?:nsp|xci|nspz|xcz)(?:\?[^"]*)?)"""", I)
        private val BUTTON_RE = Regex("""${UNI}<a[^>]+href="(https?://[^"]+)"[^>]*>(?:(?!</a>).)*?Download\s+Rom""", setOf(I, S))
        private val RESULT_RE = Regex(
            """${UNI}<div class="search-grid-img[^"]*">\s*<a href="([^"]+)">(.*?)</a>.*?""" +
                """<h4 class="search-grid-title[^"]*">\s*<a href="([^"]+)">(.*?)</a>""",
            setOf(I, S),
        )
        private val IMG_RE = Regex("""<img[^>]+src="([^"]+)"""", I)
        private val UPLOAD_SRC_RE = Regex("""src="(https?://[^"]+/wp-content/uploads/[^"]+)"""")
        private val DATE_PATH_RE = Regex("""${UNI}^/\d{4}/\d{2}/\d{2}/?$""")
        private val SKIP_PREFIXES = listOf("/author/", "/category/", "/tag/", "/feed", "/page/")
        private val MULTI_SPACE_RE = Regex("""${UNI}\s{2,}""")
        private val HASH_PREFIX_RE = Regex("""${UNI}^\d+[a-z0-9]*-""")

        // What the site adds around a game's name: "Download Pokémon Violet",
        // "Super Smash Bros Ultimate Switch (NSP/XCI) Download", "Minecraft Switch
        // (NSP/ROM) & Update", "Pikmin 2 Latest", "Disgaea Mayhem NSP/XCI Full Game".
        private val NOISE: List<Regex> = listOf(
            """^Download\s*[-–|:]?\s+""",
            """\s+Plus\s+ROMs?\s+Free\s+Download\s*$""",
            """\s+Free\s+Download\s*$""",
            """\s*[-–|]?\s*\bDownload\s*$""",
            """\s*\((?:NSP|XCI|ROM)(?:\s*/\s*(?:NSP|XCI|ROM))*\)""",
            """\s+(?:NSP|XCI)(?:\s*[/ ]\s*(?:NSP|XCI))*\b""",
            """\s*[\(\[]\s*Updated?\s*[\)\]]""",
            """\s*(?:[-–&+]|\bPlus)\s*Updated?\s*$""",
            """\s+Updated\s*$""",
            """\s*[-–]?\s*\bLatest\s*$""",
            """\s*[-–]?\s*\bFull\s+Game\b""",
            """\s+(?:For\s+)?Nintendo(?:\s+Switch)?(?:\s+ROMs?)?\s*$""",
            """\s+Switch(?:\s+ROMs?)?\s*$""",
            """\s+ROMs?\s*$""",
        ).map { Regex("${UNI}$it", I) }

        /** A game's name without the site's noise around it (the original if
         * nothing would be left). */
        fun cleanTitle(title: String?): String {
            val original = Html.collapseWs(title ?: "")
            var t = original
            repeat(4) {
                val before = t
                for (pattern in NOISE) t = pattern.replace(t, " ").trim { it in " -–|:" }
                t = MULTI_SPACE_RE.replace(t, " ").trim()
                if (t == before) return t.ifEmpty { original }
            }
            return t.ifEmpty { original }
        }

        /** The last /uploads/ image within 1600 characters before `position`. */
        private fun nearbyImage(html: String, position: Int): String? {
            // Python slices by code point; step back 1600 of them.
            val back = minOf(1600, html.codePointCount(0, position))
            val start = html.offsetByCodePoints(position, -back)
            val window = html.substring(start, position)
            for (match in IMG_RE.findAllFast(window).toList().asReversed()) {
                val src = match.groupValues[1]
                if ("/uploads/" in src) return src
            }
            return null
        }

        /** The part of the page that belongs to the game itself. */
        private fun ownContent(html: String): String {
            for (marker in listOf("class=\"entry-content\"", "class=\"post-content\"", "id=\"the-content\"")) {
                val start = html.indexOf(marker)
                if (start == -1) continue
                val end = html.indexOf("</article>", start)
                return html.substring(start, if (end != -1) end else html.length)
            }
            // Fall back to the main article block.
            val start = html.indexOf("<article")
            if (start != -1) {
                val end = html.indexOf("</article>", start)
                if (end != -1) return html.substring(start, end)
            }
            return html
        }

        private fun filenameFrom(url: String): String {
            val parsed = urlParse(url)
            val query = parsed.query
            var name = ""
            if ("filename=" in query) {
                // A query value: "+" is a space there.
                name = Html.unquote(query.substringAfter("filename=").substringBefore("&").replace("+", " "))
            }
            if (name.isEmpty()) name = Html.unquote(parsed.path.substringAfterLast('/'))
            name = name.trim()
            name = HASH_PREFIX_RE.replace(name, "") // strip the hash prefix
            return name.ifEmpty { "switch-rom.nsp" }
        }

        private class UrlParts(val netloc: String, val path: String, val query: String)

        /** urllib.parse.urlparse, the parts this adapter reads (path without ";params"). */
        private fun urlParse(url: String): UrlParts {
            var rest = url
            var scheme = ""
            val colon = rest.indexOf(':')
            if (colon > 0 && rest[0].isAsciiLetter() &&
                rest.substring(0, colon).all { it.isAsciiLetter() || it in '0'..'9' || it in "+-." }
            ) {
                scheme = rest.substring(0, colon).lowercase()
                rest = rest.substring(colon + 1)
            }
            var netloc = ""
            if (rest.startsWith("//")) {
                val end = rest.indexOfAny(charArrayOf('/', '?', '#'), 2).let { if (it == -1) rest.length else it }
                netloc = rest.substring(2, end)
                rest = rest.substring(end)
            }
            rest = rest.substringBefore('#')
            var query = ""
            val q = rest.indexOf('?')
            if (q != -1) {
                query = rest.substring(q + 1)
                rest = rest.substring(0, q)
            }
            var path = rest
            if (scheme in USES_PARAMS && ';' in path) {
                val i = path.indexOf(';', maxOf(0, path.lastIndexOf('/')))
                if (i >= 0) path = path.substring(0, i)
            }
            return UrlParts(netloc, path, query)
        }

        private val USES_PARAMS = setOf(
            "", "ftp", "hdl", "prospero", "http", "imap", "https", "shttp", "rtsp", "rtspu", "sip", "sips", "mms", "sftp", "tel",
        )

        private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
    }
}
