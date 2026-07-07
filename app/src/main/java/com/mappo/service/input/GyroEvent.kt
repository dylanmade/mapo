package com.mappo.service.input

/**
 * One sample from the device's gyroscope. Distinct shape from [AnalogEvent]
 * because the sensor reports *angular velocity* on three axes (rad/s), not a
 * normalized 2D deflection. Mode runtimes downstream interpret these values
 * per their semantics (Gyro to Mouse uses them directly as cursor velocity;
 * Gyro to Joystick Camera maps yaw/pitch to XInput right-stick deflection;
 * Gyro to Joystick Deflection integrates over time for orientation).
 *
 * **Axis convention** mirrors Android's `Sensor.TYPE_GYROSCOPE` for a device
 * held in its default orientation:
 *  - `xRadPerSec` — rotation rate around the device's X axis (pitching).
 *  - `yRadPerSec` — rotation rate around the device's Y axis (yawing).
 *  - `zRadPerSec` — rotation rate around the device's Z axis (rolling).
 *
 * Screen-orientation-aware remapping (landscape vs. portrait, dual-screen
 * Thor bottom-screen quirks) is the mode handler's responsibility — this
 * record is the raw sensor reading.
 *
 * **Sign:** positive values follow the right-hand rule around each axis.
 *
 * **Timestamp** is the sensor's monotonic nanosecond clock (`SensorEvent.timestamp`),
 * NOT `SystemClock.uptimeMillis`. Use it for delta-time calculations; don't
 * mix with wall-clock or uptime sources.
 */
data class GyroEvent(
    val xRadPerSec: Float,
    val yRadPerSec: Float,
    val zRadPerSec: Float,
    val timestampNs: Long,
    /**
     * Cached device orientation at the moment of this gyro reading, sampled
     * from a parallel `TYPE_GAME_ROTATION_VECTOR` subscription managed by
     * [GyroSensorStream]. Both in radians. Default to 0 so callers that
     * don't need orientation (rate-based modes) can ignore them, and so the
     * older 4-arg constructor stays compatible.
     *
     * Rate-based modes (Gyro to Mouse / Gyro to Joystick Camera) read
     * `xRadPerSec` / `yRadPerSec`. Tilt-based modes (Gyro to Joystick
     * Deflection) read `rollRad` / `pitchRad` and subtract a captured
     * reference orientation to compute tilt-from-rest.
     *
     * On devices without a rotation-vector sensor the fields stay at 0,
     * making tilt-based modes effectively inert (acceptable degradation;
     * the picker UI can surface this once Brick D.6 lands).
     */
    val rollRad: Float = 0f,
    val pitchRad: Float = 0f,
)
