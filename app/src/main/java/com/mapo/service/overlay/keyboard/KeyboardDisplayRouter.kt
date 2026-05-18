package com.mapo.service.overlay.keyboard

import android.view.Display
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FC2 forward-compat seam (single-screen refactor, Brick 5): "given a logical
 * overlay, return the physical display surface(s) it should attach to." Today's
 * implementation is a single-display passthrough — always returns the default
 * display. Tomorrow, when AYN Thor's bottom screen becomes a canvas extension of
 * the active overlay (FC2), the same call returns a collection of display ids
 * (top + bottom) and `KeyboardOverlayPresenter` fans out one window per element.
 *
 * **Signature is `List<Int>` even though we only ever return one entry today**,
 * exactly so the future fan-out is a single-file change to this router's body
 * rather than a refactor that ripples into every caller.
 *
 * **Why not in the manager directly?** Display selection is a policy decision
 * (per device, per user preference, per FC2 logic). The manager is mechanics
 * (`WindowManager.addView`). Separating them keeps both narrow.
 */
@Singleton
class KeyboardDisplayRouter @Inject constructor() {

    /**
     * Returns the display id(s) on which [overlayId] should be mounted. Order is
     * deliberate — when FC2 lands, the first entry is the "primary" surface and
     * subsequent entries are extension canvases. `KeyboardOverlayPresenter`
     * iterates this list and calls `manager.attach` once per display.
     *
     * Brick 5 baseline: every overlay routes to [Display.DEFAULT_DISPLAY]. Future
     * Thor support and per-overlay routing decisions land here.
     */
    fun routeOverlay(overlayId: String): List<Int> =
        listOf(Display.DEFAULT_DISPLAY)
}
