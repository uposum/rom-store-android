package io.github.uposum.romstore.core.art

import io.github.uposum.romstore.core.net.findAllFast

import io.github.uposum.romstore.core.model.Trailer
import io.github.uposum.romstore.core.net.Html
import io.github.uposum.romstore.core.net.HttpClient
import io.github.uposum.romstore.core.net.HttpError

/**
 * A game's trailer from YouTube's search page (no API key), a port of
 * media.py's youtube_trailer. The result is a [Trailer] with the video id, the
 * watch URL and the medium thumbnail.
 */
class YouTube(private val http: HttpClient) {
    /** The best-matching trailer for a title, or null. */
    suspend fun trailer(title: String): Trailer? {
        if (title.isEmpty()) return null
        val query = Html.quotePlus("$title trailer")
        val body = try {
            http.request(
                SEARCH.format(query),
                headers = mapOf("Accept-Language" to "en-US,en;q=0.9"),
                maxBytes = 4 * 1024 * 1024,
                retries = 1,
            ).text
        } catch (_: HttpError) {
            return null
        }
        return pick(body, title)
    }

    companion object {
        const val SEARCH = "https://www.youtube.com/results?search_query=%s"
        const val THUMB = "https://img.youtube.com/vi/%s/mqdefault.jpg"
        const val EMBED = "https://www.youtube.com/embed/%s?autoplay=1&rel=0"
        const val WATCH = "https://www.youtube.com/watch?v=%s"

        private val VIDEO_RE = Regex(""""videoId":"([A-Za-z0-9_-]{11})"""")
        private val TITLE_RE = Regex(""""title":\{"runs":\[\{"text":"((?:[^"\\]|\\.){3,200})"""")
        private val NON_ALNUM = Regex("[^a-z0-9]+")

        /** Where each result starts: the classic renderer or the newer lockup view model. */
        private val ITEM_RE = Regex(""""(videoRenderer|lockupViewModel)":\{""")
        private val RENDERER_ID_RE = Regex("""\G"videoId":"([A-Za-z0-9_-]{11})"""")
        private val THUMB_ID_RE = Regex("""i\.ytimg\.com/vi/([A-Za-z0-9_-]{11})/""")
        private val LOCKUP_TITLE_RE = Regex(""""lockupMetadataViewModel":\{"title":\{"content":"((?:[^"\\]|\\.){3,200})"""")
        private val LOCKUP_TYPE_RE = Regex(""""contentType":"LOCKUP_CONTENT_TYPE_([A-Z_]+)"""")
        /** How far one result reaches at most (a lockup is about 10 kB). */
        private const val ITEM_SPAN = 30_000

        private fun normalise(title: String?): String = NON_ALNUM.replace((title ?: "").lowercase(), " ").trim()

        /** A trailer for a YouTube video id: watch URL as `url`, the medium thumbnail. */
        fun trailer(videoId: String, name: String?): Trailer =
            Trailer(id = videoId, url = WATCH.format(videoId), thumb = THUMB.format(videoId), name = name)

        /** The embeddable player URL for a video id. */
        fun embedUrl(videoId: String): String = EMBED.format(videoId)

        /**
         * Pick the trailer from a search page: the video whose title starts with
         * (3), contains (2) or is contained in (1) the game's title, plus 2 for
         * "trailer" or 1 for "gameplay"/"announcement"; the first with 5 wins.
         * Each result's id and title are read from that result (a renderer has
         * many "videoId"s, so pairing them across the page mixed them up).
         */
        fun pick(body: String, title: String): Trailer? {
            val pairs = results(body).ifEmpty {
                // Neither layout found: ids and titles in page order, as the plugin does.
                VIDEO_RE.findAllFast(body).map { it.groupValues[1] }.toList()
                    .zip(TITLE_RE.findAllFast(body).map { it.groupValues[1] }.toList())
            }
            val wanted = normalise(title)
            if (wanted.isEmpty()) return null
            var best: Pair<String, String>? = null
            var bestScore = 0
            for ((video, raw) in pairs) {
                val name = jsonUnescape(raw)
                val clean = normalise(name.replace("\"", ""))
                var score = when {
                    clean.startsWith(wanted) -> 3
                    wanted in clean -> 2
                    clean in wanted -> 1
                    else -> continue
                }
                if ("trailer" in clean) score += 2 else if ("gameplay" in clean || "announcement" in clean) score += 1
                if (score > bestScore) {
                    bestScore = score
                    best = video to name
                    if (score >= 5) break
                }
            }
            return best?.let { trailer(it.first, it.second) }
        }

        /** (video id, title as written in the page) for every video result, in
         * page order; playlists, channels and shelves are left out. Walks the
         * page with one Matcher per pattern (see RegexFlags.kt). */
        fun results(body: String): List<Pair<String, String>> {
            val starts = ITEM_RE.findAllFast(body).map { it.range.first to it.groupValues[1] }.toList()
            if (starts.isEmpty()) return emptyList()
            val rendererId = RENDERER_ID_RE.toPattern().matcher(body)
            val thumbId = THUMB_ID_RE.toPattern().matcher(body)
            val runsTitle = TITLE_RE.toPattern().matcher(body)
            val lockupTitle = LOCKUP_TITLE_RE.toPattern().matcher(body)
            val lockupType = LOCKUP_TYPE_RE.toPattern().matcher(body)
            fun java.util.regex.Matcher.within(from: Int, to: Int): String? {
                region(from, to)
                return if (find()) group(1) else null
            }
            val out = mutableListOf<Pair<String, String>>()
            starts.forEachIndexed { i, (at, kind) ->
                val end = minOf(starts.getOrNull(i + 1)?.first ?: body.length, at + ITEM_SPAN, body.length)
                val open = at + kind.length + 4 // past "kind":{
                if (kind == "videoRenderer") {
                    val id = rendererId.within(open, end) ?: return@forEachIndexed
                    val name = runsTitle.within(open, end) ?: return@forEachIndexed
                    out += id to name
                } else {
                    val type = lockupType.within(open, end)
                    if (type != null && type != "VIDEO") return@forEachIndexed
                    val id = thumbId.within(open, end) ?: return@forEachIndexed
                    val name = lockupTitle.within(open, end) ?: return@forEachIndexed
                    out += id to name
                }
            }
            return out
        }

        /** A JSON string's escapes (\u0026, \", \\, \/) decoded. */
        private fun jsonUnescape(raw: String): String {
            if ('\\' !in raw) return raw
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c != '\\' || i + 1 >= raw.length) { out.append(c); i++; continue }
                when (val e = raw[i + 1]) {
                    'u' -> {
                        val code = raw.substring(i + 2, minOf(raw.length, i + 6)).takeIf { it.length == 4 }?.toIntOrNull(16)
                        if (code != null) { out.append(code.toChar()); i += 6 } else { out.append(e); i += 2 }
                    }
                    'n' -> { out.append(' '); i += 2 }
                    't' -> { out.append(' '); i += 2 }
                    else -> { out.append(e); i += 2 }
                }
            }
            return out.toString()
        }
    }
}
