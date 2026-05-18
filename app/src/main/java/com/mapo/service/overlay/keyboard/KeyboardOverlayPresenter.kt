package com.mapo.service.overlay.keyboard

import android.content.Context
import android.content.Intent
import com.mapo.service.keyboard.KeyboardController
import com.mapo.service.keyboard.asKeyboardHostState
import com.mapo.ui.screen.keyboard.KeyboardHost
import com.mapo.ui.screen.keyboard.KeyboardHostMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single coordination point for "show / hide / toggle the run-mode keyboard overlay."
 *
 * Sits one level above [KeyboardOverlayManager] (which is the window-attach mechanic)
 * because every show-the-overlay caller — the QS tile, the in-app drawer toggle, the
 * future FGS-notification action — needs the same composable wired with the same
 * mode-specific callbacks (Open Mapo, Hide overlay). Centralizing that lets callers
 * stay one-liners and prevents copy-paste drift.
 *
 * Holds no state of its own; reads attach state straight off the manager.
 */
@Singleton
class KeyboardOverlayPresenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: KeyboardOverlayManager,
    private val controller: KeyboardController,
    private val displayRouter: KeyboardDisplayRouter,
) {

    fun isShowing(): Boolean = manager.isAttached(OVERLAY_ID)

    fun show() {
        if (manager.isAttached(OVERLAY_ID)) return
        // FC2 seam: the router today returns exactly one display id (the default
        // display); tomorrow it returns N (Thor top + bottom screens as a unified
        // canvas). When FC2 lands, this loop needs per-display overlay ids
        // (e.g. "$OVERLAY_ID:$displayId") so the manager treats each window as a
        // separate entry — `isShowing()` should then return true if ANY of the
        // fan-out windows are attached. For today's N=1 case the canonical id works.
        val displays = displayRouter.routeOverlay(OVERLAY_ID)
        for (displayId in displays) {
            manager.attach(OVERLAY_ID, displayId = displayId) {
                KeyboardHost(
                    state = controller.asKeyboardHostState(),
                    mode = KeyboardHostMode.Overlay(
                        onOpenMapoActivity = ::launchMapoActivity,
                        onHideOverlay = ::hide,
                    ),
                )
            }
        }
    }

    fun hide() {
        manager.detach(OVERLAY_ID)
    }

    fun toggle() {
        if (isShowing()) hide() else show()
    }

    private fun launchMapoActivity() {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(context.packageName)
        if (launchIntent != null) {
            // Bring an existing task to front rather than spawning a duplicate. The
            // overlay stays mounted across this transition (FGS keeps the process alive),
            // so the user can re-hide it from inside the activity if they want.
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
            context.startActivity(launchIntent)
        }
    }

    companion object {
        /** Single canonical id for the run-mode keyboard overlay (one window per process). */
        const val OVERLAY_ID = "keyboard_main"
    }
}
