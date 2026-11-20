package io.github.uposum.romstore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uposum.romstore.core.model.LibraryGame
import io.github.uposum.romstore.core.model.MergedRom
import io.github.uposum.romstore.core.model.Systems
import io.github.uposum.romstore.core.sources.SourceDefs
import io.github.uposum.romstore.core.store.Regions
import io.github.uposum.romstore.core.store.ShelfItem
import io.github.uposum.romstore.formatBytes
import kotlinx.coroutines.launch

@Composable
fun StoreTab(vm: AppViewModel, gamepad: Boolean, onSetup: () -> Unit) {
    val results by vm.results.collectAsStateWithLifecycle()
    val r = results
    if (r == null) HomeView(vm, onSetup) else ResultsView(vm, r, gamepad)
}

// ------------------------------------------------------------------ home

@Composable
private fun HomeView(vm: AppViewModel, onSetup: () -> Unit) {
    val shelves by vm.shelves.collectAsStateWithLifecycle()
    val consoles by vm.consoles.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val pickLibrary = rememberFolderPicker(vm.messages::tryEmit) { vm.setLibraryFolder(it) }
    val noSources = settings.configuredSources.isEmpty()

    val dense = LocalDense.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = if (dense) 14.dp else 24.dp, end = if (dense) 14.dp else 24.dp, top = if (dense) 8.dp else 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (dense) 14.dp else 28.dp),
    ) {
        if (noSources || settings.libraryUri == null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                    if (noSources) SetupCard(
                        Icons.Rounded.Language, "Add a source to get started",
                        "ROM Store ships no site addresses. Enter the catalogue sites you use in Settings → Sources.",
                        "Set up sources", Modifier.weight(1f), onSetup,
                    )
                    if (settings.libraryUri == null) SetupCard(
                        Icons.Rounded.Folder, "Choose your ROM folder",
                        "Downloads go into a folder per console inside it (gba, snes, psx…), the layout emulator frontends expect.",
                        "Choose folder", Modifier.weight(1f), pickLibrary,
                    )
                }
            }
        }
        if (shelves.recent.isNotEmpty()) item { Shelf("Recently viewed", shelves.recent, vm) }
        if (shelves.favorites.isNotEmpty()) item { Shelf("Favourites", shelves.favorites, vm) }
        if (installed.isNotEmpty()) item { LibraryShelf(installed, vm) }
        if (consoles.isNotEmpty()) {
            item { SectionTitle("Consoles", "${consoles.size}") }
            item { ConsoleGrid(consoles) { vm.browse(it) } }
        }
    }
}

@Composable
private fun SetupCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, action: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.background(C.surface, RoundedCornerShape(16.dp)).padding(20.dp)) {
        Icon(icon, null, tint = C.accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = T.h2)
        Spacer(Modifier.height(6.dp))
        Text(text, style = T.body2)
        Spacer(Modifier.height(16.dp))
        Btn(action, onClick, kind = ButtonKind.Primary)
    }
}

private val SHELF_HEIGHT: Int @Composable get() = if (LocalDense.current) 118 else 168

@Composable
private fun Shelf(title: String, items: List<ShelfItem>, vm: AppViewModel) {
    Column {
        SectionTitle(title, "${items.size}")
        // Shifted left by the ring's width and padded back, so the focus ring is not clipped.
        LazyRow(Modifier.offset(x = (-8).dp), horizontalArrangement = Arrangement.spacedBy(if (LocalDense.current) 10.dp else 16.dp), contentPadding = PaddingValues(start = 8.dp, end = 24.dp, top = 6.dp, bottom = 6.dp)) {
            items(items, key = { it.key }) { item ->
                val ratio = coverRatio(item.cover, item.system)
                Column(Modifier.width((SHELF_HEIGHT * ratio).dp.coerceAtLeast(if (LocalDense.current) 72.dp else 96.dp))) {
                    Cover(
                        item.cover, item.system, item.title,
                        Modifier.height(SHELF_HEIGHT.dp).coverShadow()
                            .pressable(shape = RectangleShape, onY = { vm.toggleFavorite(item.title, item.system, item.cover) }) { vm.openShelfItem(item) },
                        ratio = ratio,
                    )
                    Spacer(Modifier.height(if (LocalDense.current) 4.dp else 8.dp))
                    Text(item.title, style = T.h3, fontSize = if (LocalDense.current) 11.sp else 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = if (LocalDense.current) 13.sp else 16.sp)
                    Text(Systems.label(item.system), style = T.small, fontSize = if (LocalDense.current) 10.sp else 12.sp)
                }
            }
        }
    }
}

