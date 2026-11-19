package io.github.uposum.romstore.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.uposum.romstore.core.model.Screenshot
import io.github.uposum.romstore.core.model.details
import io.github.uposum.romstore.core.model.Systems
import io.github.uposum.romstore.core.sources.SourceDefs
import io.github.uposum.romstore.core.store.DownloadOption
import io.github.uposum.romstore.core.store.JobStatus
import io.github.uposum.romstore.formatBytes

@Composable
fun GamePageView(vm: AppViewModel, page: GamePage, gamepad: Boolean) {
    val rom = page.rom
    val shelves by vm.shelves.collectAsStateWithLifecycle()
    val jobs by vm.downloads.collectAsStateWithLifecycle()
    val jobIds by vm.jobsByGame.collectAsStateWithLifecycle()
    val favorite = shelves.favorites.any { it.key == io.github.uposum.romstore.core.store.Shelves.key(rom.title, rom.system) }
    val job = jobIds[vm.gameKey(rom)]?.let { id -> jobs.firstOrNull { it.id == id } }
    val context = LocalContext.current
    var lightbox by remember(rom.id) { mutableStateOf<Int?>(null) }
    var showAll by remember(rom.id) { mutableStateOf(false) }
    val downloadFocus = remember { FocusRequester() }
    val primary = page.options.firstOrNull()
    val pickFolder = rememberFolderPicker { vm.setConsoleFolder(rom.system, it) }

    BackHandler(enabled = lightbox == null) { vm.closeGame() }
    OnPad { button ->
        if (button == PadButton.X && lightbox == null && primary != null && job?.active != true) vm.download(primary)
        true
    }
    val keys = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard
    LaunchedEffect(rom.id, page.loading) {
        if (gamepad || keys) runCatching { downloadFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(C.bg).trapFocus()) {
        // Backdrop: the first screenshot, faint, under a gradient.
        rom.media?.shots?.firstOrNull()?.let { shot ->
            AsyncImage(shot.full, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(360.dp).alpha(0.22f))
            Box(Modifier.fillMaxWidth().height(360.dp).background(Brush.verticalGradient(listOf(Color.Transparent, C.bg))))
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight * 1.1f
            val posterRatio = coverRatio(rom.coverFull ?: rom.cover ?: rom.media?.cover, rom.system)
            // The poster has its art's shape; on a short screen the column narrows instead.
            val posterWidth = if (wide) minOf(maxWidth * 0.3f, (maxHeight - 140.dp) * posterRatio).coerceAtLeast(150.dp) else minOf(maxWidth * 0.42f, 220.dp)
            val scroll = rememberScrollState()
            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    Column(Modifier.width(posterWidth + 48.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 16.dp, top = 32.dp, bottom = 24.dp)) {
                        Cover(rom.coverFull ?: rom.cover ?: rom.media?.cover, rom.system, rom.title, Modifier.width(posterWidth).coverShadow(), big = true)
                        Spacer(Modifier.height(20.dp))
                        Facts(page)
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll)
                        .padding(start = if (wide) 16.dp else 24.dp, end = 28.dp, top = 28.dp, bottom = 32.dp),
                ) {
                    if (!wide) {
                        Cover(rom.coverFull ?: rom.cover ?: rom.media?.cover, rom.system, rom.title, Modifier.width(posterWidth).coverShadow(), big = true)
                        Spacer(Modifier.height(18.dp))
                    }
                    Text(Systems.name(rom.system).uppercase(), color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(rom.title, style = T.h1, modifier = Modifier.padding(end = 120.dp))
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        rom.rating?.let { Pill(String.format("%.1f", it), color = C.star, icon = Icons.Rounded.Star) }
                        rom.year?.let { Pill("$it") }
                        rom.size?.let { Pill(formatBytes(it)) }
                        if (rom.installed) Pill("In your library", color = C.good, icon = Icons.Rounded.Check)
                    }
                    Spacer(Modifier.height(20.dp))
                    // Download, favourite, the file
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DownloadButton(page, job, Modifier.focusRequester(downloadFocus), onY = { vm.toggleFavorite(rom.title, rom.system, rom.cover) }) {
                            when {
                                job?.active == true -> vm.setTab(Tab.Downloads).also { vm.closeGame() }
                                primary != null -> vm.download(primary)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        IconBtn(
                            if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favourite",
                            { vm.toggleFavorite(rom.title, rom.system, rom.cover) }, size = 52.dp,
                            tint = if (favorite) C.star else C.text,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            if (primary != null) {
                                Text(primary.file.filename.ifEmpty { primary.file.label ?: rom.title }, style = T.h3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(optionFacts(primary), style = T.small, maxLines = 1)
                            } else if (page.loading) {
                                Text("Looking up the download…", style = T.body2)
                            } else if (page.error != null) {
                                Text(page.error, style = T.body2, color = C.warn, maxLines = 2)
                            }
                        }
                    }
                    job?.takeIf { it.status == JobStatus.ERROR }?.error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, style = T.body2, color = C.bad)
                    }
                    // Media
                    val media = rom.media
                    if (media != null && (media.shots.isNotEmpty() || media.trailer != null)) {
                        Spacer(Modifier.height(28.dp))
                        Text("Media", style = T.h2)
                        Spacer(Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            media.trailer?.let { trailer ->
                                item {
                                    MediaTile(trailer.thumb, play = true) {
                                        val url = trailer.url ?: trailer.id?.let { "https://www.youtube.com/watch?v=$it" }
                                        if (url != null) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                    }
                                }
                            }
                            itemsIndexed(media.shots) { index, shot -> MediaTile(shot.thumb) { lightbox = index } }
                        }
                    }
                    // About
                    rom.summary?.takeIf { it.length > 24 }?.let {
                        Spacer(Modifier.height(28.dp))
                        Text("About", style = T.h2)
                        Spacer(Modifier.height(10.dp))
                        Text(it, style = T.body, color = C.text2, modifier = Modifier.widthIn(max = 720.dp))
                    }
                    if (!wide) {
                        Spacer(Modifier.height(24.dp))
                        Facts(page)
                    }
                    // Other versions: five, then "Show all"
                    if (page.options.size > 1) {
                        Spacer(Modifier.height(28.dp))
                        Text("Versions", style = T.h2)
                        Spacer(Modifier.height(10.dp))
                        val list = if (showAll) page.options else page.options.take(5)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            list.forEach { option -> VersionRow(option) { vm.download(option) } }
                        }
                        if (page.options.size > 5 && !showAll) {
                            Spacer(Modifier.height(10.dp))
                            Btn("Show all ${page.options.size}", { showAll = true }, kind = ButtonKind.Ghost)
                        }
                    }
                    // Save to: always at the very bottom
                    Spacer(Modifier.height(28.dp))
                    Text("Save to", style = T.h2)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.background(C.surface, RoundedCornerShape(12.dp)).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Folder, null, tint = C.text2, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(vm.folderDescription(rom.system) ?: "No ROM folder chosen yet", style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(12.dp))
                        Btn("Change", pickFolder, kind = ButtonKind.Ghost)
                    }
                }
            }
        }
        // Previous / next and close
        Row(Modifier.align(Alignment.TopEnd).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page.list.size > 1) {
                Row(
                    Modifier.background(C.surface.copy(alpha = 0.9f), CircleShape).padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (gamepad) Glyph("L1") else IconBtn(Icons.Rounded.ChevronLeft, "Previous", { vm.stepGame(-1) }, size = 34.dp, bg = Color.Transparent)
                    Text("${page.index + 1} / ${page.list.size}", style = T.h3, modifier = Modifier.padding(horizontal = 10.dp))
                    if (gamepad) Glyph("R1") else IconBtn(Icons.Rounded.ChevronRight, "Next", { vm.stepGame(1) }, size = 34.dp, bg = Color.Transparent)
                }
                Spacer(Modifier.width(10.dp))
            }
            IconBtn(Icons.Rounded.Close, "Close", vm::closeGame, size = 44.dp, bg = C.surface.copy(alpha = 0.9f))
        }
        if (gamepad) {
            HintBar(
                listOf("L1" to "Previous", "R1" to "Next", "Y" to "Favourite", "X" to "Download", "B" to "Back", "A" to "Select"),
                Modifier.align(Alignment.BottomCenter),
            )
        }
        lightbox?.let { index -> rom.media?.shots?.let { Lightbox(it, index, onIndex = { i -> lightbox = i }) { lightbox = null } } }
    }
}

