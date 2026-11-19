package io.github.uposum.romstore.core.model

import io.github.uposum.romstore.core.net.findAllFast

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.text.Normalizer

// Region hints that show up in ROM titles: (USA), (U), [E], (Japan), ...
private val REGION_TOKENS = setOf(
    "usa", "us", "u", "europe", "eu", "e", "japan", "j", "jp", "world", "w",
    "asia", "korea", "brazil", "canada", "australia", "scandinavia", "italy",
    "spain", "france", "germany", "china", "hong kong", "taiwan", "unknown",
    "multi", "region free", "regionfree",
)
private val SHORT_REGIONS = setOf("eu", "jp", "us", "uk", "au", "br", "kr", "cn")

private val REGION_RE = Regex("""[(\[]\s*([^)\]]{1,24}?)\s*[)\]]""")
private val SPACES = Regex("""\s+""")
private val COMBINING = Regex("""\p{Mn}+""")

/** Pull the region out of a ROM title such as 'Sonic (USA).zip'. */
fun extractRegion(title: String?): String? {
    if (title.isNullOrEmpty()) return null
    for (m in REGION_RE.findAllFast(title)) {
        val group = m.groupValues[1].trim()
        val token = group.lowercase().replace(SPACES, " ")
        if (token in REGION_TOKENS) return if (token.length <= 3) group.uppercase() else group
        if (token.matches(Regex("[a-z]{2,3}")) && token in SHORT_REGIONS) return group.uppercase()
    }
    return null
}

/** 'Pokémon' -> 'Pokemon', so accents do not split a title in two. */
fun asciiFold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKD).replace(COMBINING, "").filter { it.code < 128 }

private val EXTENSIONS = setOf(
    "zip", "7z", "rar", "iso", "xci", "nsp", "rom", "bin", "chd", "cue", "gz", "tar", "exe", "gb", "gbc", "gba",
    "nes", "sfc", "smc", "z64", "n64", "v64", "nds", "3ds", "cia", "md", "gen", "sms", "gg", "32x", "pbp", "cso",
    "rvz", "wbfs", "wua", "m3u", "pce", "ws", "wsc", "ngp", "ngc", "vb", "min", "a26", "a78", "lnx", "col", "int",
    "vec", "adf", "d64", "dsk", "st", "png",
)
private val FILLER = setOf("the", "a", "an", "of", "and", "version", "edition")

/**
 * Title without region/tag noise - used to detect duplicates.
 *
 * One pass over the characters instead of six regex replacements: building a
 * box-art index runs this on thousands of names, and on Android's regex
 * engine the regex version took half a minute. Same result as the plugin's
 * normalize_title (StoreTest checks it against the regex version).
 */
fun normalizeTitle(title: String?): String {
    if (title.isNullOrEmpty()) return ""
    var text = (if (title.all { it.code < 128 }) title else asciiFold(title)).lowercase()
    // A file extension at the very end: "super mario (usa).zip".
    val dot = text.lastIndexOf('.')
    if (dot >= 0 && text.substring(dot + 1) in EXTENSIONS) text = text.substring(0, dot)
    // Tags "(...)" and "[...]" become spaces, "&" becomes " and ", anything
    // else that is not a-z/0-9 a space.
    val out = StringBuilder(text.length + 8)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '(' || c == '[') {
            var j = i + 1
            while (j < text.length && text[j] != ')' && text[j] != ']') j++
            if (j < text.length) {
                out.append(' ')
                i = j + 1
                continue
            }
        }
        when {
            c == '&' -> out.append(" and ")
            c in 'a'..'z' || c in '0'..'9' -> out.append(c)
            else -> out.append(' ')
        }
        i++
    }
    return out.split(' ').filter { it.isNotEmpty() && it !in FILLER }.joinToString(" ")
}

/** Key for matching a title against box art and library files: the normalised
 * title without spaces, so "Mega Man X" and "MegaMan X" agree. */
fun matchTitle(title: String?): String = normalizeTitle(title).replace(" ", "")

fun titleSortKey(title: String?): String =
    (title ?: "").trim().replace(Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE), "").lowercase()

