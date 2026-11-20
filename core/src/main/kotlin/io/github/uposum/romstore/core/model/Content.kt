package io.github.uposum.romstore.core.model

import io.github.uposum.romstore.core.net.findAllFast
import kotlinx.serialization.Serializable

/** What a downloadable file holds. */
@Serializable
enum class ContentKind {
    /** The game itself. */
    BASE,
    /** An update (patch) for a game. */
    UPDATE,
    /** Downloadable content: an expansion, a character pack, ... */
    DLC,
    /** The game together with its update and/or DLC in one download. */
    BUNDLE,
}

@Serializable
data class ContentInfo(
    val kind: ContentKind,
    /** "1.6.0", or "#3" for a Switch update numbered by the system. */
    val version: String? = null,
    /** A Switch title ID found in the name (16 hex digits). */
    val titleId: String? = null,
    val includesUpdate: Boolean = false,
    val includesDlc: Boolean = false,
) {
    val isAddon: Boolean get() = kind == ContentKind.UPDATE || kind == ContentKind.DLC

    /** "Update 1.6.0", "DLC", "Game + update 1.6.0 + DLC", "Includes update 1.3.0", or null for a plain base game. */
    val label: String?
        get() = when (kind) {
            ContentKind.UPDATE -> "Update" + (version?.let { " $it" } ?: "")
            ContentKind.DLC -> "DLC"
            ContentKind.BUNDLE -> listOfNotNull(
                "Game",
                if (includesUpdate) "update" + (version?.let { " $it" } ?: "") else null,
                if (includesDlc) "DLC" else null,
            ).joinToString(" + ")
            ContentKind.BASE -> if (includesUpdate && version != null) "Includes update $version" else null
        }
}

/**
 * Tells base games, updates, DLC and bundles apart from what the sites call
 * their files and posts ("[0100F2C0115B6800][v196608].nsp", "Update 1.6.0",
 * "Plus DLC With Update", "…-Update-1.6.0C-DLCs.rar"), and finds the game an
 * add-on belongs to.
 */
object Content {
    /** Consoles whose games get updates and DLC as separate files. */
    val ADDON_SYSTEMS = setOf("switch", "wiiu", "3ds", "ps3", "psvita", "ps4")

    const val UPDATES_FOLDER = "updates"
    const val DLC_FOLDER = "dlc"

    private val TITLE_ID = Regex("""(?<![0-9a-fA-F])(01[0-9a-fA-F]{14})(?![0-9a-fA-F])""")
    private val SWITCH_VERSION = Regex("""\[v(\d{1,10})]""", RegexOption.IGNORE_CASE)
    private val DOTTED_VERSION = Regex("""(?<![\w.])v?(\d{1,2}\.\d{1,2}(?:\.\d{1,3})?[a-z]?)(?![\w.])""", RegexOption.IGNORE_CASE)
    private val DLC_WORDS = Regex("""\b(dlcs?|add[- ]?ons?|season[ _-]?pass|expansion[ _-]?pass|downloadable[ _-]?content)\b""", RegexOption.IGNORE_CASE)
    // "Update400" (a site's "Update 4.0.0" without dots) counts too.
    private val UPDATE_WORDS = Regex("""\b(updates?|updated|upd|patch(?:ed)?)\d*\b""", RegexOption.IGNORE_CASE)
    private val FIRMWARE_NOTE = Regex("""\([^)]*firmware[^)]*\)""", RegexOption.IGNORE_CASE)
    private val BASE_WORDS = Regex("""\b(base[ _-]?game|base|full[ _-]?game)\b""", RegexOption.IGNORE_CASE)
    private val JOIN_WORDS = Regex("""(\+|&|\bplus\b|\bwith\b|\bincl(?:udes?|uding)?\b|\band\b)""", RegexOption.IGNORE_CASE)

    /** Everything that names add-ons in a title, for finding the game itself. */
    private val ADDON_PHRASE = Regex(
        """\s*[-–:(\[]*\s*(?:\+|&|\bplus\b|\bwith\b|\bincl(?:udes?|uding)?\b|\band\b)?\s*(?:all\s+)?""" +
            """(?:\b(?:dlcs?|updates?|updated|upd|patch(?:ed)?|add[- ]?ons?|season pass|expansion pass)\b""" +
            """(?:\s*v?\d{1,2}(?:\.\d{1,3}){1,2}[a-z]?)?)\s*[)\]]*""",
        RegexOption.IGNORE_CASE,
    )

    /** A Switch title ID in a name, upper case, or null. */
    fun titleId(text: String?): String? = text?.let { TITLE_ID.find(it)?.groupValues?.get(1)?.uppercase() }

    /**
     * The base game's title ID for an update's or DLC's ID. Base IDs are
     * multiples of 0x2000; the update is base + 0x800, DLC base + 0x1000 + n.
     */
    fun baseTitleId(titleId: String): String? {
        val value = titleId.toULongOrNull(16) ?: return null
        return (value and 0x1FFFuL.inv()).toString(16).uppercase().padStart(16, '0')
    }

    private fun kindOfTitleId(titleId: String): ContentKind? {
        val value = titleId.toULongOrNull(16) ?: return null
        return when (value and 0x1FFFuL) {
            0uL -> ContentKind.BASE
            0x800uL -> ContentKind.UPDATE
            else -> ContentKind.DLC
        }
    }

