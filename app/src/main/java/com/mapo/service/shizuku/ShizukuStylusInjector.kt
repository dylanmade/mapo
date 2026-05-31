package com.mapo.service.shizuku

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Brick C.4 follow-up — Mouse Region absolute positioning via virtual stylus.**
 *
 * Routes Mouse Region cursor targets through the Shizuku UserService's
 * virtual stylus uinput device (BTN_TOOL_PEN + ABS_X/Y). Sibling to
 * [ShizukuMouseInjector] (REL_X/Y mouse) and [ShizukuGamepadInjector]
 * (XInput axes).
 *
 * **Why a separate path:** the REL mouse and dispatchGesture finger-touch
 * paths are both *relative* on Wine's side in GameNative (verified 2026-05-30
 * on AYN Thor). Stylus tool-type events are Android's third option, and
 * Wine / DXVK / DInput paths historically treat them as absolute pen
 * positioning. This injector is the first half of testing whether
 * GameNative honors the stylus tool-type — if it does, Mouse Region gets
 * real absolute cursor positioning. If not, caller falls back to
 * dispatchGesture (with its known relative-cursor limitations).
 *
 * Returns `true` when the Shizuku stylus path was taken (caller does NOT
 * fall back). Returns `false` when Shizuku isn't ready, the binder threw,
 * or the virtual stylus failed to open on this ROM (SELinux-locked
 * `/dev/uinput`); caller runs its dispatchGesture fallback in that case.
 */
@Singleton
class ShizukuStylusInjector @Inject constructor(
    private val shizukuConnection: ShizukuConnection,
) {

    fun tryInject(xFrac: Float, yFrac: Float, displayW: Int, displayH: Int): Boolean {
        if (!shizukuConnection.isReadyFlow.value) return false
        val service = shizukuConnection.service.value ?: return false
        val x = (xFrac * displayW).toInt().coerceIn(0, displayW - 1)
        val y = (yFrac * displayH).toInt().coerceIn(0, displayH - 1)
        return try {
            service.injectStylusAbsolute(x, y, displayW, displayH)
        } catch (t: Throwable) {
            Log.w(TAG, "injectStylusAbsolute threw — falling back to dispatchGesture", t)
            false
        }
    }

    companion object {
        private const val TAG = "ShizukuStylusInjector"
    }
}
