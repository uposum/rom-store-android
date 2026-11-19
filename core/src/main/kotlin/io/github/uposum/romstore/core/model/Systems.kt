package io.github.uposum.romstore.core.model

/**
 * Console normalisation and folder names.
 *
 * `canonicalSystem()` turns whatever a source calls a platform ("N64",
 * "Nintendo 64", "playstation-1", "DOS", ...) into one stable key, and
 * `defaultFolder()` maps that key onto the folder name used inside the ROM
 * library (the ES-DE / EmuDeck naming most Android frontends understand).
 */
object Systems {
    data class Info(val key: String, val name: String, val folder: String, val short: String)

    private val table: List<Info> = listOf(
        // Nintendo
        Info("nes", "Nintendo Entertainment System", "nes", "NES"),
        Info("snes", "Super Nintendo Entertainment System", "snes", "SNES"),
        Info("n64", "Nintendo 64", "n64", "N64"),
        Info("gc", "Nintendo GameCube", "gc", "GameCube"),
        Info("wii", "Nintendo Wii", "wii", "Wii"),
        Info("wiiu", "Nintendo Wii U", "wiiu", "Wii U"),
        Info("switch", "Nintendo Switch", "switch", "Switch"),
        Info("gb", "Game Boy", "gb", "Game Boy"),
        Info("gbc", "Game Boy Color", "gbc", "Game Boy Color"),
        Info("gba", "Game Boy Advance", "gba", "Game Boy Advance"),
        Info("nds", "Nintendo DS", "nds", "Nintendo DS"),
        Info("3ds", "Nintendo 3DS", "3ds", "Nintendo 3DS"),
        Info("nds-64", "Nintendo DS 64", "nds64", "Nintendo DS 64"),
        Info("virtualboy", "Virtual Boy", "virtualboy", "Virtual Boy"),
        Info("pokemini", "Pokémon mini", "pokemini", "Pokémon mini"),
        // Sega
        Info("genesis", "Sega Genesis / Mega Drive", "genesis", "Genesis"),
        Info("mastersystem", "Sega Master System", "mastersystem", "Master System"),
        Info("gamegear", "Sega Game Gear", "gamegear", "Game Gear"),
        Info("sg1000", "Sega SG-1000", "sg1000", "SG-1000"),
        Info("sega32x", "Sega 32X", "sega32x", "Sega 32X"),
        Info("segacd", "Sega CD", "segacd", "Sega CD"),
        Info("saturn", "Sega Saturn", "saturn", "Saturn"),
        Info("dreamcast", "Sega Dreamcast", "dreamcast", "Dreamcast"),
        // Sony
        Info("psx", "PlayStation", "psx", "PlayStation"),
        Info("ps2", "PlayStation 2", "ps2", "PlayStation 2"),
        Info("psp", "PlayStation Portable", "psp", "PSP"),
        Info("ps3", "PlayStation 3", "ps3", "PlayStation 3"),
        Info("psvita", "PlayStation Vita", "psvita", "PS Vita"),
        // Atari / others
        Info("atari2600", "Atari 2600", "atari2600", "Atari 2600"),
        Info("atari5200", "Atari 5200", "atari5200", "Atari 5200"),
        Info("atari7800", "Atari 7800", "atari7800", "Atari 7800"),
        Info("atarist", "Atari ST", "atarist", "Atari ST"),
        Info("atarilynx", "Atari Lynx", "atarilynx", "Atari Lynx"),
        Info("jaguar", "Atari Jaguar", "atarijaguar", "Atari Jaguar"),
        Info("atari800", "Atari 800", "atari800", "Atari 800"),
        Info("vic20", "Commodore VIC-20", "vic20", "VIC-20"),
        Info("c64", "Commodore 64", "c64", "Commodore 64"),
        Info("amiga", "Commodore Amiga", "amiga", "Amiga"),
        Info("colecovision", "ColecoVision", "colecovision", "ColecoVision"),
        Info("intellivision", "Mattel Intellivision", "intellivision", "Intellivision"),
        Info("vectrex", "Vectrex", "vectrex", "Vectrex"),
        Info("videopac", "Magnavox Odyssey 2 / Videopac", "videopac", "Videopac"),
        Info("3do", "3DO Interactive Multiplayer", "3do", "3DO"),
        Info("wonderswan", "Bandai WonderSwan", "wonderswan", "WonderSwan"),
        Info("wonderswan-color", "Bandai WonderSwan Color", "wonderswancolor", "WonderSwan Color"),
        Info("ngp", "Neo Geo Pocket", "ngp", "Neo Geo Pocket"),
        Info("ngpc", "Neo Geo Pocket Color", "ngpc", "Neo Geo Pocket Color"),
        Info("neogeo", "Neo Geo", "neogeo", "Neo Geo"),
        Info("neogeocd", "Neo Geo CD", "neogeocd", "Neo Geo CD"),
        Info("pcengine", "NEC PC Engine / TurboGrafx-16", "pcengine", "PC Engine"),
        Info("pcenginecd", "NEC PC Engine CD", "pcenginecd", "PC Engine CD"),
        Info("pcfx", "NEC PC-FX", "pcfx", "PC-FX"),
        Info("zxspectrum", "ZX Spectrum", "zxspectrum", "ZX Spectrum"),
        Info("amstradcpc", "Amstrad CPC", "amstradcpc", "Amstrad CPC"),
        Info("msx", "MSX", "msx", "MSX"),
        Info("x68000", "Sharp X68000", "x68000", "X68000"),
        Info("pc98", "NEC PC-98", "pc98", "PC-98"),
        Info("sharp-x1", "Sharp X1", "x1", "Sharp X1"),
        Info("x1", "Sharp X1", "x1", "Sharp X1"),
        // Computers / misc
        Info("dos", "DOS / PC", "dos", "DOS"),
        Info("scummvm", "ScummVM", "scummvm", "ScummVM"),
        Info("easyrpg", "EasyRPG", "easyrpg", "EasyRPG"),
        Info("ports", "PC Ports", "ports", "PC Ports"),
        Info("pc", "PC (Windows)", "windows", "PC"),
        Info("mac", "Apple Macintosh", "macintosh", "Macintosh"),
        Info("openbor", "OpenBOR", "openbor", "OpenBOR"),
        // Arcade
        Info("arcades", "Arcade (FinalBurn Neo)", "arcade", "Arcade"),
        Info("mame", "Arcade (MAME)", "mame", "MAME"),
        Info("fba", "Arcade (FinalBurn Neo)", "fbneo", "FinalBurn Neo"),
        Info("neogeoaes", "Neo Geo AES", "neogeo", "Neo Geo"),
    )

