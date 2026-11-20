package io.github.uposum.romstore.core.art

import io.github.uposum.romstore.core.model.LibraryGame
import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.str
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BoxArtTest {
    private fun game(title: String, system: String) =
        LibraryGame(id = title, system = system, title = title, folder = "/x", files = emptyList(), size = 0, modified = 0)

    @Test
    fun prefersRegionAndPlainRelease() = runBlocking {
        val art = BoxArt(FakeHttp.unused)
        art.setIndex(
            "gb",
            listOf(
                "Pokemon - Red Version (USA, Europe) (SGB Enhanced).png",
                "Pokemon - Red Version (Japan) (Beta).png",
                "Tetris (Japan) (En).png",
                "Tetris (World) (Rev 1).png",
            ),
        )
        assertTrue(
            art.find("Pokemon Red", "gb")!!
                .endsWith("Pokemon%20-%20Red%20Version%20%28USA%2C%20Europe%29%20%28SGB%20Enhanced%29.png"),
        )
        assertTrue("World" in art.find("Tetris", "gb")!!.replace("%20", " "))
        assertNull(art.find("Pokemon Blue", "gb"))
        val entry = RomEntry(source = "x", sourceId = "1", title = "Tetris", system = "gb")
        art.apply(listOf(entry), resize = true, wait = 0.0)
        assertTrue(entry.cover!!.startsWith("https://wsrv.nl/"))
        assertTrue(entry.coverFull!!.startsWith("https://raw.githubusercontent.com/libretro-thumbnails/"))
        assertEquals(entry.coverFull, entry.extra.str("boxart"))
        assertTrue("gb" in BoxArt.SYSTEMS)
    }

    @Test
    fun ownRegionWins() {
        val art = BoxArt(FakeHttp.unused)
        art.setIndex("snes", listOf("Super Metroid (Japan, USA) (En).png", "Super Metroid (Europe) (En,Fr,De).png"))
        assertTrue("Europe" in art.find("Super Metroid (Europe)", "snes")!!.replace("%20", " "))
        assertTrue("Europe" in art.find("Super Metroid", "snes", "Europe")!!.replace("%20", " "))
        assertTrue("USA" in art.find("Super Metroid", "snes")!!.replace("%20", " "))
    }

    @Test
    fun installedGamesGetCoversByFileName() = runBlocking {
        val art = BoxArt(FakeHttp.unused)
        art.setIndex("gb", listOf("Tetris (World) (Rev 1).png"))
        val games = listOf(game("Tetris (World) (Rev 1)", "gb"), game("Unknown Homebrew", "gb"), game("sf2", "arcade"))
        art.covers(games, resize = true, wait = 0.0)
        assertTrue(games[0].cover!!.startsWith("https://wsrv.nl/"))
        assertNull(games[1].cover)
        assertNull(games[2].cover)
        art.covers(games, resize = false, wait = 0.0)
        assertTrue(games[0].cover!!.startsWith("https://raw.githubusercontent.com/libretro-thumbnails/"))
    }

    @Test
    fun namesWithoutTags() {
        val art = BoxArt(FakeHttp.unused)
        art.setIndex("snes", listOf("Legend of Zelda, The - A Link to the Past (USA).png"))
        assertEquals("The Legend of Zelda - A Link to the Past", art.name("The Legend of Zelda: A Link to the Past", "snes"))
        assertNull(art.name("Nothing", "snes"))
    }

    @Test
    fun libretroLinkFilesAreNotScans() {
        val listing =
            "<tr><td><a href=\"Rampage%20%28USA%2C%20Europe%29.png\">x</a></td><td>16-Nov-2026 10:00</td><td>19</td></tr>" +
                "<tr><td><a href=\"Rampage%20%28World%29.png\">x</a></td><td>16-Nov-2026 10:00</td><td>212K</td></tr>" +
                "<tr><td><a href=\"Tiny%20%28USA%29.png\">x</a></td><td>16-Nov-2026 10:00</td><td>4096</td></tr>"
        assertEquals(setOf("Rampage (World).png", "Tiny (USA).png"), BoxArt.scanNames(listing))
        // A listing without size cells: every link.
        assertEquals(setOf("A & B (USA).png"), BoxArt.scanNames("<a href=\"A%20&amp;%20B%20%28USA%29.png\">x</a> <a href=\"?C=N\">s</a>"))
    }

    @Test
    fun thumbUrlGoesThroughTheProxy() {
        val full = BoxArt.fullUrl("Nintendo - Game Boy", "Tetris (World) (Rev 1).png")
        assertEquals("https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy/master/Named_Boxarts/Tetris%20%28World%29%20%28Rev%201%29.png", full)
        assertEquals(
            "https://wsrv.nl/?url=raw.githubusercontent.com%2Flibretro-thumbnails%2FNintendo_-_Game_Boy%2Fmaster%2FNamed_Boxarts%2FTetris%2520%2528World%2529%2520%2528Rev%25201%2529.png&w=320&h=320&fit=inside&output=webp&q=82",
            BoxArt.thumbUrl(full),
        )
    }

    @Test
    fun indexIsFetchedOnceAndKeptOnDisk() = runBlocking {
        val root = """{"tree":[{"path":"Named_Boxarts","type":"tree","sha":"abc"},{"path":"Named_Snaps","type":"tree","sha":"def"}]}"""
        val folder = """{"tree":[{"path":"Tetris (World).png","mode":"100644","type":"blob","size":20480},""" +
            """{"path":"Tetris (Europe).png","mode":"120000","type":"blob","size":19}]}"""
        val http = FakeHttp { FakeHttp.text(it.url, if (it.url.endsWith("/master")) root else folder) }
        val dir = Files.createTempDirectory("boxart").toFile()
        val art = BoxArt(http, dir)
        val entry = RomEntry(source = "x", sourceId = "1", title = "Tetris", system = "gb")
        art.apply(listOf(entry), wait = 5.0)
        assertEquals(2, http.asked.size)
        assertEquals("https://api.github.com/repos/libretro-thumbnails/Nintendo_-_Game_Boy/git/trees/master", http.asked[0].url)
        assertEquals("https://api.github.com/repos/libretro-thumbnails/Nintendo_-_Game_Boy/git/trees/abc", http.asked[1].url)
        assertTrue(entry.coverFull!!.endsWith("Tetris%20%28World%29.png"))
        assertTrue(java.io.File(dir, "boxart/gb.json").isFile)

        val again = BoxArt(FakeHttp.unused, dir)
        val second = RomEntry(source = "x", sourceId = "2", title = "Tetris", system = "gb")
        again.apply(listOf(second), wait = 2.0) // the stored index is read inside the wait, never fetched
        assertEquals(entry.coverFull, second.coverFull)
    }
}
