package com.mapo.shizuku.service

import android.util.Log

/**
 * JNI wrapper around `/dev/uinput`-based virtual stylus InputDevice. Sibling
 * to [UinputMouse] (REL_X/Y mouse) and [UinputGamepad] (XInput gamepad); all
 * three share the `mapo_uinput.so` build.
 *
 * **Why a stylus device for Mouse Region.** The REL mouse and synthetic
 * dispatchGesture finger-touch paths are both *relative* on Wine's side
 * (verified on AYN Thor 2026-05-30 in GameNative — see Brick C.4 history).
 * Stylus tool-type events are the third option Android's input pipeline
 * exposes, and Wine / DXVK / DInput paths historically treat stylus events
 * as absolute pen positioning. If GameNative honors that here, Mouse Region
 * gets real absolute cursor positioning with no anchor / pin gymnastics.
 *
 * **Lifecycle.** Lazy-open on first [moveAbsolute] call via the service; the
 * caller (`MapoInputUserService.injectStylusAbsolute`) passes the live
 * display dimensions so the device's ABS_X/Y range matches the screen 1:1.
 * Stays open for the rest of the service process lifetime — the kernel
 * destroys the virtual device automatically when the fd closes on process
 * exit, so a SIGKILL won't leak the device.
 *
 * **SELinux fallback.** Some vendor ROMs lock down `/dev/uinput` even for
 * the shell UID. On those, [open] returns false; caller falls back to the
 * dispatchGesture absolute-touch path (with all its known relative-cursor
 * limitations).
 */
object UinputStylus {

    private const val TAG = "UinputStylus"

    init {
        try {
            System.loadLibrary("mapo_uinput")
        } catch (t: Throwable) {
            Log.e(TAG, "loadLibrary mapo_uinput failed", t)
        }
    }

    @Volatile
    private var fd: Int = -1

    @Volatile
    private var devMaxX: Int = 0

    @Volatile
    private var devMaxY: Int = 0

    /** True iff the virtual stylus device is currently open. */
    val isReady: Boolean get() = fd >= 0

    /** Current device ABS_X max. Useful when the caller needs to know how to
     *  scale fractional coordinates (Mouse Region passes the fraction-to-px
     *  itself, but defensive callers can re-clamp). */
    val maxX: Int get() = devMaxX

    /** Current device ABS_Y max. */
    val maxY: Int get() = devMaxY

    /**
     * Open `/dev/uinput` and create the virtual stylus with the given absmax.
     * Idempotent — returns true if already open with the same dimensions.
     * If dimensions changed (e.g. display rotation), tears down and reopens.
     */
    @Synchronized
    fun open(maxX: Int, maxY: Int): Boolean {
        if (fd >= 0 && devMaxX == maxX && devMaxY == maxY) return true
        if (fd >= 0) {
            // Dimensions changed → recreate with the new bounds.
            Log.i(TAG, "absmax changed ${devMaxX}x${devMaxY} → ${maxX}x${maxY}; recreating")
            closeInternal()
        }
        val opened = try {
            nativeOpen(maxX, maxY)
        } catch (t: Throwable) {
            Log.w(TAG, "nativeOpen threw", t)
            -1
        }
        if (opened < 0) {
            Log.w(TAG, "nativeOpen failed — uinput access may be SELinux-blocked on this ROM")
            return false
        }
        fd = opened
        devMaxX = maxX
        devMaxY = maxY
        Log.i(TAG, "virtual stylus opened, fd=$opened, abs ${maxX}x${maxY}")
        return true
    }

    /**
     * Move the pen to absolute position `(x, y)`. Caller should clamp to
     * `[0, maxX-1] × [0, maxY-1]`; out-of-range writes are silently capped
     * by the kernel but a defensive clamp here keeps Mapo's bookkeeping
     * predictable.
     */
    fun moveAbsolute(x: Int, y: Int) {
        val f = fd
        if (f < 0) return
        try {
            nativeMoveAbsolute(f, x, y)
        } catch (t: Throwable) {
            Log.w(TAG, "nativeMoveAbsolute threw — closing device", t)
            close()
        }
    }

    /**
     * Toggle the pen-on-surface bit (BTN_TOUCH). Mouse Region's hover-only
     * usage doesn't call this; reserved for a later brick that wants
     * stick-driven absolute taps.
     */
    fun setContact(pressed: Boolean) {
        val f = fd
        if (f < 0) return
        try {
            nativeSetContact(f, if (pressed) 1 else 0)
        } catch (t: Throwable) {
            Log.w(TAG, "nativeSetContact threw", t)
        }
    }

    /** Destroy the device and close the fd. Safe to call repeatedly. */
    @Synchronized
    fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        val f = fd
        if (f >= 0) {
            try {
                nativeClose(f)
            } catch (t: Throwable) {
                Log.w(TAG, "nativeClose threw", t)
            }
            fd = -1
            devMaxX = 0
            devMaxY = 0
        }
    }

    @JvmStatic private external fun nativeOpen(maxX: Int, maxY: Int): Int
    @JvmStatic private external fun nativeMoveAbsolute(fd: Int, x: Int, y: Int)
    @JvmStatic private external fun nativeSetContact(fd: Int, pressed: Int)
    @JvmStatic private external fun nativeClose(fd: Int)
}
