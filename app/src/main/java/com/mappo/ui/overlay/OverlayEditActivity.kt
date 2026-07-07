package com.mappo.ui.overlay

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.lifecycleScope
import com.mappo.service.overlay.element.OverlayLiveEditController
import com.mappo.ui.theme.MappoTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground host for the live overlay editor. The button-editing UI itself is drawn by
 * [OverlayLiveEditController] as `TYPE_APPLICATION_OVERLAY` windows on top of this activity;
 * this activity provides:
 *  - the **frozen backdrop** (a screenshot of the game captured at trigger time, so editing
 *    is still WYSIWYG over the real content), and
 *  - a foreground task (its own `taskAffinity`) that keeps receiving back + lifecycle
 *    callbacks while editing, so back/home can route to the exit confirm.
 *
 * We previously screen-pinned (`startLockTask`) to disable home/recents while editing, but
 * that forced an OS "pin this app?" confirmation on every open (unavoidable for a
 * non-device-owner app). Dropped in favor of explicit exits, all funneled through the same
 * "Exit overlay editing?" confirm: the toolbar Done, **back** (dispatcher → confirm), and
 * **Home** ([onUserLeaveHint] → confirm; Cancel re-foregrounds). **Recents/overview never
 * prompts and never exits** — onUserLeaveHint doesn't fire for it — so the session survives.
 *
 * Launched via [OverlayLiveEditController.requestEdit]. The controller owns the "am I
 * editing" flag; when it flips false this activity finishes.
 */
@AndroidEntryPoint
class OverlayEditActivity : ComponentActivity() {

    @Inject lateinit var controller: OverlayLiveEditController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Match MainActivity: fully immersive (bars hidden, swipe to reveal transiently) so the
        // edit surface is full-bleed and the screenshot/overlay coordinate space lines up 1:1
        // with a real fullscreen game.
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hideSystemBars()

        val backdrop = controller.backdropBitmap
        setContent {
            MappoTheme {
                if (backdrop != null) {
                    // Frozen game screenshot (captured over a real app) — opaque WYSIWYG canvas.
                    Image(
                        bitmap = backdrop.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // No screenshot (API < 30, capture failed, or launched from inside Mappo):
                    // stay transparent so the translucent activity reveals the live content
                    // behind it. The editor's own scrim (~40% black) dims it to signal edit
                    // mode — painting an opaque box here is what made edit mode look black.
                    Box(Modifier.fillMaxSize().background(Color.Transparent))
                }
            }
        }

        controller.start()

        // Back (gesture or button) → "Exit overlay editing?" confirm, drawn by the controller
        // as a top overlay window. This activity lives in its own task (taskAffinity="") so it
        // stays foreground while editing and keeps receiving back + lifecycle callbacks.
        onBackPressedDispatcher.addCallback(this) { controller.handleBack() }

        // Controller owns the edit-session lifecycle; mirror it into this activity.
        lifecycleScope.launch {
            controller.editing.collect { editing ->
                if (!editing) finishAndRemoveTask()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // HOME → the same "Exit overlay editing?" confirm (not an immediate exit). onUserLeaveHint
        // fires on Home but NOT on recents/overview — so recents never prompts and never exits
        // (hard requirement). Home can't be intercepted, so by now we've backgrounded to the
        // launcher; the controller raises the confirm over it and re-foregrounds us on Cancel.
        controller.onHomePressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        controller.stop()
        super.onDestroy()
    }
}
