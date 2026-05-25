package com.mapo.service.shizuku

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import com.mapo.data.model.AppProfileBinding
import com.mapo.data.model.Profile
import com.mapo.data.repository.AppProfileBindingRepository
import com.mapo.data.repository.ProfileRepository
import com.mapo.di.ApplicationScope
import com.mapo.service.foreground.ForegroundAppMonitor
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
 * **Brick F.** Successor to `MotionCaptureCoordinator`. Drives the Shizuku
 * UserService's enumeration switch off the same gating predicate that used to
 * drive the focused-overlay attach — plus a new clause requiring Shizuku to be
 * ready, since analog modes don't work at all without it.
 *
 * Effective behavior:
 *  - **Foreground app bound to the active profile**, AND
 *  - **Active set (with overlaying layers applied) has at least one analog
 *    [com.mapo.data.model.steam.InputSource]** (per
 *    [com.mapo.service.input.modes.requiresMotionCapture]), AND
 *  - **Shizuku connection is `Granted` and the UserService binder is alive**
 *    ([ShizukuConnection.isReadyFlow]).
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
    private val foregroundAppMonitor: ForegroundAppMonitor,
    private val profileRepository: ProfileRepository,
    private val appProfileBindingRepository: AppProfileBindingRepository,
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
     * True iff the user has an analog mode configured for the currently-
     * foregrounded app, regardless of Shizuku readiness. I.e., the
     * `foregroundAppBound && analogModeInScope` partial predicate — the
     * "shizukuReady" clause is intentionally NOT applied here.
     *
     * Used by [ShizukuHealthNotification] to detect the "wanted but Shizuku
     * isn't ready" gap and post a reminder.
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
        // `combine` only has named-lambda overloads up to 5 flows; for 6+ we use
        // the vararg form and unpack by index. The casts are local and the
        // surrounding fields keep their concrete types.
        collectionJob = applicationScope.launch {
            combine(
                foregroundAppMonitor.currentPackage,
                profileRepository.activeProfile,
                appProfileBindingRepository.getAll(),
                inputDispatcher.compiledConfig,
                inputEvaluator.activeSetIdFlow,
                inputEvaluator.activeLayerIdsFlow,
                shizukuConnection.isReadyFlow,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val foregroundPkg = values[0] as String?
                @Suppress("UNCHECKED_CAST")
                val activeProfile = values[1] as Profile?
                @Suppress("UNCHECKED_CAST")
                val bindings = values[2] as List<AppProfileBinding>
                val compiled = values[3] as CompiledConfig
                val activeSetId = values[4] as Long
                @Suppress("UNCHECKED_CAST")
                val activeLayers = values[5] as List<Long>
                val shizukuReady = values[6] as Boolean

                val activeProfileId = activeProfile?.id
                // Flush any analog state when the active profile switches —
                // synthetic dpad edges from the prior set must not leak into
                // the new one. Today this is a no-op for non-trigger modes
                // (Brick K+ fill the body for joystick modes).
                if (activeProfileId != lastActiveProfileId) {
                    Log.d(TAG, "active profile switched $lastActiveProfileId → $activeProfileId; flushing analog state")
                    inputEvaluator.flushAnalog()
                    lastActiveProfileId = activeProfileId
                }
                evaluatePredicate(
                    foregroundPkg = foregroundPkg,
                    activeProfileId = activeProfileId,
                    bindings = bindings.asSequence()
                        .filter { it.profileId == activeProfileId }
                        .mapTo(mutableSetOf()) { it.packageName },
                    compiled = compiled,
                    activeSetId = activeSetId,
                    activeLayers = activeLayers,
                    shizukuReady = shizukuReady,
                )
            }
                .distinctUntilChanged()
                .onEach { breakdown -> applyDecision(breakdown) }
                .collect { /* drain — apply is in onEach */ }
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
        _analogModeWanted.value = breakdown.foregroundAppBound && breakdown.analogModeInScope
    }

    /**
     * The degraded-mode transition: we WERE enumerating, Shizuku flipped
     * not-ready while the other clauses still hold. (If foreground app changed
     * away or the user disabled their analog mode, the user did that
     * deliberately — no toast.)
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
            && current.foregroundAppBound
            && current.analogModeInScope
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
        } catch (e: RemoteException) {
            // Shizuku died mid-call. Predicate will catch up next emission via
            // isReadyFlow flip.
            Log.w(TAG, "setEnumerationEnabled($on) threw RemoteException", e)
        }
    }

    /**
     * Pure breakdown evaluator. Returns the three clauses individually so the
     * degraded-mode transition detector can read each axis independently.
     * `shouldEnable` is just their conjunction.
     */
    internal fun evaluatePredicate(
        foregroundPkg: String?,
        activeProfileId: Long?,
        bindings: Set<String>,
        compiled: CompiledConfig,
        activeSetId: Long,
        activeLayers: List<Long>,
        shizukuReady: Boolean,
    ): PredicateBreakdown {
        val foregroundAppBound = foregroundPkg != null
            && activeProfileId != null
            && foregroundPkg in bindings
        val analogModeInScope = foregroundAppBound && hasAnalogModeInScope(compiled, activeSetId, activeLayers)
        return PredicateBreakdown(
            foregroundAppBound = foregroundAppBound,
            analogModeInScope = analogModeInScope,
            shizukuReady = shizukuReady,
        )
    }

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
     * Three-axis predicate result. `shouldEnable` is `foregroundAppBound &&
     * analogModeInScope && shizukuReady` — all three required.
     */
    internal data class PredicateBreakdown(
        val foregroundAppBound: Boolean,
        val analogModeInScope: Boolean,
        val shizukuReady: Boolean,
    ) {
        val shouldEnable: Boolean
            get() = foregroundAppBound && analogModeInScope && shizukuReady
    }

    companion object {
        private const val TAG = "ShizukuMotionCoord"
        private const val DEGRADED_TOAST_TEXT = "Shizuku disconnected — analog modes paused"
    }
}
