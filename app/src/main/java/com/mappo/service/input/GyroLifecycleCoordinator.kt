package com.mappo.service.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.InputSource
import com.mappo.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Brick D.2** — gyro sensor lifecycle gate.
 *
 * Parallel to
 * [com.mappo.service.shizuku.ShizukuMotionCoordinator] but scoped to the gyro
 * pipeline rather than the `/dev/input` enumeration. Watches the compiled
 * config + active set / layers and toggles [GyroSensorStream.start] /
 * [GyroSensorStream.stop] based on whether the active scope has any
 * binding_group on the [InputSource.GYRO] source with a real (non-`DEVICE_DEFAULT`,
 * non-`NONE`) gyro mode.
 *
 * **Why a separate coordinator** (not folded into `ShizukuMotionCoordinator`):
 *  - Gyro lifecycle is *independent* of Shizuku — the sensor is an Android
 *    API, not a `/dev/input` reader. Mappo installs without Shizuku can still
 *    run gyro modes.
 *  - The predicate clauses overlap (profile, compiled config, remap toggle)
 *    but the *decision* is different (start sensor listener vs. enable
 *    Shizuku enumeration), and the failure modes are different too
 *    (no-gyro-hardware vs. SELinux-blocked uinput).
 *
 * Keeping the two coordinators separate makes each one trivially testable
 * and the failure modes traceable to one class.
 */
