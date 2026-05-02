package com.mapo.data.model

enum class TrackpadGesture(val displayName: String) {
    TAP("On Tap"),
    DOUBLE_TAP("On Double Tap"),
    LONG_PRESS("On Long Press");
}

/** Default RemapTarget for a gesture when no explicit mapping is set. */
fun TrackpadGesture.defaultTarget(): RemapTarget = when (this) {
    TrackpadGesture.TAP        -> RemapTarget.Mouse("MOUSE_LEFT")
    TrackpadGesture.DOUBLE_TAP -> RemapTarget.Unbound
    TrackpadGesture.LONG_PRESS -> RemapTarget.Mouse("MOUSE_RIGHT")
}

/** Resolves the effective target for a trackpad button's gesture, falling back to defaults. */
fun GridButton.gestureTarget(gesture: TrackpadGesture): RemapTarget {
    val raw = gestureMappings?.get(gesture.name)
    return if (raw != null) RemapTarget.decode(raw) else gesture.defaultTarget()
}
