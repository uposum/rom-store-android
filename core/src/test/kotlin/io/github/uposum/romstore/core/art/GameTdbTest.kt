package io.github.uposum.romstore.core.art

import io.github.uposum.romstore.core.model.LibraryGame
import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.matchTitle
import io.github.uposum.romstore.core.model.str
import io.github.uposum.romstore.core.net.HttpError
import io.github.uposum.romstore.core.net.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class GameTdbTest {
    private fun tempDir() = Files.createTempDirectory("gametdb").toFile()

    private fun rom(id: String, title: String, system: String = "switch") =
        RomEntry(source = "x", sourceId = id, title = title, system = system)

    @Test
    fun titleListParsingAndGrouping() {
        val titles = GameTdb.parse(
            "TITLES = https://www.gametdb.com (type: Switch)\nAAACA = Super Mario Odyssey\n" +
                "AAAC3 = Super Mario Odyssey [Starter Pack]\nnot a line\n",
        )
        assertEquals(mapOf("AAACA" to "Super Mario Odyssey", "AAAC3" to "Super Mario Odyssey [Starter Pack]"), titles)
        assertEquals(listOf("AAACA", "AAAC3"), GameTdb.group(titles)[matchTitle("Super Mario Odyssey")])
    }

    @Test
    fun cutOffTitlesAndOtherConsoles() {
        val tdb = GameTdb(FakeHttp.unused, tempDir())
        tdb.setTitles(
            "switch",
            mapOf(
                "AC2GA" to "Mario + Rabbids: Kingdom Battle",
                "BAANA" to "Mario + Rabbids Kingdom Battle Gold Edition",
                "AABPA" to "Mario Kart 8 Deluxe",
            ),
        )
        assertEquals("cut off: the shortest full title", listOf("AC2GA"), tdb.ids("Mario + Rabbids Kingdom Switch", "switch"))
        assertEquals("too short to guess from", emptyList<String>(), tdb.ids("Mario Kart", "switch"))
        assertEquals("a console GameTDB does not have", emptyList<String>(), tdb.ids("Mario Kart 8 Deluxe", "n64"))
        assertEquals(listOf("AABPA"), tdb.ids("Download Mario Kart 8 Deluxe NSP Full Game", "switch"))
        assertEquals("Mario Kart 8 Deluxe", tdb.name("Mario Kart 8 Deluxe Switch (NSP)", "switch"))
        assertEquals("https://art.gametdb.com/wii/cover/US/RMCE01.png", GameTdb.coverUrl("wii", "RMCE01", "US", proxy = false))
        assertEquals("https://art.gametdb.com/ds/coverM/US/AMCE.jpg", GameTdb.coverUrl("ds", "AMCE", "US", "coverM", proxy = false))
        assertEquals(
            "through the proxy by default",
            "https://wsrv.nl/?url=art.gametdb.com%2Fds%2Fcover%2FUS%2FAMCE.jpg&output=webp&q=85",
            GameTdb.coverUrl("ds", "AMCE", "US"),
        )
        assertEquals("cover" to "cover", GameTdb.kinds("wii"))
        assertEquals("cover" to "coverM", GameTdb.kinds("switch"))
    }

    @Test
    fun otherRegionsDirectAndThroughTheProxy() {
        val proxied = GameTdb.alternatives(GameTdb.coverUrl("switch", "AAACA", "US"))
        assertTrue(GameTdb.coverUrl("switch", "AAACA", "EN") in proxied)
        assertTrue(proxied.all { it.startsWith("https://wsrv.nl/") })
        val direct = GameTdb.alternatives(GameTdb.coverUrl("switch", "AAACA", "US", proxy = false))
        assertEquals(
            listOf(
                "https://art.gametdb.com/switch/cover/EN/AAACA.jpg",
                "https://art.gametdb.com/switch/cover/JA/AAACA.jpg",
                "https://art.gametdb.com/switch/cover/other/AAACA.jpg",
            ),
            direct,
        )
        assertEquals(emptyList<String>(), GameTdb.alternatives("https://example.invalid/a.jpg"))
        assertEquals(emptyList<String>(), GameTdb.alternatives(null))
    }

    @Test
    fun noEshopStandInsForSwitch() = runBlocking {
        val tdb = GameTdb(FakeHttp.unused, tempDir())
        tdb.setTitles("switch", mapOf("AQMXA" to "Super Mario Bros. Wonder", "ZZZZA" to "Some eShop Game"))
        tdb.setKnown("switch", "AQMXA", "US")
        tdb.setKnown("switch", "ZZZZA", "US")
        tdb.retail = setOf("AQMXA")
        val retail = rom("1", "Super Mario Bros. Wonder")
        val eshop = rom("2", "Some eShop Game")
        tdb.apply(listOf(retail, eshop), wait = 0.0)
        assertEquals(GameTdb.coverUrl("switch", "AQMXA", "US"), retail.cover)
        assertEquals(GameTdb.coverUrl("switch", "AQMXA", "US", "coverM"), retail.coverFull)
        assertEquals("AQMXA", retail.extra.str("gametdb"))
        assertNull("an eShop-only game's GameTDB cover is a stand-in", eshop.cover)
        val shipped = GameTdb.retailIds()
        assertTrue("AQMXA" in shipped)
        assertTrue(shipped.size > 4000)
    }

    @Test
    fun knownRegionsAndNoCover() = runBlocking {
        val tdb = GameTdb(FakeHttp.unused, tempDir())
        tdb.setTitles(
            "switch",
            mapOf(
                "AABPA" to "Mario Kart 8 Deluxe", "AABPK" to "Mario Kart 8 Deluxe Bundle (Game + Pass)",
                "ACBAA" to "Animal Crossing: New Horizons", "AXN7A" to "Some Japan Game",
            ),
        )
        tdb.retail = setOf("AABPA", "AABPK", "ACBAA", "AXN7A")
        tdb.setKnown("switch", "AABPA", "US")
        tdb.setKnown("switch", "AABPK", "US")
        tdb.setKnown("switch", "ACBAA", "")
        tdb.setKnown("switch", "AXN7A", "JA")
        val kart = rom("1", "Mario Kart 8 Deluxe")
        val crossing = rom("2", "Animal Crossing: New Horizons")
        val japan = rom("3", "Some Japan Game (Japan)")
        tdb.apply(listOf(kart, crossing, japan), wait = 0.0)
        assertEquals("the plain game, not the bundle", GameTdb.coverUrl("switch", "AABPA", "US"), kart.cover)
        assertNull("known to have no cover", crossing.cover)
        assertEquals(GameTdb.coverUrl("switch", "AXN7A", "JA"), japan.cover)

        // "No cover" is believed for a week, then asked again.
        val later = GameTdb(FakeHttp.unused, tempDir())
        later.setKnown("switch", "ACBAA", "", at = 0.0)
        assertNull(later.known("switch", "ACBAA"))
    }

    @Test
    fun titleListAndProbing() = runBlocking {
        val titles = GameTdb.parse("AAACA = Super Mario Odyssey\nAAAC3 = Super Mario Odyssey [Starter Pack]\n")
        val http = FakeHttp { call ->
            when {
                "/US/" in call.url -> throw HttpError("HTTP 404", 404, call.url)
                "/EN/" in call.url -> Response(200, call.url)
                else -> throw HttpError("Network error", null, call.url)
            }
        }
        val dir = tempDir()
        val tdb = GameTdb(http, dir)
        tdb.proxy = false // direct requests: the fake server answers those
        tdb.retail = setOf("AAACA", "AAAC3")
        tdb.setTitles("switch", titles)
        val entry = rom("1", "Super Mario Odyssey")
        tdb.apply(listOf(entry), wait = 2.0)
        assertEquals("at once, the likeliest region", "https://art.gametdb.com/switch/cover/US/AAACA.jpg", entry.cover)
        tdb.settle()
        val again = rom("2", "Super Mario Odyssey")
        tdb.apply(listOf(again), wait = 2.0)
        assertEquals("then the region that has it", "https://art.gametdb.com/switch/cover/EN/AAACA.jpg", again.cover)
        assertEquals("EN", tdb.known("switch", "AAACA"))
        assertTrue(http.asked.all { it.method == "HEAD" })
        tdb.settle()
        assertEquals("kept on disk", "EN", GameTdb(http, dir).known("switch", "AAACA"))
    }

    @Test
    fun listIsFetchedAndKept() = runBlocking {
        val lines = (1..150).joinToString("\n") { "A%04dA = Game Number %d".format(it, it) }
        val http = FakeHttp { FakeHttp.text(it.url, "TITLES = https://www.gametdb.com (type: Switch)\n$lines\n") }
        val dir = tempDir()
        val tdb = GameTdb(http, dir)
        tdb.retail = emptySet()
        val game = LibraryGame(id = "g", system = "nds", title = "Game Number 7", folder = "/", files = emptyList(), size = 0, modified = 0)
        tdb.covers(listOf(game), wait = 5.0)
        assertEquals("https://www.gametdb.com/dstdb.txt?LANG=EN", http.asked.first().url)
        assertEquals(GameTdb.coverUrl("ds", "A0007A", "US"), game.cover)
        tdb.settle()
        // A second instance reads the list from disk instead of fetching it.
        val reloaded = GameTdb(FakeHttp.unused, dir)
        val entry = rom("x", "Game Number 7", "nds")
        reloaded.apply(listOf(entry), wait = 0.0)
        assertEquals(game.cover, entry.cover)
        assertEquals("Game Number 7", reloaded.name("game number 7", "nds"))
    }

    @Test
    fun downIsNotWaitedForTwice() = runBlocking {
        val calls = AtomicInteger()
        val down = FakeHttp { calls.incrementAndGet(); throw HttpError("timed out") }
        val tdb = GameTdb(down, tempDir())
        val games = listOf(rom("1", "Metroid Dread"))
        tdb.apply(games, wait = 0.5)
        val started = System.nanoTime()
        tdb.apply(games, wait = 0.5)
        assertTrue("the failed list is remembered, not fetched and waited for again", (System.nanoTime() - started) / 1e9 < 0.2)
        assertEquals(1, calls.get())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hangs = FakeHttp { delay(5000); throw HttpError("timed out") }
        val slow = GameTdb(hangs, tempDir(), scope)
        slow.listWait = 0.3
        slow.apply(games, wait = 0.3) // the first search waits (listWait at most)
        val again = System.nanoTime()
        slow.apply(games, wait = 0.3)
        assertTrue("a fetch still running is not waited for by every search", (System.nanoTime() - again) / 1e9 < 0.1)
        scope.cancel()
    }
}
