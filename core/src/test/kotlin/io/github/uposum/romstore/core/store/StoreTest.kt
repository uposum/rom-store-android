package io.github.uposum.romstore.core.store

import io.github.uposum.romstore.core.model.Dedupe
import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.Systems
import io.github.uposum.romstore.core.model.matchTitle
import io.github.uposum.romstore.core.net.HttpClient
import io.github.uposum.romstore.core.net.Html
import io.github.uposum.romstore.core.net.findAllFast
import io.github.uposum.romstore.core.net.replaceFast
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StoreTest {
    private lateinit var dir: File
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        dir = Files.createTempDirectory("romstore").toFile()
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> for ((name, data) in files) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
        return out.toByteArray()
    }

    // ------------------------------------------------------------ matching

    @Test fun titlesMatchAcrossSpellings() {
        assertEquals(matchTitle("Pokémon - Red Version (USA)"), matchTitle("Pokemon Red"))
        assertEquals(matchTitle("Mega Man X"), matchTitle("MegaMan X.zip"))
        assertEquals(matchTitle("Legend of Zelda, The & Friends"), matchTitle("legend zelda and friends"))
    }

    @Test fun mergeGroupsAndDropsFirmware() {
        val entries = listOf(
            RomEntry("a", "1", "Pokemon Red", "gb"),
            RomEntry("b", "2", "Pokémon - Red Version (USA)", "gb"),
            RomEntry("b", "3", "[BIOS] Game Boy (World)", "gb"),
            RomEntry("a", "4", "Pokemon Red", "gba"),
        )
        val merged = Dedupe.merge(entries, listOf("b", "a"))
        assertEquals(2, merged.size)
        assertEquals(listOf("b", "a"), merged.first { it.system == "gb" }.sources)
    }

    @Test fun consolesAndFolders() {
        assertEquals("snes", Systems.canonical("Super Nintendo"))
        assertEquals("psx", Systems.canonical("PlayStation 1"))
        assertEquals("megadrive", Systems.folderFor("genesis", listOf("megadrive", "snes")))
        assertEquals("nes", Systems.folderFor("nes", listOf("snes"), strict = true))
    }

    // --------------------------------------------------------------- regex

    @Test fun singleMatcherHelpersBehaveLikeTheStdlib() {
        val re = Regex("""<a href="([^"]+)">(.*?)</a>""")
        val html = """<a href="/x">X</a> and <a href="/y">Y</a>"""
        assertEquals(re.findAll(html).map { it.groupValues }.toList(), re.findAllFast(html).map { it.groupValues }.toList())
        assertEquals(re.findAll(html).map { it.range }.toList(), re.findAllFast(html).map { it.range }.toList())
        assertEquals(re.replace(html) { it.groupValues[2] }, re.replaceFast(html) { it.groupValues[2] })
        assertEquals("a b", Html.collapseWs("a  b"))
    }

    @Test fun nestedBlocksAreBalanced() {
        val html = """<div class="x"><div>in</div><div>in2</div></div><div class="x">two</div>"""
        val blocks = Html.blocks(html, "div", "class=\"x\"").map { it.second }.toList()
        assertEquals(listOf("""<div class="x"><div>in</div><div>in2</div></div>""", """<div class="x">two</div>"""), blocks)
    }

    /**
     * Kotlin's findAll / replace-with-lambda create a Matcher per match; on
     * Android each one copies the whole page into native memory, which got
     * the app killed at 2.8 GB on the first search. Use findAllFast/replaceFast.
     */
    @Test fun noPerMatchRegexHelpersInTheApp() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val offenders = listOf("core/src/main", "app/src/main").flatMap { base ->
            File(root, base).walkTopDown().filter { it.extension == "kt" && it.name != "RegexFlags.kt" }.flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> ".findAll(" in line || Regex("""\.replace\([^)]*\)\s*\{""").containsMatchIn(line) }
                    .map { (i, line) -> "${file.relativeTo(root)}:${i + 1}: ${line.trim()}" }
            }.toList()
        }
        assertTrue("use findAllFast/replaceFast:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    // ---------------------------------------------------------- downloads

    @Test fun downloadUnpacksDiscGamesAndReportsDone() = runBlocking {
        val rom = ByteArray(5000) { (it % 251).toByte() }
        server.enqueue(MockResponse.Builder().body(Buffer().write(zipOf("folder/Game (USA).iso" to rom))).build())
        val library = JavaFileLibrary(File(dir, "ROMs").apply { mkdirs() })
        val folder = library.consoleFolder("psx", create = true)!!
        val manager = DownloadManager(HttpClient(minIntervalMs = 0), { library }, File(dir, "downloads.json"), File(dir, "scratch"))
        val job = manager.enqueue("Game", "psx", "src", "Game (USA).zip", server.url("/game.zip").toString(), folder.id, extract = true, keepArchive = false)
        withTimeout(10_000) { while (manager.get(job.id)!!.active) delay(20) }
        val done = manager.get(job.id)!!
        assertEquals(JobStatus.DONE, done.status)
        assertEquals("Game (USA).iso", done.extractedTo)
        val files = File(dir, "ROMs/psx").list()!!.sorted()
        assertEquals(listOf("Game (USA).iso"), files)
        assertTrue(File(dir, "ROMs/psx/Game (USA).iso").readBytes().contentEquals(rom))
    }

    @Test fun aWebPageIsNeverSavedAsAGame() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("<!DOCTYPE html><html>download page</html>").build())
        val library = JavaFileLibrary(File(dir, "ROMs").apply { mkdirs() })
        val folder = library.consoleFolder("gba", create = true)!!
        val manager = DownloadManager(HttpClient(minIntervalMs = 0), { library }, null, null)
        val job = manager.enqueue("Game", "gba", "src", "Game.zip", server.url("/g").toString(), folder.id)
        withTimeout(15_000) { while (manager.get(job.id)!!.active) delay(20) }
        val failed = manager.get(job.id)!!
        assertEquals(JobStatus.ERROR, failed.status)
        assertTrue(failed.error!!, "download page" in failed.error!!)
        assertFalse(File(dir, "ROMs/gba/Game.zip").exists())
    }

    @Test fun aRestartContinuesThePartialFile() = runBlocking {
        val data = ByteArray(100_000) { (it % 97).toByte() }
        val library = JavaFileLibrary(File(dir, "ROMs").apply { mkdirs() })
        val folder = library.consoleFolder("gba", create = true)!!
        File(dir, "ROMs/gba/Game.gba.part").writeBytes(data.copyOfRange(0, 40_000))
        // The queue as the app left it: one download that was running.
        val state = File(dir, "downloads.json")
        val job = DownloadJob("dl-1", "Game", "gba", "src", "Game.gba", server.url("/g").toString(), folder.id, status = JobStatus.DOWNLOADING)
        state.writeText(kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(DownloadJob.serializer()), listOf(job)))
        server.enqueue(
            MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 40000-99999/100000")
                .body(Buffer().write(data.copyOfRange(40_000, data.size))).build(),
        )
        val manager = DownloadManager(HttpClient(minIntervalMs = 0), { library }, state, null)
        assertEquals(JobStatus.QUEUED, manager.get("dl-1")!!.status)
        assertEquals(1, manager.resumePending())
        withTimeout(10_000) { while (manager.get("dl-1")!!.active) delay(20) }
        assertEquals("bytes=40000-", server.takeRequest().headers["Range"])
        assertEquals(JobStatus.DONE, manager.get("dl-1")!!.status)
        assertTrue(File(dir, "ROMs/gba/Game.gba").readBytes().contentEquals(data))
        assertFalse(File(dir, "ROMs/gba/Game.gba.part").exists())
    }

    // ------------------------------------------------------------- library

    @Test fun libraryGroupsDiscsAndSkipsNotes() {
        val root = File(dir, "ROMs")
        File(root, "psx").mkdirs()
        File(root, "psx/Game (USA) (Disc 1).chd").writeBytes(ByteArray(2000))
        File(root, "psx/Game (USA) (Disc 2).chd").writeBytes(ByteArray(2000))
        File(root, "psx/Game (USA).m3u").writeText("Game (USA) (Disc 1).chd\n")
        File(root, "psx/systeminfo.txt").writeText("notes")
        File(root, "psx/tiny.bin").writeBytes(ByteArray(10))
        val library = JavaFileLibrary(root)
        val games = LibraryScan.installedGames(library)
        assertEquals(1, games.size)
        assertEquals(3, games[0].files.size)
        val result = LibraryScan.uninstall(library, games[0].id)
        assertTrue(result.ok)
        assertEquals(listOf("systeminfo.txt", "tiny.bin"), File(root, "psx").list()!!.sorted())
    }

    @Test fun installedBadge() {
        val root = File(dir, "ROMs")
        File(root, "snes").mkdirs()
        File(root, "snes/Super Metroid (USA).sfc").writeBytes(ByteArray(2000))
        val index = LibraryIndex()
        val folder = JavaFileLibrary(root).consoleFolder("snes", false)
        assertNotNull(folder)
        assertTrue(index.contains(folder, "Super Metroid"))
        assertFalse(index.contains(folder, "Super Mario World"))
    }

    @Test fun shelvesKeepFavouritesAndRecent() {
        val shelves = Shelves(File(dir, "shelves.json"))
        assertTrue(shelves.toggleFavorite("Chrono Trigger", "snes", null))
        shelves.addRecent("Chrono Trigger", "snes", null)
        shelves.addRecent("Metroid", "nes", null)
        val again = Shelves(File(dir, "shelves.json"))
        assertTrue(again.isFavorite("Chrono Trigger (USA)", "snes"))
        assertEquals(listOf("Metroid", "Chrono Trigger"), again.state.value.recent.map { it.title })
    }

    @Test fun placeholderCoversAreNoCovers() {
        val entries = listOf(
            RomEntry("a", "1", "Breath of the Wild", "wiiu", cover = "https://static.example.com/webp/roms/image-unavailable-cover.webp"),
            RomEntry("a", "2", "Wind Waker", "gc", cover = "https://static.example.com/webp/roms/wind-waker-cover.webp"),
        )
        dropPlaceholderCovers(entries)
        assertEquals(null, entries[0].cover)
        assertNotNull(entries[1].cover)
    }

    @Test fun regionFilterKeepsUnknownGames() {
        val usa = Dedupe.merge(listOf(RomEntry("a", "1", "Game (USA)", "snes"))).first()
        val unknown = Dedupe.merge(listOf(RomEntry("a", "2", "Other", "snes"))).first()
        assertTrue(Regions.includes(usa, "USA"))
        assertFalse(Regions.includes(usa, "Japan"))
        assertTrue(Regions.includes(unknown, "Japan"))
    }
}

