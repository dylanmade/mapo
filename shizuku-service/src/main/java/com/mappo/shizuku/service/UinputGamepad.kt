package com.mappo.shizuku.service

import android.util.Log

/**
 * JNI wrapper around `/dev/uinput`-based virtual XInput-style gamepad InputDevice.
 *
 * Companion to [UinputMouse]. Both devices live in the same `libmappo_uinput.so`;
 * this object exposes the gamepad-side JNI surface. The virtual gamepad is what
 * Joystick Move, Mouse Region, and NONE-mode counter-injection use to push real
 * analog stick / trigger / dpad values to the foreground app.
 *
 * **Device identity**: Microsoft + Xbox 360 wired controller IDs (0x045E / 0x028E)
 * so game compatibility heuristics that gate on vendor/product accept it. The
 * device *name* is "Mappo Virtual Gamepad" — identifiable in `/proc/bus/input/devices`
 * and `getevent -l` for debugging.
 *
 * **Axis layout** (xpad / Xbox 360 wired controller convention, matching the
 * vendor/product pair we declare — see uinput_gamepad.c for the rationale):
 *  - Left stick:  ABS_X / ABS_Y     (-32768..32767, signed int16)
 *  - Right stick: ABS_RX / ABS_RY   (-32768..32767, signed int16)
 *  - Left trigger:  ABS_Z           (0..255 unsigned)
 *  - Right trigger: ABS_RZ          (0..255 unsigned)
 *  - D-pad hat:   ABS_HAT0X / ABS_HAT0Y (-1, 0, 1)
 *
 * NOTE: this is the *emit* convention for our virtual Xbox 360 identity, not
 * the *read* convention for the AYN Thor's physical controller (which reports
 * its right stick on ABS_Z/ABS_RZ — see `reference_thor_axis_convention.md`).
 *
 * **Lifecycle**: lazy-open on first `writeAxes()` call (via the caller's
 * `open()` invocation); explicit `close()` from the service's `destroy()` hook.
 * Kernel cleans the device up automatically when the fd closes (process exit),
 * so an abrupt SIGKILL won't leak the device.
 *
 * **Why this works without root**: shell UID (2000), which Shizuku's UserService
 * runs as, has `/dev/uinput` access on AOSP-derived ROMs (verified on AYN Thor
 * 2026-05-25 for the mouse device; gamepad uses the same path). SELinux-locked
 * vendor builds occasionally block it; if `open()` returns false, analog modes
 * that depend on the gamepad become inert (caller should surface degradation).
 */
object UinputGamepad {

    private const val TAG = "UinputGamepad"

    init {
        try {
            System.loadLibrary("mappo_uinput")
        } catch (t: Throwable) {
            Log.e(TAG, "loadLibrary mappo_uinput failed", t)
        }
    }

    @Volatile
    private var fd: Int = -1

    /** True iff we successfully opened uinput and created the virtual gamepad. */
    val isReady: Boolean get() = fd >= 0

    /**
     * Open `/dev/uinput` and create the virtual gamepad. Idempotent — returns
     * true if already open. Returns false on any failure (analog modes
     * depending on the gamepad become inert and should surface a user-visible
     * degradation banner).
     */
    @Synchronized
    fun open(): Boolean {
        if (fd >= 0) return true
        val opened = try {
            nativeOpen()
        } catch (t: Throwable) {
            Log.w(TAG, "nativeOpen threw", t)
            -1
        }
        fd = opened
        if (opened < 0) {
            Log.w(TAG, "nativeOpen failed — uinput access may be SELinux-blocked on this ROM")
            return false
        }
        Log.i(TAG, "virtual gamepad opened, fd=$opened")
        return true
    }

    /**
     * Batch-write all eight analog axes followed by SYN_REPORT. The runtime
     * calls this once per AnalogEvent that targets the gamepad, passing the
     * current full state (caller-maintained — no in-singleton state cache).
     *
     * Value ranges (clamped by the kernel if out-of-range, but callers should
     * clamp explicitly for predictability):
     *  - `leftX/Y`, `rightX/Y`: -32768..32767 (signed int16)
     *  - `leftTrigger`, `rightTrigger`: 0..255
     *  - `hatX`, `hatY`: -1, 0, 1
     */
    fun writeAxes(
        leftX: Int, leftY: Int,
        rightX: Int, rightY: Int,
        leftTrigger: Int, rightTrigger: Int,
        hatX: Int, hatY: Int,
    ) {
        val f = fd
        if (f < 0) return
        try {
            nativeWriteAxes(f, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger, hatX, hatY)
        } catch (t: Throwable) {
            Log.w(TAG, "nativeWriteAxes threw — closing device", t)
            close()
        }
    }

    /**
     * Press or release a single gamepad button. `btnCode` is the standard
     * linux/input.h `BTN_*` constant ([Buttons] enumerates the supported set).
     */
    fun writeButton(btnCode: Int, pressed: Boolean) {
        val f = fd
        if (f < 0) return
        try {
            nativeWriteButton(f, btnCode, if (pressed) 1 else 0)
        } catch (t: Throwable) {
            Log.w(TAG, "nativeWriteButton threw", t)
        }
    }

    /**
     * Destroy the virtual device + close the fd. Safe to call repeatedly;
     * idempotent on already-closed state. Kernel auto-cleans on process exit
     * — this is the explicit teardown path for the service's `destroy()`.
     */
    @Synchronized
    fun close() {
        val f = fd
        if (f >= 0) {
            try {
                nativeClose(f)
            } catch (t: Throwable) {
                Log.w(TAG, "nativeClose threw", t)
            }
            fd = -1
        }
    }

    @JvmStatic private external fun nativeOpen(): Int
    @JvmStatic private external fun nativeWriteAxes(
        fd: Int,
        lx: Int, ly: Int, rx: Int, ry: Int,
        lt: Int, rt: Int, hatX: Int, hatY: Int,
    )
    @JvmStatic private external fun nativeWriteButton(fd: Int, btnCode: Int, pressed: Int)
    @JvmStatic private external fun nativeClose(fd: Int)

    /**
     * Standard linux/input.h `BTN_*` constants for the gamepad's declared
     * button set. Kotlin-side mirror so callers don't have to import the
     * native header.
     */
    object Buttons {
        const val A = 0x130        // BTN_A
        const val B = 0x131        // BTN_B
        const val X = 0x133        // BTN_X
        const val Y = 0x134        // BTN_Y
        const val LB = 0x136       // BTN_TL
        const val RB = 0x137       // BTN_TR
        const val LT_DIGITAL = 0x138  // BTN_TL2 (digital threshold; analog is ABS_BRAKE)
        const val RT_DIGITAL = 0x139  // BTN_TR2
        const val SELECT = 0x13A   // BTN_SELECT
        const val START = 0x13B    // BTN_START
        const val MODE = 0x13C     // BTN_MODE (Xbox guide)
        const val THUMBL = 0x13D   // BTN_THUMBL (left stick click)
        const val THUMBR = 0x13E   // BTN_THUMBR (right stick click)
    }
}
