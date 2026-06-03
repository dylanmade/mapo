package com.mapo.service.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.mapo.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * **Brick D.1** — gyroscope sensor reader. Wraps Android's
 * [SensorManager.registerListener] for [Sensor.TYPE_GYROSCOPE] and exposes a
 * [SharedFlow] of [GyroEvent]s for downstream mode runtimes.
 *
 * Lifecycle is explicit ([start] / [stop]) — the coordinator (Brick D.2)
 * drives it off the gating predicate so the sensor listener is only
 * registered when at least one GYRO source is configured for a real (non-
 * stub) gyro mode in the active set or layer. Listener-not-registered =
 * zero battery cost, which is the whole point of gating this off.
 *
 * **No-gyro hardware.** Anbernic / Retroid budget handhelds may ship without
 * a gyroscope. [SensorManager.getDefaultSensor] returns null in that case;
 * [start] returns `false` and emits no events. Caller should surface a
 * one-time warning ("This device has no gyroscope — gyro bindings will be
 * inert") through whatever UX channel makes sense.
 *
 * **Sampling rate** — uses [SensorManager.SENSOR_DELAY_GAME] (~20 ms) by
 * default. That's the conventional "good enough for input" choice; FASTEST
 * is platform-dependent and not meaningfully better for sub-20 ms mouse
 * cadence. Settable via [start]'s parameter if a future mode needs finer
 * resolution.
 */
@Singleton
class GyroSensorStream @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val inputEvaluator: InputEvaluator,
) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val gyroSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /**
     * Companion orientation sensor for tilt-based gyro modes (Gyro to Joystick
     * Deflection). `TYPE_GAME_ROTATION_VECTOR` is gyro+accel fused — no
     * compass, so no indoor magnetometer flakiness — and drift-free. On
     * devices without it, we fall back to `TYPE_ROTATION_VECTOR` (which adds
     * the compass) so absolute "yaw" is at least available. On devices with
     * neither, tilt fields stay at 0 and tilt-based modes degrade to a
     * permanently-neutral stick (acceptable inertness; the picker should
     * eventually warn the user).
     */
    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** True iff the device has a gyroscope sensor at all. */
    val hasGyro: Boolean = gyroSensor != null

    /** True iff the device has an orientation sensor for tilt-based modes. */
    val hasOrientation: Boolean = rotationSensor != null

    // Cached device orientation in radians, updated on every rotation-vector
    // event. The gyro reader stamps these onto each [GyroEvent] so tilt-based
    // modes don't need their own sensor subscription. Volatile because the
    // sensor callback runs on a hardware thread and the gyro callback (writer
    // → reader from this perspective) may run on a different one.
    @Volatile private var cachedRollRad: Float = 0f
    @Volatile private var cachedPitchRad: Float = 0f

    private val _events = MutableSharedFlow<GyroEvent>(
        replay = 0,
        // 64-deep buffer with DROP_OLDEST. The sensor fires at ~50 Hz on
        // SENSOR_DELAY_GAME (20 ms cadence). A 64-event backlog is ~1.3 s of
        // buffering — enough to ride out a brief consumer stall without
        // either dropping events or back-pressuring the sensor callback
        // (which runs on a binder-ish thread).
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Subscribe to receive every gyroscope reading while [isRunning] is true. */
    val events: SharedFlow<GyroEvent> = _events.asSharedFlow()

    private val running = AtomicBoolean(false)

    /** True iff the sensor listener is currently registered with the OS. */
    val isRunning: Boolean get() = running.get()

    init {
        // Forward every gyro event into the evaluator. Collector runs on the
        // ApplicationScope so a slow [InputEvaluator] pass can't backpressure
        // the sensor pipeline (the sensor callback already uses tryEmit on a
        // DROP_OLDEST buffer for the same reason). Defensive try/catch around
        // the dispatch — a misbehaving gyro mode handler shouldn't kill the
        // collector loop and silently stop forwarding events.
        scope.launch {
            try {
                events.collect { event ->
                    try {
                        inputEvaluator.handleGyroReading(event)
                    } catch (t: Throwable) {
                        Log.w(TAG, "handleGyroReading threw on $event", t)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "gyro events collector crashed", t)
            }
        }
    }

    /**
     * Register the sensor listener. Returns `true` on success, `false` when
     * the device has no gyro or the OS rejected the registration (rare).
     * Idempotent — repeat calls while already running are no-ops.
     */
    @Synchronized
    fun start(samplingPeriodHint: Int = SensorManager.SENSOR_DELAY_GAME): Boolean {
        if (running.get()) return true
        val mgr = sensorManager ?: run {
            Log.w(TAG, "no SensorManager — gyro disabled")
            return false
        }
        val sensor = gyroSensor ?: run {
            Log.w(TAG, "no TYPE_GYROSCOPE sensor on this device — gyro modes will be inert")
            return false
        }
        val ok = mgr.registerListener(this, sensor, samplingPeriodHint)
        if (!ok) {
            Log.w(TAG, "registerListener returned false — gyro disabled")
            return false
        }
        // Register the rotation-vector companion alongside the gyro. Not
        // fatal if it fails — gyro events still fire with rollRad/pitchRad
        // stuck at their last cached value (initially 0), so rate-based
        // modes are unaffected and tilt-based modes just stay neutral.
        rotationSensor?.let { rot ->
            val rotOk = mgr.registerListener(this, rot, samplingPeriodHint)
            if (rotOk) {
                Log.i(TAG, "rotation-vector listener registered (sensor=${rot.name})")
            } else {
                Log.w(TAG, "rotation-vector registerListener returned false — tilt modes inert")
            }
        } ?: Log.i(TAG, "no rotation-vector sensor — tilt modes will be inert")
        running.set(true)
        Log.i(TAG, "gyro listener registered (sensor=${sensor.name}, delay=$samplingPeriodHint)")
        return true
    }

    /** Unregister the listener. Idempotent. */
    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // SensorManager.unregisterListener(this) covers all sensors this
        // listener is registered for — drops both gyro + rotation-vector
        // with one call.
        sensorManager?.unregisterListener(this)
        Log.i(TAG, "gyro listener unregistered")
    }

    // ── SensorEventListener ─────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> handleGyroEvent(event)
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> updateOrientationCache(event)
            else -> Unit
        }
    }

    private fun handleGyroEvent(event: SensorEvent) {
        val v = event.values
        if (v.size < 3) return
        // tryEmit because SensorEventListener callbacks run on a hardware
        // thread; suspending here would block the sensor pipeline.
        val emitted = _events.tryEmit(
            GyroEvent(
                xRadPerSec = v[0],
                yRadPerSec = v[1],
                zRadPerSec = v[2],
                timestampNs = event.timestamp,
                rollRad = cachedRollRad,
                pitchRad = cachedPitchRad,
            )
        )
        if (!emitted && BuildLogVerbose) {
            // Should be rare with the 64-deep DROP_OLDEST buffer; logged
            // here so we'd catch a backpressure regression in testing.
            Log.v(TAG, "tryEmit dropped (buffer full / no collector)")
        }
    }

    /**
     * Convert the rotation-vector sensor's quaternion → roll + pitch in
     * radians using the standard Tait-Bryan ZYX intrinsic formula. Roll is
     * rotation around the device's X axis, pitch around its Y axis.
     *
     * The fourth value (w) is optional in some Android versions of the
     * sensor's value array — `event.values[3]` may not be present. We
     * reconstruct it from the unit-quaternion constraint when missing.
     *
     * Yaw is computable too but not cached because no current mode needs it
     * — tilt-based Deflection only uses roll + pitch ("tilt to lean").
     */
    private fun updateOrientationCache(event: SensorEvent) {
        val rp = quaternionToRollPitch(event.values) ?: return
        cachedRollRad = rp.first
        cachedPitchRad = rp.second
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Logged but not gated on — even LOW_ACCURACY gyro is usable for
        // input modes. Calibration improves while running.
        Log.d(TAG, "gyro accuracy changed: $accuracy")
    }

    companion object {
        private const val TAG = "GyroSensorStream"
        // Compile-time flag; flip to true when chasing a buffer-overflow
        // regression. Keeps the verbose drop log quiet in normal operation.
        private const val BuildLogVerbose = false

        /**
         * Pure quaternion → (roll, pitch) conversion via the standard
         * Tait-Bryan ZYX intrinsic formula. Roll is rotation around the
         * device's X axis, pitch around its Y axis. Returns null if [values]
         * is missing the minimum three quaternion components.
         *
         * The fourth value (w) is optional in some Android versions of the
         * sensor's value array — `values[3]` may not be present. We
         * reconstruct it from the unit-quaternion constraint when missing.
         *
         * Extracted from [updateOrientationCache] so the math is unit-testable
         * without constructing a [android.hardware.SensorEvent] (which is
         * package-private to construct outside the framework).
         */
        @androidx.annotation.VisibleForTesting
        internal fun quaternionToRollPitch(values: FloatArray): Pair<Float, Float>? {
            if (values.size < 3) return null
            val qx = values[0]
            val qy = values[1]
            val qz = values[2]
            val qw = if (values.size >= 4) {
                values[3]
            } else {
                val sq = 1f - qx * qx - qy * qy - qz * qz
                if (sq < 0f) 0f else sqrt(sq)
            }
            val roll = atan2(2f * (qw * qx + qy * qz), 1f - 2f * (qx * qx + qy * qy))
            // asin's input must be clamped — float noise can push it past ±1.
            val pitch = asin((2f * (qw * qy - qz * qx)).coerceIn(-1f, 1f))
            return roll to pitch
        }
    }
}
