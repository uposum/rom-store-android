package io.github.uposum.romstore.core.art

/** Image URL rules from the plugin's images.py: size variants, the Referer a host wants, image sniffing. */
object ImageUrls {
    private val HOST_RE = Regex("""^https?://([^/]+)/""")

    /**
     * The best sized variant of a known image URL for `kind` ("card" or
     * "detail"). Romspedia publishes several sizes of every cover; the card
     * only needs a small one while the detail view wants the full art.
     */
    fun bestVariant(url: String?, kind: String = "card"): String? {
        if (url.isNullOrEmpty()) return null
        // Up to v1.16.0 screenshots were stored as .../upload/screenshot_huge/...,
        // which IGDB answers with a 404 (size names start with "t_").
        val fixed = url.replace("/upload/screenshot_huge/", "/upload/t_screenshot_huge/")
        if (kind == "detail") return fixed
        // Romspedia: <base>-cover.webp -> <base>-cover-280x398.webp
        if ("static.romspedia.com" in fixed && fixed.endsWith("-cover.webp")) {
            return fixed.dropLast("-cover.webp".length) + "-cover-280x398.webp"
        }
        // IGDB screenshots: the medium one (~50 kB) is plenty for a thumbnail.
        if ("images.igdb.com" in fixed && "/t_screenshot_huge/" in fixed) {
            return fixed.replace("/t_screenshot_huge/", "/t_screenshot_med/")
        }
        // IGDB covers: the 180x256 variant is indistinguishable on a card.
        if ("images.igdb.com" in fixed && "/t_cover_big/" in fixed) {
            return fixed.replace("/t_cover_big/", "/t_cover_med/")
        }
        return fixed
    }

    /**
     * JPEG, PNG, WebP, GIF, BMP or AVIF/HEIC by their first bytes. An error
     * page, a proxy's JSON error or one of libretro's link files is not a
     * picture and must not be cached as one.
     */
    fun looksLikeImage(body: ByteArray): Boolean {
        fun at(offset: Int, text: String): Boolean =
            body.size >= offset + text.length && text.indices.all { body[offset + it] == text[it].code.toByte() }
        val b = body
        return (b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) ||
            (b.size >= 4 && b[0] == 0x89.toByte() && at(1, "PNG")) ||
            (at(0, "RIFF") && at(8, "WEBP")) ||
            at(0, "GIF8") ||
            at(0, "BM") ||
            at(4, "ftypavif") || at(4, "ftypheic")
    }

    /** The Referer an image host expects. */
    fun refererFor(url: String): String {
        val host = HOST_RE.find(url)?.groupValues?.get(1) ?: ""
        if ("myabandonware" in host) return "https://www.myabandonware.com/"
        if ("romspedia" in host) return "https://www.romspedia.com/"
        return if (host.isNotEmpty()) "https://$host/" else ""
    }
}
