package io.github.uposum.romstore.core

import io.github.uposum.romstore.core.art.FakeHttp
import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.net.Response
import io.github.uposum.romstore.core.sources.RetrosticSource
import io.github.uposum.romstore.core.sources.Source
import io.github.uposum.romstore.core.sources.SourceOptions
import io.github.uposum.romstore.core.sources.SwitchRomsSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sites that move between example.com and www.example.com, and Retrostic's search pages. */
class SourceHostsTest {
    private fun page(url: String, body: String) =
        Response(200, url, mapOf("Content-Type" to "text/html; charset=utf-8"), body.toByteArray())

    @Test fun wwwAndTheBareHostAreOneSite() {
        assertEquals("retrostic.com", Source.hostOf("https://www.retrostic.com/roms/snes/page/2"))
        assertEquals("retrostic.com", Source.hostOf("https://Retrostic.com"))
    }

    @Test fun aCatalogueRedirectedToWwwKeepsPaging() = runBlocking {
        // retrostic.com answers every page from www.retrostic.com.
        val http = FakeHttp { asked ->
            val n = asked.url.substringAfter("/page/", "1").toInt()
            val www = asked.url.replace("://retrostic.com", "://www.retrostic.com")
            page(www, """<table><tr><td><a href="/roms/snes/game-$n"><p itemprop="name">Game $n</p></a></td></tr></table>""")
        }
        val source = RetrosticSource(http, SourceOptions("retrostic", "Retrostic", "https://retrostic.com"))
        assertEquals((1..6).map { "Game $it" }, source.browse("snes", 40).map { it.title })
    }

    @Test fun theDownloadFormIsPostedToThePagesOwnHost() = runBlocking {
        val http = FakeHttp { asked ->
            when (asked.method) {
                "GET" -> page(
                    asked.url.replace("://retrostic.com", "://www.retrostic.com"),
                    """<form id="dl" action="/roms/snes/x/download" method="post"><input type="hidden" name="session" value="abc"></form>""",
                )
                else -> page(asked.url, """<script>window.location.href = "https://files.example.org/x.zip"</script>""")
            }
        }
        val source = RetrosticSource(http, SourceOptions("retrostic", "Retrostic", "https://retrostic.com"))
        val entry = RomEntry(source = "retrostic", sourceId = "roms/snes/x", title = "X", system = "snes", url = "https://retrostic.com/roms/snes/x")
        val files = source.resolve(entry)
        assertEquals("https://www.retrostic.com/roms/snes/x/download", http.asked.single { it.method == "POST" }.url)
        assertEquals("https://files.example.org/x.zip", files.single().url)
    }

    @Test fun retrosticSearchAsksForThePage() = runBlocking {
        val http = FakeHttp { asked ->
            page(asked.url, """<a class='page-link' href='/search?currentpage=1&search_term_string=zelda'>1</a>""" +
                """<a class='page-link' href='/search?currentpage=3&search_term_string=zelda'>3</a>""")
        }
        val source = RetrosticSource(http, SourceOptions("retrostic", "Retrostic", "https://www.retrostic.com"))
        source.search("zelda", 40, 1)
        source.search("zelda", 40, 2)
        assertEquals(
            listOf(
                "https://www.retrostic.com/search?search_term_string=zelda",
                "https://www.retrostic.com/search?currentpage=2&search_term_string=zelda",
            ),
            http.asked.map { it.url },
        )
        assertEquals(3, source.lastPageCount)
    }

    @Test fun switchRomsCardsOnTheOtherHostCount() = runBlocking {
        val http = FakeHttp { asked ->
            page(asked.url, """<a href="https://www.nintendo-switch-roms.com/super-game-deluxe/">Super Game Deluxe</a>""" +
                """<a href="https://nintendo-switch-roms.com/other-game-title/">Other Game Title</a>""")
        }
        val source = SwitchRomsSource(http, SourceOptions("switchroms", "Switch ROMs", "https://nintendo-switch-roms.com"))
        val titles = source.browse("switch", 2).map { it.title }
        assertTrue(titles.toString(), "Super Game Deluxe" in titles && "Other Game Title" in titles)
    }
}
