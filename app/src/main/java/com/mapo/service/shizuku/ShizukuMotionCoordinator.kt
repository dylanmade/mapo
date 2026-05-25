package com.mapo.service.shizuku

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.mapo.data.model.Profile
import com.mapo.data.repository.ProfileRepository
import com.mapo.di.ApplicationScope
import com.mapo.service.input.CompiledConfig
import com.mapo.service.input.InputDispatcher
import com.mapo.service.input.InputEvaluator
import com.mapo.service.input.modes.requiresMotionCapture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Brick F + Brick J follow-up.** Drives the Shizuku UserService's enumeration
 * switch off a 3-clause predicate:
 *
 *  - **Remap toggle is on** ([InputDispatcher.remapEnabled]) — the master kill-
 *    switch users flip with the gamepad button. Off → no Mapo input handling
 *    of any kind, digital or analog.
 *  - **Active profile's compiled config has at least one analog
 *    [com.mapo.data.model.steam.InputSource]** in the active set or layers
 *    (per [com.mapo.service.input.modes.requiresMotionCapture]).
 *  - **Shizuku connection is `Granted` and the UserService binder is alive**
 *    ([ShizukuConnection.isReadyFlow]).
 *
 * **History.** Pre-Brick-J the predicate also required "foreground app bound to
 * the active profile." That was a leftover from the pre-Shizuku focused-overlay
 * era: attaching the overlay disrupted IME / back gesture / app-switcher, so we
 * had to gate the overlay's attach behind "we're definitely in a game." With
 * Shizuku, `/dev/input` reads have zero user-visible side effect — the only
 * reason to scope tighter than the active profile would be battery, and `Os.poll`
 * at 250 ms is cheap. Brick J dropped the clause; analog modes now follow the
 * active profile, which itself follows the user's auto-switch / manual choice.
 * **Trade-off:** when the user has Mapo's own activity in the foreground with
 * remap on, stick deflection will fire bindings inside Mapo. Toggle off the
 * gamepad to edit safely. Same workflow as today's keyboard-blocklist nav.
 *
 * When all three hold: `IMapoInputService.setEnumerationEnabled(true)` — the
 * service starts reading `/dev/input/event*` and streaming `RawAnalogEvent`s.
 * When any clause fails: `setEnumerationEnabled(false)` — service stops reading,
 * closes FDs, saves battery.
 *
 * **Degraded-mode toast.** If we were enumerating (all three clauses true) and
 * Shizuku flips not-ready mid-session (e.g. the user kills Shizuku Manager from
 * notifications), surface a system Toast so the in-game user knows analog input
 * has paused. Digital remap survives via [ShizukuKeyInjector]'s automatic
 * reflection fallback — that path doesn't need Shizuku. Cold-start
 * (Shizuku-never-ready) gating is the dialog from Brick G's territory, not this
 * toast.
 *
 * **shizukuModeActive.** Exposed StateFlow for [ShizukuKeyInjector]'s inject
 * gate. Mirrors the predicate result — true exactly when motion enumeration is
 * enabled.
 *
 * **Lifecycle.** Started from `InputAccessibilityService.onServiceConnected`,
 * stopped from `onUnbind`. Uses the application-scoped supervisor so
 * coordination survives Mapo's activity backgrounding (the common case during
 * gameplay).
 */
