package io.github.uposum.romstore.core

import io.github.uposum.romstore.core.art.FakeHttp
import io.github.uposum.romstore.core.model.Content
import io.github.uposum.romstore.core.model.ContentKind
import io.github.uposum.romstore.core.net.FileHosts
import io.github.uposum.romstore.core.net.HttpError
import io.github.uposum.romstore.core.sources.ArchiveOrgSource
import io.github.uposum.romstore.core.sources.NswGameSource
import io.github.uposum.romstore.core.sources.SourceOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.zip.GZIPInputStream

/** The Internet Archive and NSWGame readers against pages recorded in Nov 2026, and the file-host rules. */
class FileHostSourcesTest {
    private val pages: Map<String, String> by lazy {
        val stream = javaClass.getResourceAsStream("/fixtures/filehost_sources.json.gz")!!
        val obj = Json.parseToJsonElement(GZIPInputStream(stream).bufferedReader().readText()) as JsonObject
        obj.mapValues { it.value.jsonPrimitive.content }
    }

    private val http = FakeHttp { asked ->
        val url = asked.url
        val body = when {
            "advancedsearch.php" in url -> pages["archive:search"]
            "/metadata/" in url -> pages["archive:metadata/" + java.net.URLDecoder.decode(url.substringAfter("/metadata/"), "UTF-8")]
            "?s=" in url -> pages["nswgame:search"]
            "/category/switch/" in url && "/page/" !in url -> pages["nswgame:category"]
            "mario-kart-8-deluxe-nintendo-switch-download-free" in url -> pages["nswgame:post"]
            else -> null
        }
        if (body == null) throw HttpError("HTTP 404", 404, url) else FakeHttp.text(url, body)
    }

    private val nsw = NswGameSource(http, SourceOptions("nswgame", "NSWGame", "https://nswgf.com"))
    private val archive = ArchiveOrgSource(http, SourceOptions("archiveorg", "Internet Archive", "https://archive.org"))

    @Test fun nswGameSearchTellsConsolesFromTheSlug() = runBlocking {
        val results = nsw.search("mario kart 8", 40, 1)
        val deluxe = results.first { it.title == "Mario Kart 8 Deluxe" }
        assertEquals("switch", deluxe.system)
        assertNotNull(deluxe.cover)
        assertEquals("wiiu", results.first { it.title == "Mario Kart 8" }.system)
    }

    @Test fun nswGameCategoryIsACatalogue() = runBlocking {
        val games = nsw.browse("switch", 40)
        assertTrue(games.size >= 10)
        assertTrue(games.all { it.system == "switch" })
    }

    @Test fun nswGamePostGivesBaseGameUpdateAndDlc() = runBlocking {
        val entry = nsw.search("mario kart 8", 40, 1).first { it.title == "Mario Kart 8 Deluxe" }
        val files = nsw.resolve(entry)
        val kinds = files.groupBy { Content.classifyFile(it.filename, it.label, entry.title).kind }
        // The base game whole on 1fichier (the parts on Megaup are left out), named after the archive.
        val base = kinds[ContentKind.BASE].orEmpty()
        assertTrue(base.any { "1fichier.com" in it.url && it.filename.endsWith(".nsp.rar") && ".part" !in it.filename })
        assertTrue(files.none { ".part1" in it.url || ".part2" in it.url })
        // Update 4.0.1 on four hosts, 1fichier first (the app can fetch it).
        val update = files.filter { it.label?.startsWith("Update 4.0.1") == true }
        assertEquals(4, update.size)
        assertTrue("1fichier.com" in update.first().url)
        assertEquals(ContentKind.UPDATE, Content.classifyFile(update.first().filename, update.first().label, entry.title).kind)
        // The Booster Course: its file name says nothing, its label says DLC.
        assertTrue(kinds[ContentKind.DLC].orEmpty().any { "Booster Course" in (it.label ?: "") })
        assertTrue(files.any { it.region == "China" })
    }

