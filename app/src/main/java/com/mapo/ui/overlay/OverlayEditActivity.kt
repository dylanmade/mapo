package com.mapo.ui.overlay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
 *  - a foreground task that **owns the edit-session lifecycle** — when the user leaves via
 *    home/recents the session is torn down here so the overlay windows don't linger.
 *
 * We previously screen-pinned (`startLockTask`) to disable home/recents while editing, but
 * that forced an OS "pin this app?" confirmation on every open (unavoidable for a
 * non-device-owner app). Dropped in favor of letting the user leave freely + a clean teardown
 * on [onStop].
 *
 * Launched via [OverlayLiveEditController.requestEdit]. The controller owns the "am I
 * editing" flag; when it flips false (toolbar ✓ / back) this activity finishes.
 */
@AndroidEntryPoint
class OverlayEditActivity : ComponentActivity() {

    @Inject lateinit var controller: OverlayLiveEditController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // full-bleed so the screenshot aligns 1:1 with the overlay windows

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

        // Controller owns the edit-session lifecycle; mirror it into this activity.
        lifecycleScope.launch {
            controller.editing.collect { editing ->
                if (!editing) finishAndRemoveTask()
            }
        }
    }

    // No screen-pinning, so the user can leave via home/recents at any time. End the edit
    // session here, or the overlay windows (scrim/toolbar/buttons/config) would linger over
    // whatever they land on. onStop covers home, recents, and the normal finish alike;
    // controller.stop() is idempotent and flips `editing` false (which also drives the
    // collector above). Rotation etc. don't reach here (configChanges handles them).
    override fun onStop() {
        super.onStop()
        if (controller.isEditing()) controller.stop()
        if (!isFinishing) finishAndRemoveTask()
    }

    override fun onDestroy() {
        controller.stop()
        super.onDestroy()
    }
}
