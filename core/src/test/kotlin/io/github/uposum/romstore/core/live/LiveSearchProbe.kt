package io.github.uposum.romstore.core.live

import io.github.uposum.romstore.core.art.BoxArt
import io.github.uposum.romstore.core.art.GameTdb
import io.github.uposum.romstore.core.art.IgdbCovers
import io.github.uposum.romstore.core.art.SteamGridCovers
import io.github.uposum.romstore.core.art.ArtKeys
import io.github.uposum.romstore.core.net.HttpClient
import io.github.uposum.romstore.core.sources.SourceDefs
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import java.nio.file.Files

/** Live, opt-in (-Dlive=1): where memory goes in a first search. */
class LiveSearchProbe {
    private fun used() = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_000_000

    @Test
    fun rawCovers() = runBlocking {
        Assume.assumeTrue(System.getProperty("live") == "1")
        val src = SourceDefs.build("romspedia", HttpClient(), "www.romspedia.com")!!
        src.search(System.getProperty("q") ?: "zelda", 30).forEach { println("${it.system} | ${it.title} | ${it.cover}") }
    }

    @Test
    fun pspOverHttp() = runBlocking {
        Assume.assumeTrue(System.getProperty("live") == "1")
        val http = HttpClient()
        for ((id, address) in listOf(
            "romspedia" to "http://www.romspedia.com", "retrostic" to "http://www.retrostic.com",
            "romsdl" to "http://romsdl.com", "romsfun" to "http://romsfun.com",
        )) {
            val source = SourceDefs.build(id, http, address)!!
            val result = runCatching { source.browse("psp", 200) }
            println("$id via $address: " + result.fold({ "${it.size} PSP games, first: ${it.firstOrNull()?.title}" }, { "FAILED ${it.message}" }))
        }
    }

    @Test
    fun pageTwo() = runBlocking {
        Assume.assumeTrue(System.getProperty("live") == "1")
        val http = HttpClient()
        val src = SourceDefs.build("romspedia", http, "www.romspedia.com")!!
        for (page in 1..3) {
            val t = System.nanoTime()
            val entries = src.search("zelda", 30, page)
            println("page $page: ${entries.size} entries in ${(System.nanoTime() - t) / 1_000_000} ms, pages=${src.lastPageCount}")
        }
    }

    @Test
    fun firstSearch() = runBlocking {
        Assume.assumeTrue(System.getProperty("live") == "1")
        val http = HttpClient()
        val dir = Files.createTempDirectory("probe").toFile()
        val src = SourceDefs.build("romspedia", http, "www.romspedia.com")!!
        val entries = src.search("zelda", 30)
        println("search: ${entries.size} entries, ${used()} MB")
        val boxart = BoxArt(http, dir)
        boxart.apply(entries, resize = true)
        System.gc(); println("boxart: ${used()} MB")
        val gametdb = GameTdb(http, dir)
        gametdb.apply(entries.filter { it.system in GameTdb.PLATFORMS })
        System.gc(); println("gametdb: ${used()} MB")
        val sgdb = SteamGridCovers(http, { ArtKeys.STEAMGRIDDB_KEY }, dir)
        sgdb.apply(entries)
        System.gc(); println("sgdb: ${used()} MB")
        val igdb = IgdbCovers(http, cacheDir = dir)
        igdb.lookupMany(entries.map { it.title }.take(14), 14)
        System.gc(); println("igdb: ${used()} MB")
        entries.forEach { println("${it.system} ${it.title} -> ${it.cover}") }
    }
}
