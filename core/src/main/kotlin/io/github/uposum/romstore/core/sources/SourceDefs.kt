package io.github.uposum.romstore.core.sources

import io.github.uposum.romstore.core.net.HttpClient

/**
 * Every site the app has a reader for. The app ships no addresses: the user
 * enters where each site is (Settings → Sources), and a source without an
 * address is never asked.
 */
object SourceDefs {
    class Def(
        val id: String,
        val label: String,
        /** The reader's own site, used by the live self-test only. */
        val homepage: String,
        val supportsBrowse: Boolean,
        val create: (HttpClient, SourceOptions) -> Source,
    )

    val ALL: List<Def> = listOf(
        Def("switchroms", "Switch ROMs", "https://nintendo-switch-roms.com", true) { h, o -> SwitchRomsSource(h, o) },
        Def("romspedia", "Romspedia", "https://www.romspedia.com", true) { h, o -> RomspediaSource(h, o) },
        Def("retrostic", "Retrostic", "https://www.retrostic.com", true) { h, o -> RetrosticSource(h, o) },
        Def("romsdl", "RomsDL", "https://romsdl.com", true) { h, o -> RetrosticSource(h, o) },
        Def("romsfun", "RomsFun", "https://romsfun.com", true) { h, o -> RomsFunSource(h, o) },
        Def("myabandonware", "My Abandonware", "https://www.myabandonware.com", false) { h, o -> MyAbandonwareSource(h, o) },
        Def("homebrewhub", "Homebrew Hub", "https://hh3.gbdev.io", true) { h, o -> HomebrewHubSource(h, o) },
        Def("nswgame", "NSWGame", "https://nswgf.com", true) { h, o -> NswGameSource(h, o) },
        Def("archiveorg", "Internet Archive", "https://archive.org", false) { h, o -> ArchiveOrgSource(h, o) },
    )

    val IDS: List<String> = ALL.map { it.id }

    fun get(id: String): Def? = ALL.firstOrNull { it.id == id }

    fun label(id: String): String = get(id)?.label ?: id

    /** "example.com/" -> "https://example.com"; blank stays blank. */
    fun normalizeAddress(value: String?): String {
        var v = (value ?: "").trim()
        if (v.isEmpty()) return ""
        if ("://" !in v) v = "https://$v"
        return v.trimEnd('/')
    }

    fun build(id: String, http: HttpClient, address: String): Source? {
        val def = get(id) ?: return null
        val base = normalizeAddress(address).ifEmpty { return null }
        return def.create(http, SourceOptions(id, def.label, base))
    }
}
