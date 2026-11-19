package io.github.uposum.romstore.core.art

import io.github.uposum.romstore.core.net.HttpClient
import io.github.uposum.romstore.core.net.Response
import java.util.Collections

/** One request as the code under test made it. */
data class Asked(val url: String, val method: String, val headers: Map<String, String>, val body: String?)

/** An HttpClient whose answers come from `handler` (throw HttpError for a failure). */
class FakeHttp(private val handler: suspend (Asked) -> Response) : HttpClient(minIntervalMs = 0) {
    val asked: MutableList<Asked> = Collections.synchronizedList(mutableListOf())

    override suspend fun request(
        url: String, method: String, data: ByteArray?, headers: Map<String, String>,
        allowRedirects: Boolean, maxBytes: Int, retries: Int, polite: Boolean,
    ): Response {
        val call = Asked(url, method, headers, data?.toString(Charsets.UTF_8))
        asked += call
        return handler(call)
    }

    companion object {
        fun text(url: String, body: String, status: Int = 200) =
            Response(status, url, mapOf("Content-Type" to "text/plain; charset=utf-8"), body.toByteArray())

        val unused = FakeHttp { error("no network in this test: ${it.url}") }
    }
}
