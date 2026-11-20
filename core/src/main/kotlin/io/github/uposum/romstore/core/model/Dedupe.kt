package io.github.uposum.romstore.core.model

/**
 * Merge results coming from different sources and drop duplicates.
 *
 * Console firmware the catalogues list among the games ("[BIOS] Atari Jaguar
 * (World)", the Atari ST's "TOS v1.04") is never a result: the store never
 * offers BIOS files.
 */
object Dedupe {
    private val FIRMWARE_RE = Regex("""^\s*[\[(]BIOS[\])]|\(BIOS\)|^\s*TOS v\d""", RegexOption.IGNORE_CASE)

    fun isFirmware(title: String?): Boolean = FIRMWARE_RE.containsMatchIn(title ?: "")

    // matchTitle ignores spacing, accents and filler words, so "Pokemon Red",
    // "Pokémon - Red Version (USA)" and "Pokemon Red Version" are one game.
    // Add-on words are left out, so "Game Update 1.2" and "Game + DLC" join "Game".
    private fun groupKey(entry: RomEntry) = "${entry.system}::${Content.baseKey(entry.title)}"

    private fun isAddonPost(entry: RomEntry) = Content.classify("", entry.title).isAddon

    /**
     * Group entries by (system, normalised title) and order them by priority.
     * The groups are interleaved round robin across the sources, so a single
     * source can never push every other source off the first page.
     */
    fun merge(input: List<RomEntry>, priority: List<String> = emptyList()): List<MergedRom> {
        val entries = input.filterNot { isFirmware(it.title) }
        val groups = LinkedHashMap<String, MutableList<RomEntry>>()
        val perSource = LinkedHashMap<String, MutableList<RomEntry>>()
        for (e in entries) {
            groups.getOrPut(groupKey(e)) { mutableListOf() }.add(e)
            perSource.getOrPut(e.source) { mutableListOf() }.add(e)
        }
        fun prio(source: String) = priority.indexOf(source).let { if (it < 0) priority.size else it }
        val rank = compareBy<RomEntry>(
            { if (isAddonPost(it)) 1 else 0 },
            { prio(it.source) },
            { it.rank },
            { e -> -listOf<Any?>(e.cover, e.rating, e.downloads, e.year, e.size, e.summary).count(::truthy) },
            { titleSortKey(it.title) },
        )
        for (g in groups.values) g.sortWith(rank)

        val orderedSources = perSource.keys.sortedWith(compareBy({ prio(it) }, { it }))
        val orderedKeys = mutableListOf<String>()
        val seen = HashSet<String>()
        var index = 0
        while (true) {
            var added = false
            for (source in orderedSources) {
                val list = perSource.getValue(source)
                if (index < list.size) {
                    val key = groupKey(list[index])
                    if (seen.add(key)) orderedKeys.add(key)
                    added = true
                }
            }
            if (!added) break
            index++
        }
        return orderedKeys.mapIndexed { position, key ->
            val group = groups.getValue(key)
            val primary = group.first()
            MergedRom(
                id = "rom-$position-${primary.normalizedTitle.take(48).ifEmpty { "game" }}".replace(" ", "-"),
                title = primary.title,
                system = primary.system,
                entries = group,
            )
        }
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        else -> true
    }

    fun sort(roms: List<MergedRom>, sort: String): List<MergedRom> = when (sort) {
        "downloads" -> roms.sortedWith(compareBy({ -(it.downloads ?: 0L) }, { titleSortKey(it.title) }))
        "rating" -> roms.sortedWith(compareBy({ -(it.rating ?: 0.0) }, { titleSortKey(it.title) }))
        "year" -> roms.sortedWith(compareBy({ -(it.year ?: 0) }, { titleSortKey(it.title) }))
        "size" -> roms.sortedWith(compareBy({ -(it.size ?: 0L) }, { titleSortKey(it.title) }))
        "title" -> roms.sortedBy { titleSortKey(it.title) }
        else -> roms
    }
}
