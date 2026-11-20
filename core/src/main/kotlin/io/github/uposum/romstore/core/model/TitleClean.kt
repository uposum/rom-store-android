package io.github.uposum.romstore.core.model

import io.github.uposum.romstore.core.net.replaceFast

/**
 * A game's name as people write it, from what the sites and uploads call it:
 *
 *     "Ratchet & Clank: A Crack in Time [BCES-00511] (Review Code)" → "Ratchet & Clank: A Crack in Time"
 *     "SCES 532.85.Ratchet - Gladiator"                            → "Ratchet - Gladiator"
 *     "Ratchet Clank - Size Matters (v1.01)"                       → "Ratchet Clank - Size Matters"
 *     "Mario_Kart_8_Deluxe_[0100152000022000][v0].nsp"             → "Mario Kart 8 Deluxe"
 *     "Gran Turismo 4 USA SCUS 97328 ( 1.01) PS 2 .iso"            → "Gran Turismo 4"
 *
 * Tags (regions, languages, versions, serials, dump flags, site names) go;
 * what a download bundles ("+ Update", "(DLC)") stays, because it tells a
 * game from its add-ons. Box art, SteamGridDB and IGDB are looked up with
 * these names, so a clean name is also a found cover.
 */
object TitleClean {
    private val I = RegexOption.IGNORE_CASE

    private val EXTENSION = Regex(
        """\.(nsp|nsz|xci|xcz|iso|cso|chd|pbp|zip|7z|rar|bin|cue|gdi|cdi|wbfs|rvz|wua|wux|cia|3ds|nds|gba|gbc|gb|nes|sfc|smc|n64|z64|v64|pkg|vpk|md|gen)$""",
        I,
    )
    /** Console serials: BCES-00511, SLUS_209.37, NPEA00457, SCES 532.85, CUSA12345. */
    private val SERIAL = Regex("""[\[(]?\b[A-Z]{4}[-_ ]?\d{3}[.\s]?\d{2}\b[\])]?\.?""")
    /** A site's name in a release ("[taodung.com]", "(nsw2u.com)", "-Ziperto"). */
    private val SITE = Regex("""[\[(]\s*[\w.-]*\.(com|net|org|to|io|me|cc|in|info|xyz)\s*[\])]|[-_ ](ziperto|nswtch|nsw2u|romsfun|taodung)\b""", I)
    private val GROUP = Regex("""\s*[\[(][^\[\]()]*[\])]""")
    private val ADDON_WORDS = Regex("""\b(updates?|upd|dlcs?|add-?ons?|season pass|bundle|plus)\b|\+""", I)
    /** Words that only say what kind of file or where it is from, at the end of a name. */
    private val TRAILING = Regex(
        """(?:[\s_.,:-]+(?:usa|eur|europe|jpn|japan|pal|ntsc(?:-[uj])?|ntsc-u|world|multi\d*|en|review|demo|iso|rom|roms|nsp|xci|nsz|cso|chd|ps ?[1-5]|psp|psx|switch|nintendo switch|wii ?u?|gamecube|ngc|3ds|nds|full game|free download|download|v?\d+\.\d+(?:\.\d+)?))+\s*$""",
        I,
    )
    private val SPACES = Regex("""\s{2,}""")
    private val DOTTED = Regex("""(\w\.){2,}\w""")

    fun display(raw: String?): String {
        var t = (raw ?: "").trim()
        if (t.isEmpty()) return t
        t = EXTENSION.replace(t, "")
        // "Mario_Kart_8" and "Mario.Kart.8.Deluxe" are spaced with something else.
        if (' ' !in t && ('_' in t || DOTTED.containsMatchIn(t))) t = t.replace('_', ' ').replace('.', ' ')
        t = t.replace(" _ ", " & ").replace('_', ' ')
        t = SITE.replace(t, "")
        t = SERIAL.replace(t, " ")
        // Bracket and parenthesis tags, except those that say what is bundled.
        var previous: String
        do {
            previous = t
            t = GROUP.replaceFast(t) { m -> if (ADDON_WORDS.containsMatchIn(m.value)) m.value else "" }
        } while (t != previous)
        t = SPACES.replace(t, " ").trim()
        repeat(3) { t = TRAILING.replace(t, "").trim() }
        t = t.trim(' ', '-', ':', ',', '.', '_', '|')
        t = SPACES.replace(t, " ")
        return t.ifEmpty { (raw ?: "").trim() }
    }
}

