package io.github.uposum.romstore.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * Signed download links that stop working. File hosts put an expiry into the
 * link (`?token=<base64 JSON with "e"/"exp">`, `?expires=<seconds>`); a site
 * that never renews its posts keeps handing out a dead one, which answers
 * HTTP 410. Reading the expiry saves a failed download.
 */
object LinkCheck {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val TOKEN_PARAMS = setOf("token", "t", "auth", "sig", "signature", "jwt")
    private val TIME_PARAMS = setOf("expires", "expire", "expiry", "exp", "e")
    private val TOKEN_FIELDS = listOf("e", "exp", "expires", "expiry")

    /** When the link stops working, in epoch seconds, if it says so. */
    fun expiresAt(url: String): Long? {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val name = pair.substringBefore('=').lowercase()
            val value = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull() ?: continue
            if (name in TIME_PARAMS) value.toLongOrNull()?.let { if (plausible(it)) return it }
            if (name in TOKEN_PARAMS) fromToken(value)?.let { return it }
        }
        return null
    }

    /** The link's own expiry has passed (a minute's margin for the download to start). */
    fun expired(url: String, nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        expiresAt(url)?.let { it < nowSeconds + 60 } ?: false

    fun expiredMessage(url: String): String {
        val at = expiresAt(url)
        val date = at?.let { SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(it * 1000)) }
        return "The site's link to this file expired${date?.let { " on $it" } ?: ""}; the site has to renew it"
    }

    /** What a failed status means, without the (long, signed) URL. */
    fun statusMessage(code: Int, host: String): String = when (code) {
        410 -> "The site's link to this file has expired (HTTP 410 from $host)"
        404 -> "The file is gone from $host (HTTP 404): the site's link is broken"
        401, 403 -> "$host refused the download (HTTP $code)"
        429 -> "$host is limiting downloads (HTTP 429); try again later"
        else -> "HTTP $code from $host"
    }

    private fun plausible(seconds: Long) = seconds in 1_000_000_000L..99_999_999_999L

    /** A JWT (`header.payload.sig`) or a `payload.sig` token whose payload is base64 JSON. */
    private fun fromToken(token: String): Long? {
        for (part in token.split('.').take(2)) {
            val text = decode(part) ?: continue
            if (!text.trimStart().startsWith("{")) continue
            val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: continue
            for (field in TOKEN_FIELDS) {
                val value = runCatching { obj[field]?.jsonPrimitive?.longOrNull }.getOrNull() ?: continue
                if (plausible(value)) return value
            }
        }
        return null
    }

    private fun decode(part: String): String? = runCatching {
        val clean = part.replace('+', '-').replace('/', '_').trimEnd('=')
        String(Base64.getUrlDecoder().decode(clean), Charsets.UTF_8)
    }.getOrNull()
}
