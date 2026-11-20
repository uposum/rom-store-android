package io.github.uposum.romstore.core.art

import io.github.uposum.romstore.core.net.HttpError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class IgdbCoversTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun list(json: String): List<JsonObject> = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }

    private val games = """[
        {"id": 1, "name": "Super Mario Odyssey"},
        {"id": 2, "name": "Super Mario Odyssey", "rating": 91.4, "videos": [{"video_id": "abcdefghijk", "name": "Launch Trailer"}],
         "cover": {"image_id": "co1abc"}, "summary": "A trip.", "screenshots": [{"image_id": "sc1", "width": 1920, "height": 1080}],
         "genres": [{"name": "Platform"}, {"name": "Adventure"}], "platforms": [{"name": "Nintendo Switch"}],
         "involved_companies": [{"company": {"name": "Nintendo EPD"}}, {"company": {"name": "Nintendo"}}, {"company": {"name": "Third"}}],
         "artworks": [{"image_id": "ar1", "width": 3840, "height": 2160}], "first_release_date": 1509062400, "rating_count": 1200},
        {"id": 3, "name": "Super Mario Odyssey: Kingdom Adventures"}
    ]"""

    @Test
    fun ratingIsOutOfFive() {
        assertEquals(4.6, IgdbCovers.rating(obj("""{"rating": 91.4}""")))
        assertEquals(4.0, IgdbCovers.rating(obj("""{"total_rating": 80}""")))
        assertEquals("every score is out of 100", 4.2, IgdbCovers.rating(obj("""{"aggregated_rating": 83}""")))
        assertEquals("Python's round(): half to even on the exact value", 2.2, IgdbCovers.rating(obj("""{"rating": 45}""")))
        assertNull(IgdbCovers.rating(obj("""{"rating": null}""")))
        assertNull(IgdbCovers.rating(obj("""{}""")))
    }

    @Test
    fun bestGameIsTheRichOne() {
        assertEquals("2", IgdbCovers.bestGame(list(games), "super mario odyssey")!!["id"].toString())
        assertNull("no shared words", IgdbCovers.bestGame(list("""[{"name": "Tetris"}]"""), "pokemon red"))
        assertEquals(
            "three of four words", "7",
            IgdbCovers.bestGame(list("""[{"id": 7, "name": "zelda wind legend remix"}]"""), "legend zelda wind waker")!!["id"].toString(),
        )
        assertNull("two of four words", IgdbCovers.bestGame(list("""[{"name": "zelda wind remix"}]"""), "legend zelda wind waker"))
        assertNull("a different game", IgdbCovers.bestGame(list("""[{"name": "Kirby Air Riders"}]"""), "super mario odyssey"))
    }

    @Test
    fun theMatchComesBeforeTheMetadata() {
        val metroid = list("""[
            {"id": 1, "name": "Metroid Prime", "rating": 90, "videos": [{"video_id": "abcdefghijk"}], "screenshots": [{"image_id": "s"}], "summary": "x"},
            {"id": 2, "name": "Metroid"}
        ]""")
        assertEquals("the same name beats a richer longer one", "2", IgdbCovers.bestGame(metroid, "Metroid")!!["id"].toString())
        assertNull("whole words: \"art\" is not in \"mario kart\"", IgdbCovers.bestGame(list("""[{"name": "Art"}]"""), "Mario Kart"))
        assertNull("whole words: \"mario kart\" does not start \"mario karting\"", IgdbCovers.bestGame(list("""[{"name": "Mario Karting"}]"""), "Mario Kart"))
        assertEquals(
            "a longer name with the whole title still matches", "3",
            IgdbCovers.bestGame(list("""[{"id": 3, "name": "Mario Kart 8 Deluxe"}]"""), "Mario Kart")!!["id"].toString(),
        )
    }

    @Test
    fun infoFromTheBestGame() {
        val info = IgdbCovers.info(IgdbCovers.bestGame(list(games), "super mario odyssey")!!)!!
        assertEquals("https://images.igdb.com/igdb/image/upload/t_cover_big/co1abc.jpg", info.cover)
        assertEquals(4.6, info.rating)
        assertEquals(1200, info.ratingCount)
        assertEquals("co1abc", info.coverId)
        assertEquals("${IgdbCovers.SCREENSHOT_BASE}sc1.jpg", info.shots.single().full)
        assertTrue(IgdbCovers.SCREENSHOT_BASE.endsWith("/upload/t_screenshot_huge/"))
        assertEquals(listOf(IgdbImage("ar1", 3840, 2160)), info.artworks)
        assertEquals("abcdefghijk", info.trailer!!.id)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", info.trailer.url)
        assertEquals("Launch Trailer", info.trailer.name)
        assertEquals(listOf("Nintendo EPD", "Nintendo"), info.developers)
        assertEquals(listOf("Platform", "Adventure"), info.genres)
        assertEquals(1509062400L, info.released)
        assertNull("nothing worth showing", IgdbCovers.info(obj("""{"name": "Bare"}""")))
    }

    @Test
    fun lookupUsesTheTokenAndTheCache() = runBlocking {
        var down = false
        val http = FakeHttp { call ->
            if (down) throw HttpError("Network error", null, call.url)
            when (call.url) {
                IgdbCovers.TOKEN_URL -> {
                    assertTrue(call.body!!.contains("grant_type=client_credentials"))
                    FakeHttp.text(call.url, """{"access_token": "tok", "expires_in": 5000}""")
                }
                IgdbCovers.API_URL -> {
                    assertEquals("Bearer tok", call.headers["Authorization"])
                    assertEquals("id", call.headers["Client-ID"])
                    assertTrue(call.body!!.startsWith("search \"Super Mario Odyssey\"; fields name,cover.image_id"))
                    FakeHttp.text(call.url, if ("Odyssey" in call.body) games else "[]")
                }
                else -> error(call.url)
            }
        }
        val dir = Files.createTempDirectory("igdb").toFile()
        val igdb = IgdbCovers(http, "id", "secret", dir)
        val info = igdb.lookup(" Super Mario Odyssey ")
        assertEquals(4.6, info!!.rating)
        assertEquals(2, http.asked.size)
        assertEquals(info, igdb.lookup("super mario odyssey"))
        assertEquals("answered from memory", 2, http.asked.size)

        val again = IgdbCovers(http, "id", "secret", dir)
        assertEquals("kept on disk", info, again.lookup("Super Mario Odyssey"))
        assertEquals(2, http.asked.size)
        assertEquals(info, again.cached("Super Mario Odyssey"))

        down = true
        val offline = IgdbCovers(http, "id", "secret", Files.createTempDirectory("igdb").toFile())
        assertNull(offline.lookup("Zelda Breath of the Wild"))
        assertNull(IgdbCovers(http, "", "", dir).lookup("Super Mario Odyssey"))
        assertNull("too short to search", igdb.lookup("The Ab"))
    }

    @Test
    fun lookupManyKeepsTheTitles() = runBlocking {
        val http = FakeHttp { call ->
            if (call.url == IgdbCovers.TOKEN_URL) FakeHttp.text(call.url, """{"access_token": "tok"}""")
            else FakeHttp.text(call.url, if ("Odyssey" in call.body!!) games else "[]")
        }
        val found = IgdbCovers(http, "id", "secret").lookupMany(listOf("Super Mario Odyssey", "", "Unknown Thing", "Super Mario Odyssey"))
        assertEquals(setOf("Super Mario Odyssey", "Unknown Thing"), found.keys)
        assertEquals(4.6, found["Super Mario Odyssey"]!!.rating)
        assertNull(found["Unknown Thing"])
    }
}