/**
 * Whether a result is about what was searched for: every word of the query
 * starts a word of the title, or (words of five letters and more) is one
 * typo away from one ("ratcher" finds "Ratchet"). Sites that search their
 * whole pages (WordPress) or match loosely return games that only mention
 * the words; those are left out.
 */
object Relevance {
    private val STOP = setOf("the", "a", "an", "of", "and", "&", "to", "in", "for", "on")

    fun matches(query: String, title: String): Boolean {
        val words = normalizeTitle(query).split(' ').filter { it.isNotEmpty() && it !in STOP }
        if (words.isEmpty()) return true
        val titleWords = normalizeTitle(title).split(' ').filter { it.isNotEmpty() }
        val joined = titleWords.joinToString("")
        return words.all { w ->
            titleWords.any { it.startsWith(w) } || (w.length >= 4 && w in joined) ||
                (w.length >= 5 && titleWords.any { t -> close(w, t) || (t.length > w.length && close(w, t.take(w.length))) })
        }
    }

    /** At most one letter changed, added or left out. */
    private fun close(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}

/**
 * The name a download is saved under: the game's clean name, what the file
 * is (update, DLC), its region, and for Switch files the title ID and version
 * the emulators and the library read. Disc sets whose files refer to each
 * other by name (.cue/.bin, .gdi, .m3u) keep their names.
 *
 *     "Mario_Kart_8_Deluxe_[0100152000022800][v1507328]_[taodung.com].rar"
 *         → "Mario Kart 8 Deluxe Update 4.0.1 [0100152000022800][v1507328].rar"
 */
object FileNames {
    private val KEEP = listOf(".cue", ".bin", ".gdi", ".m3u", ".ccd", ".img", ".sub", ".mds", ".mdf", ".toc")
    private val EXT = Regex("""(\.(nsp|nsz|xci|xcz|iso|cso|chd|pbp|wbfs|rvz|wua|wux|cia|3ds|nds|gba|gbc|gb|nes|sfc|smc|n64|z64|v64|pkg|vpk|md|gen|sms|gg|pce|elf))?\.(zip|7z|rar|nsp|nsz|xci|xcz|iso|cso|chd|pbp|wbfs|rvz|wua|wux|cia|3ds|nds|gba|gbc|gb|nes|sfc|smc|n64|z64|v64|pkg|vpk|md|gen|sms|gg|pce|elf)$""", RegexOption.IGNORE_CASE)
    private val SWITCH_VERSION = Regex("""\[v\d+]""", RegexOption.IGNORE_CASE)
    private val FORBIDDEN = Regex("""[\\/:*?"<>|]""")

    /** The clean name without extension and without Switch IDs ("Mario Kart 8 Deluxe Update 4.0.1 (Europe)"). */
    fun base(title: String, content: ContentInfo, region: String?): String {
        val game = TitleClean.display(Content.baseTitle(title)).ifEmpty { TitleClean.display(title) }
        val what = when (content.kind) {
            ContentKind.BASE -> null
            else -> content.label?.takeIf { it.isNotEmpty() }
        }
        val reg = region?.takeIf { it.isNotBlank() && it.length <= 24 && it.lowercase() !in setOf("world", "unknown") }
        return listOfNotNull(game, what, reg?.let { "($it)" }).joinToString(" ")
    }

    /** `original` renamed to `base`, keeping its extension and the Switch title ID / version it carries. */
    fun rename(original: String, base: String, system: String): String {
        val lower = original.lowercase()
        if (KEEP.any { lower.endsWith(it) }) return original
        val ext = EXT.find(original)?.value ?: return original
        val ids = if (system == "switch") {
            listOfNotNull(Content.titleId(original)?.let { "[$it]" }, SWITCH_VERSION.find(original)?.value?.lowercase()).joinToString("")
        } else ""
        // "Title: Subtitle" is written "Title - Subtitle" in file names (":" is not allowed on SD cards).
        val joined = listOf(base, ids).filter { it.isNotEmpty() }.joinToString(" ").replace(": ", " - ")
        val name = FORBIDDEN.replace(joined, " ").replace(Regex("\\s{2,}"), " ").trim()
        return if (name.isEmpty()) original else name + ext.lowercase()
    }
}
