package io.github.uposum.romstore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** File and post names as the sites publish them (seen live, Nov 2026). */
class ContentTest {
    private fun kind(file: String, title: String? = null) = Content.classify(file, title).kind

    @Test fun switchTitleIdsDecideTheKind() {
        assertEquals(ContentKind.BASE, kind("[ROMsNS] Kirby and the Forgotten Land [01004D300C5AE000][v0].nsp"))
        val update = Content.classify("Zelda TOTK [0100F2C0115B6800][v196608].nsp")
        assertEquals(ContentKind.UPDATE, update.kind)
        assertEquals("#3", update.version)
        assertEquals(ContentKind.DLC, kind("Zelda TOTK DLC [0100F2C0115B7001][v0].nsp"))
        assertEquals("0100F2C0115B6000", Content.baseTitleId("0100F2C0115B7001"))
        assertEquals("0100F2C0115B6000", Content.baseTitleId("0100F2C0115B6800"))
        assertEquals("0100F2C0115B6000", Content.baseTitleId("0100F2C0115B6000"))
    }

    @Test fun wordsInFileNames() {
        assertEquals(ContentKind.BASE, kind("The-Legend-of-Zelda-Links-Awakening-NSP-Base-Game.rar"))
        val bundle = Content.classify("Animal-Crossing-New-Horizons-Switch-NSPNSZXCI-Update-1.6.0C-DLCs.rar")
        assertEquals(ContentKind.BUNDLE, bundle.kind)
        assertTrue(bundle.includesUpdate && bundle.includesDlc)
        assertEquals("1.6.0C", bundle.version)
        assertEquals(ContentKind.UPDATE, kind("Pokemon Scarlet Update v3.0.1.nsp"))
        assertEquals(ContentKind.DLC, kind("Pokemon Scarlet The Indigo Disk DLC.nsp"))
        assertEquals(ContentKind.BUNDLE, kind("Assassin's_Creed_Switch_NSP_Base_Games_+_Update.rar"))
        val built = Content.classify("[ROMsNS] Super Mario Odyssey v1.3.0_.xci")
        assertEquals(ContentKind.BASE, built.kind)
        assertTrue(built.includesUpdate)
        assertEquals("Includes update 1.3.0", built.label)
        assertFalse(Content.classify("Super Mario World (V1.0) (E).zip").includesUpdate)
        assertEquals(ContentKind.BASE, kind("Tetris DX (JU) [C][!].zip"))
    }

    @Test fun thePostTitleCanSayItIsABundle() {
        assertEquals(ContentKind.BUNDLE, kind("[ROMsNS] My Hero Ones Justice 2 [US].nsp", "My Hero One’s Justice 2 Plus DLC With Update"))
        // A game page mentioning updates elsewhere does not make the base file a bundle.
        assertEquals(ContentKind.BASE, kind("[ROMsNS] Pokemon Brilliant Diamond [0100000011D90000][v0].nsp", "Pokémon Brilliant Diamond Plus Updated"))
    }

    @Test fun addOnsBelongToTheirGame() {
        val game = Content.baseKey("My Hero One’s Justice 2")
        assertEquals(game, Content.baseKey("My Hero One’s Justice 2 Plus DLC With Update"))
        assertEquals(Content.baseKey("Animal Crossing New Horizons"), Content.baseKey("Animal Crossing: New Horizons Update 1.6.0"))
        assertEquals(Content.baseKey("Pokemon Scarlet"), Content.baseKey("Pokémon Scarlet + DLC"))
        assertEquals("Persona 5 Royal", Content.baseTitle("Persona 5 Royal (Update)"))
        assertEquals("Mortal Kombat 1 Premium Edition", Content.baseTitle("Mortal Kombat 1 Premium Edition"))
    }
}
