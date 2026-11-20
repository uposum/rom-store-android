package io.github.uposum.romstore.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uposum.romstore.BuildConfig
import io.github.uposum.romstore.core.model.Systems
import io.github.uposum.romstore.core.store.Regions
import io.github.uposum.romstore.core.store.SourceInfo
import io.github.uposum.romstore.formatBytes
import io.github.uposum.romstore.storage.SafLibrary
import java.text.DateFormat
import java.util.Date

enum class SettingsSection(val label: String, val icon: ImageVector) {
    Library("ROM folders", Icons.Rounded.Folder),
    Sources("Sources", Icons.Rounded.Language),
    Downloads("Downloads", Icons.Rounded.Download),
    Artwork("Artwork", Icons.Rounded.Image),
    Display("Display", Icons.Rounded.Tune),
    Storage("Storage", Icons.Rounded.Storage),
    About("About", Icons.Rounded.Info),
}

/** Settings: a section list and its pane. Every change saves at once. */
@Composable
fun SettingsTab(vm: AppViewModel, section: SettingsSection, onSection: (SettingsSection) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 640.dp
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(if (wide) 230.dp else 64.dp).fillMaxHeight().padding(start = 16.dp, top = 16.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SettingsSection.entries.forEach { s ->
                    val selected = s == section
                    val shape = RoundedCornerShape(10.dp)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .pressable(shape = shape, onFocus = { onSection(s) }) { onSection(s) }
                            .background(if (selected) C.surface2 else Color.Transparent, shape)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(s.icon, s.label, tint = if (selected) C.accent else C.text2, modifier = Modifier.size(20.dp))
                        if (wide) {
                            Spacer(Modifier.width(12.dp))
                            Text(s.label, style = T.h3, color = if (selected) C.text else C.text2)
                        }
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(start = 16.dp, end = 28.dp, top = 18.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (section) {
                    SettingsSection.Library -> libraryPane(vm)
                    SettingsSection.Sources -> sourcesPane(vm)
                    SettingsSection.Downloads -> downloadsPane(vm)
                    SettingsSection.Artwork -> artworkPane(vm)
                    SettingsSection.Display -> displayPane(vm)
                    SettingsSection.Storage -> storagePane(vm)
                    SettingsSection.About -> aboutPane()
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.libraryPane(vm: AppViewModel) {
    item {
        val settings by vm.settings.collectAsStateWithLifecycle()
        val pick = rememberFolderPicker { vm.setLibraryFolder(it) }
        PaneTitle("ROM folders", "Each console's games go into its own folder inside the ROM folder (gba, snes, psx…), the layout ES-DE, Daijishō and most emulators read. Pick a different folder for a console below if you keep it elsewhere.")
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ROM folder", style = T.h3)
                    Text(SafLibrary.describe(settings.libraryUri) ?: "Not chosen yet", style = T.body2)
                }
                Btn(if (settings.libraryUri == null) "Choose" else "Change", pick, kind = if (settings.libraryUri == null) ButtonKind.Primary else ButtonKind.Secondary)
            }
        }
    }
    item { Spacer(Modifier.height(8.dp)); Text("Per console", style = T.h2) }
    item { ConsoleFolders(vm) }
}

@Composable
private fun ConsoleFolders(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    val pick = rememberFolderPicker { uri -> pending?.let { vm.setConsoleFolder(it, uri) } }
    val keys = (Systems.POPULAR + settings.consoleFolders.keys).distinct().let { base ->
        if (showAll) (base + Systems.known().map { it.key }).distinct() else base
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { key ->
            val custom = settings.consoleFolders[key]
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Systems.label(key), style = T.h3)
                        Text(vm.folderDescription(key) ?: "Inside the ROM folder: ${Systems.defaultFolder(key)}", style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (custom != null) C.accent else C.text2)
                    }
                    if (custom != null) {
                        Btn("Reset", { vm.setConsoleFolder(key, null) }, kind = ButtonKind.Ghost)
                        Spacer(Modifier.width(8.dp))
                    }
                    Btn("Choose", { pending = key; pick() })
                }
            }
        }
        if (!showAll) Btn("Show every console", { showAll = true }, kind = ButtonKind.Ghost)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sourcesPane(vm: AppViewModel) {
    item {
        PaneTitle(
            "Sources",
            "ROM Store reads these catalogue sites but ships none of their addresses: enter the address of each site you use. A source without an address is never asked. Sites that ask for a login, a captcha or a browser check are reported, never worked around.",
        )
    }
    item { SpeedTestCard(vm) }
    item {
        val sources by vm.sources.collectAsStateWithLifecycle()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { sources.forEach { SourceCard(vm, it) } }
    }
}

@Composable
private fun SpeedTestCard(vm: AppViewModel) {
    val status by vm.speedTest.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Speed test", style = T.h3)
                Text(
                    if (status.running) "Testing ${status.current?.let { io.github.uposum.romstore.core.sources.SourceDefs.label(it) } ?: ""} (${status.done.size + 1} of ${status.total})…"
                    else "Reads a few seconds of one real file per source, so the game page offers the fastest first.",
                    style = T.body2,
                )
            }
            if (status.running) CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            else Btn("Test all now", vm::startSpeedTest, enabled = settings.configuredSources.isNotEmpty())
        }
        Spacer(Modifier.height(10.dp))
        ToggleRow("Test once a week by itself", null, settings.speedTestAuto) { on -> vm.updateSettings { it.copy(speedTestAuto = on) } }
    }
}