@Composable
private fun LibraryShelf(games: List<LibraryGame>, vm: AppViewModel) {
    Column {
        SectionTitle("In your library", "${games.size} game${if (games.size == 1) "" else "s"} · ${formatBytes(games.sumOf { it.size })}")
        // Shifted left by the ring's width and padded back, so the focus ring is not clipped.
        LazyRow(Modifier.offset(x = (-8).dp), horizontalArrangement = Arrangement.spacedBy(if (LocalDense.current) 10.dp else 16.dp), contentPadding = PaddingValues(start = 8.dp, end = 24.dp, top = 6.dp, bottom = 6.dp)) {
            items(games.take(30), key = { it.id }) { game ->
                val ratio = coverRatio(game.cover, game.system)
                Column(Modifier.width((SHELF_HEIGHT * ratio).dp.coerceAtLeast(if (LocalDense.current) 72.dp else 96.dp))) {
                    Cover(
                        game.cover, game.system, game.name ?: game.title,
                        Modifier.height(SHELF_HEIGHT.dp).coverShadow().pressable(shape = RectangleShape) { vm.openLibraryGame(game) },
                        ratio = ratio,
                    )
                    Spacer(Modifier.height(if (LocalDense.current) 4.dp else 8.dp))
                    Text(game.name ?: game.title, style = T.h3, fontSize = if (LocalDense.current) 11.sp else 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = if (LocalDense.current) 13.sp else 16.sp)
                    Text(Systems.label(game.system), style = T.small, fontSize = if (LocalDense.current) 10.sp else 12.sp)
                }
            }
        }
    }
}

