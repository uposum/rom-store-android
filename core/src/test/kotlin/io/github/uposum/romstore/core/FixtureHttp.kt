package io.github.uposum.romstore.core

import io.github.uposum.romstore.core.net.HttpClient
import io.github.uposum.romstore.core.net.HttpError
import io.github.uposum.romstore.core.net.Response
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Answers from a recording of the real sites (tests/fixtures in the plugin
 * repository, recorded by tools/record_fixtures.py there). A request that was
 * not recorded fails loudly: a parser that suddenly asks for other pages is a
 * change worth looking at.
 */
class FixtureHttp(private val calls: JsonObject) : HttpClient(minIntervalMs = 0) {
    val asked = mutableListOf<String>()

    private fun key(method: String, url: String, data: ByteArray?): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(data ?: ByteArray(0))
            .joinToString("") { "%02x".format(it) }.take(10)
        return "$method $url $digest"
    }

    private fun lookup(key: String): JsonObject {
        asked += key
        return calls[key]?.jsonObject ?: throw HttpError("not in the recording: $key")
    }

    override suspend fun request(
        url: String, method: String, data: ByteArray?, headers: Map<String, String>,
        allowRedirects: Boolean, maxBytes: Int, retries: Int, polite: Boolean,
    ): Response {
        val item = lookup(key(method, url, data))
        item["error"]?.let { throw HttpError(it.jsonPrimitive.content, item["status"]?.jsonPrimitive?.intOrNull, url) }
        val headerMap = (item["headers"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull ?: "" } ?: emptyMap()
        return Response(
            status = item["status"]!!.jsonPrimitive.int,
            url = item["url"]!!.jsonPrimitive.content,
            headers = headerMap,
            body = Base64.getDecoder().decode(item["body"]!!.jsonPrimitive.content),
        )
    }

    override suspend fun resolveRedirect(url: String, headers: Map<String, String>, maxHops: Int): String =
        lookup(key("REDIRECT", url, null))["final"]!!.jsonPrimitive.content

    companion object {
        fun fixture(sourceId: String): JsonObject {
            val stream = FixtureHttp::class.java.getResourceAsStream("/fixtures/$sourceId.json.gz")
                ?: error("no fixture recorded for $sourceId")
            val text = GZIPInputStream(stream).bufferedReader().readText()
            return Json.parseToJsonElement(text).jsonObject
        }
    }
}
