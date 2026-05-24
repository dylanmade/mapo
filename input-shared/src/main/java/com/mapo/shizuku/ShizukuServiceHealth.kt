package com.mapo.shizuku

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Diagnostic snapshot of the UserService's internal state. Sent periodically
 * (and on demand) via [IMapoInputCallback.onServiceHealth]. Brick B emits
 * only the protocol version; Brick C+ fills in device count + last-event time.
 *
 * @param protocolVersion Mirrors [IMapoInputService.getProtocolVersion]. Useful
 *  in logs so a captured logcat snippet identifies which service binary was
 *  running at the time.
 * @param openedDeviceCount Number of `/dev/input/event*` file descriptors the
 *  service currently holds open (parked + active). Helps diagnose "service is
 *  alive but no events" cases.
 * @param lastEventMonotonicNs Monotonic nanos of the most recent `RawAnalogEvent`
 *  emitted. A long gap (with `openedDeviceCount > 0`) indicates the gamepad
 *  went idle or events stopped flowing.
 */
@Parcelize
data class ShizukuServiceHealth(
    val protocolVersion: Int,
    val openedDeviceCount: Int,
    val lastEventMonotonicNs: Long,
) : Parcelable