/** Every console as a tile: the name on the maker's colour. */
@Composable
private fun ConsoleGrid(consoles: List<String>, onOpen: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = maxOf(2, (maxWidth / 190.dp).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (row in consoles.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (key in row) ConsoleTile(key, Modifier.weight(1f)) { onOpen(key) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ConsoleTile(key: String, modifier: Modifier, onClick: () -> Unit) {
    val tint = systemTint(key)
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.heightIn(min = if (LocalDense.current) 46.dp else 64.dp).pressable(shape = shape, onClick = onClick).background(C.surface, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(5.dp).height(if (LocalDense.current) 46.dp else 64.dp).background(tint, RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)))
        Column(Modifier.padding(horizontal = 14.dp, vertical = if (LocalDense.current) 6.dp else 10.dp)) {
            Text(Systems.label(key), style = T.h3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val full = Systems.name(key)
            if (full != Systems.label(key)) Text(full, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// --------------------------------------------------------------- results

private val SORTS = listOf("relevance" to "Best match", "title" to "Title", "downloads" to "Most downloaded", "rating" to "Top rated", "year" to "Newest", "size" to "Largest")

@Composable
private fun ResultsView(vm: AppViewModel, r: Results, gamepad: Boolean) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val consoles by vm.consoles.collectAsStateWithLifecycle()
    val shown = remember(r) { r.shown }
    val dense = LocalDense.current
    // The focused cover is named in the bar at the bottom of the screen.
    DisposableEffect(r.query, r.system) {
        vm.focused.value = null
        onDispose { vm.focused.value = null }
    }
    // A new search or console starts at the top, so the first card is there to take focus.
    val grid = androidx.compose.runtime.saveable.rememberSaveable(r.query, r.system, r.browse, saver = androidx.compose.foundation.lazy.grid.LazyGridState.Saver) {
        androidx.compose.foundation.lazy.grid.LazyGridState()
    }
    val scope = rememberCoroutineScope()
    val firstCard = remember { androidx.compose.ui.focus.FocusRequester() }
    val keys = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard
    // With the pad, focus goes to the first game once the results are in.
    LaunchedEffect(r.query, r.system, r.loading) {
        if (keys && !r.loading && shown.isNotEmpty()) {
            kotlinx.coroutines.delay(50)
            runCatching { firstCard.requestFocus() }
        }
    }

    // Load the next page when the end comes into view (the count as it is now, not when this started).
    val count by androidx.compose.runtime.rememberUpdatedState(shown.size)
    LaunchedEffect(grid, r.query) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= count - 8) vm.loadMore() }
    }
    OnPad { button ->
        if (vm.browser.value != null || vm.game.value != null) return@OnPad true
        when (button) {
            PadButton.R2 -> scope.launch { grid.animateScrollToItem((grid.firstVisibleItemIndex + 24).coerceAtMost(maxOf(0, shown.size - 1))) }
            PadButton.L2 -> scope.launch { grid.animateScrollToItem((grid.firstVisibleItemIndex - 24).coerceAtLeast(0)) }
            else -> {}
        }
        true
    }

    Column(Modifier.fillMaxSize()) {
        // Title and filters
        val side = if (dense) 14.dp else 24.dp
        Row(
            Modifier.fillMaxWidth().padding(start = side, end = side, top = if (dense) 5.dp else 14.dp, bottom = if (dense) 5.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (r.browse) Systems.label(r.system ?: "") else "“${r.query}”",
                style = T.h1, fontSize = if (dense) 17.sp else 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(10.dp))
            if (!r.loading) Text("${shown.size}${if (r.hasMore && shown.isNotEmpty()) "+" else ""} games", style = T.body2, fontSize = if (dense) 12.sp else 13.sp)
            if (!r.loading && r.errors.isNotEmpty() && shown.isNotEmpty()) {
                // Games were found: a failing source is a note, not a row of its own.
                Spacer(Modifier.width(10.dp))
                val shape = RoundedCornerShape(8.dp)
                Row(
                    // Says why, and asks the failed sources again (a paused one included).
                    Modifier.pressable(shape = shape) { vm.messages.tryEmit(errorText(r.errors) + " · asking again…"); vm.retrySources() }
                        .background(C.surface, shape).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = C.warn, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${r.errors.size} source${if (r.errors.size == 1) "" else "s"} failed", color = C.warn, fontSize = 11.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(if (dense) 6.dp else 8.dp)) {
                if (!r.browse) item {
                    Selector("All consoles", listOf<Pair<String?, String>>(null to "All consoles") + consoles.map { it to Systems.label(it) }, r.system, vm::setConsoleFilter)
                }
                item { Selector("All regions", listOf("all" to "All regions") + Regions.ALL.map { it to it }, r.region, vm::setRegion) }
                item {
                    Selector("All sources", listOf("all" to "All sources") + settings.configuredSources.map { it to SourceDefs.label(it) }, r.source, vm::setSource)
                }
                item { Selector("Sort", SORTS, r.sort, vm::setSort) }
            }
        }
        if (r.errors.isNotEmpty() && !r.loading && shown.isEmpty()) ErrorsLine(r.errors)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                r.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = C.accent, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                }
                shown.isEmpty() -> EmptyState(
                    if (r.errors.isNotEmpty() && r.all.isEmpty()) Icons.Rounded.CloudOff else Icons.Rounded.SearchOff,
                    when {
                        r.all.isEmpty() && r.errors.size >= settings.configuredSources.size && r.errors.isNotEmpty() -> "Your sources didn't answer"
                        r.all.isEmpty() -> "Nothing found"
                        else -> "Nothing in this region or source"
                    },
                    when {
                        settings.configuredSources.isEmpty() -> "Set up a source in Settings → Sources first."
                        r.all.isEmpty() && r.errors.size >= settings.configuredSources.size && r.errors.isNotEmpty() ->
                            "Every source failed (see above). Check the connection and the addresses in Settings → Sources."
                        r.all.isEmpty() && r.browse -> "Your sources list no games for this console."
                        r.all.isEmpty() -> "Try another spelling, or fewer words."
                        else -> "Change the filters above to see the other ${r.all.size}."
                    },
                    action = if (r.errors.isNotEmpty() && r.all.isEmpty()) ({
                        Btn("Try again", vm::retrySources, kind = ButtonKind.Primary, icon = Icons.Rounded.Refresh)
                    }) else null,
                )
                else -> LazyVerticalGrid(
                    state = grid,
                    // Handhelds: ~8 covers a row and two rows on screen.
                    columns = GridCells.Adaptive(
                        when {
                            dense && settings.showTitles -> 92.dp
                            dense -> 76.dp
                            settings.showTitles -> 128.dp
                            else -> 116.dp
                        },
                    ),
                    contentPadding = PaddingValues(start = side, end = side, top = if (dense) 4.dp else 6.dp, bottom = if (dense) 12.dp else 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (dense) 10.dp else 18.dp),
                    verticalArrangement = Arrangement.spacedBy(if (dense) 12.dp else 20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { vm.gameKey(it) + it.id }) { rom ->
                        GridCard(rom, settings.showTitles, chip = !r.browse,
                            modifier = if (rom === shown.first()) Modifier.focusRequester(firstCard) else Modifier,
                            onFocus = { vm.focused.value = rom },
                            onFavorite = { vm.toggleFavorite(rom.title, rom.system, rom.cover) },
                        ) { vm.openGame(rom, shown) }
                    }
                    if (r.loadingMore) item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = C.accent, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun errorText(errors: Map<String, String>) = errors.entries.joinToString(" · ") { "${SourceDefs.label(it.key)}: ${it.value}" }

@Composable
private fun ErrorsLine(errors: Map<String, String>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.WarningAmber, null, tint = C.warn, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            errorText(errors),
            style = T.small, color = C.warn, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One cover in the grid: the whole item, in its real box shape, untitled. */
@Composable
private fun GridCard(rom: MergedRom, showTitle: Boolean, chip: Boolean, modifier: Modifier = Modifier, onFocus: () -> Unit, onFavorite: () -> Unit, onClick: () -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().coverShadow().then(modifier).pressable(shape = RectangleShape, onFocus = onFocus, onY = onFavorite, onClick = onClick)) {
            Cover(rom.cover, rom.system, rom.title, Modifier.fillMaxWidth(), showChip = chip)
            if (rom.installed) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp).background(C.good, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, "In your library", tint = Color.White, modifier = Modifier.size(15.dp)) }
            }
        }
        if (showTitle) {
            Spacer(Modifier.height(6.dp))
            Text(rom.title, style = T.h3, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Names the focused cover (covers carry no title); shown in the bar at the bottom. */
@Composable
fun androidx.compose.foundation.layout.RowScope.FocusedInfo(rom: MergedRom) {
    val dense = LocalDense.current
    val small = if (dense) 12.sp else 13.sp
    Text(rom.title, style = T.h3, fontSize = if (dense) 13.sp else 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    val facts = listOfNotNull(
        Systems.label(rom.system),
        rom.year?.toString(),
        formatBytes(rom.size).ifEmpty { null },
        rom.downloads?.let { "${formatCount(it)} downloads" },
    )
    for (fact in facts) {
        Text("  ·  ", color = C.text3, fontSize = small)
        Text(fact, color = C.text2, fontSize = small, maxLines = 1)
    }
    rom.rating?.let {
        Text("  ·  ", color = C.text3, fontSize = small)
        Icon(Icons.Rounded.Star, null, tint = C.star, modifier = Modifier.size(if (dense) 13.dp else 15.dp))
        Spacer(Modifier.width(3.dp))
        Text(String.format("%.1f", it), color = C.star, fontSize = small, fontWeight = FontWeight.Bold)
    }
}
