package com.mapo.shizuku.service

import android.util.Log

/**
 * JNI wrapper around `ioctl(fd, EVIOCGRAB, ...)` for `/dev/input/event*`
 * devices Mapo's UserService has open.
 *
 * **EVIOCGRAB** is the Linux kernel call for taking exclusive access of an
 * evdev device — while a process holds the grab, the kernel routes the
 * device's events to that process's fd ONLY. Android's system InputReader,
 * which normally reads every input device and dispatches events as
 * MotionEvents / KeyEvents to the foreground app, stops receiving events
 * from a grabbed device.
 *
 * That's how Mapo prevents the OS-level dual-dispatch when its virtual
 * gamepad and a physical controller would otherwise both reach the game:
 * grab the physical, let only Mapo's virtual gamepad through. Mapo
 * forwards what it needs back to the game via the virtual gamepad
 * (analog passthrough) or via Shizuku key inject (digital passthrough).
 *
 * Lifecycle: a single fd may be grabbed and released repeatedly. The
 * kernel cleans up automatically when the fd closes (process exit), so a
 * SIGKILL on the UserService won't leave the device permanently grabbed.
 *
 * Lives in the same `libmapo_uinput.so` as the uinput devices — separate
 * concerns (read-side ioctls vs. write-side device creation) but the
 * library load is shared.
 */
object EvdevGrab {

    private const val TAG = "EvdevGrab"

    init {
        try {
            System.loadLibrary("mapo_uinput")
        } catch (t: Throwable) {
            Log.e(TAG, "loadLibrary mapo_uinput failed", t)
        }
    }

    /**
     * Acquire or release exclusive access for [fd]. Returns true on success.
     * Logs the kernel errno on failure (typically EBUSY if some other
     * process already grabbed the device, or ENOTTY if [fd] doesn't point
     * at an evdev device).
     */
    fun setGrabbed(fd: Int, grabbed: Boolean): Boolean {
        if (fd < 0) return false
        return try {
            nativeGrab(fd, if (grabbed) 1 else 0) == 0
        } catch (t: Throwable) {
            Log.w(TAG, "nativeGrab(fd=$fd, grabbed=$grabbed) threw", t)
            false
        }
    }

    @JvmStatic
    private external fun nativeGrab(fd: Int, grab: Int): Int
}