    val ALL: Map<String, Info> = table.associateBy { it.key }

    /** The console tiles and the console filter, in this order (the rest by name). */
    val POPULAR = listOf(
        "switch", "ps2", "psx", "gba", "snes", "n64", "nds", "3ds", "gc", "wii", "nes", "gb", "gbc",
        "psp", "genesis", "dreamcast", "ps3", "wiiu", "psvita", "saturn", "mastersystem", "gamegear",
        "segacd", "sega32x", "arcades", "mame", "neogeo", "pcengine", "dos",
    )

    private val aliases = HashMap<String, String>()

    private fun key(text: String?): String = (text ?: "").lowercase().filter { it.isLetterOrDigit() }

    private fun register(canonical: String, vararg names: String) {
        for (n in names) aliases[key(n)] = canonical
    }

    init {
        register("nes", "nintendo entertainment system", "nintendo nes", "famicom")
        register("snes", "super nintendo", "super nintendo entertainment system", "nintendo snes", "super nes", "sfc")
        register("n64", "nintendo 64", "nintendo-64", "n64", "nintendo64")
        register("gc", "gamecube", "nintendo gamecube", "game cube", "ngc")
        register("wii", "nintendo wii", "wii")
        register("wiiu", "nintendo wii u", "wii u", "wiiu")
        register("switch", "nintendo switch", "ns", "nx", "switch")
        register("gb", "game boy", "gameboy", "gb", "game boy original")
        register("gbc", "game boy color", "gameboy color", "gbc")
        register("gba", "game boy advance", "gameboy advance", "gba")
        register("nds", "nintendo ds", "ds", "nds")
        register("3ds", "nintendo 3ds", "3ds", "3ds xl")
        register("genesis", "sega genesis", "mega drive", "megadrive", "genesis", "md")
        register("mastersystem", "sega master system", "master system", "mastersystem", "sms")
        register("gamegear", "sega game gear", "game gear", "gamegear", "gg")
        register("saturn", "sega saturn", "saturn")
        register("dreamcast", "sega dreamcast", "dreamcast", "dc")
        register("psx", "playstation", "playstation 1", "ps1", "psx", "sony playstation")
        register("ps2", "playstation 2", "ps2", "sony playstation 2")
        register("psp", "playstation portable", "psp", "sony psp")
        register("ps3", "playstation 3", "ps3")
        register("psvita", "playstation vita", "ps vita", "psvita")
        register("atari2600", "atari 2600", "2600", "atari2600")
        register("atari5200", "atari 5200", "5200")
        register("atari7800", "atari 7800", "7800")
        register("atarist", "atari st", "st", "atarist")
        register("atarilynx", "atari lynx", "lynx")
        register("c64", "commodore 64", "c64", "commodore64")
        register("amiga", "commodore amiga", "amiga")
        register("vic20", "commodore vic-20", "vic-20", "vic20")
        register("3do", "3do", "3do interactive multiplayer", "panasonic 3do")
        register("wonderswan", "bandai wonderswan", "wonderswan", "ws")
        register("wonderswan-color", "bandai wonderswan color", "wonderswan color", "wsc")
        register("ngp", "neo geo pocket", "ngp")
        register("ngpc", "neo geo pocket color", "ngpc")
        register("neogeo", "neo geo", "neogeo", "aes", "neo geo aes")
        register("pcengine", "pc engine", "turbografx-16", "turbografx 16", "tg16", "pc engine / turbografx-16")
        register("pcenginecd", "pc engine cd", "turbografx-cd", "segacd pce", "pc engine cd / turbografx-cd")
        register("zxspectrum", "zx spectrum", "spectrum", "zxspectrum")
        register("amstradcpc", "amstrad cpc", "cpc")
        register("msx", "msx", "msx1", "msx2")
        register("dos", "dos", "ms-dos", "pc dos", "msdos")
        register("scummvm", "scummvm")
        register("easyrpg", "easyrpg")
        register("ports", "ports", "pc ports", "ports/pc")
        register("pc", "windows", "pc", "windows pc", "microsoft windows")
        register("mac", "macintosh", "mac os", "apple macintosh")
        register("openbor", "openbor")
        register("arcades", "arcade", "arcades", "arcade (fba)", "fbneo")
        register("mame", "mame", "mame 2003 plus", "arcade (mame)", "mame current")
        register("fba", "fba", "final burn neo", "arcade (finalburn neo)")
        register("jaguar", "atari jaguar", "jaguar")
    }

