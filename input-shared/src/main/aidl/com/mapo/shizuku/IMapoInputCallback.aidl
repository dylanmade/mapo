package com.mapo.shizuku;

import com.mapo.shizuku.RawAnalogEvent;
import com.mapo.shizuku.ShizukuServiceHealth;

/**
 * Callback from the Shizuku-uid UserService back into :app. `oneway` because
 * :app's handlers can be slow (Hilt dispatch, coroutine emit) and we don't
 * want to backpressure the /dev/input read thread.
 *
 * Transaction codes are pinned so adding a method later doesn't renumber
 * existing ones (preserves Brick B + B-aware service binary versions).
 */
interface IMapoInputCallback {
    oneway void onAnalogEvent(in RawAnalogEvent event) = 1;
    oneway void onDeviceAdded(int deviceId, String name, int capsBitmap) = 2;
    oneway void onDeviceRemoved(int deviceId) = 3;
    oneway void onServiceHealth(in ShizukuServiceHealth health) = 4;
    /**
     * Raw key event from a grabbed `/dev/input/event*` device. While EVIOCGRAB
     * is held the OS InputReader no longer dispatches these as Android
     * KeyEvents, so :app needs to receive them directly so its activator
     * engine + DEVICE_DEFAULT-passthrough logic can still run.
     *
     *  - `linuxKeyCode`: the raw `BTN_*` / `KEY_*` constant from
     *    `linux/input-event-codes.h` (e.g. `BTN_A = 0x130`).
     *  - `pressed`: true = press, false = release.
     *  - `timestampNs`: monotonic sensor / kernel timestamp.
     *
     * Fires once per state change (press / release), NOT continuously while
     * a button is held.
     */
    oneway void onRawKeyEvent(int linuxKeyCode, boolean pressed, long timestampNs) = 5;
}