/** A single downloadable file for a ROM entry. */
@Serializable
data class RomFile(
    val id: String,
    val filename: String,
    val url: String = "",
    val size: Long? = null,
    val region: String? = null,
    val label: String? = null,
    /** Page the url was resolved from. */
    val sourceUrl: String? = null,
)

/** One result coming from one source. Mutable like the Python dataclass:
 * resolving a game fills in its details in place. */
@Serializable
data class RomEntry(
    val source: String,
    val sourceId: String,
    var title: String,
    var system: String,
    var url: String = "",
    var cover: String? = null,
    var coverFull: String? = null,
    var summary: String? = null,
    var rating: Double? = null,
    var ratingCount: Int? = null,
    var downloads: Long? = null,
    var year: Int? = null,
    var size: Long? = null,
    var region: String? = null,
    var developer: String? = null,
    var publisher: String? = null,
    var genres: List<String> = emptyList(),
    var files: List<RomFile> = emptyList(),
    var rank: Int = 0,
    /** Free-form extras: "error", "details" (map), "boxart", "gametdb", "banner", ... */
    var extra: MutableMap<String, JsonElement> = mutableMapOf(),
) {
    val key: String get() = "$source:$sourceId"
    val normalizedTitle: String get() = normalizeTitle(title)
    val error: String? get() = extra.str("error")
}

/** A de-duplicated title that may exist on several sources. */
@Serializable
data class MergedRom(
    var id: String,
    val title: String,
    val system: String,
    var entries: List<RomEntry> = emptyList(),
    var media: GameMedia? = null,
    var installed: Boolean = false,
) {
    val sources: List<String> get() = entries.map { it.source }.distinct()
    val cover: String? get() = entries.firstNotNullOfOrNull { it.cover }
    val coverFull: String? get() = entries.firstNotNullOfOrNull { it.coverFull ?: it.cover }
    val rating: Double? get() = entries.mapNotNull { it.rating }.maxOrNull()
    val downloads: Long? get() = entries.mapNotNull { it.downloads }.maxOrNull()
    val year: Int? get() = entries.firstNotNullOfOrNull { it.year }
    val regions: List<String>
        get() = entries.mapNotNull { it.region ?: extractRegion(it.title) }.distinct()
    val size: Long?
        get() {
            for (e in entries) {
                e.size?.takeIf { it > 0 }?.let { return it }
                e.files.firstNotNullOfOrNull { f -> f.size?.takeIf { it > 0 } }?.let { return it }
            }
            return null
        }
    val summary: String? get() = media?.summary ?: entries.firstNotNullOfOrNull { it.summary }
}

@Serializable
data class Screenshot(val thumb: String, val full: String = thumb)

@Serializable
data class Trailer(val id: String? = null, val url: String? = null, val thumb: String? = null, val name: String? = null)

/** Artwork and facts for the game page (IGDB, the site's own gallery, YouTube). */
@Serializable
data class GameMedia(
    val cover: String? = null,
    val shots: List<Screenshot> = emptyList(),
    val trailer: Trailer? = null,
    val summary: String? = null,
    val genres: List<String> = emptyList(),
    val developers: List<String> = emptyList(),
    val released: String? = null,
)

// ---------------------------------------------------------------- extra helpers

fun MutableMap<String, JsonElement>.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

fun MutableMap<String, JsonElement>.putStr(key: String, value: String?) {
    if (value == null) remove(key) else this[key] = kotlinx.serialization.json.JsonPrimitive(value)
}

fun MutableMap<String, JsonElement>.flag(key: String): Boolean {
    val v = this[key] as? kotlinx.serialization.json.JsonPrimitive ?: return false
    return if (v.isString) v.content.isNotEmpty() else v.content != "false" && v.content != "null" && v.content != "0"
}

fun MutableMap<String, JsonElement>.putFlag(key: String, value: Boolean = true) {
    this[key] = kotlinx.serialization.json.JsonPrimitive(value)
}

fun MutableMap<String, JsonElement>.putDetails(details: Map<String, String>) {
    this["details"] = kotlinx.serialization.json.JsonObject(
        details.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) },
    )
}

fun MutableMap<String, JsonElement>.details(): Map<String, String> {
    val obj = this["details"] as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
    return obj.mapValues { (it.value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" }
}
