package io.github.uposum.romstore.core.net

import kotlinx.coroutines.delay

/**
 * File hosts that catalogue sites link to instead of serving files
 * themselves. Each is handled the way its own page works for a visitor:
 *
 * - 1fichier: the page counts down (about a minute), then a form hands out a
 *   one-time link. A guest gets one download every so often; when the host
 *   says to wait, that is reported, never worked around. The link is fetched
 *   when the download starts (`resolvesLate`), so a retry gets a fresh one.
 * - megaup, buzzheavier, send.now, vikingfile, datanodes, frdl and the like
 *   put a Cloudflare check or a captcha in front of the file: only a browser
 *   gets it (`needsBrowser`), so the app offers the site's page instead.
 */
object FileHosts {
    private val BROWSER_ONLY = listOf(
        "megaup.net", "buzzheavier.com", "send.now", "vikingfile.com", "vik1ngfile.site",
        "datanodes.to", "frdl.to", "freedlink", "mediafire.com", "gofile.io", "qiwi.gg",
        "ouo.io", "ouo.press", "shrinkme", "mega.nz", "drive.google.com", "uptobox", "rapidgator",
        "nitroflare", "katfile", "ddownload", "filecrypt",
    )

    private val HOST = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^/?#@]*@)?([^/?#:]+)""")

    /** The link's host without "www." (links carry "[", "]" and spaces that java.net.URI rejects). */
    fun host(url: String): String = HOST.find(url.trim())?.groupValues?.get(1)?.lowercase()?.removePrefix("www.") ?: ""

    /** A readable name for the host ("1fichier", "Megaup"). */
    fun label(url: String): String {
        val h = host(url)
        return when {
            h.endsWith("1fichier.com") -> "1fichier"
            h.isEmpty() -> "link"
            else -> h.substringBeforeLast('.').substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    fun needsBrowser(url: String): Boolean {
        val h = host(url)
        return BROWSER_ONLY.any { h == it || h.endsWith(".$it") || it in h }
    }

    fun resolvesLate(url: String): Boolean = host(url).endsWith("1fichier.com")

    /** The host can be downloaded from in the app (directly or after its wait). */
    fun inApp(url: String): Boolean = !needsBrowser(url)

    fun browserMessage(url: String): String =
        "${label(url)} asks for a browser check (Cloudflare or a captcha); get this file from the site in a browser"

    /**
     * The link that serves the bytes, fetched now (it works once).
     * `note` reports what is happening ("1fichier: 42 s").
     */
    suspend fun resolve(http: HttpClient, url: String, note: (String) -> Unit = {}): String {
        if (!resolvesLate(url)) return url
        return oneFichier(http, url, note)
    }

    private val WAIT_MINUTES = Regex("""must wait\s+(\d+)\s+minutes""", RegexOption.IGNORE_CASE)
    private val COUNTDOWN = Regex("""var\s+ct\s*=\s*(\d+)""")
    private val ADZ = Regex("""name="adz"\s+value="([^"]*)"""")
    private val LINK = Regex("""https://[a-z0-9-]+\.1fichier\.com/[A-Za-z0-9_-]{6,}""")

    private suspend fun oneFichier(http: HttpClient, link: String, note: (String) -> Unit): String {
        // "?<file id>&af=<uploader>": with the extra parameter the host answers a page that only sets a cookie.
        val url = link.replace("&amp;", "&").substringBefore('&')
        val page = http.request(url, polite = false).text
        WAIT_MINUTES.find(page)?.let {
            throw HttpError(
                "1fichier lets a guest download one file at a time and then asks to wait ${it.groupValues[1]} minutes; try later or another host",
                429, url, retry = false,
            )
        }
        if ("adz" !in page) throw HttpError("1fichier did not offer this file (it may have been removed)", 404, url, retry = false)
        val adz = ADZ.find(page)?.groupValues?.get(1) ?: ""
        val seconds = (COUNTDOWN.find(page)?.groupValues?.get(1)?.toIntOrNull() ?: 60).coerceIn(0, 180)
        for (left in seconds downTo 1) {
            note("Waiting for 1fichier… $left s")
            delay(1000)
        }
        note("Starting…")
        val answer = http.postForm(url, mapOf("adz" to adz), headers = mapOf("Referer" to url)).text
        WAIT_MINUTES.find(answer)?.let {
            throw HttpError("1fichier asks to wait ${it.groupValues[1]} minutes before the next guest download", 429, url, retry = false)
        }
        return LINK.findAllFast(answer).map { it.value }.firstOrNull { !it.startsWith("https://img.") }
            ?: throw HttpError("1fichier did not hand out the file", 502, url, retry = false)
    }
}
