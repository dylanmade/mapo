package com.mapo.service.input

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
)
