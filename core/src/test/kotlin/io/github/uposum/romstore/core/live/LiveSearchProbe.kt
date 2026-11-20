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

    /** The whole store as the app runs it: every reader at its own address, the cover rules, merging. */
    @Test
    fun storeProbe(): Unit = runBlocking {
        Assume.assumeTrue(System.getProperty("live") == "1")
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "romstore-probe").apply { mkdirs() }
        val settings = io.github.uposum.romstore.core.store.SettingsStore(java.io.File(dir, "s.json"))
        settings.update { it.copy(sourceAddresses = SourceDefs.ALL.associate { d -> d.id to d.homepage }) }
        val store = io.github.uposum.romstore.core.store.StoreService(settings, java.io.File(dir, "data"), java.io.File(dir, "cache"), library = { null })
        store.artwork = io.github.uposum.romstore.core.store.StoreArtwork(store.http, java.io.File(dir, "art"), settings, store.scope)
        store.onTiming = { println("   TIMING $it") }
        if (System.getProperty("fresh") == "1") java.io.File(dir, "art").deleteRecursively()
        val what = System.getProperty("q") ?: "search:ratchet"
        for (job in what.split(";")) {
            val (kind, arg) = job.split(":", limit = 2)
            val r = if (kind == "browse") store.browse(arg) else store.search(arg)
            println("== $job: ${r.results.size} results, errors=${r.errors}")
            for (rom in r.results) {
                val c = rom.cover
                println("  ${rom.system.padEnd(8)} | ${rom.title.take(60).padEnd(60)} | ${rom.entries.joinToString(",") { it.source }} | ${if (c == null) "NO COVER" else c.substringAfter("//").substringBefore("/")} ")
            }
        }
    }

    @Test
    fun browseTimes(): Unit = runBlocking {
        Assume.assumeTrue(System.getProperty("live") == "1")
        val system = System.getProperty("q") ?: "switch"
        for (def in SourceDefs.ALL) {
            val src = SourceDefs.build(def.id, HttpClient(), def.homepage) ?: continue
            if (!src.supportsBrowse || system !in src.browseSystems()) continue
            val t = System.nanoTime()
            val r = runCatching { src.browse(system, 200) }
            val ms = (System.nanoTime() - t) / 1_000_000
            val list = r.getOrDefault(emptyList())
            val covers = list.mapNotNull { it.cover?.substringAfter("//")?.substringBefore("/") }.groupingBy { it }.eachCount()
            println("   BROWSE ${def.id}: ${list.size} games in $ms ms ${r.exceptionOrNull()?.message ?: ""} covers=$covers")
            list.mapNotNull { it.cover }.take(6).forEach { println("   COVER ${def.id} $it") }
        }
    }

    /** Unpack a local archive (-Dq=path) the way the app does, with the app's memory (-DtestHeap=192m). */
    @Test
    fun unpackProbe() {
        Assume.assumeTrue(System.getProperty("live") == "1")
        val path = java.io.File(System.getProperty("q") ?: return)
        val out = java.nio.file.Files.createTempDirectory("unpack").toFile()
        val lib = io.github.uposum.romstore.core.store.JavaFileLibrary(out)
        val folder = lib.consoleFolder("psp", create = true)!!
        java.io.File(out, "psp/${path.name}").let { path.copyTo(it) }
        val node = folder.list().first { it.name == path.name }
        println("   UNPACK heap=${Runtime.getRuntime().maxMemory() shr 20} MB limitKb=${io.github.uposum.romstore.core.store.Unpack.sevenZipMemoryKb()}")
        val r = runCatching { io.github.uposum.romstore.core.store.Unpack.unpack(node, folder, java.io.File(out, "scratch")) }
        println("   UNPACK result: " + (r.getOrNull()?.joinToString { "${it.name} ${it.size}" } ?: "${r.exceptionOrNull()}"))
        out.deleteRecursively()
    }
}