private fun optionFacts(option: DownloadOption): String = listOfNotNull(
    option.sourceLabel,
    formatBytes(option.file.size ?: option.entry.size).ifEmpty { null },
    option.file.region ?: option.entry.region,
    formatEta(option.eta).ifEmpty { null }?.let { "about $it" },
).joinToString("  ·  ")

@Composable
private fun DownloadButton(page: GamePage, job: io.github.uposum.romstore.core.store.DownloadJob?, modifier: Modifier, onY: () -> Unit, onClick: () -> Unit) {
    val active = job?.active == true
    val enabled = active || page.options.isNotEmpty()
    Btn(
        text = "", onClick = onClick, kind = ButtonKind.Primary, big = true, enabled = enabled, onY = onY,
        modifier = modifier.widthIn(min = 220.dp),
    ) {
        when {
            page.loading && !active -> {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Loading", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            active -> Column(Modifier.width(200.dp)) {
                val fraction = if (job!!.total > 0) job.received.toFloat() / job.total else null
                Text(
                    when (job.status) {
                        JobStatus.EXTRACTING -> "Unpacking…"
                        JobStatus.QUEUED -> "Waiting…"
                        else -> fraction?.let { "Downloading ${(it * 100).toInt()}%" } ?: "Downloading…"
                    },
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                ProgressLine(fraction, Modifier.fillMaxWidth(), color = Color.White)
            }
            job?.status == JobStatus.DONE -> {
                Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Downloaded", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            else -> {
                Icon(Icons.Rounded.Download, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Download", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Facts(page: GamePage) {
    val rom = page.rom
    val details = rom.entries.firstOrNull()?.extra?.details() ?: emptyMap()
    val media = rom.media
    val facts = listOfNotNull(
        (media?.developers?.firstOrNull() ?: rom.entries.firstNotNullOfOrNull { it.developer })?.let { "Developer" to it },
        rom.entries.firstNotNullOfOrNull { it.publisher }?.let { "Publisher" to it },
        (media?.genres?.takeIf { it.isNotEmpty() }?.take(2)?.joinToString(", ") ?: details["Genre/s"] ?: details["Category"])?.let { "Genre" to it },
        rom.downloads?.let { "Downloads" to formatCount(it) },
        (media?.released ?: rom.year?.toString())?.let { "Released" to it },
        rom.regions.takeIf { it.isNotEmpty() }?.let { "Region" to it.joinToString(", ") },
        "Found on" to rom.sources.joinToString(", ") { SourceDefs.label(it) }.ifEmpty { "—" },
    )
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        facts.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { (label, value) -> Labeled(label, value, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun VersionRow(option: DownloadOption, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).pressable(shape = shape, onClick = onClick).background(C.surface, shape).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(option.file.filename.ifEmpty { option.file.label ?: option.entry.title }, style = T.h3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(optionFacts(option), style = T.small, maxLines = 1)
        }
        Icon(Icons.Rounded.Download, "Download", tint = C.text2, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MediaTile(url: String?, play: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.height(124.dp).aspectRatio(16f / 9f).pressable(shape = shape, onClick = onClick).clip(shape).background(C.surface)) {
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (play) Box(Modifier.align(Alignment.Center).size(46.dp).background(Color(0xAA000000), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, "Play trailer", tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}

/** A screenshot full screen: left/right or A for the next, B back. */
@Composable
private fun Lightbox(shots: List<Screenshot>, index: Int, onIndex: (Int) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color(0xF2000000)).trapFocus()
            .focusRequester(focus)
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.DirectionRight, Key.DirectionCenter, Key.Enter -> { onIndex((index + 1) % shots.size); true }
                    Key.DirectionLeft -> { onIndex((index - 1 + shots.size) % shots.size); true }
                    else -> false
                }
            }
            .pressable(shape = RectangleShape, ring = 0.dp) { onIndex((index + 1) % shots.size) },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(shots[index].full, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(24.dp))
        Text("${index + 1} / ${shots.size}", style = T.h3, modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp))
        Row(Modifier.align(Alignment.TopEnd).padding(16.dp)) { IconBtn(Icons.Rounded.Close, "Close", onClose) }
    }
}
