package io.github.uposum.romstore.core.store

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

internal val storeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/** Read a small JSON file, or null when it is missing or broken. */
internal fun <T> readJson(file: File?, serializer: KSerializer<T>): T? {
    if (file == null || !file.isFile) return null
    return runCatching { storeJson.decodeFromString(serializer, file.readText()) }.getOrNull()
}

/** Write a small JSON file atomically (temp file + rename). */
internal fun <T> writeJson(file: File?, serializer: KSerializer<T>, value: T) {
    file ?: return
    runCatching {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(storeJson.encodeToString(serializer, value))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
