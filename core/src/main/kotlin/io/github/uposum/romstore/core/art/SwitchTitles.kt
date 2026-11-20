package io.github.uposum.romstore.core.art

import io.github.uposum.romstore.core.model.Content
import io.github.uposum.romstore.core.model.matchTitle
import io.github.uposum.romstore.core.net.readNBytesCompat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream

private val TAGS_RE = Regex("""\s*[(\[{][^)\]}]*[)\]}]""")
private val NOISE_RE = Regex(
    """\b(?:nsp|xci|nsz|xcz|full\s+game|eshop|update|dlc|base\s+game)\b|\bv\d+(?:\.\d+)*\b""",
    RegexOption.IGNORE_CASE,
)
private val ARTICLE_RE = Regex("""^(.+?), (The|A|An)\b""")
private val SPACES = Regex("""\s+""")

/** urllib.parse.unquote_plus: "+" is a space, %XX a UTF-8 byte; a broken escape stays as it is. */
internal fun unquotePlus(text: String): String {
    val out = ByteArrayOutputStream()
    val bytes = text.replace('+', ' ').toByteArray(Charsets.UTF_8)
    var i = 0
    while (i < bytes.size) {
        val b = bytes[i]
        if (b == '%'.code.toByte() && i + 2 < bytes.size) {
            val hex = String(bytes, i + 1, 2, Charsets.US_ASCII).toIntOrNull(16)
            if (hex != null) {
                out.write(hex)
                i += 3
                continue
            }
        }
        out.write(b.toInt())
        i++
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

/**
 * A readable name from a file name: no "+" or "_" for spaces, no tags, no
 * "NSP"/"v0" noise, and "Title, The" turned round.
 */
fun cleanName(title: String?): String {
    var name = title ?: ""
    if ('%' in name || ('+' in name && ' ' !in name.trim())) name = unquotePlus(name)
    name = name.replace('_', ' ')
    name = TAGS_RE.replace(name, " ")
    name = NOISE_RE.replace(name, " ")
    // "Legend of Zelda, The - The Wind Waker" -> "The Legend of Zelda - The Wind Waker"
    name = ARTICLE_RE.replace(name.trim(), "$2 $1")
    return SPACES.replace(name, " ").trim(' ', '-', '.', '+').ifEmpty { (title ?: "").trim() }
}

/**
 * Switch title ID -> name, from the list shipped in the jar
 * (data/switch_titles.json.gz, made from blawar's titledb by the plugin's
 * tools/update_switch_titles.py). It is read on first use (about 19k names),
 * so call from a background thread. `extraNames` can add names the list does
 * not have yet (the plugin reads Ryujinx's game list there).
 */
class SwitchTitles(
    private val resource: String = RESOURCE,
    private val extraNames: () -> Map<String, String> = { emptyMap() },
) {
    @Volatile private var names: Map<String, String>? = null
    private var byKey: Map<String, String> = emptyMap()

    private fun load(): Map<String, String> {
        names?.let { return it }
        synchronized(this) {
            names?.let { return it }
            val loaded = LinkedHashMap<String, String>()
            readResourceJson(resource).obj()?.forEach { (tid, name) ->
                (name as? JsonPrimitive)?.contentOrNull?.let { loaded[tid.uppercase()] = it }
            }
            val keys = HashMap<String, String>()
            for ((tid, name) in loaded) keys.putIfAbsent(matchTitle(name), tid)
            byKey = keys
            names = loaded
            return loaded
        }
    }

    /** The game's name for a title ID, or null. */
    fun name(titleId: String?): String? {
        if (titleId.isNullOrEmpty()) return null
        val tid = titleId.uppercase()
        return load()[tid] ?: extraNames().entries.firstOrNull { it.key.uppercase() == tid }?.value
    }

    /** The base game's title ID for a name, or null. */
    fun find(title: String): String? {
        load()
        for (candidate in listOf(title, cleanName(title))) {
            val key = matchTitle(candidate)
            if (key.isEmpty()) continue
            byKey[key]?.let { return it }
            for ((tid, name) in extraNames()) {
                if (matchTitle(name) == key) return tid.uppercase()
            }
        }
        return null
    }

    /** How many names the shipped list has (0 if it could not be read). */
    val size: Int get() = load().size

    companion object {
        const val RESOURCE = "/data/switch_titles.json.gz"
    }
}

// ---------------------------------------------------------------- title IDs

private val TITLE_ID_RE = Regex("""[\[(]?\b(01[0-9a-fA-F]{14})\b[\])]?""")
private val TICKET_RE = Regex("""^[0-9a-fA-F]{32}\.tik$""")

/**
 * The base game's title ID of a Switch file: from "[0100…]" in the file name,
 * else the ticket name inside an NSP/NSZ (read through `header`, which opens
 * the file; it is only called for .nsp/.nsz), else by the game's name
 * (`lookup`, e.g. [SwitchTitles.find]). Updates (…800) and DLC map to the base
 * game (…000).
 */
fun switchTitleId(
    fileName: String,
    header: (() -> InputStream?)? = null,
    lookup: ((String) -> String?)? = null,
): String? {
    val base = fileName.substringAfterLast('/').substringAfterLast('\\')
    var tid = TITLE_ID_RE.find(base)?.groupValues?.get(1)
    val lower = base.lowercase()
    if (tid == null && header != null && (lower.endsWith(".nsp") || lower.endsWith(".nsz"))) {
        tid = orNull { header()?.use { nspTicketTitle(it) } }
    }
    if (tid == null && lookup != null) {
        val dot = base.lastIndexOf('.')
        tid = lookup(if (dot > 0) base.substring(0, dot) else base)
    }
    if (tid.isNullOrEmpty() || tid.length < 3) return null
    // The base game's ID: an update is base + 0x800, a DLC base + 0x1000 + n
    // (…E000 + 0x1001 = …F001), so the low 13 bits go, not the last three digits.
    return Content.baseTitleId(tid) ?: (tid.dropLast(3) + "000").uppercase()
}

/**
 * An NSP is a PFS0: its ticket is named after the rights ID, which starts with
 * the title ID. Reads the header and the string table from `stream`.
 */
fun nspTicketTitle(stream: InputStream): String? {
    val head = ByteArray(16)
    if (stream.readNBytesCompat(head) < 16) return null
    if (String(head, 0, 4, Charsets.US_ASCII) != "PFS0") return null
    val count = le32(head, 4)
    val table = le32(head, 8)
    if (count > 4096 || table > (1L shl 20)) return null
    if (!skipFully(stream, count * 24)) return null
    val buf = ByteArray(table.toInt())
    val n = stream.readNBytesCompat(buf)
    for (part in String(buf, 0, n, Charsets.ISO_8859_1).split('\u0000')) {
        val text = part.filter { it.code < 128 }
        if (text.lowercase().endsWith(".tik") && TICKET_RE.matches(text)) return text.take(16)
    }
    return null
}

private fun le32(bytes: ByteArray, at: Int): Long =
    (0 until 4).fold(0L) { acc, i -> acc or ((bytes[at + i].toLong() and 0xff) shl (8 * i)) }

private fun skipFully(stream: InputStream, count: Long): Boolean {
    var left = count
    while (left > 0) {
        val skipped = stream.skip(left)
        if (skipped > 0) {
            left -= skipped
        } else {
            if (stream.read() < 0) return false
            left--
        }
    }
    return true
}
