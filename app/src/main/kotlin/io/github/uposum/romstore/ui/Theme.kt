package io.github.uposum.romstore.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** One dark palette, the same as the Steam Deck store. */
object C {
    val bg = Color(0xFF0B0E13)
    val bar = Color(0xFF10141B)
    val surface = Color(0xFF171C24)
    val surface2 = Color(0xFF1F2530)
    val line = Color(0xFF262D39)
    val text = Color(0xFFF2F4F7)
    val text2 = Color(0xFF9AA4B2)
    val text3 = Color(0xFF6B7584)
    val accent = Color(0xFF3B8CFF)
    val accentDim = Color(0x333B8CFF)
    val good = Color(0xFF45C27A)
    val warn = Color(0xFFF2B63D)
    val bad = Color(0xFFF0585E)
    val star = Color(0xFFF5C451)
    val focus = Color.White
}

object T {
    val brand = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = C.text, letterSpacing = (-0.2).sp)
    val tab = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val h1 = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, color = C.text, lineHeight = 32.sp, letterSpacing = (-0.4).sp)
    val h2 = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold, color = C.text, letterSpacing = (-0.2).sp)
    val h3 = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.text)
    val body = TextStyle(fontSize = 14.sp, color = C.text, lineHeight = 21.sp)
    val body2 = TextStyle(fontSize = 13.sp, color = C.text2, lineHeight = 18.sp)
    val small = TextStyle(fontSize = 12.sp, color = C.text2)
    val label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = C.text3, letterSpacing = 1.sp)
    val chip = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = C.text, letterSpacing = 0.4.sp)
}

@Composable
fun RomStoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, onPrimary = Color.White, background = C.bg, onBackground = C.text,
            surface = C.surface, onSurface = C.text, surfaceVariant = C.surface2, onSurfaceVariant = C.text2,
            outline = C.line,
        ),
        content = content,
    )
}

/** A console's accent colour (by maker), for tiles and covers without art. */
fun systemTint(system: String): Color {
    val families = mapOf(
        Color(0xFFE5484D) to listOf("nes", "snes", "n64", "gc", "wii", "wiiu", "switch", "gb", "gbc", "gba", "nds", "nds-64", "3ds", "virtualboy", "pokemini"),
        Color(0xFF4C82FF) to listOf("psx", "ps2", "ps3", "psp", "psvita"),
        Color(0xFF2BB5E8) to listOf("genesis", "mastersystem", "gamegear", "sg1000", "sega32x", "segacd", "saturn", "dreamcast"),
        Color(0xFFF2913D) to listOf("atari2600", "atari5200", "atari7800", "atarist", "atarilynx", "jaguar", "atari800"),
        Color(0xFFE9C046) to listOf("pcengine", "pcenginecd", "pcfx"),
        Color(0xFFD7A94B) to listOf("neogeo", "neogeocd", "ngp", "ngpc"),
        Color(0xFFA877FF) to listOf("arcades", "mame", "fba"),
        Color(0xFF4FC98A) to listOf("dos", "pc", "mac", "amiga", "c64", "vic20", "zxspectrum", "amstradcpc", "msx", "x68000", "pc98", "scummvm", "ports", "easyrpg", "openbor"),
    )
    return families.entries.firstOrNull { system in it.value }?.key ?: Color(0xFF8A95A3)
}
