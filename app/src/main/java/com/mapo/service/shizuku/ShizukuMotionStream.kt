package com.mapo.service.shizuku

import android.os.RemoteException
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.mapo.data.model.steam.InputSource
import com.mapo.di.ApplicationScope
import com.mapo.service.input.AnalogEvent
import com.mapo.service.input.InputEvaluator
import com.mapo.shizuku.IMapoInputCallback
import com.mapo.shizuku.InputSourceId
import com.mapo.shizuku.RawAnalogEvent
import com.mapo.shizuku.ShizukuServiceHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-side bridge to the Shizuku UserService's motion-event stream. Subscribes
 * to [ShizukuConnection.service] and re-registers our callback every time the
 * binder cycles (service restart, Shizuku service flap, etc.).
 *
 * **Brick D scope.** Each [RawAnalogEvent] is converted to an [AnalogEvent]
 * (sourceId → InputSource enum, ns → ms) and handed to
 * [InputEvaluator.handleAnalogReadings] one event at a time. The mode evaluator
 * is event-driven; per-event dispatch keeps latency at zero added milliseconds,
 * which matters more than the saved evaluator passes a batching window would
 * give us. If future profiling shows mode resolution as a hot loop, add a
 * per-source dedupe window here without touching the evaluator's contract.
 *
 * [analogEvents] still publishes the raw stream for observation/debugging —
 * dropping it would lose the AYN-Thor-style verification path.
 *
 * **Threading.** [callback] methods run on a binder thread; [_analogEvents]
 * with `extraBufferCapacity = 256` decouples binder-thread tryEmit from the
 * scope's collector so a slow [InputEvaluator] pass can't backpressure
 * `/dev/input` reads. DROP_OLDEST overflow: under a 256-event burst we
 * intentionally drop the oldest unprocessed event rather than block the binder.
 */
@Singleton
class ShizukuMotionStream @Inject constructor(
    private val connection: ShizukuConnection,
    private val inputEvaluator: InputEvaluator,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _analogEvents = MutableSharedFlow<RawAnalogEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val analogEvents: SharedFlow<RawAnalogEvent> = _analogEvents.asSharedFlow()

    /**
     * Internal so tests can drive the dispatch path directly without spinning up
     * a live Shizuku binding. Production code never touches it — the
     * UserService talks to us through this Stub.
     */
    @VisibleForTesting
    internal val callback = object : IMapoInputCallback.Stub() {
        override fun onAnalogEvent(event: RawAnalogEvent?) {
            if (event == null) return
            Log.d(TAG, "RawAnalogEvent ${event.sourceOrdinal} x=${event.x} y=${event.y}")
            _analogEvents.tryEmit(event)
        }

        override fun onDeviceAdded(deviceId: Int, name: String?, capsBitmap: Int) {
            Log.i(TAG, "device added: id=$deviceId name=$name caps=0x${capsBitmap.toString(16)}")
        }

        override fun onDeviceRemoved(deviceId: Int) {
            Log.i(TAG, "device removed: id=$deviceId")
        }

        override fun onServiceHealth(health: ShizukuServiceHealth?) {
            Log.d(TAG, "service health: $health")
        }

        override fun onRawKeyEvent(linuxKeyCode: Int, pressed: Boolean, timestampNs: Long) {
            // Fires only while the UserService has EVIOCGRAB held on the
            // device — see `MapoInputUserService.handleEvent`'s EV_KEY branch.
            // We forward straight to the evaluator's raw-key path so the
            // existing activator engine + DEVICE_DEFAULT passthrough logic
            // handles the routing decision. No SharedFlow buffering — the
            // event cadence is low (button taps, not stick reads), so a
            // direct dispatch keeps latency minimal and the surface small.
            try {
                inputEvaluator.handleRawKeyReading(linuxKeyCode, pressed, timestampNs)
            } catch (t: Throwable) {
                Log.w(TAG, "handleRawKeyReading threw key=0x${linuxKeyCode.toString(16)} pressed=$pressed", t)
            }
        }
    }

    init {
        scope.launch {
            try {
                connection.service.collect { svc ->
                    if (svc != null) {
                        try {
                            svc.registerCallback(callback)
                            Log.i(TAG, "registered callback on UserService")
                        } catch (e: RemoteException) {
                            Log.w(TAG, "registerCallback failed; UserService may be dying", e)
                        } catch (e: Throwable) {
                            Log.w(TAG, "registerCallback unexpected failure", e)
                        }
                    }
                    // Intentionally NOT calling unregisterCallback on null transition:
                    // the binder is dead → the callback's binder is implicitly dropped
                    // by RemoteCallbackList on the service side. Calling unregister via
                    // a dead binder throws DeadObjectException; better to let the
                    // service garbage-collect us.
                }
            } catch (t: Throwable) {
                // Top-level guard so an unhandled throw doesn't propagate to the
                // global uncaught-exception handler and tear down the app
                // process (Brick G revocation-race follow-up 2026-05-24).
                Log.e(TAG, "service.collect callback-registration loop crashed", t)
            }
        }

        // Brick D: feed converted events into the activator engine.
        scope.launch {
            try {
                _analogEvents.collect { raw ->
                    val converted = convertToAnalogEvent(raw) ?: return@collect
                    try {
                        inputEvaluator.handleAnalogReadings(listOf(converted))
                    } catch (t: Throwable) {
                        // Defensive: a misbehaving SourceMode shouldn't kill the
                        // motion stream — next event resumes the pipeline.
                        Log.w(TAG, "handleAnalogReadings threw on $converted", t)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "analogEvents collector crashed", t)
            }
        }
    }

    /**
     * Map [RawAnalogEvent] (sourceId-keyed, normalized values, ns timestamp) to
     * the in-app [AnalogEvent] (InputSource-keyed, ms timestamp). Returns null
     * for `InputSourceId.UNKNOWN` or any future ID `:app` doesn't know about —
     * silently dropping is the correct degraded behavior across an out-of-sync
     * service binary.
     */
    @VisibleForTesting
    internal fun convertToAnalogEvent(raw: RawAnalogEvent): AnalogEvent? {
        val source = when (raw.sourceOrdinal) {
            InputSourceId.DPAD -> InputSource.DPAD
            InputSourceId.LEFT_JOYSTICK -> InputSource.LEFT_JOYSTICK
            InputSourceId.RIGHT_JOYSTICK -> InputSource.RIGHT_JOYSTICK
            InputSourceId.LEFT_TRIGGER -> InputSource.LEFT_TRIGGER
            InputSourceId.RIGHT_TRIGGER -> InputSource.RIGHT_TRIGGER
            else -> return null
        }
        return AnalogEvent(
            source = source,
            x = raw.x,
            y = raw.y,
            timestampMs = raw.timestampNs / 1_000_000L,
        )
    }

    companion object {
        private const val TAG = "ShizukuMotionStream"
    }
}