    /** A stable system key for a free-form platform string ("unknown" if none fits). */
    fun canonical(raw: String?): String {
        if (raw.isNullOrBlank()) return "unknown"
        val low = key(raw.trim())
        aliases[low]?.let { return it }
        if (low in ALL) return low
        for (info in table) {
            val label = key(info.name)
            if (low.isNotEmpty() && (low in label || label in low)) return info.key
        }
        return "unknown"
    }

    fun name(key: String): String = ALL[key]?.name ?: "Unknown"
    fun label(key: String): String = ALL[key]?.short ?: if (key.isNotEmpty() && key != "unknown") key.uppercase() else "Unknown"
    fun defaultFolder(key: String): String = ALL[key]?.folder ?: key

    /** Folder names other frontends use for the same console; a library that
     * already has one of them keeps using it. */
    val FOLDER_ALIASES: Map<String, List<String>> = mapOf(
        "genesis" to listOf("megadrive", "md"),
        "psx" to listOf("ps1", "playstation"),
        "gc" to listOf("gamecube", "ngc"),
        "nds" to listOf("ds"),
        "pcengine" to listOf("tg16", "turbografx16"),
        "arcades" to listOf("arcade", "fbneo", "fba"),
        "jaguar" to listOf("jaguar", "atarijaguar"),
        "wonderswan-color" to listOf("wonderswancolor", "wsc"),
        "pc" to listOf("windows", "pc"),
        "mac" to listOf("macintosh", "mac"),
        "sega32x" to listOf("32x", "sega32x"),
        "segacd" to listOf("megacd", "segacd"),
        "mastersystem" to listOf("sms", "mastersystem"),
        "3ds" to listOf("n3ds", "3ds"),
    )

