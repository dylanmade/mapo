package com.mapo.ui

import androidx.compose.ui.platform.ViewConfiguration

/**
 * App-wide gesture defaults. Anywhere the app implements a gesture (tap, long-press,
 * drag-to-reorder, drag-to-resize, swipe), it should reach for these tokens first
 * so identical-feeling affordances behave identically across components — drift
 * between, e.g., the tab bar's reorder threshold and the button grid's reorder
 * threshold makes the app feel inconsistent even when both work in isolation.
 *
 * Component-local overrides need a clear, written reason ("trackpad button
 * long-press is an input gesture, not a UI gesture"). Otherwise: use these.
 */
object MapoGesture {

    /**
     * After a long-press has fired and the gesture is in its lifted state, how
     * far the finger must travel before we treat the gesture as a reorder drag,
     * expressed as a multiplier on [ViewConfiguration.touchSlop].
     *
     * The base [ViewConfiguration.touchSlop] is tuned for "tap vs scroll" — it
     * trips at the slightest tremor, which is fine for distinguishing intent
     * before a press has committed but too eager when the user is already
     * holding a contextual menu open and we need to distinguish "still
     * holding" from "now intending to drag." 2.5× reads as deliberate without
     * feeling sluggish.
     */
    const val REORDER_SLOP_MULTIPLIER = 2.5f

    /** [REORDER_SLOP_MULTIPLIER] applied to the current [viewConfig]'s touch slop. */
    fun reorderSlopPx(viewConfig: ViewConfiguration): Float =
        viewConfig.touchSlop * REORDER_SLOP_MULTIPLIER
}
