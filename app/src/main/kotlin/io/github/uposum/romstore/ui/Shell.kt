package io.github.uposum.romstore.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uposum.romstore.R
import kotlinx.coroutines.launch

/** Picks a folder with the system picker and keeps access to it. */
@Composable
fun rememberFolderPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            onPicked(uri)
        }
    }
    return { launcher.launch(null) }
}

/** The whole app: top bar with the tabs and search, the open tab, overlays. */
@Composable
fun Shell(vm: AppViewModel) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val game by vm.game.collectAsStateWithLifecycle()
    val libraryGame by vm.libraryGame.collectAsStateWithLifecycle()
    val jobs by vm.downloads.collectAsStateWithLifecycle()
    val gamepad = rememberGamepad()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var settingsSection by remember { mutableStateOf(SettingsSection.Library) }

    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            scope.launch { snackbar.showSnackbar(message) }
        }
    }

    OnPad { button ->
        val overlay = game != null || libraryGame != null
        when (button) {
            PadButton.L1 -> if (game != null) vm.stepGame(-1) else if (!overlay) vm.stepTab(-1)
            PadButton.R1 -> if (game != null) vm.stepGame(1) else if (!overlay) vm.stepTab(1)
            PadButton.X -> if (!overlay) { vm.setTab(Tab.Store); vm.focusSearch.tryEmit(Unit) }
            PadButton.START -> if (!overlay) vm.setTab(Tab.Downloads)
            else -> {}
        }
        true
    }

    BackHandler(enabled = tab != Tab.Store && game == null && libraryGame == null) { vm.setTab(Tab.Store) }
    BackHandler(enabled = tab == Tab.Store && results != null && game == null) { vm.goHome() }

    val active = jobs.count { it.active }
    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(vm, tab, active, jobs.filter { it.active }, gamepad)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.Store -> StoreTab(vm, gamepad, onSetup = { settingsSection = SettingsSection.Sources; vm.setTab(Tab.Settings) })
                    Tab.Library -> LibraryTab(vm)
                    Tab.Downloads -> DownloadsTab(vm)
                    Tab.Settings -> SettingsTab(vm, settingsSection) { settingsSection = it }
                }
            }
            val focusedRom by vm.focused.collectAsStateWithLifecycle()
            val showFocused = tab == Tab.Store && results != null && focusedRom != null
            if ((gamepad || showFocused) && game == null && libraryGame == null) {
                HintBar(
                    leading = if (showFocused) ({ FocusedInfo(focusedRom!!) }) else null,
                    hints = if (!gamepad) emptyList() else when (tab) {
                        Tab.Store -> if (results != null) listOf("Y" to "Favourite", "X" to "Search", "B" to "Back", "A" to "Open")
                        else listOf("X" to "Search", "A" to "Open")
                        Tab.Library -> listOf("B" to "Store", "A" to "Open")
                        Tab.Downloads -> listOf("B" to "Store", "A" to "Select")
                        Tab.Settings -> listOf("B" to "Store", "A" to "Select")
                    },
                )
            }
        }
        AnimatedVisibility(visible = game != null, enter = fadeIn(), exit = fadeOut()) {
            game?.let { GamePageView(vm, it, gamepad) }
        }
        AnimatedVisibility(visible = libraryGame != null, enter = fadeIn(), exit = fadeOut()) {
            libraryGame?.let { LibraryGamePage(vm, it, gamepad) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (gamepad) 44.dp else 20.dp)) { data ->
            Snackbar(
                containerColor = C.surface2, contentColor = C.text, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 520.dp),
            ) { Text(data.visuals.message, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun TopBar(vm: AppViewModel, tab: Tab, active: Int, activeJobs: List<io.github.uposum.romstore.core.store.DownloadJob>, gamepad: Boolean) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().background(C.bar)) {
        // A handheld in landscape gets one row; a narrow (portrait) screen two.
        val oneRow = maxWidth >= 760.dp
        val wideEnough = maxWidth >= 560.dp
        val searchWidth = minOf(360.dp, maxWidth * 0.34f)
        val dense = LocalDense.current
        val logo = if (dense) 24.dp else 32.dp
        Column(Modifier.fillMaxWidth().padding(horizontal = if (dense) 12.dp else 16.dp, vertical = if (dense) 3.dp else 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(logo).background(Brush.linearGradient(listOf(Color(0xFF3B8CFF), Color(0xFF1550D6))), RoundedCornerShape(if (dense) 7.dp else 9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground), null, tint = Color.Unspecified, modifier = Modifier.size(logo * 1.38f))
                }
                if (wideEnough) {
                    Spacer(Modifier.width(if (dense) 8.dp else 10.dp))
                    Text("ROM Store", style = T.brand, fontSize = if (dense) 14.sp else 17.sp, maxLines = 1)
                }
                Spacer(Modifier.width(if (dense) 10.dp else 14.dp))
                if (gamepad) { Glyph("L1"); Spacer(Modifier.width(6.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Tab.entries.forEach { t ->
                        TabPill(t, selected = t == tab, badge = if (t == Tab.Downloads && active > 0) active else 0,
                            progress = if (t == Tab.Downloads && active > 0) {
                                val total = activeJobs.sumOf { it.total }
                                if (total > 0) activeJobs.sumOf { it.received }.toFloat() / total else null
                            } else null,
                        ) { vm.setTab(t) }
                    }
                }
                if (gamepad) { Spacer(Modifier.width(6.dp)); Glyph("R1") }
                if (oneRow) {
                    Spacer(Modifier.weight(1f).width(14.dp))
                    SearchField(vm, Modifier.width(searchWidth))
                }
            }
            if (!oneRow) {
                Spacer(Modifier.height(8.dp))
                SearchField(vm, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TabPill(tab: Tab, selected: Boolean, badge: Int, progress: Float?, onClick: () -> Unit) {
    val dense = LocalDense.current
    val shape = RoundedCornerShape(if (dense) 8.dp else 10.dp)
    Column(
        Modifier.pressable(shape = shape, onClick = onClick)
            .background(if (selected) C.surface2 else Color.Transparent, shape)
            .padding(horizontal = if (dense) 10.dp else 14.dp, vertical = if (dense) 4.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tab.label, style = T.tab, fontSize = if (dense) 13.sp else 15.sp, color = if (selected) C.text else C.text2)
            if (badge > 0) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(18.dp).background(C.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Text("$badge", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (progress != null) {
            Spacer(Modifier.height(if (dense) 2.dp else 4.dp))
            ProgressLine(progress, Modifier.width(if (dense) 44.dp else 56.dp).height(if (dense) 2.dp else 4.dp))
        }
    }
}

/**
 * The search field. With a gamepad it is a button first (so moving across it
 * does not pop the keyboard up); pressing it, X, or touching it types.
 */
@Composable
private fun SearchField(vm: AppViewModel, modifier: Modifier) {
    val query by vm.query.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(22.dp)

    LaunchedEffect(Unit) { vm.focusSearch.collect { editing = true } }
    LaunchedEffect(editing) {
        if (editing) {
            runCatching { requester.requestFocus() }
            keyboard?.show()
        }
    }

    fun submit() {
        editing = false
        hadFocus = false
        keyboard?.hide()
        vm.search(query)
        focus.moveFocus(FocusDirection.Down)
    }

    Row(
        modifier.height(if (LocalDense.current) 32.dp else 44.dp)
            .then(if (!editing) Modifier.pressable(shape = shape) { editing = true } else Modifier)
            .background(C.surface, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = C.text2, modifier = Modifier.size(if (LocalDense.current) 17.dp else 20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (editing) {
                BasicTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = C.text, fontSize = if (LocalDense.current) 13.sp else 15.sp),
                    cursorBrush = SolidColor(C.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }, onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(requester)
                        .onFocusChanged {
                            if (it.isFocused) hadFocus = true else if (hadFocus && editing) { editing = false; hadFocus = false }
                        }
                        .onPreviewKeyEvent {
                            if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (it.key) {
                                Key.DirectionDown -> { editing = false; keyboard?.hide(); focus.moveFocus(FocusDirection.Down); true }
                                Key.Enter, Key.NumPadEnter -> { submit(); true }
                                Key.Escape, Key.Back -> { editing = false; keyboard?.hide(); true }
                                else -> false
                            }
                        },
                )
            }
            if (!editing) {
                Text(
                    query.ifEmpty { "Search games" }, color = if (query.isEmpty()) C.text3 else C.text, fontSize = if (LocalDense.current) 13.sp else 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(26.dp).pressable(shape = CircleShape) { vm.goHome(); editing = false }.background(C.surface2, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, "Clear", tint = C.text2, modifier = Modifier.size(15.dp)) }
        }
    }
}

/** Hide everything that scrolls under the bars' height: a column filling the rest. */
@Composable
fun FullHeight(content: @Composable () -> Unit) = Box(Modifier.fillMaxHeight()) { content() }
