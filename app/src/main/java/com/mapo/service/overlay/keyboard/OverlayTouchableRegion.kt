package com.mapo.service.overlay.keyboard

import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.roundToInt

/**
 * Per-overlay registry of "this rect inside the overlay should receive touches; everything
 * else passes through to the foreground app underneath."
 *
 * **Why this exists.** An Android window claims every touch inside its bounds before the
 * view hierarchy hit-tests. Compose returning "not handled" from an empty region is too
 * late — Android has already committed the event to this window and won't replay it to
 * whatever's below. The only way to make a sub-rect of a window genuinely passthrough is
 * to tell WindowManager up-front, via `ViewTreeObserver.OnComputeInternalInsetsListener`
 * + `TOUCHABLE_INSETS_REGION`, which rects are touchable.
 *
 * Wired at the WindowManager layer by [KeyboardOverlayManager]. Compose children opt in
 * via [overlayTouchable], which is a no-op in activity mode ([LocalOverlayTouchableRegion]
 * is null there).
 */
class OverlayTouchableRegion {
    // Plain map, not Snapshot state: writes happen during onGloballyPositioned and are
    // consumed by the View layer's inset-compute pass, not by Compose recomposition.
    private val rects = mutableMapOf<Any, Rect>()
    private var onChanged: () -> Unit = {}

    internal fun setOnChanged(listener: () -> Unit) {
        onChanged = listener
    }

    fun put(key: Any, rect: Rect) {
        val existing = rects[key]
        if (existing != null && existing == rect) return
        rects[key] = Rect(rect)
        onChanged()
    }

    fun remove(key: Any) {
        if (rects.remove(key) != null) onChanged()
    }

    internal fun snapshot(): List<Rect> = rects.values.toList()
}

val LocalOverlayTouchableRegion = staticCompositionLocalOf<OverlayTouchableRegion?> { null }

/**
 * Marks the wrapped element as a touch-target inside an overlay window. The element's
 * bounds (in window coordinates) join the union of rects that the overlay's WindowManager
 * touchable region accepts; everything outside that union falls through to the foreground.
 *
 * No-op when no [OverlayTouchableRegion] is provided (activity mode), so callers can apply
 * this modifier unconditionally without branching on host mode.
 */
@Composable
fun Modifier.overlayTouchable(): Modifier {
    val region = LocalOverlayTouchableRegion.current ?: return this
    val key = remember { Any() }
    DisposableEffect(region, key) {
        onDispose { region.remove(key) }
    }
    return this.onGloballyPositioned { coords ->
        val b = coords.boundsInWindow()
        region.put(
            key,
            Rect(
                b.left.roundToInt(),
                b.top.roundToInt(),
                b.right.roundToInt(),
                b.bottom.roundToInt(),
            ),
        )
    }
}
