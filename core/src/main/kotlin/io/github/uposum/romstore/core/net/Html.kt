package io.github.uposum.romstore.core.net

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Small regex-based HTML helpers, a direct port of the plugin's htmlutil.py.
 * The sites use predictable markup; the parsers are written against these
 * helpers so they behave exactly like the plugin's (same recorded pages, same
 * results in the tests).
 */
object Html {
    private val SCRIPT_STYLE = Regex("<(script|style)\\b.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")
    // (?U): Python 3's \s also matches the no-break space (&nbsp;) and other Unicode spaces.
    private val WS = Regex("${UNI}\\s+")
    private val ATTR = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*("[^"]*"|'[^']*'|[^\s"'>]+)""")

    /** Remove markup and collapse whitespace. */
    fun stripTags(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var text = SCRIPT_STYLE.replace(raw, " ")
        text = BR.replace(text, " ")
        text = TAG.replace(text, " ")
        return collapseWs(unescape(text))
    }

    fun collapseWs(text: String?): String = WS.replace(text ?: "", " ").trim()

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
        "rdquo" to "”", "ldquo" to "“", "copy" to "©", "reg" to "®", "trade" to "™", "eacute" to "é",
        "egrave" to "è", "aacute" to "á", "agrave" to "à", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
        "uuml" to "ü", "ouml" to "ö", "auml" to "ä", "ntilde" to "ñ", "ccedil" to "ç", "szlig" to "ß",
        "times" to "×", "middot" to "·", "laquo" to "«", "raquo" to "»", "deg" to "°", "frac12" to "½",
        "bull" to "•", "euro" to "€", "pound" to "£", "yen" to "¥", "iexcl" to "¡", "iquest" to "¿",
        "Eacute" to "É", "Uuml" to "Ü", "Ouml" to "Ö", "Auml" to "Ä", "ecirc" to "ê", "acirc" to "â",
        "ocirc" to "ô", "icirc" to "î", "ucirc" to "û", "euml" to "ë", "iuml" to "ï", "#039" to "'",
    )
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);?")

    /** html.unescape(): named and numeric character references. */
    fun unescape(text: String?): String {
        if (text.isNullOrEmpty() || '&' !in text) return text ?: ""
        return ENTITY.replaceFast(text) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> NAMED[body] ?: m.value
            }
        }
    }

    fun parseAttrs(raw: String?): Map<String, String> {
        val attrs = LinkedHashMap<String, String>()
        for (m in ATTR.findAllFast(raw ?: "")) {
            val name = m.groupValues[1].lowercase()
            var value = m.groupValues[2]
            if (value.startsWith("'") || value.startsWith("\"")) value = value.substring(1, value.length - 1)
            attrs[name] = unescape(value)
        }
        return attrs
    }

    data class Anchor(val href: String, val attrs: Map<String, String>, val inner: String, val start: Int, val end: Int) {
        val text: String get() = stripTags(inner)
        val title: String get() = attrs["title"] ?: ""
    }

    /** Every <a> element, handling nested anchors like the plugin does. */
    fun anchors(html: String): Sequence<Anchor> = sequence {
        var pos = 0
        val length = html.length
        while (true) {
            val start = html.indexOf("<a", pos)
            if (start == -1) return@sequence
            val tagEnd = html.indexOf(">", start)
            if (tagEnd == -1) return@sequence
            val tagRaw = html.substring(start, tagEnd + 1)
            val third = tagRaw.getOrNull(2)
            if (third == '/' || third == '!') {
                pos = tagEnd + 1; continue
            }
            val attrs = parseAttrs(tagRaw.substring(2, tagRaw.length - 1))
            val href = attrs["href"]
            if (href == null) {
                pos = tagEnd + 1; continue
            }
            var depth = 1
            var scan = tagEnd + 1
            while (scan < length && depth > 0) {
                val nextOpen = html.indexOf("<a", scan)
                val nextClose = html.indexOf("</a", scan)
                if (nextClose == -1) break
                if (nextOpen != -1 && nextOpen < nextClose) {
                    val probe = html.substring(nextOpen, minOf(nextOpen + 4, length)).lowercase()
                    // Same rule as the plugin: "<article", "<aside", ... open
                    // something their "</a..." close balances; "<a " does not.
                    val c = probe.getOrNull(2)
                    if (c == null || c !in "/ >\t\n") depth += 1
                    scan = nextOpen + 2
                    continue
                }
                depth -= 1
                scan = nextClose + 3
            }
            val end = minOf(scan, length)
            val inner = html.substring(tagEnd + 1, maxOf(tagEnd + 1, end))
            yield(Anchor(href, attrs, inner, start, end))
            pos = end
        }
    }

    /** Index just past the closing tag of an element opened before `start`, or -1. */
    fun findMatchingClose(html: String, tag: String, start: Int): Int {
        // One Matcher each, only ever moving forward (see RegexFlags.kt: a
        // new Matcher per step copies the page into native memory on Android).
        val open = Regex("<${Regex.escape(tag)}\\b", RegexOption.IGNORE_CASE).toPattern().matcher(html)
        val close = Regex("</${Regex.escape(tag)}\\s*>", RegexOption.IGNORE_CASE).toPattern().matcher(html)
        var openAt = -1; var openEnd = -1; var openDone = false
        var closeAt = -1; var closeEnd = -1; var closeDone = false
        fun openFrom(i: Int) {
            while (!openDone && openAt < i) {
                if (open.find()) { openAt = open.start(); openEnd = open.end() } else { openDone = true; openAt = -1 }
            }
        }
        fun closeFrom(i: Int) {
            while (!closeDone && closeAt < i) {
                if (close.find()) { closeAt = close.start(); closeEnd = close.end() } else { closeDone = true; closeAt = -1 }
            }
        }
        var depth = 1
        var i = start
        while (i < html.length) {
            closeFrom(i)
            if (closeAt < 0) return -1
            openFrom(i)
            if (openAt >= 0 && openAt < closeAt) {
                depth += 1
                i = openEnd
                continue
            }
            depth -= 1
            i = closeEnd
            if (depth == 0) return i
        }
        return -1
    }

    /** (openTag, blockHtml) for every balanced element of `tag`; `needle`
     * filters on the opening tag only, e.g. 'class="single-rom"'. */
    fun blocks(html: String, tag: String = "div", needle: String? = null): Sequence<Pair<String, String>> = sequence {
        val pattern = Regex("<${Regex.escape(tag)}\\b[^>]*>", RegexOption.IGNORE_CASE)
        for (m in pattern.findAllFast(html)) {
            if (needle != null && needle !in m.value) continue
            val end = findMatchingClose(html, tag, m.range.last + 1)
            if (end == -1) continue
            yield(m.value to html.substring(m.range.first, end))
        }
    }

    fun firstGroup(html: String, pattern: String, group: Int = 1): String? =
        Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.getOrNull(group)

    /** urljoin(base, href), with protocol-relative links taking the base's scheme. */
    fun absolute(base: String, href: String?): String {
        if (href.isNullOrBlank()) return base
        val h = href.trim()
        if (h.startsWith("//")) return (if (base.startsWith("https")) "https:" else "http:") + h
        if (h.startsWith("http://") || h.startsWith("https://")) return h
        return try {
            URI(base).resolve(h.replace(" ", "%20")).toString()
        } catch (_: Exception) {
            try {
                URI(base).resolve(encodeUnsafe(h)).toString()
            } catch (_: Exception) {
                base.trimEnd('/') + "/" + h.trimStart('/')
            }
        }
    }

    private fun encodeUnsafe(s: String): String = buildString {
        for (c in s) {
            if (c.code > 127 || c in " \"<>{}|\\^`[]") {
                for (b in c.toString().toByteArray(StandardCharsets.UTF_8)) append('%').append(String.format("%02X", b))
            } else append(c)
        }
    }

    fun filenameFromUrl(url: String): String {
        val path = try { URI(url.replace(" ", "%20")).rawPath ?: "" } catch (_: Exception) { url.substringBefore('?') }
        val last = path.substringAfterLast('/')
        return try { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { last }
    }

    private val SIZE = Regex("""([\d.,]+)\s*(B|KB|MB|GB|TB|KiB|MiB|GiB)""", RegexOption.IGNORE_CASE)

    /** '5.97MB' -> 5970000 (bytes). */
    fun parseSize(text: String?): Long? {
        if (text.isNullOrEmpty()) return null
        val m = SIZE.find(text) ?: return null
        val value = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val factor = when (m.groupValues[2].lowercase()) {
            "b" -> 1.0
            "kb" -> 1e3; "kib" -> 1024.0
            "mb" -> 1e6; "mib" -> 1024.0 * 1024
            "gb" -> 1e9; "gib" -> 1024.0 * 1024 * 1024
            "tb" -> 1e12; "tib" -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (value * factor).toLong()
    }

    /** A string safe to use as a file name on Android's file systems (FAT/exFAT too). */
    fun safeFilename(name: String?, fallback: String = "download"): String {
        var n = (name ?: "").trim().replace("/", "_").replace("\\", "_")
        n = n.replace(Regex("[\\x00-\\x1f\\x7f]"), "")
        n = n.replace(Regex("[:*?\"<>|]"), "_")
        n = collapseWs(n).trim('.', ' ')
        if (n.isEmpty()) n = fallback
        return n.take(180)
    }

    /** Python's urllib.parse.quote_plus (letters, digits and "_.-~" stay as they are). */
    fun quotePlus(text: String): String = buildString {
        for (b in text.toByteArray(StandardCharsets.UTF_8)) {
            val c = b.toInt() and 0xff
            when {
                c == ' '.code -> append('+')
                (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) ||
                    c == '_'.code || c == '.'.code || c == '-'.code || c == '~'.code -> append(c.toChar())
                else -> append('%').append(String.format("%02X", c))
            }
        }
    }

    /** Python's urllib.parse.quote with safe="/". */
    fun quote(text: String, safe: String = "/"): String = buildString {
        for (b in text.toByteArray(StandardCharsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            when {
                (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) ||
                    ch in "_.-~" || (c < 128 && ch in safe) -> append(ch)
                else -> append('%').append(String.format("%02X", c))
            }
        }
    }

    /** urllib.parse.unquote: decodes every valid %XX run as UTF-8 (bad bytes
     * become U+FFFD) and leaves a stray "%" as it is, so "100% Mario%20Kart"
     * gives "100% Mario Kart" (URLDecoder would throw on it). "+" stays "+". */
    fun unquote(text: String): String {
        if ('%' !in text) return text
        val out = StringBuilder(text.length)
        val bytes = java.io.ByteArrayOutputStream()
        fun flush() {
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), StandardCharsets.UTF_8))
                bytes.reset()
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length &&
                text[i + 1].code < 128 && text[i + 2].code < 128 &&
                Character.digit(text[i + 1], 16) >= 0 && Character.digit(text[i + 2], 16) >= 0
            ) {
                bytes.write(Character.digit(text[i + 1], 16) * 16 + Character.digit(text[i + 2], 16))
                i += 3
            } else {
                flush()
                out.append(c)
                i += 1
            }
        }
        flush()
        return out.toString()
    }

    /** urlencode(fields) for a form body, in the map's order. */
    fun formEncode(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { "${quotePlus(it.key)}=${quotePlus(it.value)}" }
}
