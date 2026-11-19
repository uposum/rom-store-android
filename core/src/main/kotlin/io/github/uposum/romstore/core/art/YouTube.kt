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
        private val TITLE_RE = Regex(""""title":\{"runs":\[\{"text":"((?:[^"\\]|\\.){3,90})"""")
        private val NON_ALNUM = Regex("[^a-z0-9]+")

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
         * Ids and titles are paired in page order, as the plugin does.
         */
        fun pick(body: String, title: String): Trailer? {
            val ids = VIDEO_RE.findAllFast(body).map { it.groupValues[1] }.toList()
            val titles = TITLE_RE.findAllFast(body).map { it.groupValues[1] }.toList()
            val wanted = normalise(title)
            if (wanted.isEmpty()) return null
            var best: Pair<String, String>? = null
            var bestScore = 0
            for ((video, name) in ids.zip(titles)) {
                val clean = normalise(name.replace("\\\"", ""))
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
    }
}