@Singleton
class GyroLifecycleCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inputDispatcher: InputDispatcher,
    private val inputEvaluator: InputEvaluator,
    private val gyroSensorStream: GyroSensorStream,
    private val mouseEmitter: com.mappo.service.input.MouseEmitterImpl,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {

    private var collectionJob: Job? = null

    /**
     * Tracks whether the device is currently in an interactive state (screen
     * on). Gating gyro processing on this is necessary because:
     *  - The gyro sensor itself is the non-wakeup variant ("sh5001 Gyroscope
     *    Non-wakeup" on Thor), so it doesn't directly wake the CPU from
     *    suspend.
     *  - But Mappo dispatches gamepad / mouse uinput events from gyro readings.
     *    Android's PowerManager treats injected input events as user activity
     *    — so a device sitting in a pocket / bag with gyro mode active would
     *    have any motion bump → gyro events → uinput injects → screen wakes.
     *  - Bug verified on Thor 2026-05-31: shaking the device with screen off
     *    woke it every time, draining battery and lighting up at random.
     *
     * Initial value seeded from [PowerManager.isInteractive] so we don't have
     * to wait for the first broadcast to know whether to start.
     */
    private val screenOn = MutableStateFlow(initialIsInteractive())

    /**
     * Listens for screen-on / screen-off broadcasts. `ACTION_USER_PRESENT`
     * isn't required — we re-engage on `ACTION_SCREEN_ON` because the user is
     * actively interacting (even from the lockscreen, e.g. glancing at
     * notifications); the predicate's other clauses still gate on
     * remap-enabled + scope-has-gyro-mode, so a screen-on-but-no-active-gyro
     * profile stays inert anyway.
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "screen off — gyro will suspend if active")
                    screenOn.value = false
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.i(TAG, "screen on — gyro will resume if otherwise wanted")
                    screenOn.value = true
                }
            }
        }
    }

    private fun initialIsInteractive(): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isInteractive ?: true
    } catch (t: Throwable) {
        Log.w(TAG, "isInteractive seed read failed; defaulting screenOn=true", t)
        true
    }

    /**
     * Subscribe to the predicate inputs. Idempotent — calling `start` while
     * already running is a no-op. Safe to call from any thread.
     */
    fun start() {
        if (collectionJob?.isActive == true) {
            Log.d(TAG, "start: already running")
            return
        }
        if (!gyroSensorStream.hasGyro) {
            // Device has no gyroscope at all. Skip the predicate loop entirely —
            // no flows to combine, no decision to make. A future binding edit
            // could surface this in the picker UI as "gyro unavailable on this
            // device"; for now the sensor stream's [GyroSensorStream.start]
            // returns false and gyro modes silently fall through to stub.
            Log.i(TAG, "start: device has no gyroscope; coordinator idle")
            return
        }
        Log.i(TAG, "start: subscribing to predicate inputs")
        try {
            context.registerReceiver(
                screenStateReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "screen-state receiver registration failed; gating will trust initial value only", t)
        }
        collectionJob = applicationScope.launch {
            try {
                combine(
                    inputDispatcher.compiledConfig,
                    inputEvaluator.activeSetIdFlow,
                    inputEvaluator.activeLayerIdsFlow,
                    inputDispatcher.remapEnabled,
                    screenOn,
                ) { values ->
                    @Suppress("UNCHECKED_CAST")
                    val compiled = values[0] as CompiledConfig
                    val activeSetId = values[1] as Long
                    val activeLayers = values[2] as List<Long>
                    val remapEnabled = values[3] as Boolean
                    val screenIsOn = values[4] as Boolean
                    remapEnabled && screenIsOn &&
                        hasGyroModeInScope(compiled, activeSetId, activeLayers)
                }
                    .distinctUntilChanged()
                    .onEach { wanted -> applyDecision(wanted) }
                    .collect { /* drain — apply is in onEach */ }
            } catch (t: Throwable) {
                // Top-level guard so an unhandled throw doesn't propagate to
                // the global uncaught-exception handler.
                Log.e(TAG, "predicate combine loop crashed", t)
            }
        }
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
        // Stop the listener on the way out — battery + no-spurious-events.
        gyroSensorStream.stop()
        try {
            context.unregisterReceiver(screenStateReceiver)
        } catch (t: Throwable) {
            // Receiver may already be unregistered (idempotency) or never
            // registered (start failed early). Either way, not fatal.
            Log.d(TAG, "screen-state receiver unregister: ${t.javaClass.simpleName}")
        }
        Log.i(TAG, "stop: subscriptions cancelled, gyro listener paused")
    }

    private fun applyDecision(wanted: Boolean) {
        Log.i(TAG, "decision: gyroWanted=$wanted, gyroRunning=${gyroSensorStream.isRunning}")
        if (wanted) {
            gyroSensorStream.start()
        } else {
            gyroSensorStream.stop()
            // Zero the GYRO slot in the mouse emitter so the integration loop
            // exits cleanly when the sensor stops. Without this, the last
            // non-zero velocity from before the predicate flipped false
            // persists and the cursor drifts (or in dispatchGesture-fallback
            // mode, the open touch session lingers and produces phantom click
            // / drag) until the next `flushAnalog` clears all slots. Cleared
            // here is the right scope — only this coordinator owns the GYRO
            // contribution, so stick / trackpad velocities are untouched.
            mouseEmitter.setStickVelocity(InputSource.GYRO, 0f, 0f)
        }
    }

    /**
     * True iff the resolved active set or any active layer has a binding_group
     * on [InputSource.GYRO] with a mode that needs the sensor running.
     * `DEVICE_DEFAULT` (pass-through to hardware) and `NONE` (silenced — no
     * sensor needed because we don't fire events) both return false.
     *
     * Extracted as an internal pure helper so the predicate is unit-testable
     * without coroutine flows.
     */
    @Suppress("UnusedReceiverParameter")  // method-form for unit-test ergonomics
    internal fun hasGyroModeInScope(
        compiled: CompiledConfig,
        activeSetId: Long,
        activeLayers: List<Long>,
    ): Boolean {
        val resolvedSetId = if (activeSetId == 0L) compiled.startingActionSetId else activeSetId
        val set = compiled.sets[resolvedSetId] ?: return false
        if (setHasActiveGyroBinding(set.inputs)) return true
        for (layerId in activeLayers) {
            val layer = set.layers[layerId] ?: continue
            if (setHasActiveGyroBinding(layer.inputs)) return true
        }
        return false
    }

    private fun setHasActiveGyroBinding(
        inputs: Map<InputAddress, CompiledInput>,
    ): Boolean = inputs.entries.any { (address, input) ->
        address.source == InputSource.GYRO &&
            input.mode != BindingMode.DEVICE_DEFAULT &&
            input.mode != BindingMode.NONE
    }

    companion object {
        private const val TAG = "GyroLifecycleCoord"
    }
}
