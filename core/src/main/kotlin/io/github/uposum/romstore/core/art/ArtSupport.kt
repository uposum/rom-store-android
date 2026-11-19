package io.github.uposum.romstore.core.art

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.GZIPInputStream

/** Where the art lookups run their background work by default. */
fun defaultArtScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Seconds since the epoch, like Python's time.time() (the cache files store it). */
internal fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

/** Wait for background jobs up to `seconds`; whatever is not done keeps running. */
internal suspend fun waitFor(jobs: Collection<Job>, seconds: Double) {
    if (jobs.isEmpty() || seconds <= 0) return
    withTimeoutOrNull((seconds * 1000).toLong().coerceAtLeast(1)) { jobs.joinAll() }
}

/** Run `block`, turning any failure except cancellation into null. */
internal inline fun <T> orNull(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** Small JSON cache files: read leniently, written atomically (temp file + rename). */
internal object JsonFiles {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(file: File?): JsonElement? {
        if (file == null || !file.isFile) return null
        return orNull { Json.parseToJsonElement(file.readText(Charsets.UTF_8)) }
    }

    fun write(file: File?, element: JsonElement) {
        if (file == null) return
        try {
            file.parentFile?.mkdirs()
            // A name of its own: two background writers never share a temp file.
            val temp = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
            temp.writeText(element.toString(), Charsets.UTF_8)
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                if (!temp.renameTo(file)) {
                    temp.delete()
                }
            }
        } catch (_: Exception) {
            // A cache that cannot be written is only slower next time.
        }
    }
}

/** A gzipped JSON file shipped in the jar (core/src/main/resources/data/). */
internal fun readResourceJson(path: String): JsonElement? = orNull {
    val stream = ArtKeys::class.java.getResourceAsStream(path) ?: return null
    GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()) }
}

// ---------------------------------------------------------------- json access

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray

/** A string field ("" and non-strings count as missing). */
internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

/** A field as text whatever its JSON type (ids may be numbers). */
internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotEmpty() }

internal fun JsonObject.number(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

/** Python truthiness of a JSON value. */
internal fun truthy(value: JsonElement?): Boolean = when (value) {
    null, JsonNull -> false
    is JsonObject -> value.isNotEmpty()
    is JsonArray -> value.isNotEmpty()
    is JsonPrimitive -> when {
        value.isString -> value.content.isNotEmpty()
        value.booleanOrNull != null -> value.booleanOrNull == true
        else -> (value.doubleOrNull ?: 0.0) != 0.0
    }
}
