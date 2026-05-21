package com.mapo.service.input.capture

import android.content.Context
import android.view.MotionEvent
import android.view.View

/**
 * Invisible 1×1 [View] whose only purpose is to receive [onGenericMotionEvent]
 * while the production motion-capture overlay is attached. The visible window
 * has a transparent background and is positioned at the screen's top-left
 * corner — the user never sees it.
 *
 * Returns `false` from the motion-event handler so events keep flowing through
 * the normal Android input pipeline (no behavioral steal — we observe analog
 * state on the way past, the foreground game still receives the event).
 *
 * Kept as a separate file from [MotionCaptureOverlayManager] for testability:
 * Robolectric can construct this view + dispatch synthetic [MotionEvent]s
 * without needing a real `WindowManager`.
 */
class MotionCaptureView(context: Context) : View(context) {

    /**
     * Set by [MotionCaptureOverlayManager] before [attach][MotionCaptureOverlayManager.attach]
     * mounts the view. Cleared on detach so a torn-down callback can't fire
     * against a stale view that the window manager hasn't finished removing.
     */
    var onMotion: ((MotionEvent) -> Unit)? = null

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        onMotion?.invoke(event)
        return false
    }
}