    /**
     * What a file holds, from its file name and (optionally) the post's title.
     * The file name wins where both say something; titles of game pages often
     * mention updates the file does not contain.
     */
    /**
     * A file as a site lists it: its name says what it is, else the label the
     * site gives it ("Update 4.0.1 (v1507328)", "Booster Course DLC"), for
     * hosts whose file names say nothing.
     */
    fun classifyFile(filename: String?, label: String?, title: String? = null): ContentInfo {
        val byName = classify(filename, title)
        if (label.isNullOrBlank()) return byName
        // "(Required Firmware – v22.5.0)" is not the update's version.
        val byLabel = classify(FIRMWARE_NOTE.replace(label, ""), null)
        if (byName.isAddon || byName.kind == ContentKind.BUNDLE) {
            // The label's "4.0.1" reads better than the system's "#23".
            val better = byLabel.version?.takeIf { byLabel.kind == byName.kind && !it.startsWith("#") && (byName.version == null || byName.version.startsWith("#")) }
            return if (better != null) byName.copy(version = better) else byName
        }
        return if (byLabel.isAddon) byLabel.copy(titleId = byLabel.titleId ?: byName.titleId) else byName
    }

    fun classify(filename: String?, title: String? = null): ContentInfo {
        val name = (filename ?: "").replace('_', ' ').replace('+', ' ')
        val text = name + " " + (title ?: "")
        val tid = titleId(name) ?: titleId(title)
        val switchVersion = SWITCH_VERSION.find(name)?.groupValues?.get(1)?.toLongOrNull()
        val dotted = DOTTED_VERSION.findAllFast(name.replace('-', ' ')).map { it.groupValues[1] }.lastOrNull()
            ?: DOTTED_VERSION.findAllFast((title ?: "").replace('-', ' ')).map { it.groupValues[1] }.lastOrNull()

        // A Switch title ID says it exactly.
        tid?.let { id ->
            when (kindOfTitleId(id)) {
                ContentKind.UPDATE -> return ContentInfo(ContentKind.UPDATE, dotted ?: switchVersion?.let(::switchVersionLabel), id)
                ContentKind.DLC -> return ContentInfo(ContentKind.DLC, null, id)
                else -> {}
            }
        }

        val dlc = DLC_WORDS.containsMatchIn(name) || (name.isBlank() && DLC_WORDS.containsMatchIn(text))
        val update = UPDATE_WORDS.containsMatchIn(name) || (name.isBlank() && UPDATE_WORDS.containsMatchIn(text))
        val base = BASE_WORDS.containsMatchIn(name) || tid != null
        val joined = JOIN_WORDS.containsMatchIn(name)

        // The post's title can say the download bundles everything ("Plus DLC With Update").
        val titleDlc = title != null && DLC_WORDS.containsMatchIn(title)
        val titleUpdate = title != null && UPDATE_WORDS.containsMatchIn(title)
        val titleJoined = title != null && JOIN_WORDS.containsMatchIn(title)

        return when {
            (dlc || update) && (base || joined || (dlc && update)) ->
                ContentInfo(ContentKind.BUNDLE, if (update) dotted else null, tid, includesUpdate = update, includesDlc = dlc)
            dlc -> ContentInfo(ContentKind.DLC, null, tid)
            update -> ContentInfo(ContentKind.UPDATE, dotted, tid)
            // Only the post says so: a game page whose title adds "Plus DLC With Update".
            !base && titleJoined && (titleDlc || titleUpdate) && name.isNotBlank() && !BASE_WORDS.containsMatchIn(name) && switchVersion == null ->
                ContentInfo(ContentKind.BUNDLE, if (titleUpdate) dotted else null, tid, includesUpdate = titleUpdate, includesDlc = titleDlc)
            else -> {
                // "Super Mario Odyssey v1.3.0.xci": the game with an update built in.
                val built = dotted != null && !base && dotted != "1.0" && dotted != "1.0.0"
                ContentInfo(ContentKind.BASE, if (built) dotted else null, tid, includesUpdate = built)
            }
        }
    }

    /** Switch update numbers: v196608 is the 3rd update (65536 per release). */
    private fun switchVersionLabel(value: Long): String? = if (value <= 0) null else "#${value / 65536}"

    /** The game a title is about, without "Update 1.2", "+ DLC", "Plus DLC With Update"... */
    fun baseTitle(title: String): String {
        var t = title
        repeat(3) { t = ADDON_PHRASE.replaceFast(t, "") }
        t = t.replace(Regex("""\s+[-–:+&]+\s*$"""), "").trim()
        return t.ifEmpty { title }
    }

    /** Matching key for "the same game", add-on words left out. */
    fun baseKey(title: String): String = matchTitle(baseTitle(title))

    private fun Regex.replaceFast(input: String, with: String): String {
        val matcher = toPattern().matcher(input)
        val out = StringBuilder()
        var last = 0
        while (matcher.find()) {
            out.append(input, last, matcher.start()).append(with)
            last = matcher.end()
        }
        out.append(input, last, input.length)
        return out.toString()
    }
}
