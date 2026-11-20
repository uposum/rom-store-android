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

    // Trimmed from a live search page (Nov 2026): every result carries several
    // "videoId"s (thumbnail, watch endpoint, menu), so ids and titles cannot be
    // paired across the page.
    private val rendererPage = """{"itemSectionRenderer":{"contents":[""" +
        """{"videoRenderer":{"videoId":"xQI2hWQQCyc","thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/xQI2hWQQCyc/hq720.jpg"}]},""" +
        """"title":{"runs":[{"text":"God of War: Ghost of Sparta - Story Trailer"}]},""" +
        """"navigationEndpoint":{"watchEndpoint":{"videoId":"xQI2hWQQCyc"}},"menu":{"videoId":"xQI2hWQQCyc"}}},""" +
        """{"videoRenderer":{"videoId":"Zz9ZPU8NmOk","thumbnail":{},"title":{"runs":[{"text":"Ghost of Sparta Gameplay"}]},""" +
        """"navigationEndpoint":{"watchEndpoint":{"videoId":"Zz9ZPU8NmOk"}}}}]}}"""

    // The newer layout: a lockup per result, the id only in the thumbnail.
    private val lockupPage = """[""" +
        """{"lockupViewModel":{"contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://i.ytimg.com/vi/4_l_Va5YwbM/hq720.jpg"}]}}}}},""" +
        """"metadata":{"lockupMetadataViewModel":{"title":{"content":"God Of War: Ghost Of Sparta - Trailer & Gameplay"}}},""" +
        """"contentId":"PLA7704F432243D9C0","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST"}},""" +
        """{"lockupViewModel":{"contentImage":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://i.ytimg.com/vi/aaaaaaaaaaa/hq720.jpg"}]}}},""" +
        """"metadata":{"lockupMetadataViewModel":{"title":{"content":"Ghost of Sparta speedrun"}}},""" +
        """"contentId":"aaaaaaaaaaa","contentType":"LOCKUP_CONTENT_TYPE_VIDEO"}},""" +
        """{"lockupViewModel":{"contentImage":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://i.ytimg.com/vi/bbbbbbbbbbb/hq720.jpg"}]}}},""" +
        """"metadata":{"lockupMetadataViewModel":{"title":{"content":"God of War: Ghost of Sparta & Chains of Olympus - Launch Trailer"}}},""" +
        """"contentId":"bbbbbbbbbbb","contentType":"LOCKUP_CONTENT_TYPE_VIDEO"}}]"""

    @Test
    fun eachResultKeepsItsOwnTitle() {
        assertEquals(
            listOf("xQI2hWQQCyc" to "God of War: Ghost of Sparta - Story Trailer", "Zz9ZPU8NmOk" to "Ghost of Sparta Gameplay"),
            YouTube.results(rendererPage),
        )
        assertEquals("xQI2hWQQCyc", YouTube.pick(rendererPage, "God of War: Ghost of Sparta")!!.id)

        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), YouTube.results(lockupPage).map { it.first })
        val trailer = YouTube.pick(lockupPage, "God of War: Ghost of Sparta")!!
        assertEquals("a playlist is not a video", "bbbbbbbbbbb", trailer.id)
        assertEquals("God of War: Ghost of Sparta & Chains of Olympus - Launch Trailer", trailer.name)
    }
}
