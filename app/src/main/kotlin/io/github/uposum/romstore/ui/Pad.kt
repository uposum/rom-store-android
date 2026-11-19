package io.github.uposum.romstore.ui

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Where focus was before an overlay (the game page) opened, so closing it puts
 * focus back on the cover that opened it.
 */
object FocusMemory {
    private var last: FocusRequester? = null
    private var saved = ArrayDeque<FocusRequester?>()

    fun record(requester: FocusRequester) { last = requester }

    /** An overlay opens: keep where focus is now. */
    fun push() { saved.addLast(last) }

    /** The overlay closed: focus goes back. */
    fun pop() {
        val target = if (saved.isEmpty()) null else saved.removeLast()
        last = target
        target?.let { runCatching { it.requestFocus() } }
    }
}

/** Keep focus inside an overlay while it is open, and give it back afterwards. */
@Composable
fun Modifier.trapFocus(): Modifier {
    DisposableEffect(Unit) {
        FocusMemory.push()
        onDispose { FocusMemory.pop() }
    }
    return this.focusGroup().focusProperties { onExit = { cancelFocusChange() } }
}

/** Buttons the app handles globally (the shell decides what they do). */
enum class PadButton { L1, R1, L2, R2, X, START, SELECT }

object Pad {
    val events = MutableSharedFlow<PadButton>(extraBufferCapacity = 8)

    /** KEYCODE -> global button, for MainActivity.dispatchKeyEvent. */
    fun global(keyCode: Int): PadButton? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_L1 -> PadButton.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> PadButton.R1
        KeyEvent.KEYCODE_BUTTON_L2 -> PadButton.L2
        KeyEvent.KEYCODE_BUTTON_R2 -> PadButton.R2
        KeyEvent.KEYCODE_BUTTON_X -> PadButton.X
        KeyEvent.KEYCODE_BUTTON_START -> PadButton.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> PadButton.SELECT
        else -> null
    }
}

/** Collect global pad buttons while this composable is on screen. */
@Composable
fun OnPad(handler: (PadButton) -> Boolean) {
    val current by rememberUpdatedState(handler)
    LaunchedEffect(Unit) { Pad.events.collect { current(it) } }
}

/** True while a gamepad (a handheld's built-in controls count) is connected. */
@Composable
fun rememberGamepad(): Boolean {
    val context = LocalContext.current
    val state = remember { mutableStateOf(hasGamepad(context)) }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { state.value = hasGamepad(context) }
            override fun onInputDeviceRemoved(deviceId: Int) { state.value = hasGamepad(context) }
            override fun onInputDeviceChanged(deviceId: Int) { state.value = hasGamepad(context) }
        }
        manager.registerInputDeviceListener(listener, null)
        onDispose { manager.unregisterInputDeviceListener(listener) }
    }
    return state.value
}

private fun hasGamepad(context: Context): Boolean = InputDevice.getDeviceIds().any { id ->
    val device = InputDevice.getDevice(id) ?: return@any false
    val sources = device.sources
    !device.isVirtual && (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
        sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
}

/**
 * Something you can press with a finger or the A button. Focus always looks
 * the same: a white ring around the control. `onY` handles the Y button while
 * this control has focus (favourite).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    shape: Shape = RoundedCornerShape(10.dp),
    ring: Dp = 2.5.dp,
    gap: Dp = 3.dp,
    enabled: Boolean = true,
    onFocus: (() -> Unit)? = null,
    onY: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusCallback by rememberUpdatedState(onFocus)
    val requester = remember { FocusRequester() }
    LaunchedEffect(focused) {
        if (focused) {
            FocusMemory.record(requester)
            focusCallback?.invoke()
        }
    }
    val yHandler by rememberUpdatedState(onY)
    return this
        .onPreviewKeyEvent {
            val y = yHandler
            if (y != null && it.key == Key.ButtonY) {
                if (it.type == KeyEventType.KeyUp) y()
                true
            } else false
        }
        .focusRequester(requester)
        // The ring is for buttons: after a touch it would only mark a stale spot.
        .focusRing(focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard, shape, ring, gap)
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/** The white focus ring, drawn just outside the element. */
fun Modifier.focusRing(focused: Boolean, shape: Shape = RoundedCornerShape(10.dp), ring: Dp = 2.5.dp, gap: Dp = 3.dp): Modifier =
    if (!focused) this else drawWithContent {
        drawContent()
        val stroke = ring.toPx()
        val g = gap.toPx() + stroke / 2
        val radius = when (shape) {
            is RoundedCornerShape -> shape.topStart.toPx(size, this) + g
            RectangleShape -> g
            CircleShape -> size.minDimension / 2 + g
            else -> g
        }
        drawRoundRect(
            color = C.focus,
            topLeft = Offset(-g, -g),
            size = Size(size.width + 2 * g, size.height + 2 * g),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(stroke),
        )
    }

/** A button glyph as on the controller: "A", "B", "L1"... */
@Composable
fun Glyph(label: String, modifier: Modifier = Modifier) {
    val round = label.length == 1
    Box(
        modifier
            .then(if (round) Modifier.size(22.dp) else Modifier.height(22.dp).widthIn(min = 30.dp))
            .background(C.surface2, if (round) CircleShape else RoundedCornerShape(6.dp))
            .padding(horizontal = if (round) 0.dp else 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = C.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** The bottom bar that says what the buttons do (only with a gamepad). */
@Composable
fun HintBar(hints: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(C.bar).padding(horizontal = 20.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEachIndexed { i, (button, action) ->
            if (i > 0) Spacer(Modifier.width(18.dp))
            Glyph(button)
            Spacer(Modifier.width(7.dp))
            Text(action, color = C.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
