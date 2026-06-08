package com.mapo.ui.overlay

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import com.mapo.service.overlay.element.OverlayLiveEditController
import com.mapo.ui.theme.MapoTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground host for the live overlay editor. The button-editing UI itself is drawn by
 * [OverlayLiveEditController] as `TYPE_APPLICATION_OVERLAY` windows on top of this activity;
 * this activity provides:
 *  - the **frozen backdrop** (a screenshot of the game captured at trigger time, so editing
 *    is still WYSIWYG over the real content), and
 *  - a foreground task to launch the session from.
 *
 * We previously screen-pinned (`startLockTask`) to disable home/recents while editing, but
 * that forced an OS "pin this app?" confirmation on every open (unavoidable for a
 * non-device-owner app). Dropped in favor of letting the user leave freely; edit mode (the
 * overlay windows) persists across home/recents and ends only on the toolbar Done.
 *
 * Launched via [OverlayLiveEditController.requestEdit]. The controller owns the "am I
 * editing" flag; when it flips false (toolbar ✓ / back) this activity finishes.
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
            MapoTheme {
                if (backdrop != null) {
                    // Frozen game screenshot (captured over a real app) — opaque WYSIWYG canvas.
                    Image(
                        bitmap = backdrop.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // No screenshot (API < 30, capture failed, or launched from inside Mapo):
                    // stay transparent so the translucent activity reveals the live content
                    // behind it. The editor's own scrim (~40% black) dims it to signal edit
                    // mode — painting an opaque box here is what made edit mode look black.
                    Box(Modifier.fillMaxSize().background(Color.Transparent))
                }
            }
        }

        controller.start()

        // Controller owns the edit-session lifecycle; mirror it into this activity. Edit mode
        // ends ONLY when the user taps Done (✓) / back in the toolbar — NOT on home/recents.
        // The buttons are `TYPE_APPLICATION_OVERLAY` windows, so they survive the activity
        // being backgrounded (an accidental recents/home tap must not drop the user's
        // in-progress edits). We intentionally do NOT tear down in onStop for that reason;
        // onDestroy is the only safety net (system reclaim).
        lifecycleScope.launch {
            controller.editing.collect { editing ->
                if (!editing) finishAndRemoveTask()
            }
        }
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
