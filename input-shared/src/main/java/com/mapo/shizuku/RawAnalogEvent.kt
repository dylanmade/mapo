package com.mapo.shizuku

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * One axis reading from the Shizuku-uid UserService, sent over AIDL to :app's
 * [com.mapo.service.shizuku.ShizukuMotionStream] which converts to `AnalogReading`
 * and hands to `InputEvaluator.handleAnalogReadings`.
 *
 * @param sourceOrdinal Ordinal of [com.mapo.data.model.steam.InputSource] in :app.
 *  Mirrored here so :shizuku-service doesn't need the Steam schema on its classpath.
 *  WARNING: this couples to the enum order. If `InputSource` ever reorders, the
 *  service binary must be rebuilt.
 * @param x Primary axis value, normalized to [-1.0, 1.0] (or [0.0, 1.0] for
 *  uni-directional sources like triggers).
 * @param y Secondary axis value when meaningful (joysticks), 0f otherwise.
 * @param timestampNs Monotonic nanos from `SystemClock.elapsedRealtimeNanos()` —
 *  set on the service side at parse time so :app's batching window doesn't
 *  obscure the original event time.
 */
@Parcelize
data class RawAnalogEvent(
    val sourceOrdinal: Int,
    val x: Float,
    val y: Float,
    val timestampNs: Long,
) : Parcelable
