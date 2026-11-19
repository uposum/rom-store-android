package io.github.uposum.romstore.core.art

import io.github.uposum.romstore.core.net.HttpError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeTest {
    private fun video(id: String, title: String) = """{"videoId":"$id","thumbnail":{},"title":{"runs":[{"text":"$title"}]}}"""

    @Test
    fun picksTheTrailer() {
        val body = listOf(
            video("aaaaaaaaaaa", "Unrelated video"),
            video("bbbbbbbbbbb", "Metroid Dread - Gameplay"),
            video("ccccccccccc", "Metroid Dread - Official Trailer"),
            video("ddddddddddd", "Metroid Dread Trailer 2"),
        ).joinToString(",")
        val trailer = YouTube.pick(body, "Metroid Dread")!!
        assertEquals("ccccccccccc", trailer.id)
        assertEquals("https://www.youtube.com/watch?v=ccccccccccc", trailer.url)
        assertEquals("https://img.youtube.com/vi/ccccccccccc/mqdefault.jpg", trailer.thumb)
        assertEquals("Metroid Dread - Official Trailer", trailer.name)
        assertEquals("https://www.youtube.com/embed/ccccccccccc?autoplay=1&rel=0", YouTube.embedUrl("ccccccccccc"))
        assertNull(YouTube.pick(video("aaaaaaaaaaa", "Something else entirely"), "Metroid Dread"))
        assertEquals(
            "an escaped quote in the title",
            "eeeeeeeeeee", YouTube.pick(video("eeeeeeeeeee", """The \"Zelda\" trailer"""), "Zelda")!!.id,
        )
    }

    @Test
    fun searchesWithTheTitle() = runBlocking {
        val http = FakeHttp { FakeHttp.text(it.url, video("ccccccccccc", "Tetris 99 Trailer")) }
        assertEquals("ccccccccccc", YouTube(http).trailer("Tetris 99")!!.id)
        assertEquals("https://www.youtube.com/results?search_query=Tetris+99+trailer", http.asked.single().url)
        val down = FakeHttp { throw HttpError("Network error") }
        assertNull(YouTube(down).trailer("Tetris 99"))
        assertNull(YouTube(down).trailer(""))
    }
}
