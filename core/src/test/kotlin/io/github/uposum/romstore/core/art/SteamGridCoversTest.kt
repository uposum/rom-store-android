package io.github.uposum.romstore.core.art

import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.flag
import io.github.uposum.romstore.core.net.HttpError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SteamGridCoversTest {
    private fun games(json: String): List<JsonObject> = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }

    @Test
    fun cleanTitlesAndPick() {
        assertEquals("Super Mario Odyssey", SteamGridCovers.cleanTitle("Download Super Mario Odyssey NSP Full Game"))
        assertEquals("Tetris", SteamGridCovers.cleanTitle("Tetris (World) (Rev 1)"))
        val list = games("""[{"id": 1, "name": "Mario Party 5"}, {"id": 2, "name": "Kirby: Super Star Ultra"}]""")
        assertEquals("2", SteamGridCovers.pick(list, "Kirby Super Star Ultra (USA)")!!["id"].toString())
        assertNull("never a different game", SteamGridCovers.pick(list, "Kirby"))
        assertEquals(
            "a cut-off title", "3",
            SteamGridCovers.pick(games("""[{"id": 3, "name": "Mario + Rabbids Kingdom Battle"}]"""), "Mario + Rabbids Kingdom")!!["id"].toString(),
        )
        assertNull("no id, no game", SteamGridCovers.pick(games("""[{"name": "Tetris"}]"""), "Tetris"))
    }

    @Test
    fun matchingCachingAndErrors() = runBlocking {
        var down = false
        val http = FakeHttp { call ->
            assertEquals("Bearer k", call.headers["Authorization"])
            if (down) throw HttpError("Network error", null, call.url)
            val body = if ("/search/" in call.url) {
                if ("Bubble" in call.url) """{"success": true, "data": [{"id": 9, "name": "Bubble Bobble"}]}""" else """{"success": true, "data": []}"""
            } else {
                assertTrue(call.url.endsWith("/grids/game/9?${SteamGridCovers.GRIDS}"))
                """{"success": true, "data": [{"url": "https://cdn.invalid/full.png", "thumb": "https://cdn.invalid/thumb.png", "mime": "image/png"}]}"""
            }
            FakeHttp.text(call.url, body)
        }
        val cache = Files.createTempDirectory("sgdb").toFile()
        val sgdb = SteamGridCovers(http, { " k " }, cache)
        val bubble = RomEntry(source = "x", sourceId = "1", title = "Bubble Bobble (USA)", system = "nes")
        val nothing = RomEntry(source = "x", sourceId = "2", title = "Unknown Homebrew", system = "nes")
        val boxed = RomEntry(source = "x", sourceId = "3", title = "Bubble Bobble", system = "nes", cover = "https://box.invalid/x.png")
        sgdb.apply(listOf(bubble, nothing, boxed))
        assertEquals("https://cdn.invalid/thumb.png" to "https://cdn.invalid/full.png", bubble.cover to bubble.coverFull)
        assertTrue(bubble.extra.flag("sgdb"))
        assertNull(nothing.cover)
        assertEquals("a game with a cover keeps it", "https://box.invalid/x.png", boxed.cover)
        assertTrue(http.asked.any { it.url == "${SteamGridCovers.API}/search/autocomplete/Bubble%20Bobble" })
        sgdb.settle()

        val asked = http.asked.size
        val again = SteamGridCovers(http, { "k" }, cache)
        val twice = listOf(
            RomEntry(source = "x", sourceId = "4", title = "Bubble Bobble", system = "nes"),
            RomEntry(source = "x", sourceId = "5", title = "Unknown Homebrew", system = "nes"),
        )
        again.apply(twice)
        assertEquals("found and not found are both remembered on disk", asked, http.asked.size)
        assertEquals("https://cdn.invalid/thumb.png", twice[0].cover)
        assertEquals(emptyList<String>(), again.cached("Unknown Homebrew"))

        down = true
        val offline = SteamGridCovers(http, { "k" }, Files.createTempDirectory("sgdb").toFile())
        assertNull("a network error is not an answer", offline.lookup("Bubble Bobble"))
        assertNull(offline.cached("Bubble Bobble"))

        assertFalse(SteamGridCovers(http, { "  " }).enabled)
        assertEquals(ArtKeys.STEAMGRIDDB_KEY, ArtKeys.steamGridDbKey(""))
        assertEquals("the user's own key wins", "mine", ArtKeys.steamGridDbKey(" mine "))
    }

    @Test
    fun anUnsuccessfulAnswerIsAnError() = runBlocking {
        val http = FakeHttp { FakeHttp.text(it.url, """{"success": false, "errors": ["bad key"]}""") }
        assertNull(SteamGridCovers(http, { "k" }).lookup("Tetris"))
    }
}
