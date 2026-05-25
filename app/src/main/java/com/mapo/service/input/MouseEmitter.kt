package com.mapo.service.input

import android.util.Log
import com.mapo.data.model.steam.InputSource
import com.mapo.di.ApplicationScope
import com.mapo.service.input.modes.MouseEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Brick J.** Production [MouseEmitter] — the continuous-output sibling of the
 * digital edge path in [InputEvaluator.dispatchReadings].
 *
 * **Why a periodic integration step is required here (and only here).** Mapo's
 * input pipeline is event-driven everywhere else — `:shizuku-service`'s
 * `/dev/input/event*` reader only emits an `ABS_*` event when an axis value
 * actually changes, not on a fixed cadence. That's correct for thresholds,
 * synthetic edges, and the trigger soft-press path. But for "stick deflected
 * to constant +0.5 → cursor must keep moving," there is no follow-up event
 * after the initial deflection — the kernel goes silent until the user moves
 * the stick again. So the cursor would freeze the instant the user holds the
 * stick still. The integration loop here re-applies the latest known velocity
 * at a steady cadence ([STEP_INTERVAL_MS]) for *exactly* the window where the
 * stick is non-zero. When every source's velocity returns to zero the loop
 * exits and we're back to event-driven everywhere.
 *
 * **Per-source velocity slots.** Each `evaluate()` call from a continuous mode
 * (Mouse Joystick, Joystick Camera, Brick K Joystick Move) writes its current
 * (vx, vy) in pixels/second into [velocities] for its [InputSource]. The
 * integration loop sums all non-zero slots each step (LJ + RJ can drive the
 * cursor simultaneously — Steam-faithful), multiplies by elapsed dt, and
 * pushes one `dispatcher.injectMouseMove(dx, dy)` per step. Sub-pixel
 * residuals are carried forward so slow motion still moves at all.
 *
 * **Drag-lifecycle coordination.** The trackpad path on
 * `InputAccessibilityService` uses [InputDispatcher.startMouseDrag] /
 * `endMouseDrag` for finger-down / finger-up. Calling those from here would
 * reset the cursor to the safe-zone center every time the user starts
 * deflecting the stick. So this brick adds a separate
 * [InputDispatcher.beginContinuousCursor] / `endContinuousCursor` lifecycle
 * that mirrors the drag-active flag the segment chain reads, but skips the
 * position reset. The trackpad keeps its prior behavior bit-identical.
 */
@Singleton
class MouseEmitterImpl @Inject constructor(
    private val dispatcher: InputDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) : MouseEmitter {

    private data class Velocity(val vx: Float, val vy: Float) {
        val isZero: Boolean get() = vx == 0f && vy == 0f
    }

    private val velocities = EnumMap<InputSource, Velocity>(InputSource::class.java)
    private val lock = Any()

    @Volatile
    private var integrationJob: Job? = null

    @Volatile
    private var continuousActive: Boolean = false

    /**
     * Carried-forward fractional motion. dispatchGesture takes integer-ish
     * pixel deltas; a `vx = 30 px/sec` over 8 ms is 0.24 px per step — would
     * truncate to zero every step and the cursor wouldn't move. Accumulating
     * the residual keeps slow motion alive.
     */
    @Volatile
    private var residualX: Float = 0f

    @Volatile
    private var residualY: Float = 0f

    override fun setStickVelocity(source: InputSource, vxPxPerSec: Float, vyPxPerSec: Float) {
        synchronized(lock) {
            if (vxPxPerSec == 0f && vyPxPerSec == 0f) {
                velocities.remove(source)
            } else {
                velocities[source] = Velocity(vxPxPerSec, vyPxPerSec)
            }
        }
        ensureIntegrationRunning()
    }

    override fun clearAllVelocities() {
        synchronized(lock) {
            velocities.clear()
        }
        // The running loop notices the empty map on its next step and exits.
    }

    private fun ensureIntegrationRunning() {
        if (integrationJob?.isActive == true) return
        val hasAny = synchronized(lock) { velocities.values.any { !it.isZero } }
        if (!hasAny) return
        Log.d(TAG, "integration loop starting (interval=${STEP_INTERVAL_MS}ms)")
        integrationJob = scope.launch {
            beginContinuousIfNeeded()
            try {
                while (isActive) {
                    delay(STEP_INTERVAL_MS)
                    var sumVx = 0f
                    var sumVy = 0f
                    val isEmpty: Boolean
                    synchronized(lock) {
                        for ((_, v) in velocities) {
                            sumVx += v.vx
                            sumVy += v.vy
                        }
                        isEmpty = velocities.isEmpty()
                    }
                    if (isEmpty) break

                    // Use the nominal step interval as dt rather than reading
                    // a wall clock. Rationale:
                    //  - Bounded drift: even if a step lands late due to JVM
                    //    scheduling, the *next* step still fires after another
                    //    STEP_INTERVAL_MS so cumulative error is self-correcting
                    //    over the lifetime of a stick deflection (sub-second).
                    //  - Determinism in tests: kotlinx-coroutines-test advances
                    //    `delay` on virtual time but SystemClock reads real
                    //    time. Mixing the two left dt ≈ 0 in tests and the
                    //    cursor never moved. Nominal dt eliminates the seam.
                    val dx = sumVx * DT_SEC + residualX
                    val dy = sumVy * DT_SEC + residualY
                    val ix = dx.toInt()
                    val iy = dy.toInt()
                    residualX = dx - ix
                    residualY = dy - iy

                    if (ix != 0 || iy != 0) {
                        dispatcher.injectMouseMove(ix.toFloat(), iy.toFloat())
                    }
                }
            } finally {
                residualX = 0f
                residualY = 0f
                endContinuousIfNeeded()
                Log.d(TAG, "integration loop exited")
            }
        }
    }

    private fun beginContinuousIfNeeded() {
        if (!continuousActive) {
            dispatcher.beginContinuousCursor()
            continuousActive = true
        }
    }

    private fun endContinuousIfNeeded() {
        if (continuousActive) {
            dispatcher.endContinuousCursor()
            continuousActive = false
        }
    }

    companion object {
        private const val TAG = "MouseEmitter"

        /**
         * ~120 Hz integration. `dispatchGesture` accepts roughly 100-200
         * gestures/sec on Android 13 before dropping; 8 ms gives us headroom
         * while staying well under one display frame's worth of cursor lag
         * (16.7 ms at 60 Hz; 8.3 ms at 120 Hz devices like AYN Thor).
         */
        const val STEP_INTERVAL_MS: Long = 8L

        /** Nominal seconds per integration step. See dt comment in the loop. */
        private const val DT_SEC: Float = STEP_INTERVAL_MS / 1000f
    }
}
