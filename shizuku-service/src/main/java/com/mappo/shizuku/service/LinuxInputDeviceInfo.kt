package com.mappo.shizuku.service

import android.util.Log
import java.io.File

/**
 * Metadata lookup for `/dev/input/eventN` devices via sysfs.
 *
 * Single helper today: [readDeviceName], used by the reader thread to filter
 * out Mappo's own virtual uinput devices ([UinputMouse], [UinputGamepad]).
 * Without that filter, every axis write to the virtual gamepad echoes back
 * through the matching `/dev/input/eventN`, the reader emits it as a
 * RawAnalogEvent, the evaluator dispatches it through Joystick Move mode,
 * which writes to the virtual gamepad again → infinite loop with the console
 * fans pegged (verified 2026-05-28 on AYN Thor).
 *
 * Implementation reads `/sys/class/input/eventN/device/name`, a public sysfs
 * read available without ioctls or hidden-API access. The corresponding C
 * EVIOCGNAME ioctl would also work but would require getting the int fd out
 * of FileDescriptor — Os.open's return type — which is a hidden-API call on
 * Android. Sysfs is simpler and equally reliable.
 */
object LinuxInputDeviceInfo {

    private const val TAG = "LinuxInputDeviceInfo"

    /**
     * Read the device name for the given `/dev/input/eventN` path. Returns
     * null if the sysfs entry isn't readable (e.g. device just disappeared,
     * SELinux block) — caller should err on the conservative side
     * (don't-filter) when the name is unknown.
     */
    fun readDeviceName(devicePath: String): String? {
        // /dev/input/event5 → /sys/class/input/event5/device/name
        val eventName = devicePath.substringAfterLast('/')
        val sysfsPath = "/sys/class/input/$eventName/device/name"
        return try {
            File(sysfsPath).takeIf { it.exists() }?.readText()?.trim()
        } catch (t: Throwable) {
            Log.w(TAG, "readDeviceName($devicePath) failed", t)
            null
        }
    }
}
