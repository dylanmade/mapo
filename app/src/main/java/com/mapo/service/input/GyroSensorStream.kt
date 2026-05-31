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

    /** True iff the device has a gyroscope sensor at all. */
    val hasGyro: Boolean = gyroSensor != null

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
        running.set(true)
        Log.i(TAG, "gyro listener registered (sensor=${sensor.name}, delay=$samplingPeriodHint)")
        return true
    }

    /** Unregister the listener. Idempotent. */
    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        sensorManager?.unregisterListener(this)
        Log.i(TAG, "gyro listener unregistered")
    }

    // ── SensorEventListener ─────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_GYROSCOPE) return
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
            )
        )
        if (!emitted && BuildLogVerbose) {
            // Should be rare with the 64-deep DROP_OLDEST buffer; logged
            // here so we'd catch a backpressure regression in testing.
            Log.v(TAG, "tryEmit dropped (buffer full / no collector)")
        }
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
    }
}
