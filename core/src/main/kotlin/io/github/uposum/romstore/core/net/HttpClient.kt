package io.github.uposum.romstore.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HttpError(
    message: String,
    val status: Int? = null,
    val url: String? = null,
    /** False when trying again cannot help (a web page instead of the file, a browser check). */
    val retry: Boolean = status == null || status >= 500,
) : IOException(message)

class Response(
    val status: Int,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    var body: ByteArray = ByteArray(0),
) {
    /** Header value regardless of case. */
    fun header(name: String, default: String = ""): String =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: default

    val text: String
        get() {
            val ctype = header("content-type")
            var charset = "utf-8"
            if ("charset=" in ctype) charset = ctype.substringAfter("charset=").substringBefore(";").trim().ifEmpty { "utf-8" }
            return try {
                String(body, Charset.forName(charset))
            } catch (_: Exception) {
                String(body, Charsets.UTF_8)
            }
        }

    fun json(): JsonElement = Json.parseToJsonElement(text)

    val ok: Boolean get() = status in 200..399
}

/** What a speed sample measured: bytes read in `seconds` after the first byte came at `firstByte`. */
data class Sample(val bytes: Double, val seconds: Double, val firstByte: Double)

/** Where a download is written. The app implements it on top of Android's
 * storage access framework; tests use a plain file. */
interface DownloadSink {
    /** Bytes already there from an earlier attempt (0 when starting fresh). */
    fun existingSize(): Long

    /** Open for writing; `append` continues after the existing bytes. */
    fun open(append: Boolean): OutputStream

    /** Free space where the file goes, or null when unknown. */
    fun freeSpace(): Long? = null
}

const val DEFAULT_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

fun humanSize(size: Long): String {
    var value = size.toDouble()
    for (unit in listOf("B", "KB", "MB", "GB", "TB")) {
        if (value < 1024 || unit == "TB") return if (unit == "B") "${value.toLong()} B" else String.format("%.1f %s", value, unit)
        value /= 1024
    }
    return String.format("%.1f TB", value)
}

/**
 * HTTP for the sources, artwork lookups and downloads: OkHttp with an
 * in-memory cookie jar (some sites hand out a session with their forms), a
 * small per-host gap for the scrapers (`polite`), retries for transient
 * failures and streaming downloads with resume. A port of httpclient.py.
 */
