package com.mapo.data.model

enum class TrackpadGesture(val displayName: String) {
    TAP("On Tap"),
    DOUBLE_TAP("On Double Tap"),
    LONG_PRESS("On Long Press");
}

/**
 * The effective dispatch target for a trackpad gesture. Trackpad gestures map directly to
 * the unified onTap / onDoubleTap / onHold behavior fields shared with regular buttons.
 */
fun GridButton.gestureTarget(gesture: TrackpadGesture): RemapTarget = when (gesture) {
    TrackpadGesture.TAP        -> onTapTarget
    TrackpadGesture.DOUBLE_TAP -> onDoubleTapTarget
    TrackpadGesture.LONG_PRESS -> onHoldTarget
}