    /** The folder a console's games go into, given the folders a library has
     * (case-insensitive, aliases, then the default name). `strict` never takes
     * a loose match ("nes" must not take "snes"). */
    fun folderFor(key: String, available: Collection<String>, strict: Boolean = false): String {
        val preferred = defaultFolder(key)
        if (available.isEmpty()) return preferred
        val lowered = available.associateBy { it.lowercase() }
        lowered[preferred.lowercase()]?.let { return it }
        lowered[key.lowercase()]?.let { return it }
        for (alias in FOLDER_ALIASES[key].orEmpty()) lowered[alias]?.let { return it }
        val compact = key(preferred)
        available.firstOrNull { key(it) == compact }?.let { return it }
        if (strict) return preferred
        return preferred
    }

    /** Every console, by name (for Settings and the library). */
    fun known(): List<Info> = table.filter { it.key != "x1" && it.key != "neogeoaes" }.sortedBy { it.name }

    /**
     * The shape of a console's box art, width / height of the front cover.
     * Measured from the libretro-thumbnails box-art scans (USA/Europe medians).
     * Never take these from IGDB: it crops every cover to 3:4.
     */
    val BOX_RATIOS: Map<String, Float> = mapOf(
        "nes" to 0.7f, "snes" to 1.41f, "n64" to 1.37f, "gc" to 0.71f, "wii" to 0.714f, "wiiu" to 0.714f,
        "switch" to 0.62f, "gb" to 1.0f, "gbc" to 1.0f, "gba" to 1.0f, "nds" to 1.12f, "nds-64" to 1.12f,
        "3ds" to 1.13f, "virtualboy" to 1.0f, "pokemini" to 1.0f,
        "sg1000" to 0.7f, "mastersystem" to 0.706f, "genesis" to 0.7f, "segacd" to 0.705f, "sega32x" to 0.725f,
        "saturn" to 0.62f, "dreamcast" to 1.0f, "gamegear" to 0.65f,
        "psx" to 1.0f, "ps2" to 0.705f, "ps3" to 0.87f, "psp" to 0.58f, "psvita" to 0.785f,
        "atari2600" to 0.735f, "atari5200" to 0.735f, "atari7800" to 0.723f, "atari800" to 0.75f,
        "atarist" to 0.78f, "atarilynx" to 1.04f, "jaguar" to 0.71f,
        "pcengine" to 1.0f, "pcenginecd" to 1.0f, "pcfx" to 1.0f, "neogeo" to 0.7f, "neogeoaes" to 0.7f,
        "neogeocd" to 1.0f, "ngp" to 1.0f, "ngpc" to 1.0f, "wonderswan" to 0.815f, "wonderswan-color" to 0.815f,
        "3do" to 0.55f, "colecovision" to 0.732f, "intellivision" to 0.722f, "vectrex" to 0.743f, "videopac" to 0.7f,
        "arcades" to 0.71f, "mame" to 0.71f, "fba" to 0.71f,
        "msx" to 0.71f, "c64" to 0.77f, "vic20" to 0.77f, "amiga" to 0.78f, "zxspectrum" to 0.7f,
        "amstradcpc" to 0.7f, "x68000" to 0.71f, "pc98" to 0.71f, "x1" to 0.71f, "sharp-x1" to 0.71f,
        "dos" to 0.78f, "pc" to 0.78f, "mac" to 0.78f, "ports" to 0.78f, "scummvm" to 0.78f,
        "easyrpg" to 0.75f, "openbor" to 0.75f,
    )

    fun boxRatio(key: String): Float = BOX_RATIOS[key] ?: (5f / 7f)
}
