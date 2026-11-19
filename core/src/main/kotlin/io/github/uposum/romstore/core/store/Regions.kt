package io.github.uposum.romstore.core.store

import io.github.uposum.romstore.core.net.findAllFast

import io.github.uposum.romstore.core.model.MergedRom

/** The header's region filter: USA, Europe, Japan (src/components/common.ts). */
object Regions {
    val ALL = listOf("USA", "Europe", "Japan")

    private val WORDS = mapOf(
        "usa" to "USA", "us" to "USA", "u" to "USA", "america" to "USA", "north america" to "USA", "canada" to "USA",
        "europe" to "Europe", "eu" to "Europe", "e" to "Europe", "uk" to "Europe", "united kingdom" to "Europe",
        "germany" to "Europe", "france" to "Europe", "spain" to "Europe", "italy" to "Europe", "netherlands" to "Europe",
        "sweden" to "Europe", "scandinavia" to "Europe", "australia" to "Europe", "pal" to "Europe",
        "es" to "Europe", "de" to "Europe", "fr" to "Europe", "it" to "Europe", "nl" to "Europe", "sv" to "Europe",
        "japan" to "Japan", "jp" to "Japan", "j" to "Japan", "jpn" to "Japan", "ntsc_j" to "Japan",
        "world" to "World", "w" to "World",
    )
    private val TAG = Regex("""[(\[][^)\]]+[)\]]""")
    private val SPLIT = Regex("[,/]")

    private fun addAll(text: String?, into: MutableSet<String>) {
        text ?: return
        for (word in text.split(SPLIT)) WORDS[word.trim().lowercase().replace('-', '_')]?.let { into += it }
    }

    /** Regions found in a free text: a region field or a title's tags. */
    fun of(text: String?): Set<String> {
        val found = HashSet<String>()
        text ?: return found
        addAll(text, found)
        for (tag in TAG.findAllFast(text)) addAll(tag.value.substring(1, tag.value.length - 1), found)
        return found
    }

    /** The regions a game is known to exist in; empty when no source says. */
    fun of(rom: MergedRom): Set<String> {
        val found = HashSet<String>()
        rom.regions.forEach { addAll(it, found) }
        for (entry in rom.entries) {
            addAll(entry.region, found)
            for (tag in TAG.findAllFast(entry.title)) addAll(tag.value.substring(1, tag.value.length - 1), found)
        }
        return found
    }

    /** Games known only from other regions are hidden; unknown ones stay; "World" fits all. */
    fun includes(rom: MergedRom, region: String): Boolean {
        if (region == "all" || region.isEmpty()) return true
        val regions = of(rom)
        return regions.isEmpty() || "World" in regions || region in regions
    }

    fun matches(text: String?, region: String): Boolean {
        val r = of(text)
        return region in r || "World" in r
    }
}
