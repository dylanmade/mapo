package com.mappo.ui.screen.home

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "HomeBackdrop"

/**
 * Process-wide holder for the home frame's frozen, blurred screenshot backdrop.
 *
 * Written by the accessibility service (Select+A chord captures BEFORE launching the activity,
 * so the shot never contains Mappo's own window) and by MainActivity (launcher-icon path, where
 * the capture races our fading-in window — see [isFresh]). Read by `HandheldFrame`.
 *
 * The blur is approximated with cascaded bilinear downscales instead of any window-blur or
 * RenderEffect API: the device compositor's blur path renders cycling vignette bands at the
 * screen edges (observed on-device 2026-07-06, both FLAG_BLUR_BEHIND and background blur), so
 * we keep the entire effect inside our own bitmap pipeline.
 */
object HomeBackdrop {

    /** How recent a capture attempt must be for [isFresh] — long enough to cover the gap
     *  between the chord's pre-launch capture and the activity's onNewIntent/onCreate. */
    private const val FRESH_WINDOW_MS = 1500L

    private val _bitmap = MutableStateFlow<ImageBitmap?>(null)
    val bitmap: StateFlow<ImageBitmap?> get() = _bitmap

    @Volatile
    private var capturedAtMs = 0L

    /**
     * True when a capture attempt landed within the last [FRESH_WINDOW_MS]. MainActivity uses
     * this to avoid replacing the chord path's clean pre-launch capture with a self-capture
     * that may include Mappo's own window.
     */
    fun isFresh(): Boolean = SystemClock.uptimeMillis() - capturedAtMs < FRESH_WINDOW_MS

    /**
     * Blur (cascaded ¼ → ¹⁄₁₂ downscale; bilinear upsampling at draw time smooths the rest)
     * and publish the given display screenshot; null (capture failed / unsupported) clears the
     * backdrop so the frame falls back to the live sharp background. Takes ownership of
     * [screenshot] and recycles it.
     */
    fun setFrom(screenshot: Bitmap?) {
        capturedAtMs = SystemClock.uptimeMillis()
        if (screenshot == null) {
            _bitmap.value = null
            return
        }
        val blurred = try {
            val quarter = Bitmap.createScaledBitmap(
                screenshot,
                (screenshot.width / 4).coerceAtLeast(1),
                (screenshot.height / 4).coerceAtLeast(1),
                true,
            )
            val twelfth = Bitmap.createScaledBitmap(
                quarter,
                (screenshot.width / 12).coerceAtLeast(1),
                (screenshot.height / 12).coerceAtLeast(1),
                true,
            )
            if (twelfth !== quarter) quarter.recycle()
            twelfth
        } catch (e: Exception) {
            Log.w(TAG, "backdrop downscale failed", e)
            null
        } finally {
            if (!screenshot.isRecycled) screenshot.recycle()
        }
        Log.d(TAG, "backdrop set: ${blurred?.width}x${blurred?.height}")
        _bitmap.value = blurred?.asImageBitmap()
    }
}
