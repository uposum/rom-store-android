package io.github.uposum.romstore.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCleanTest {
    private fun check(raw: String, clean: String) = assertEquals(raw, clean, TitleClean.display(raw))

    @Test fun uploadsAndSitesBecomeGameNames() {
        check("Ratchet & Clank: A Crack in Time [BCES-00511] (Review Code)", "Ratchet & Clank: A Crack in Time")
        check("SCES 532.85.Ratchet - Gladiator", "Ratchet - Gladiator")
        check("Ratchet Clank - Size Matters (v1.01)", "Ratchet Clank - Size Matters")
        check("Mario_Kart_8_Deluxe_[0100152000022000][v0].nsp", "Mario Kart 8 Deluxe")
        check("Gran Turismo 4 USA SCUS 97328 ( 1.01) PS 2 .iso", "Gran Turismo 4")
        check("Gran Turismo 4 (Europe, Australia) (En,Fr,De,Es,It)", "Gran Turismo 4")
        check("Zelda Tears Of The Kingdom ( Unity Troll) (nsw 2u.com)", "Zelda Tears Of The Kingdom")
        check("Ratchet & Clank A Crack in Time PS3 EUR Review.7z", "Ratchet & Clank A Crack in Time")
        check("Mario.Kart.8.Deluxe.DLC.Booster.Course.Pass", "Mario Kart 8 Deluxe DLC Booster Course Pass")
        check("Pokemon Emerald Legacy (v1.1.4)", "Pokemon Emerald Legacy")
        check("Ratchet _ Clank - Up Your Arsenal (USA)", "Ratchet & Clank - Up Your Arsenal")
    }

    @Test fun keepsWhatTellsAGameFromItsAddOns() {
        check("Persona 5 Royal (Update)", "Persona 5 Royal (Update)")
        check("Pokémon Violet + DLC", "Pokémon Violet + DLC")
    }

    @Test fun leavesCleanNamesAlone() {
        check("The Legend of Zelda: Breath of the Wild", "The Legend of Zelda: Breath of the Wild")
        check("Final Fantasy VII", "Final Fantasy VII")
        check("Tetris DX", "Tetris DX")
        check("Grand Theft Auto: Vice City Stories", "Grand Theft Auto: Vice City Stories")
        check("Metroid Prime 4 Beyond", "Metroid Prime 4 Beyond")
        check("2048", "2048")
        check("F-Zero", "F-Zero")
        check("Wii Sports", "Wii Sports")
        check("Switch Force", "Switch Force")
    }
}

class FileNamesTest {
    @Test fun switchFilesKeepTheirIdsAndLoseTheJunk() {
        val upd = Content.classifyFile("Mario_Kart_8_Deluxe_[0100152000022800][v1507328]_[taodung.com].rar", "Update 4.0.1 (v1507328) · NSP · USA · 1fichier", "Mario Kart 8 Deluxe")
        val base = FileNames.base("Mario Kart 8 Deluxe", upd, "USA")
        assertEquals("Mario Kart 8 Deluxe Update 4.0.1 (USA)", base)
        assertEquals("Mario Kart 8 Deluxe Update 4.0.1 (USA) [0100152000022800][v1507328].rar",
            FileNames.rename("Mario_Kart_8_Deluxe_[0100152000022800][v1507328]_[taodung.com].rar", base, "switch"))
        val game = Content.classifyFile("Mario_Kart_8_Deluxe_[0100152000022000][v0].nsp.rar", "Base game · NSP · USA · 1fichier", "Mario Kart 8 Deluxe")
        assertEquals("Mario Kart 8 Deluxe [0100152000022000][v0].nsp.rar",
            FileNames.rename("Mario_Kart_8_Deluxe_[0100152000022000][v0].nsp.rar", FileNames.base("Mario Kart 8 Deluxe", game, null), "switch"))
    }

    @Test fun otherConsolesGetNameAndRegion() {
        val info = Content.classify("Ratchet & Clank A Crack in Time PS3 EUR Review.7z", "Ratchet & Clank: A Crack in Time")
        assertEquals("Ratchet & Clank - A Crack in Time (Europe).7z",
            FileNames.rename("Ratchet & Clank A Crack in Time PS3 EUR Review.7z", FileNames.base("Ratchet & Clank: A Crack in Time", info, "Europe"), "ps3"))
    }

    @Test fun discSetsKeepTheirNames() {
        val info = Content.classify("Game (USA) (Track 1).bin", "Game")
        assertEquals("Game (USA) (Track 1).bin", FileNames.rename("Game (USA) (Track 1).bin", FileNames.base("Game", info, "USA"), "psx"))
        assertEquals("Game (USA).cue", FileNames.rename("Game (USA).cue", "Game", "psx"))
    }
}