open class HttpClient(
    private val userAgent: String = DEFAULT_UA,
    timeoutSeconds: Long = 30,
    private val minIntervalMs: Long = 250,
) {
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = cookieStore.getOrPut(url.host) { mutableListOf() }
            synchronized(list) {
                for (c in cookies) {
                    list.removeAll { it.name == c.name && it.path == c.path }
                    list.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val out = mutableListOf<Cookie>()
            for ((_, list) in cookieStore) synchronized(list) {
                list.removeAll { it.expiresAt < now }
                out += list.filter { it.matches(url) }
            }
            return out
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val noRedirect: OkHttpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val downloadClient: OkHttpClient = client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()

    /** For downloads handed over by the in-app browser: they bring their own Cookie header. */
    private val downloadClientOwnCookies: OkHttpClient = downloadClient.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()

    private val hostLocks = ConcurrentHashMap<String, Mutex>()
    private val lastRequest = ConcurrentHashMap<String, Long>()

    /** Keep a small gap between requests to the same host (politeness). */
    private suspend fun throttle(host: String) {
        if (host.isEmpty() || minIntervalMs <= 0) return
        hostLocks.getOrPut(host) { Mutex() }.withLock {
            val wait = (lastRequest[host] ?: 0L) + minIntervalMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequest[host] = System.currentTimeMillis()
        }
    }

    private fun host(url: String): String = url.toHttpUrlOrNull()?.host ?: ""

    private fun build(url: String, method: String, data: ByteArray?, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url)
        val hasUa = headers.keys.any { it.equals("User-Agent", true) }
        if (!hasUa) builder.header("User-Agent", userAgent)
        if (headers.keys.none { it.equals("Accept", true) }) {
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        }
        if (headers.keys.none { it.equals("Accept-Language", true) }) builder.header("Accept-Language", "en-US,en;q=0.9")
        for ((k, v) in headers) builder.header(k, v)
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
        when (method.uppercase()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            else -> builder.method(method.uppercase(), (data ?: ByteArray(0)).toRequestBody(contentType?.toMediaTypeOrNull()))
        }
        return builder.build()
    }

    private fun openOnce(url: String, method: String, data: ByteArray?, headers: Map<String, String>, allowRedirects: Boolean, maxBytes: Int): Response {
        val call = (if (allowRedirects) client else noRedirect).newCall(build(url, method, data, headers))
        try {
            call.execute().use { resp ->
                val body = resp.body.source().let { source ->
                    val buffer = okio.Buffer()
                    var total = 0L
                    while (total < maxBytes) {
                        val read = source.read(buffer, minOf(65536L, maxBytes - total))
                        if (read == -1L) break
                        total += read
                    }
                    buffer.readByteArray()
                }
                val headerMap = LinkedHashMap<String, String>()
                for ((k, v) in resp.headers) headerMap.putIfAbsent(k, v)
                return Response(resp.code, resp.request.url.toString(), headerMap, body)
            }
        } catch (e: UnknownHostException) {
            throw HttpError("Host not found: ${host(url)}", url = url)
        } catch (e: InterruptedIOException) {
            throw HttpError("Request timed out: $url", url = url)
        } catch (e: IOException) {
            throw HttpError("Request failed: ${e.message ?: e.javaClass.simpleName}", url = url)
        } catch (e: IllegalArgumentException) {
            throw HttpError("Bad address: $url", url = url)
        }
    }

    /** Fetch a URL, retrying transient failures. `polite` keeps the per-host
     * gap that the scrapers need; image CDNs do not need it. */
    open suspend fun request(
        url: String,
        method: String = "GET",
        data: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true,
        maxBytes: Int = 8 * 1024 * 1024,
        retries: Int = 2,
        polite: Boolean = true,
    ): Response {
        var last: HttpError? = null
        for (attempt in 0..retries) {
            currentCoroutineContext().ensureActive()
            if (polite) throttle(host(url))
            try {
                val resp = withContext(Dispatchers.IO) { openOnce(url, method, data, headers, allowRedirects, maxBytes) }
                if (resp.status >= 500 && resp.status != 501 && resp.status != 505) {
                    last = HttpError("HTTP ${resp.status} for $url", resp.status, url)
                } else if (resp.status >= 400) {
                    throw HttpError("HTTP ${resp.status} for $url", resp.status, url)
                } else {
                    return resp
                }
            } catch (e: HttpError) {
                if (e.status != null && e.status in 400..499) throw e
                if (e.message?.startsWith("Host not found") == true || e.message?.startsWith("Bad address") == true) throw e
                last = e
            }
            if (attempt < retries) delay(800L * (attempt + 1))
        }
        throw last ?: HttpError("Request failed: $url", url = url)
    }

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 8 * 1024 * 1024): String =
        request(url, "GET", null, headers, maxBytes = maxBytes).text

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap(), polite: Boolean = false): JsonElement =
        request(url, "GET", null, mapOf("Accept" to "application/json") + headers, polite = polite).json()

    suspend fun postJson(url: String, payload: String, headers: Map<String, String> = emptyMap()): Response =
        request(url, "POST", payload.toByteArray(), mapOf("Content-Type" to "application/json") + headers)

    /** POST an html form (used by sites that hand out a session token). */
    suspend fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): Response {
        val body = Html.formEncode(fields).toByteArray()
        val hdrs = LinkedHashMap<String, String>()
        hdrs["Content-Type"] = "application/x-www-form-urlencoded"
        val parts = url.split("/", limit = 4)
        if (parts.size > 2) hdrs["Origin"] = parts[0] + "//" + parts[2]
        hdrs["Referer"] = url
        hdrs.putAll(headers)
        return request(url, "POST", body, hdrs)
    }

    /** The final URL of a redirect chain, without downloading the body. */
    open suspend fun resolveRedirect(url: String, headers: Map<String, String> = emptyMap(), maxHops: Int = 8): String {
        var current = url
        repeat(maxHops) {
            val resp = withContext(Dispatchers.IO) { openOnce(current, "GET", null, headers, false, 65536) }
            if (resp.status in REDIRECT_CODES) {
                val location = resp.header("Location").ifEmpty { return current }
                current = Html.absolute(current, location)
            } else {
                return current
            }
        }
        return current
    }

    /** A clear reason when a file server wants a browser, else null. */
    fun blocked(code: Int, headers: okhttp3.Headers, host: String): String? {
        val cloudflare = headers.values("Server").any { "cloudflare" in it.lowercase() } || headers["cf-mitigated"] != null
        if (code in setOf(403, 429, 503) && cloudflare) {
            return "$host asks for a browser check (Cloudflare); get this file from the site in a browser"
        }
        return null
    }

    /** A download link that answered with a web page instead of the file. */
    fun isWebPage(contentType: String?, first: ByteArray): Boolean {
        val start = String(first, 0, minOf(first.size, 2048), Charsets.ISO_8859_1).trimStart().lowercase()
        return "text/html" in (contentType ?: "").lowercase() && (start.startsWith("<!doctype html") || "<html" in start)
    }

    /** Read the start of a download and throw it away: how fast it comes. */
    open suspend fun sample(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Long = 8L * 1024 * 1024, maxSeconds: Double = 6.0): Sample =
        withContext(Dispatchers.IO) {
            val host = host(url)
            throttle(host)
            val req = build(url, "GET", null, headers + ("Accept-Encoding" to "identity"))
            val started = System.nanoTime()
            try {
                downloadClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw HttpError(blocked(resp.code, resp.headers, host) ?: "HTTP ${resp.code} from $host", resp.code, url)
                    }
                    val stream = resp.body.byteStream()
                    val first = ByteArray(65536)
                    val n = stream.readNBytesCompat(first)
                    val firstByte = (System.nanoTime() - started) / 1e9
                    if (isWebPage(resp.header("Content-Type"), first.copyOf(maxOf(n, 0)))) {
                        throw HttpError("the link opens a download page on ${resp.request.url.host}, which needs a browser", url = url, retry = false)
                    }
                    val transfer = System.nanoTime()
                    var read = 0L
                    val buf = ByteArray(262144)
                    while (n + read < maxBytes && (System.nanoTime() - transfer) / 1e9 < maxSeconds) {
                        val r = stream.read(buf)
                        if (r < 0) break
                        read += r
                    }
                    val seconds = (System.nanoTime() - transfer) / 1e9
                    Sample(read.toDouble(), maxOf(seconds, 0.001), firstByte)
                }
            } catch (e: HttpError) {
                throw e
            } catch (e: IOException) {
                throw HttpError("Network error: ${e.message}", url = url)
            }
        }

    /**
     * Stream a URL into `sink`. Returns the size of the file. With `resume`
     * the bytes already in the sink are continued with a Range request; a
     * server that ignores the range sends the whole file again, which then
     * simply overwrites it.
     */
    open suspend fun download(
        url: String,
        sink: DownloadSink,
        headers: Map<String, String> = emptyMap(),
        resume: Boolean = false,
        cancel: AtomicBoolean = AtomicBoolean(false),
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        val host = host(url)
        throttle(host)
        var existing = if (resume) sink.existingSize() else 0L
        val hdrs = LinkedHashMap(headers)
        if (existing > 0) {
            hdrs["Range"] = "bytes=$existing-"
            hdrs["Accept-Encoding"] = "identity"
        }
        val ownCookies = hdrs.keys.any { it.equals("Cookie", ignoreCase = true) }
        val call = (if (ownCookies) downloadClientOwnCookies else downloadClient).newCall(build(url, "GET", null, hdrs))
        val response = try {
            call.execute()
        } catch (e: UnknownHostException) {
            throw HttpError("Host not found: $host", url = url)
        } catch (e: IOException) {
            throw HttpError("Download failed: ${e.message}", url = url)
        }
        response.use { resp ->
            if (resp.code == 416 && existing > 0) {
                onProgress(existing, existing)
                return@withContext existing
            }
            if (!resp.isSuccessful) {
                throw HttpError(blocked(resp.code, resp.headers, host) ?: "HTTP ${resp.code} while downloading $url", resp.code, url)
            }
            val partial = existing > 0 && resp.code == 206
            if (!partial) existing = 0
            var total = resp.body.contentLength().takeIf { it > 0 } ?: 0L
            if (total > 0) {
                total += existing
                val free = sink.freeSpace()
                if (free != null && free > 0 && total - existing > free) {
                    throw HttpError("Not enough free space: the file needs ${humanSize(total - existing)}, ${humanSize(free)} is free", url = url, retry = false)
                }
            }
            val stream = resp.body.byteStream()
            var written = existing
            var first: ByteArray? = null
            if (!partial) {
                val buf = ByteArray(65536)
                val n = stream.readNBytesCompat(buf)
                first = buf.copyOf(maxOf(n, 0))
                if (isWebPage(resp.header("Content-Type"), first)) {
                    throw HttpError(
                        "The link opens a download page on ${resp.request.url.host} instead of the file; get it from the site in a browser",
                        url = url,
                        retry = false,
                    )
                }
            }
            try {
                sink.open(append = partial).use { out ->
                    if (first != null && first.isNotEmpty()) {
                        out.write(first)
                        written += first.size
                        onProgress(written, total)
                    }
                    val buf = ByteArray(262144)
                    while (true) {
                        if (cancel.get()) throw HttpError("Download cancelled", url = url)
                        currentCoroutineContext().ensureActive()
                        val r = stream.read(buf)
                        if (r < 0) break
                        if (r == 0) continue
                        out.write(buf, 0, r)
                        written += r
                        onProgress(written, total)
                    }
                }
            } catch (e: HttpError) {
                throw e
            } catch (e: IOException) {
                throw HttpError("Download failed: ${e.message}", url = url)
            }
            onProgress(written, written)
            written
        }
    }
}

/** InputStream.readNBytes for API levels before 33: fill `buf` as far as the stream allows. */
fun java.io.InputStream.readNBytesCompat(buf: ByteArray): Int {
    var n = 0
    while (n < buf.size) {
        val r = read(buf, n, buf.size - n)
        if (r < 0) break
        n += r
    }
    return n
}
