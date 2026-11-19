package io.github.uposum.romstore.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Unarchive
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uposum.romstore.core.model.LibraryGame
import io.github.uposum.romstore.core.model.Systems
import io.github.uposum.romstore.formatBytes

@Composable
fun LibraryTab(vm: AppViewModel) {
    val games by vm.installed.collectAsStateWithLifecycle()
    val loading by vm.libraryLoading.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var console by remember { mutableStateOf<String?>(null) }
    val pickLibrary = rememberFolderPicker { vm.setLibraryFolder(it) }
    val systems = remember(games) { games.map { it.system }.distinct().sortedBy { Systems.label(it) } }
    val shown = remember(games, console) { if (console == null) games else games.filter { it.system == console } }

    if (settings.libraryUri == null && settings.consoleFolders.isEmpty()) {
        EmptyState(
            Icons.Rounded.Folder, "No ROM folder yet",
            "Pick the folder your emulators read games from. ROM Store keeps one folder per console inside it.",
        ) { Btn("Choose folder", pickLibrary, kind = ButtonKind.Primary) }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", style = T.h1, fontSize = 24.sp)
            Spacer(Modifier.width(10.dp))
            Text("${games.size} game${if (games.size == 1) "" else "s"} · ${formatBytes(games.sumOf { it.size }).ifEmpty { "0B" }}", style = T.body2)
            Spacer(Modifier.weight(1f))
            if (loading) CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(vm.libraryDescription() ?: "", style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
        }
        if (systems.size > 1) {
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                item { FilterChip("All", console == null) { console = null } }
                items(systems) { s -> FilterChip(Systems.label(s), console == s) { console = s } }
            }
        }
        if (games.isEmpty() && !loading) {
            EmptyState(Icons.Rounded.Inventory2, "Your library is empty", "Games you download show up here, and so do the ones already in your console folders.")
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(116.dp),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(shown, key = { it.id }) { game ->
                Column {
                    Cover(
                        game.cover, game.system, game.name ?: game.title,
                        Modifier.fillMaxWidth().coverShadow().pressable(shape = RectangleShape) { vm.openLibraryGame(game) },
                        showChip = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(game.name ?: game.title, style = T.h3, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formatBytes(game.size), style = T.small, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Text(
        text,
        modifier = Modifier.pressable(shape = shape, onClick = onClick)
            .background(if (selected) C.accent else C.surface, shape).padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (selected) Color.White else C.text2, fontSize = 13.sp,
    )
}

/** An installed game: open it in an emulator, unpack it, look it up, delete it. */
@Composable
fun LibraryGamePage(vm: AppViewModel, game: LibraryGame, gamepad: Boolean) {
    val context = LocalContext.current
    var confirm by remember(game.id) { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val keep = remember { FocusRequester() }
    BackHandler { if (confirm) confirm = false else vm.openLibraryGame(null) }
    val keys = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard
    LaunchedEffect(game.id) { if (gamepad || keys) runCatching { first.requestFocus() } }
    LaunchedEffect(confirm) { if (confirm) runCatching { keep.requestFocus() } }

    Box(Modifier.fillMaxSize().background(C.bg).trapFocus()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight * 1.1f
            val ratio = coverRatio(game.cover, game.system)
            val posterWidth = if (wide) minOf(maxWidth * 0.3f, (maxHeight - 80.dp) * ratio).coerceAtLeast(140.dp) else 200.dp
            Row(Modifier.fillMaxSize().padding(start = 32.dp, end = 28.dp, top = 28.dp)) {
                if (wide) {
                    Cover(game.cover, game.system, game.name ?: game.title, Modifier.width(posterWidth).coverShadow(), big = true)
                    Spacer(Modifier.width(32.dp))
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    if (!wide) {
                        Cover(game.cover, game.system, game.name ?: game.title, Modifier.width(posterWidth).coverShadow(), big = true)
                        Spacer(Modifier.height(16.dp))
                    }
                    Text(Systems.name(game.system).uppercase(), color = C.accent, fontSize = 13.sp, letterSpacing = 1.4.sp, style = T.h3)
                    Spacer(Modifier.height(6.dp))
                    Text(game.name ?: game.title, style = T.h1, modifier = Modifier.padding(end = 60.dp))
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(formatBytes(game.size))
                        Pill("${game.files.size} file${if (game.files.size == 1) "" else "s"}")
                    }
                    Spacer(Modifier.height(22.dp))
                    if (confirm) {
                        Text("Delete ${game.name ?: game.title}? This removes its files from the console folder.", style = T.body)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Btn("Keep", { confirm = false }, Modifier.focusRequester(keep), kind = ButtonKind.Secondary, big = true)
                            Btn("Delete", { vm.uninstall(game) }, kind = ButtonKind.Danger, icon = Icons.Rounded.Delete, big = true)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (game.needsUnpack) {
                                Btn("Unpack", { vm.unpack(game) }, Modifier.focusRequester(first), kind = ButtonKind.Primary, icon = Icons.Rounded.Unarchive, big = true)
                            } else {
                                Btn("Open with…", {
                                    vm.openWith(game)?.let { runCatching { context.startActivity(it) }.onFailure { vm.messages.tryEmit("No app can open this file") } }
                                }, Modifier.focusRequester(first), kind = ButtonKind.Primary, icon = Icons.AutoMirrored.Rounded.OpenInNew, big = true)
                            }
                            Btn("Store page", { vm.storePage(game) }, icon = Icons.Rounded.Search, big = true)
                            Btn("Delete", { confirm = true }, icon = Icons.Rounded.Delete, big = true)
                        }
                    }
                    if (game.needsUnpack) {
                        Spacer(Modifier.height(10.dp))
                        Text("This console's emulators read disc images, not archives: unpack it before playing.", style = T.body2, color = C.warn)
                    }
                    Spacer(Modifier.height(28.dp))
                    Text("Files", style = T.h2)
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.background(C.surface, RoundedCornerShape(12.dp)).padding(vertical = 6.dp)) {
                        game.files.forEach { file ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(file.name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(12.dp))
                                Text(formatBytes(file.size), style = T.small)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(vm.folderDescription(game.system) ?: "", style = T.small)
                }
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
            IconBtn(Icons.Rounded.Close, "Close", { vm.openLibraryGame(null) })
        }
        if (gamepad) HintBar(listOf("B" to "Back", "A" to "Select"), Modifier.align(Alignment.BottomCenter))
    }
}
