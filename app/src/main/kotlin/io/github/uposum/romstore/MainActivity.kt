package io.github.uposum.romstore

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.uposum.romstore.ui.AppViewModel
import io.github.uposum.romstore.ui.Pad
import io.github.uposum.romstore.ui.RomStoreTheme
import io.github.uposum.romstore.ui.Shell
import io.github.uposum.romstore.ui.Tab

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    companion object {
        const val EXTRA_TAB = "tab"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Full screen: a handheld's screen is small, the store wants all of it.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        handle(intent)
        setContent {
            RomStoreTheme { Shell(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_TAB) == "downloads") vm.setTab(Tab.Downloads)
    }

    /**
     * The controller: A presses what has focus (Compose clicks on the d-pad
     * centre), B goes back, the shoulder buttons, X, Start and Select go to
     * the shell. Y and the d-pad go to the focused control as they are.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> return super.dispatchKeyEvent(remap(event, KeyEvent.KEYCODE_DPAD_CENTER))
            KeyEvent.KEYCODE_BUTTON_B -> {
                // Back on release, like the system back key.
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
                return true
            }
        }
        val button = Pad.global(event.keyCode)
        if (button != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) Pad.events.tryEmit(button)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun remap(event: KeyEvent, code: Int) = KeyEvent(
        event.downTime, event.eventTime, event.action, code, event.repeatCount,
        event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
    )

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }
}
