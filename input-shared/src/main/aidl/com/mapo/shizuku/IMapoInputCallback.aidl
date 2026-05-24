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
}