@Composable
private fun SourceCard(vm: AppViewModel, source: SourceInfo) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(source.label, style = T.h3)
                    Spacer(Modifier.width(8.dp))
                    val (color, text) = when {
                        !source.configured -> C.text3 to "not set up"
                        !source.enabled -> C.text3 to "off"
                        source.restingUntil != null -> C.warn to "resting after failures"
                        source.health.failures > 0 -> C.warn to "failing"
                        source.health.okAt != null -> C.good to "working"
                        else -> C.text2 to "not used yet"
                    }
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(text, style = T.small, color = color)
                }
                val speed = source.speed?.speed
                val facts = listOfNotNull(
                    speed?.let { "${formatBytes(it.toLong())}/s" },
                    source.speed?.delay?.takeIf { speed != null && it > 0.5 }?.let { String.format("%.0f s to start", it) },
                    source.speed?.testError?.let { "last test: $it" },
                    source.health.error?.takeIf { source.health.failures > 0 }?.let { "last error: $it" },
                )
                if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), style = T.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Toggle(source.enabled && source.configured, enabled = source.configured) { vm.setSourceEnabled(source.id, it) }
        }
        Spacer(Modifier.height(10.dp))
        EditableField(source.address, "Address, e.g. example.com", KeyboardType.Uri) { vm.setSourceAddress(source.id, it) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.downloadsPane(vm: AppViewModel) {
    item { PaneTitle("Downloads", null) }
    item {
        val s by vm.settings.collectAsStateWithLifecycle()
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("At the same time", style = T.h3)
                    Text("How many downloads run at once.", style = T.body2)
                }
                IconBtn(Icons.Rounded.Remove, "Fewer", { vm.updateSettings { it.copy(maxConcurrent = (it.maxConcurrent - 1).coerceAtLeast(1)) } }, size = 40.dp)
                Text("${s.maxConcurrent}", style = T.h2, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconBtn(Icons.Rounded.Add, "More", { vm.updateSettings { it.copy(maxConcurrent = (it.maxConcurrent + 1).coerceAtMost(6)) } }, size = 40.dp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Card {
            ToggleRow("Updates and DLC in their own folders", "Saved to updates/ and dlc/ inside the console folder, so game lists do not show them as games. Emulators install them from there.", s.addonFolders) { on -> vm.updateSettings { it.copy(addonFolders = on) } }
            Divider()
            ToggleRow("Unpack disc games", "PlayStation, GameCube, Switch and other disc consoles' emulators cannot read a .zip or .7z: those are unpacked, and the archive deleted.", s.autoUnpackDiscs) { on -> vm.updateSettings { it.copy(autoUnpackDiscs = on) } }
            Divider()
            ToggleRow("Unpack every archive", "Also unpack cartridge games (most emulators read their .zip directly).", s.extractArchives) { on -> vm.updateSettings { it.copy(extractArchives = on) } }
            if (s.extractArchives) {
                Divider()
                ToggleRow("Keep the archive", "Keep the .zip next to the unpacked files.", s.keepArchive) { on -> vm.updateSettings { it.copy(keepArchive = on) } }
            }
        }
        Spacer(Modifier.height(10.dp))
        Card {
            Text("Preferred region", style = T.h3)
            Text("The game page offers this region's file first.", style = T.body2)
            Spacer(Modifier.height(10.dp))
            Selector("None", listOf("" to "No preference") + Regions.ALL.map { it to it }, s.preferredRegion, onSelect = { r -> vm.updateSettings { it.copy(preferredRegion = r) } })
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.artworkPane(vm: AppViewModel) {
    item {
        PaneTitle("Artwork", "Covers come from libretro-thumbnails' box scans first (real proportions, right platform), GameTDB for Nintendo's newer consoles, then the site, SteamGridDB, and IGDB.")
        val s by vm.settings.collectAsStateWithLifecycle()
        Card {
            ToggleRow(
                "Uniform covers",
                "SteamGridDB's covers first, all in one 2:3 shape. Off: real box scans first, in each console's own box shape.",
                s.coverStyle == "uniform",
            ) { on -> vm.updateSettings { it.copy(coverStyle = if (on) "uniform" else "box") } }
            ToggleRow("Box scans from libretro-thumbnails", null, s.libretroBoxart) { on -> vm.updateSettings { it.copy(libretroBoxart = on) } }
            Divider()
            ToggleRow("Small covers through wsrv.nl", "Resizes box scans for the grid (about 25 kB each instead of a full scan).", s.boxartResize) { on -> vm.updateSettings { it.copy(boxartResize = on) } }
            Divider()
            ToggleRow("IGDB ratings, screenshots and trailers", null, s.igdbEnabled) { on -> vm.updateSettings { it.copy(igdbEnabled = on) } }
            Divider()
            ToggleRow("Prefer IGDB covers", "IGDB crops every cover to 3:4, so by default it only fills in games without one.", s.igdbReplaceCovers) { on -> vm.updateSettings { it.copy(igdbReplaceCovers = on) } }
        }
        Spacer(Modifier.height(10.dp))
        Card {
            Text("SteamGridDB API key", style = T.h3)
            Text("Optional: your own key (steamgriddb.com → Preferences → API). Without one the built-in key is used.", style = T.body2)
            Spacer(Modifier.height(10.dp))
            EditableField(s.steamgriddbApiKey, "Your key (optional)", KeyboardType.Ascii) { key -> vm.updateSettings { it.copy(steamgriddbApiKey = key.trim()) } }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.displayPane(vm: AppViewModel) {
    item {
        PaneTitle("Display", null)
        val s by vm.settings.collectAsStateWithLifecycle()
        Card {
            ToggleRow("Titles under covers", "The grid shows covers only; the focused game is named in the strip below it.", s.showTitles) { on -> vm.updateSettings { it.copy(showTitles = on) } }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.storagePane(vm: AppViewModel) {
    item {
        PaneTitle("Storage", "Searches and game pages are kept for a few days, so opening them again is instant and works offline.")
        val s by vm.settings.collectAsStateWithLifecycle()
        var stats by remember { mutableStateOf(vm.store.cacheSize()) }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cached pages", style = T.h3)
                    Text("${stats.first} pages · ${formatBytes(stats.second).ifEmpty { "0B" }} · kept ${s.searchCacheHours.toInt()} hours", style = T.body2)
                }
                Btn("Clear", { vm.clearCache(); stats = vm.store.cacheSize() })
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.aboutPane() {
    item {
        val context = LocalContext.current
        PaneTitle("ROM Store ${BuildConfig.VERSION_NAME}", "A store-style browser for the catalogue sites you choose. It contains no games, no BIOS files and no site addresses: it searches the sites you enter and saves the file you pick into your own folder. What you download, and whether you may, is your responsibility.")
        Card {
            Text("Controls", style = T.h3)
            Spacer(Modifier.height(6.dp))
            listOf(
                "L1 / R1" to "Previous / next tab (on a game page: previous / next game)",
                "X" to "Search (on a game page: download)",
                "Y" to "Favourite the focused game",
                "L2 / R2" to "Scroll the results a page",
                "Start" to "Downloads",
                "B" to "Back",
            ).forEach { (button, action) ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(button.substringBefore(" "))
                    Spacer(Modifier.width(10.dp))
                    Text(action, style = T.body2)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Btn("Source code on GitHub", {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/uposum/rom-store-android"))) }
        }, kind = ButtonKind.Ghost)
    }
}

// ----------------------------------------------------------------- parts

@Composable
private fun PaneTitle(title: String, text: String?) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, style = T.h1, fontSize = 22.sp)
        if (text != null) {
            Spacer(Modifier.height(6.dp))
            Text(text, style = T.body2, modifier = Modifier.widthIn(max = 640.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(C.surface, RoundedCornerShape(14.dp)).padding(16.dp)) { content() }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(C.line))
}

@Composable
private fun ToggleRow(title: String, text: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).pressable(shape = shape, gap = 6.dp) { onChange(!on) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.h3)
            if (text != null) Text(text, style = T.body2)
        }
        Spacer(Modifier.width(12.dp))
        SwitchLook(on)
    }
}

@Composable
private fun Toggle(on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Box(Modifier.pressable(shape = CircleShape, enabled = enabled) { onChange(!on) }) { SwitchLook(on, enabled) }
}

@Composable
private fun SwitchLook(on: Boolean, enabled: Boolean = true) {
    Box(
        Modifier.width(46.dp).height(26.dp).background(if (on) C.accent else C.surface2, CircleShape).padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = if (on) 20.dp else 0.dp).size(20.dp).background(if (enabled) Color.White else C.text3, CircleShape))
    }
}

/** A text value: a button until pressed (so the d-pad can pass over it), then a field. */
@Composable
private fun EditableField(value: String, placeholder: String, type: KeyboardType, onCommit: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(value) }
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(10.dp)
    fun commit() {
        editing = false
        hadFocus = false
        keyboard?.hide()
        if (text != value) onCommit(text)
    }
    LaunchedEffect(editing) { if (editing) { runCatching { requester.requestFocus() }; keyboard?.show() } }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 42.dp)
            .then(if (!editing) Modifier.pressable(shape = shape) { editing = true } else Modifier)
            .background(C.bg, shape).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (editing) {
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = TextStyle(color = C.text, fontSize = 15.sp),
                cursorBrush = SolidColor(C.accent),
                keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged {
                    // The field starts unfocused; only leaving it after it had focus ends the edit.
                    if (it.isFocused) hadFocus = true else if (hadFocus && editing) commit()
                },
            )
        } else {
            Text(value.ifEmpty { placeholder }, color = if (value.isEmpty()) C.text3 else C.text, fontSize = 15.sp, fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Suppress("unused")
private fun formatTime(ms: Long?): String = ms?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: ""