/** The plugin's normalize_title, regex for regex, to check the fast version against. */
private fun regexNormalize(title: String): String {
    var text = io.github.uposum.romstore.core.model.asciiFold(title).lowercase()
    text = Regex(
        """\.(zip|7z|rar|iso|xci|nsp|xci|rom|bin|chd|cue|gz|tar|exe|iso|gb|gbc|gba|nes|sfc|smc|z64|n64|v64|nds|3ds|cia|md|gen|sms|gg|32x|pbp|cso|rvz|wbfs|wua|m3u|pce|ws|wsc|ngp|ngc|vb|min|a26|a78|lnx|col|int|vec|adf|d64|dsk|st|png)$""",
        RegexOption.IGNORE_CASE,
    ).replace(text, "")
    text = Regex("""[(\[][^)\]]*[)\]]""").replace(text, " ")
    text = text.replace("&", " and ")
    text = Regex("[^a-z0-9]+").replace(text, " ")
    text = Regex("""\b(the|a|an|of|and|version|edition)\b""").replace(text, " ")
    return Regex("""\s+""").replace(text, " ").trim()
}

class NormalizeTitleTest {
    @Test fun fastVersionMatchesTheRegexVersion() {
        val stream = javaClass.getResourceAsStream("/data/switch_titles.json.gz")!!
        val text = java.util.zip.GZIPInputStream(stream).bufferedReader().readText()
        val names = Regex("\"([^\"]{1,120})\"").findAll(text).map { it.groupValues[1] }.toList()
        val extra = listOf(
            "Pokémon - Red Version (USA).zip", "Legend of Zelda, The - A Link to the Past (USA, Europe) [!].sfc",
            "Game (Disc 1) (unclosed", "A & B", "Tetris DX (JU) [C][!].zip", "  The  Edition  ", ".zip", "Foo.tar.gz",
            "Mario Kart 8 Deluxe Edition", "MegaMan X", "Ōkami", "(USA)", "Final Fantasy VII (Disc 2) [SCUS-94164]",
        )
        var checked = 0
        for (name in names + extra) {
            assertEquals(name, regexNormalize(name), io.github.uposum.romstore.core.model.normalizeTitle(name))
            checked++
        }
        assertTrue(checked > 10_000)
    }
}
