package io.github.uposum.romstore.core.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SwitchTitlesTest {
    /** A minimal NSP (PFS0) holding empty files with these names. */
    private fun pfs0(names: List<String>): ByteArray {
        val table = names.joinToString("") { "$it\u0000" }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("PFS0".toByteArray())
        out.write(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(names.size).putInt(table.size).putInt(0).array())
        var offset = 0
        for (name in names) {
            out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putLong(0).putLong(0).putInt(offset).putInt(0).array())
            offset += name.length + 1
        }
        out.write(table)
        return out.toByteArray()
    }

    @Test
    fun switchTitleIds() {
        assertEquals("0100F2C0115B6000", switchTitleId("/roms/switch/Zelda [0100F2C0115B6000][v0].nsp"))
        assertEquals(
            "an update's ID is the base game's folder",
            "0100F2C0115B6000", switchTitleId("Zelda Update [0100f2c0115b6800].nsp"),
        )
        val ticket = "0100f2c0115b60000000000000000004.tik"
        val nsp = pfs0(listOf("abc.nca", ticket, "0100f2c0115b60000000000000000004.cert"))
        assertEquals("from the ticket inside the NSP", "0100F2C0115B6000", switchTitleId("Zelda.nsp", { ByteArrayInputStream(nsp) }))
        assertEquals("as the ticket spells it", "0100f2c0115b6000", nspTicketTitle(ByteArrayInputStream(nsp)))
        assertNull("only NSP/NSZ headers are read", switchTitleId("Zelda.xci", { ByteArrayInputStream(nsp) }))
        assertNull(switchTitleId("Zelda.xci", { ByteArrayInputStream("HEAD".repeat(10).toByteArray()) }))
        assertNull("not a PFS0", switchTitleId("Zelda.nsp", { ByteArrayInputStream("HEAD".repeat(10).toByteArray()) }))
        assertNull("cut short", switchTitleId("Zelda.nsp", { ByteArrayInputStream(nsp.copyOf(40)) }))
        assertNull("no header to read", switchTitleId("Zelda.nsp", { null }))
        assertNull(switchTitleId("Zelda.nsp", { throw java.io.IOException("gone") }))
    }

    @Test
    fun cleanNames() {
        assertEquals("Super Mario Bros. Wonder", cleanName("+Super+Mario+Bros.+Wonder"))
        assertEquals("The Legend of Zelda - The Wind Waker", cleanName("Legend of Zelda, The - The Wind Waker (USA)"))
        assertEquals("Mario Kart 8 Deluxe", cleanName("Mario_Kart_8_Deluxe_[0100152000022000][v0]"))
        assertEquals("a real +", "Mario + Rabbids Kingdom Battle", cleanName("Mario + Rabbids Kingdom Battle"))
        assertEquals("Pokémon Legends Arceus", cleanName("Pok%C3%A9mon%20Legends%20Arceus%20NSP"))
        assertEquals("100% Orange Juice", cleanName("100% Orange Juice"))
    }

    @Test
    fun namesFromTheShippedList() {
        val titles = SwitchTitles()
        assertTrue(titles.size > 10000)
        assertEquals("from the shipped list", "010015100B514000", titles.find("+Super+Mario+Bros.+Wonder"))
        assertEquals("Super Mario Bros. Wonder", titles.name("010015100B514000"))
        assertEquals("Super Mario Bros. Wonder", titles.name("010015100b514000"))
        assertNull(titles.find("A Game Nobody Made"))
        assertNull(titles.name(null))

        val withExtra = SwitchTitles(extraNames = { mapOf("0100abcdef012000" to "A Game Nobody Made") })
        assertEquals("0100ABCDEF012000", withExtra.find("A_Game_Nobody_Made"))
        assertEquals("A Game Nobody Made", withExtra.name("0100ABCDEF012000"))

        assertEquals(
            "an XCI without the ID in its name",
            "010015100B514000", switchTitleId("+Super+Mario+Bros.+Wonder.xci", lookup = titles::find),
        )
    }
}
