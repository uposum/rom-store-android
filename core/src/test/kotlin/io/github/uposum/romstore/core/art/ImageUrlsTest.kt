package io.github.uposum.romstore.core.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageUrlsTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun onlyImagesAreCached() {
        assertTrue(ImageUrls.looksLikeImage(bytes(0xFF, 0xD8, 0xFF, 0xE0) + ByteArray(20)))
        assertTrue(ImageUrls.looksLikeImage(bytes(0x89) + "PNG\r\n\u001a\n".toByteArray() + ByteArray(20)))
        assertTrue(ImageUrls.looksLikeImage("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertTrue(ImageUrls.looksLikeImage("GIF89a".toByteArray()))
        assertTrue(ImageUrls.looksLikeImage("BM....".toByteArray()))
        assertTrue(ImageUrls.looksLikeImage("\u0000\u0000\u0000\u001cftypavif".toByteArray()))
        assertFalse(ImageUrls.looksLikeImage("<!DOCTYPE html><html>".toByteArray()))
        assertFalse("a libretro link file", ImageUrls.looksLikeImage("Rampage (World).png".toByteArray()))
        assertFalse(ImageUrls.looksLikeImage("{\"error\": \"404\"}".toByteArray()))
        assertFalse(ImageUrls.looksLikeImage(ByteArray(0)))
        assertFalse(ImageUrls.looksLikeImage("RIFF".toByteArray()))
    }

    @Test
    fun igdbScreenshotLinksAreValid() {
        for (url in listOf(
            "https://images.igdb.com/igdb/image/upload/screenshot_huge/abc.jpg",
            "https://images.igdb.com/igdb/image/upload/t_screenshot_huge/abc.jpg",
        )) {
            assertTrue("/upload/t_screenshot_huge/abc.jpg" in ImageUrls.bestVariant(url, "detail")!!)
            assertTrue("/upload/t_screenshot_med/abc.jpg" in ImageUrls.bestVariant(url, "card")!!)
        }
        assertEquals(
            "https://images.igdb.com/igdb/image/upload/t_cover_med/co1.jpg",
            ImageUrls.bestVariant("https://images.igdb.com/igdb/image/upload/t_cover_big/co1.jpg"),
        )
        assertEquals(
            "https://static.romspedia.com/webp/roms/sonic-cover-280x398.webp",
            ImageUrls.bestVariant("https://static.romspedia.com/webp/roms/sonic-cover.webp"),
        )
        assertEquals(
            "https://static.romspedia.com/webp/roms/sonic-cover.webp",
            ImageUrls.bestVariant("https://static.romspedia.com/webp/roms/sonic-cover.webp", "detail"),
        )
        assertNull(ImageUrls.bestVariant(null))
        assertNull(ImageUrls.bestVariant(""))
    }

    @Test
    fun referers() {
        assertEquals("https://www.myabandonware.com/", ImageUrls.refererFor("https://media.myabandonware.com/x.png"))
        assertEquals("https://www.romspedia.com/", ImageUrls.refererFor("https://static.romspedia.com/x.webp"))
        assertEquals("https://art.gametdb.com/", ImageUrls.refererFor("https://art.gametdb.com/switch/cover/US/A.jpg"))
        assertEquals("", ImageUrls.refererFor("not a url"))
    }
}
