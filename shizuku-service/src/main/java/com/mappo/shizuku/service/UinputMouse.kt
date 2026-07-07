package com.mappo.shizuku.service

import android.util.Log

/**
 * JNI wrapper around `/dev/uinput`-based virtual SOURCE_MOUSE InputDevice.
 *
 * Why this exists: SOURCE_MOUSE `MotionEvent`s injected via
 * `IInputManager.injectInputEvent` require an already-registered SOURCE_MOUSE
 * InputDevice with a pointer controller; without one, events deliver to
 * nothing visible. Real USB/Bluetooth mice register through the kernel's
 * uinput interface. We do the same from the shell-UID UserService process.
 * The kernel surfaces the virtual device through `/dev/input/eventN`,
 * Android's InputReader picks it up and registers a proper InputDevice +
 * pointer controller, and the OS renders the cursor for us.
 *
 * **Why this works without root**: shell UID (2000), which Shizuku's
 * UserService runs as, has `/dev/uinput` access on AOSP-derived ROMs.
 * SELinux-locked vendor builds occasionally block it; if `open()` returns
 * `false`, caller should fall back to the synthetic-touch dispatchGesture
 * path. Verified on AYN Thor 2026-05-25 (Android 13 stock).
 *
 * **Lifecycle**: lazy-open on first `move()` call; explicit `close()` from
 * the service's `destroy()` hook. The kernel cleans up the virtual device
 * automatically when the fd closes (e.g. service process exit), so an
 * abrupt SIGKILL won't leak the device.
 */
object UinputMouse {

    private const val TAG = "UinputMouse"

    init {
        try {
            System.loadLibrary("mappo_uinput")
        } catch (t: Throwable) {
            Log.e(TAG, "loadLibrary mappo_uinput failed", t)
        }
    }

    @Volatile
    private var fd: Int = -1

    /** True iff we successfully opened uinput and created a virtual device. */
    val isReady: Boolean get() = fd >= 0

    /**
     * Open `/dev/uinput` and create the virtual SOURCE_MOUSE device.
     * Idempotent — returns true if already open. Returns false on any
     * failure (caller falls back to dispatchGesture).
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
        Log.i(TAG, "virtual mouse opened, fd=$opened")
        return true
    }

    /**
     * Dispatch a relative motion event. Deltas are integer pixels — caller
     * should accumulate sub-pixel residuals before calling. Zero deltas are
     * skipped on the native side but still emit a SYN_REPORT, so calling
     * this every velocity tick with `(0,0)` is wasted work but not harmful.
     */
    fun move(dx: Int, dy: Int) {
        val f = fd
        if (f < 0) return
        try {
            nativeMove(f, dx, dy)
        } catch (t: Throwable) {
            Log.w(TAG, "nativeMove threw — closing device", t)
            close()
        }
    }

    /**
     * Press or release a mouse button. `btnCode` must be one of the
     * `LinuxInputConstants.BTN_*` values (passed as ints across AIDL).
     */
    fun button(btnCode: Int, pressed: Boolean) {
        val f = fd
        if (f < 0) return
        try {
            nativeButton(f, btnCode, if (pressed) 1 else 0)
        } catch (t: Throwable) {
            Log.w(TAG, "nativeButton threw", t)
        }
    }

    /**
     * Emit a scroll-wheel event. `dx`/`dy` are integer notch counts (1 =
     * one wheel detent up/right, -1 = down/left). Most apps respond to
     * `dy`; horizontal scroll is supported but used less often.
     */
    fun scroll(dx: Int, dy: Int) {
        val f = fd
        if (f < 0) return
        try {
            nativeScroll(f, dx, dy)
        } catch (t: Throwable) {
            Log.w(TAG, "nativeScroll threw", t)
        }
    }

    /**
     * Destroy the virtual device and close the fd. Safe to call repeatedly.
     * The kernel cleans up automatically on process exit; this is for the
     * service's `destroy()` path so we tear down cleanly on Shizuku unbind.
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
    @JvmStatic private external fun nativeMove(fd: Int, dx: Int, dy: Int)
    @JvmStatic private external fun nativeButton(fd: Int, btnCode: Int, pressed: Int)
    @JvmStatic private external fun nativeScroll(fd: Int, dx: Int, dy: Int)
    @JvmStatic private external fun nativeClose(fd: Int)
}
