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
    fun switchFiles() = runBlocking {
        Assume.assumeTrue(System.getProperty("live") == "1")
        val http = HttpClient()
        val q = System.getProperty("q")?.ifEmpty { null } ?: "monster hunter rise"
        for ((id, address) in listOf("romsfun" to "https://romsfun.com", "romspedia" to "https://www.romspedia.com", "switchroms" to "https://nintendo-switch-roms.com")) {
            val source = SourceDefs.build(id, http, address)!!
            val entries = runCatching { source.search(q, 10) }.getOrElse { println("$id search failed: ${it.message}"); continue }
            val switch = entries.filter { it.system == "switch" }.take(2)
            println("== $id: ${entries.size} results, switch: ${switch.map { it.title }}")
            for (e in switch) {
                val files = runCatching { source.resolve(e) }.getOrElse { println("   resolve failed ${it.message}"); emptyList() }
                println("   ${e.title} -> ${files.size} files")
                files.forEach { println("      label=${it.label} | name=${it.filename} | size=${it.size} | ${it.url.take(110)}") }
            }
        }
    }

    @Test
    fun switchBrowseFiles() = runBlocking {
        Assume.assumeTrue(System.getProperty("live") == "1")
        val http = HttpClient()
        for ((id, address) in listOf("romspedia" to "https://www.romspedia.com", "romsfun" to "https://romsfun.com")) {
            val source = SourceDefs.build(id, http, address)!!
            val entries = runCatching { source.browse("switch", 40) }.getOrElse { println("$id browse failed: ${it.message}"); continue }
            println("== $id switch catalogue: ${entries.size}")
            for (e in entries.take(3)) {
                val files = runCatching { source.resolve(e) }.getOrElse { println("   resolve failed ${it.message}"); emptyList() }
                println("   ${e.title} -> ${files.size} files")
                files.take(12).forEach { println("      label=${it.label} | name=${it.filename} | size=${it.size}") }
            }
        }
    }

    @Test
    fun anySearch() = runBlocking {
        Assume.assumeTrue(System.getProperty("live") == "1")
        val id = System.getProperty("src") ?: "switchroms"
        val address = SourceDefs.get(id)!!.homepage
        val source = SourceDefs.build(id, HttpClient(), address)!!
        val entries = source.search(System.getProperty("q") ?: "update", 40)
        entries.forEach { println("   ${it.system} | ${it.title} | ${it.url}") }
        if (System.getProperty("resolve") == "1") entries.take(4).forEach { e ->
            runCatching { source.resolve(e) }.onFailure { x -> println("      ${e.title}: FAILED ${x.message}") }.getOrDefault(emptyList()).forEach { println("      ${e.title}: ${it.filename} | ${it.label} | ${it.url}") }
        }
    }

    @Test
    fun romsfunDownload(): Unit = runBlocking {
        Assume.assumeTrue(System.getProperty("live") == "1")
        val http = HttpClient()
        val source = SourceDefs.build("romsfun", http, "https://romsfun.com")!!
        val q = System.getProperty("q")?.ifEmpty { null } ?: "pokemon emerald"
        val entries = source.search(q, 10)
        println("search: ${entries.map { "${it.system}:${it.title}" }.take(5)}")
        val entry = entries.first()
        val files = source.resolve(entry)
        println("files: ${files.size}")
        files.take(3).forEach { println("   file label=${it.label} name=${it.filename} url=${it.url}") }
        val file = files.firstOrNull() ?: return@runBlocking
        val link = runCatching { source.resolveDownload(file) }
        println("resolveDownload: " + link.fold({ it }, { "FAILED ${it.javaClass.simpleName}: ${it.message}" }))
        link.getOrNull()?.let { url ->
            val r = runCatching { http.sample(url, maxBytes = 200_000, maxSeconds = 3.0) }
            println("sample: " + r.fold({ "ok ${it.bytes} bytes" }, { "FAILED ${it.message}" }))
            val head = runCatching { http.request(url, maxBytes = 4096, retries = 0) }
            println("GET: " + head.fold({ "${it.status} ${it.url} ${it.header("content-type")} | ${it.text.take(300).replace("\n", " ")}" }, { "FAILED ${it.message}" }))
        }
    }

    @Test
    fun romsfunBig(): Unit = runBlocking {
        Assume.assumeTrue(System.getProperty("live") == "1")
        val http = HttpClient()
        val source = SourceDefs.build("romsfun", http, "https://romsfun.com")!!
        val q = System.getProperty("q")?.ifEmpty { null } ?: "gran turismo 4"
        val entry = source.search(q, 10).first()
        val files = source.resolve(entry)
        println("${entry.system} ${entry.title}: ${files.size} files")
        val picks = files.sortedByDescending { it.size ?: 0 }.take(2) + files.filter { (it.label ?: "").contains("USA") }.take(1)
        for (file in picks.distinct()) {
            val link = runCatching { source.resolveDownload(file) }
            val host = link.getOrNull()?.let { java.net.URI(it.replace(" ", "%20")).host }
            println("  ${file.label} size=${file.size}")
            println("    -> " + link.fold({ "host=$host ${it.take(90)}" }, { "FAILED ${it.message}" }))
            link.getOrNull()?.let { url ->
                val r = runCatching { http.sample(url, maxBytes = 300_000, maxSeconds = 4.0) }
                println("    sample: " + r.fold({ "ok ${it.bytes.toLong()} bytes" }, { "FAILED ${it.message}" }))
            }
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

    @Test
    fun fileHostSources(): Unit = runBlocking {
        Assume.assumeTrue(System.getProperty("live") == "1")
        val http = HttpClient()
        val q = System.getProperty("q")?.ifEmpty { null } ?: "mario kart 8 deluxe"
        val archive = SourceDefs.build("archiveorg", http, "https://archive.org")!!
        val found = archive.search(q, 40)
        found.forEach { e -> println("   archive | ${e.system} | ${e.title} | ${e.files.joinToString { "${it.filename} (${it.size})" }.take(140)}") }
        found.flatMap { it.files }.firstOrNull { (it.size ?: 0) > 50_000_000 }?.let { f ->
            val s = http.sample(f.url, maxBytes = 30L * 1024 * 1024, maxSeconds = 8.0)
            println("   archive speed: ${s.bytes / s.seconds / 1e6} MB/s over ${s.bytes} bytes")
        }
        val nsw = SourceDefs.build("nswgame", http, "https://nswgf.com")!!
        val entry = nsw.search(q, 20).firstOrNull { it.system == "switch" } ?: return@runBlocking
        val files = nsw.resolve(entry)
        files.forEach { println("   nswgame | ${it.label} | ${it.filename}") }
        val late = files.firstOrNull { io.github.uposum.romstore.core.net.FileHosts.resolvesLate(it.url) } ?: return@runBlocking
        val link = runCatching { io.github.uposum.romstore.core.net.FileHosts.resolve(http, late.url) { println("     note: $it") } }
        println("   1fichier: " + (link.getOrNull() ?: link.exceptionOrNull()?.message))
    }
}