    @Test fun archiveFindsTheGameItsUpdatesAndDlcButNoModsOrFirmware() = runBlocking {
        val entries = archive.search("mario kart 8 deluxe", 40, 1)
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { it.system == "switch" })
        val files = entries.flatMap { e -> e.files.map { e to it } }
        val kinds = files.map { (e, f) -> Content.classifyFile(f.filename, f.label, e.title).kind }.toSet()
        assertTrue(ContentKind.UPDATE in kinds)
        assertTrue(ContentKind.DLC in kinds)
        assertTrue(files.any { (_, f) -> f.filename == "Mario Kart 8 Deluxe.nsp" && (f.size ?: 0) > 7_000_000_000 })
        val titles = entries.map { it.title.lowercase() }
        assertFalse(titles.any { "300cc" in it || "kiosk" in it || "mods" in it || "soundtrack" in it })
        assertTrue(files.all { (_, f) -> f.url.startsWith("https://archive.org/download/") })
        assertFalse(files.any { (_, f) -> "firmware" in f.filename.lowercase() || f.filename.endsWith(".keys") })
    }

    @Test fun archiveQueriesWithoutUpdateAndDlcWords() {
        assertEquals(listOf("mario", "kart", "8", "deluxe"), ArchiveOrgSource.queryWords("Mario Kart 8 Deluxe update"))
        assertEquals("switch", ArchiveOrgSource.systemFor("nsp", ""))
        assertEquals("ps2", ArchiveOrgSource.systemFor("iso", "Gran Turismo 4 PS2 NTSC"))
        assertEquals("wiiu", ArchiveOrgSource.systemFor("zip", "Mario Kart 8 Wii U"))
        assertEquals(null, ArchiveOrgSource.systemFor("zip", "Some Program"))
    }

    @Test fun fileHostRules() {
        assertTrue(FileHosts.needsBrowser("https://megaup.net/2j2H9/x.rar"))
        assertTrue(FileHosts.needsBrowser("https://vik1ngfile.site/f/ag3QQlZIHe"))
        assertFalse(FileHosts.needsBrowser("https://1fichier.com/?2wddmigh6q683dvcc0tn&af=1951572"))
        assertTrue(FileHosts.resolvesLate("https://1fichier.com/?2wddmigh6q683dvcc0tn"))
        assertFalse(FileHosts.resolvesLate("https://archive.org/download/x/y.nsp"))
        assertEquals("1fichier", FileHosts.label("https://1fichier.com/?abc"))
        assertEquals("Megaup", FileHosts.label("https://megaup.net/abc"))
    }

    @Test fun oneFichierHandsOutItsLinkAfterTheForm() = runBlocking {
        val fake = FakeHttp { asked ->
            when (asked.method) {
                "GET" -> FakeHttp.text(asked.url, """<script>var ct = 0</script><form id="f1"><input type="hidden" name="adz" value="7.1"/></form>""")
                else -> {
                    assertEquals("adz=7.1", asked.body)
                    FakeHttp.text(asked.url, """<a href="https://img.1fichier.com/x.png"></a><a href="https://a-37.1fichier.com/c1285062876" class="ok btn-general">Click here</a>""")
                }
            }
        }
        val notes = mutableListOf<String>()
        assertEquals("https://a-37.1fichier.com/c1285062876", FileHosts.resolve(fake, "https://1fichier.com/?abc", notes::add))
    }

    @Test fun oneFichierWaitIsReportedNotWorkedAround() = runBlocking {
        val fake = FakeHttp { FakeHttp.text(it.url, "<br>You must wait 118 minutes or subscribe") }
        try {
            FileHosts.resolve(fake, "https://1fichier.com/?abc")
            fail("expected the wait to be reported")
        } catch (e: HttpError) {
            assertTrue("118 minutes" in (e.message ?: ""))
            assertFalse(e.retry)
            assertEquals(1, fake.asked.size)
        }
    }

    @Test fun switchUploadsAreNamedByTheirTitleIdSoTheyGroupWithTheGame() = runBlocking {
        val entries = archive.search("mario kart 8 deluxe", 40, 1)
        val names = mapOf("0100152000022000" to "Mario Kart 8 Deluxe")
        io.github.uposum.romstore.core.store.nameByTitleId(entries) { names[it] }
        val keys = entries.map { Content.baseKey(it.title) }.toSet()
        assertEquals(setOf(Content.baseKey("Mario Kart 8 Deluxe")), keys)
        assertTrue(entries.any { it.title == "Mario Kart 8 Deluxe DLC" })
        assertTrue(entries.any { it.title.startsWith("Mario Kart 8 Deluxe Update") })
    }

    @Test fun labelsGiveReadableUpdateVersions() {
        val upd = Content.classifyFile("Mario_Kart_8_Deluxe_[0100152000022800][v1507328]_[taodung.com].rar", "Update 4.0.1 (v1507328) · NSP · USA · 1fichier", "Mario Kart 8 Deluxe")
        assertEquals(ContentKind.UPDATE, upd.kind)
        assertEquals("4.0.1", upd.version)
        val old = Content.classifyFile("MK8-DLUXE-(US)-NSwTcH-NSP-Update400-Ziperto.rar", "Update 4.0.0 (v1441792) (Required Firmware – v22.5.0) · NSP · USA · Megaup", "Mario Kart 8 Deluxe")
        assertEquals(ContentKind.UPDATE, old.kind)
        assertEquals("4.0.0", old.version)
    }
}
