package com.mappo.service.input

import android.util.Log
import com.mappo.data.model.steam.InputSource
import com.mappo.di.ApplicationScope
import com.mappo.service.input.modes.MouseEmitter
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
 * **Why a periodic integration step is required here (and only here).** Mappo's
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

    /**
     * Per-source Mouse Region phase (Brick C.4 — dispatchGesture revision
     * 2026-05-30).
     *
     *  - [AbsSourcePhase.UNTOUCHED] — never produced a Mouse Region event
     *    in the current profile. First event snaps the cursor to screen
     *    center, so the cursor doesn't sit at whatever stale position the
     *    host (e.g. Wine in GameNative) last put it.
     *  - [AbsSourcePhase.AT_CENTER] — stick is at rest; cursor is
     *    presumed at screen center. Subsequent rest events are no-ops.
     *  - [AbsSourcePhase.DEFLECTED] — stick is past deadzone; the cursor
     *    is being driven by absolute-touch updates.
     *
     * Snap-to-center fires on any transition into [AbsSourcePhase.AT_CENTER]
     * (UNTOUCHED → AT_CENTER on first activation; DEFLECTED → AT_CENTER on
     * stick release). The deflected path just dispatches the target as
     * absolute touch each event — the gesture-segment chain on the service
     * side keeps the synthetic finger "down" so Wine sees continuous
     * tracking with no spurious clicks.
     *
     * Cleared on profile / action-set switch via [clearAllVelocities].
     */
    private enum class AbsSourcePhase { UNTOUCHED, AT_CENTER, DEFLECTED }
    private val absSourcePhases = EnumMap<InputSource, AbsSourcePhase>(InputSource::class.java)
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

    override fun setStickAbsoluteTarget(source: InputSource, xFrac: Float, yFrac: Float) {
        val needBeginSession: Boolean
        synchronized(lock) {
            needBeginSession = !anyDeflectedLocked() && !continuousActive
            absSourcePhases[source] = AbsSourcePhase.DEFLECTED
        }
        if (needBeginSession) {
            dispatcher.beginContinuousCursor()
            continuousActive = true
        }
        dispatcher.dispatchAbsoluteTouch(xFrac, yFrac)
    }

    override fun clearStickAbsoluteTarget(source: InputSource) {
        val needSnap: Boolean
        val needBeginSession: Boolean
        val shouldEndSession: Boolean
        synchronized(lock) {
            val priorPhase = absSourcePhases[source] ?: AbsSourcePhase.UNTOUCHED
            needSnap = priorPhase != AbsSourcePhase.AT_CENTER
            absSourcePhases[source] = AbsSourcePhase.AT_CENTER
            needBeginSession = needSnap && !continuousActive
            // Don't end the session on snap-back — the synthetic finger
            // stays "down" at center so Wine doesn't see ACTION_UP (which
            // could register as a click). Session ends on
            // [clearAllVelocities] (profile / action-set switch).
            shouldEndSession = false
        }
        if (needBeginSession) {
            dispatcher.beginContinuousCursor()
            continuousActive = true
        }
        if (needSnap) {
            // Snap fires on:
            //  - UNTOUCHED → AT_CENTER: first ever Mouse Region event when
            //    the stick is at rest. Cursor jumps from wherever the host
            //    put it (often top-left in Wine) to screen center.
            //  - DEFLECTED → AT_CENTER: stick release. Cursor slides from
            //    last deflected target back to center via the gesture
            //    segment chain.
            dispatcher.dispatchAbsoluteTouch(0.5f, 0.5f)
        }
        if (shouldEndSession) {
            dispatcher.endContinuousCursor()
            continuousActive = false
        }
    }

    /** Caller must hold [lock]. */
    private fun anyDeflectedLocked(): Boolean =
        absSourcePhases.values.any { it == AbsSourcePhase.DEFLECTED }

    override fun clearAllVelocities() {
        synchronized(lock) {
            velocities.clear()
            absSourcePhases.clear()
            instantResidualX = 0f
            instantResidualY = 0f
        }
        if (continuousActive && integrationJob?.isActive != true) {
            dispatcher.endContinuousCursor()
            continuousActive = false
        }
    }

    /**
     * Separate residual accumulator for [addRelativeDelta]. Kept distinct
     * from [residualX] / [residualY] (which serve the velocity-integration
     * loop) so a flick-stick mode's instant injections don't fight the
     * integration loop's sub-pixel carrying.
     */
    @Volatile
    private var instantResidualX: Float = 0f

    @Volatile
    private var instantResidualY: Float = 0f

    override fun scheduleSmoothDelta(dx: Float, dy: Float, durationMs: Long) {
        if (dx == 0f && dy == 0f) return
        if (durationMs <= 0L) {
            addRelativeDelta(dx, dy)
            return
        }
        val steps = ((durationMs + STEP_INTERVAL_MS - 1) / STEP_INTERVAL_MS).coerceAtLeast(1L)
        val perStepDx = dx / steps
        val perStepDy = dy / steps
        scope.launch {
            try {
                var emitted = 0L
                while (emitted < steps) {
                    addRelativeDelta(perStepDx, perStepDy)
                    emitted++
                    if (emitted < steps) delay(STEP_INTERVAL_MS)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "scheduleSmoothDelta playout crashed (dx=$dx dy=$dy duration=${durationMs}ms)", t)
            }
        }
    }

    override fun addRelativeDelta(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val accumX: Float
        val accumY: Float
        val ix: Int
        val iy: Int
        synchronized(lock) {
            accumX = instantResidualX + dx
            accumY = instantResidualY + dy
            ix = accumX.toInt()
            iy = accumY.toInt()
            instantResidualX = accumX - ix
            instantResidualY = accumY - iy
        }
        if (ix == 0 && iy == 0) return
        // Begin a continuous-cursor session if neither the velocity loop nor
        // a mouse-region session has already opened one. Required so the
        // service routes the inject through the uinput mouse path; without
        // an active session the dispatcher's mouse channel is dormant.
        if (!continuousActive) {
            dispatcher.beginContinuousCursor()
            continuousActive = true
        }
        dispatcher.injectMouseMove(ix.toFloat(), iy.toFloat())
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