@Singleton
class ShizukuMotionCoordinator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val profileRepository: ProfileRepository,
    private val inputDispatcher: InputDispatcher,
    private val inputEvaluator: InputEvaluator,
    private val shizukuConnection: ShizukuConnection,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {

    @Volatile
    private var collectionJob: Job? = null

    @Volatile
    private var lastActiveProfileId: Long? = null

    /**
     * Last full predicate breakdown observed. Used to detect the degraded-mode
     * transition (predicate `shouldEnable` was true, Shizuku flipped not-ready
     * while the rest of the predicate still holds). `null` until the first
     * combine emission.
     */
    @Volatile
    private var lastBreakdown: PredicateBreakdown? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _shizukuModeActive = MutableStateFlow(false)
    /**
     * True iff the Shizuku UserService is currently enumerating /dev/input for
     * this coordinator — i.e. the inject gate
     * ([ShizukuKeyInjector.tryInject]'s second condition) should pass.
     */
    val shizukuModeActive: StateFlow<Boolean> = _shizukuModeActive.asStateFlow()

    private val _analogModeWanted = MutableStateFlow(false)
    /**
     * True iff the user has remap enabled AND the active profile has an analog
     * mode configured — i.e. the `remapEnabled && analogModeConfigured` partial
     * predicate. The "shizukuReady" clause is intentionally NOT applied here:
     * the health notification fires precisely when the user *wants* analog
     * input but Shizuku isn't there to provide it.
     */
    val analogModeWanted: StateFlow<Boolean> = _analogModeWanted.asStateFlow()

    /**
     * Begin observing the gating predicate. Idempotent — re-starting an
     * already-running coordinator is a no-op. Safe to call from any thread.
     */
    fun start() {
        if (collectionJob?.isActive == true) {
            Log.d(TAG, "start: already running")
            return
        }
        Log.i(TAG, "start: subscribing to predicate inputs")
        collectionJob = applicationScope.launch {
            try {
                combine(
                    profileRepository.activeProfile,
                    inputDispatcher.compiledConfig,
                    inputEvaluator.activeSetIdFlow,
                    inputEvaluator.activeLayerIdsFlow,
                    inputDispatcher.remapEnabled,
                    shizukuConnection.isReadyFlow,
                ) { values ->
                    val activeProfile = values[0] as Profile?
                    val compiled = values[1] as CompiledConfig
                    val activeSetId = values[2] as Long
                    @Suppress("UNCHECKED_CAST")
                    val activeLayers = values[3] as List<Long>
                    val remapEnabled = values[4] as Boolean
                    val shizukuReady = values[5] as Boolean

                    val activeProfileId = activeProfile?.id
                    // Flush analog state on profile switch so synthetic dpad
                    // edges + Mouse Joystick velocity from the prior profile
                    // don't leak across the boundary. `InputEvaluator.flushAnalog`
                    // covers both: latched synthetic edges (Brick 5) AND
                    // MouseEmitter velocity slots (Brick J).
                    if (activeProfileId != lastActiveProfileId) {
                        Log.d(TAG, "active profile switched $lastActiveProfileId → $activeProfileId; flushing analog state")
                        inputEvaluator.flushAnalog()
                        lastActiveProfileId = activeProfileId
                    }
                    evaluatePredicate(
                        compiled = compiled,
                        activeSetId = activeSetId,
                        activeLayers = activeLayers,
                        remapEnabled = remapEnabled,
                        shizukuReady = shizukuReady,
                    )
                }
                    .distinctUntilChanged()
                    .onEach { breakdown -> applyDecision(breakdown) }
                    .collect { /* drain — apply is in onEach */ }
            } catch (t: Throwable) {
                // Top-level guard against revocation-race throws propagating to
                // the global uncaught-exception handler (Brick G follow-up
                // 2026-05-24). All inner calls have their own try/catch, but
                // an unforeseen path could still throw here.
                Log.e(TAG, "predicate combine loop crashed", t)
            }
        }
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
        // Pause enumeration on the way out. May fail if the binder is already
        // gone — that's fine; the service tears down on its own when we exit.
        tryToggleEnumeration(false)
        _shizukuModeActive.value = false
        _analogModeWanted.value = false
        lastBreakdown = null
        Log.i(TAG, "stop: subscriptions cancelled, enumeration paused")
    }

    private fun applyDecision(breakdown: PredicateBreakdown) {
        val prior = lastBreakdown
        lastBreakdown = breakdown
        Log.d(TAG, "decision: $breakdown")

        if (shouldShowDegradedToast(prior, breakdown)) {
            showDegradedToast()
        }

        tryToggleEnumeration(breakdown.shouldEnable)
        _shizukuModeActive.value = breakdown.shouldEnable
        _analogModeWanted.value = breakdown.remapEnabled && breakdown.analogModeConfigured
    }

    /**
     * The degraded-mode transition: we WERE enumerating, Shizuku flipped
     * not-ready while the user's remap toggle + analog config still hold. (If
     * the user toggled off the gamepad or removed their analog mode, the user
     * did that deliberately — no toast.)
     *
     * Extracted as a pure helper so tests can poke the transition matrix
     * without staging coroutine flows.
     */
    internal fun shouldShowDegradedToast(
        prior: PredicateBreakdown?,
        current: PredicateBreakdown,
    ): Boolean {
        if (prior == null) return false
        return prior.shouldEnable
            && !current.shizukuReady
            && current.remapEnabled
            && current.analogModeConfigured
    }

    private fun showDegradedToast() {
        mainHandler.post {
            Toast.makeText(appContext, DEGRADED_TOAST_TEXT, Toast.LENGTH_LONG).show()
            Log.i(TAG, "degraded-mode toast posted: \"$DEGRADED_TOAST_TEXT\"")
        }
    }

    private fun tryToggleEnumeration(on: Boolean) {
        val service = shizukuConnection.service.value
        if (service == null) {
            // Most likely "Shizuku not ready" → no binder. Predicate already
            // accounts for this; no need to log loudly.
            if (on) Log.d(TAG, "tryToggleEnumeration(true) requested but service binder null — predicate should have already gated this out")
            return
        }
        try {
            service.setEnumerationEnabled(on)
        } catch (t: Throwable) {
            // Broad catch: when Shizuku revokes our permission it tears down
            // the UserService process *before* our state machine reacts. The
            // binder transaction can throw RemoteException (DeadObjectException),
            // SecurityException (permission lost mid-call), or IllegalStateException
            // (Shizuku-internal proxy state). All are non-fatal — predicate
            // catches up on the next isReadyFlow emission.
            Log.w(TAG, "setEnumerationEnabled($on) threw", t)
        }
    }

    /**
     * Pure breakdown evaluator. Returns the three clauses individually so the
     * degraded-mode transition detector can read each axis independently.
     * `shouldEnable` is just their conjunction.
     */
    internal fun evaluatePredicate(
        compiled: CompiledConfig,
        activeSetId: Long,
        activeLayers: List<Long>,
        remapEnabled: Boolean,
        shizukuReady: Boolean,
    ): PredicateBreakdown = PredicateBreakdown(
        remapEnabled = remapEnabled,
        analogModeConfigured = hasAnalogModeInScope(compiled, activeSetId, activeLayers),
        shizukuReady = shizukuReady,
    )

    private fun hasAnalogModeInScope(
        compiled: CompiledConfig,
        activeSetId: Long,
        activeLayers: List<Long>,
    ): Boolean {
        // Resolve the set the same way `InputEvaluator.resolveActiveSet` does:
        // activeSetId of 0L means "lazy-uninit — use compiled.startingActionSetId."
        val resolvedSetId = if (activeSetId == 0L) compiled.startingActionSetId else activeSetId
        val set = compiled.sets[resolvedSetId] ?: return false
        if (set.inputs.values.any { it.mode.requiresMotionCapture() }) return true
        for (layerId in activeLayers) {
            val layer = set.layers[layerId] ?: continue
            if (layer.inputs.values.any { it.mode.requiresMotionCapture() }) return true
        }
        return false
    }

    /**
     * Three-axis predicate result. `shouldEnable` is `remapEnabled &&
     * analogModeConfigured && shizukuReady` — all three required.
     */
    internal data class PredicateBreakdown(
        val remapEnabled: Boolean,
        val analogModeConfigured: Boolean,
        val shizukuReady: Boolean,
    ) {
        val shouldEnable: Boolean
            get() = remapEnabled && analogModeConfigured && shizukuReady
    }

    companion object {
        private const val TAG = "ShizukuMotionCoord"
        private const val DEGRADED_TOAST_TEXT = "Shizuku disconnected — analog modes paused"
    }
}
