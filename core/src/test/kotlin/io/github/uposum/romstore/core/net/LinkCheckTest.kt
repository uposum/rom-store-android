package io.github.uposum.romstore.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkCheckTest {
    // The link a Switch post served in Nov 2026: its token ran out on 9 Aug 2026 (HTTP 410).
    private val switchLink = "https://drive.dlarchive.my.id/dl/file/2026%2F01%2F10%2Fhe55ixui-Mario_Kart_8_Deluxe.nsp" +
        "?token=eyJrIjoiMjAyNi8wMS8xMC9oZTU1aXh1aS1NYXJpb19LYXJ0XzhfRGVsdXhlLm5zcCIsImUiOjE3ODYyNzI2MTEsImYiOiIgTWFyaW8gS2FydCA4IERlbHV4ZS5uc3AifQ" +
        ".c4W_I7N2XE8vndr7xleu7vaoSmooyzUn1l8-Mr-Pp1k&filename=%5BROMsNS%5D+Mario+Kart+8+Deluxe.nsp"

    @Test fun readsTheExpiryInsideAToken() {
        assertEquals(1786272611L, LinkCheck.expiresAt(switchLink))
        assertTrue(LinkCheck.expired(switchLink, nowSeconds = 1_795_000_000L))
        assertFalse(LinkCheck.expired(switchLink, nowSeconds = 1_780_000_000L))
        assertTrue(LinkCheck.expiredMessage(switchLink).contains("9 Aug 2026"))
    }

    @Test fun readsJwtsAndPlainExpiryParameters() {
        // header.payload.signature with "exp"
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjE3MDAwMDAwMDB9.sig"
        assertEquals(1700000000L, LinkCheck.expiresAt("https://h.example/f.zip?token=$jwt"))
        assertEquals(1700000000L, LinkCheck.expiresAt("https://h.example/f.zip?Expires=1700000000&Signature=x"))
    }

    @Test fun linksWithoutAnExpiryNeverCountAsExpired() {
        assertNull(LinkCheck.expiresAt("https://direct-stor.example/_UPLOAD/2026/01/27/game.nsp"))
        assertNull(LinkCheck.expiresAt("https://h.example/f.zip?token=notbase64json&e=12"))
        assertFalse(LinkCheck.expired("https://h.example/f.zip?id=5"))
    }

    @Test fun statusMessagesLeaveTheUrlOut() {
        val text = LinkCheck.statusMessage(410, "drive.dlarchive.my.id")
        assertTrue("expired" in text)
        assertFalse("token" in text)
    }
}
