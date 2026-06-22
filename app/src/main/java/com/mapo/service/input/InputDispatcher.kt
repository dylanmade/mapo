package com.mapo.service.input

import com.mapo.data.model.RemapTarget
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Performs an input-dispatch action (or accessibility query) requested by the
 * [InputDispatcher]. Implemented by `InputAccessibilityService`; null while the service
 * isn't connected, in which case dispatcher methods are no-ops and [InputDispatcher.isReady]
 * returns false.
 */
interface InputSink {
    /** Inject a full DOWN+UP cycle for the given key code string. Used by the virtual keyboard. */
    fun injectKey(code: String): Boolean

    /**
     * Inject a key DOWN edge. Used by the physical-remap evaluator pipeline, which holds
     * the down state until the matching [injectKeyUp]. Returns false for non-key codes
     * (e.g. "MOUSE_LEFT") so the evaluator can skip persisting an unfilled press.
     */
    fun injectKeyDown(code: String): Boolean

    /** Inject a key UP edge — pair with a prior [injectKeyDown] on the same code. */
    fun injectKeyUp(code: String): Boolean

    /**
     * Click-shaped output dispatch. For [RemapTarget.Mouse], [sendAsGesture] selects between
     * synthetic touch via `AccessibilityService.dispatchGesture` (true — emulator-friendly)
     * and real mouse buttons via uinput (false — standard-Android-app-friendly). Ignored
     * for [RemapTarget.Keyboard] / [RemapTarget.Gamepad].
     */
    fun dispatchTargetAsClick(target: RemapTarget, sendAsGesture: Boolean = false)
    fun startMouseDrag()
    fun injectMouseMove(dx: Float, dy: Float)

    /**
     * Brick C.4: Mouse Region absolute-position path. Targets a fractional
     * screen position (`0..1` on each axis, `(0.5, 0.5)` = center) and emits
     * the relative delta from the current cursor position to reach it. Used
     * by [com.mapo.service.input.modes.MouseRegionMode]; the velocity-based
     * stick modes use [injectMouseMove] directly.
     *
     * **Limitation (documented 2026-05-30):** the REL-delta approach can't
     * be re-anchored when an external source moves the cursor (Wine's
     * touch-to-cursor translation in GameNative is the canonical case). A
     * pin-then-move attempt via large negative REL events failed to pin
     * Wine's cursor reliably — likely Wine applies a per-event acceleration
     * curve that distorts our deltas. Result: cursor at game start sits at
     * whatever the host put it (often the top-left corner in Wine), and
     * the Mouse Region "bounds" can shift if touch moves the cursor.
     *
     * Kept for native-Android fallback, but no production caller routes
     * through it as of 2026-05-30 — Mouse Region uses
     * [dispatchAbsoluteTouch] instead.
     */
    fun injectMouseMoveAbsoluteFraction(xFrac: Float, yFrac: Float)

    /**
     * Brick C.4 (revised 2026-05-30): Mouse Region absolute-position path
     * via `dispatchGesture` synthetic touch. Targets a fractional screen
     * position; the gesture-segment chain (same machinery as the trackpad
     * cursor path) keeps a continuous synthetic finger "down" at the
     * target while Mouse Region is active, with no ACTION_UP between
     * events (so emulators that translate touch to cursor don't see
     * spurious clicks).
     *
     * **Trade-off vs. [injectMouseMoveAbsoluteFraction]:** dispatchGesture
     * touches are natively absolute (no acceleration distortion, no
     * delta-vs-position desync) and work cleanly in emulators that read
     * touch as cursor (GameNative / RetroArch / DOSBox Pure). In native
     * Android apps, dispatchGesture touches don't move the OS mouse cursor
     * — Mouse Region in those contexts is inert. That's the intended
     * scope: Mouse Region's use case is precision aim in PC games via
     * emulator; the velocity modes (Joystick Mouse) cover native-Android
     * cursor needs.
     */
    fun dispatchAbsoluteTouch(xFrac: Float, yFrac: Float)

    fun endMouseDrag()

    /**
     * Brick J: begin a continuous-cursor session (analog stick → mouse modes).
     * Mirrors the drag-active state [startMouseDrag] sets so the service's
     * gesture-stroke chaining keeps `dispatchGesture` segments flowing, but —
     * crucially — does NOT reset cursor position. The trackpad path resets the
     * cursor to the safe-zone center on each finger-down; analog modes need
     * the cursor to stay where the user last left it.
     */
    fun beginContinuousCursor()

    /** Brick J: end a continuous-cursor session — pair with [beginContinuousCursor]. */
    fun endContinuousCursor()

    /**
     * Returns the package name of the foreground app on the device's primary (default)
     * display, or null if it can't be determined or matches Mapo itself. Implemented via
     * `getWindowsOnAllDisplays()` so it works across both screens of dual-display devices.
     */
    fun queryPrimaryDisplayForegroundPackage(): String?

    /**
     * Capture a screenshot of the default display as a software [Bitmap] (used as a frozen
     * backdrop for the overlay editor). Returns null via [onResult] when unavailable —
     * `AccessibilityService.takeScreenshot` is API 30+, so older devices always get null.
     * [onResult] is invoked on the main thread.
     */
    fun captureScreenshot(onResult: (android.graphics.Bitmap?) -> Unit)
}

/**
 * Hilt-managed bridge between the [com.mapo.service.InputAccessibilityService] and the
 * rest of the app. Replaces the previous static-singleton + static-mutable-field
 * coupling so the VM/overlay can be tested without touching the service class.
 *
 * - **State** (`compiledConfig`, `remapEnabled`, `overlayFocus`) is published by the
 *   ViewModel/overlay and read by the service inline (e.g. in `onKeyEvent`). Read
 *   via `.value` for synchronous access on the main thread.
 * - **Actions** (key/gesture injection, drag) are forwarded to the registered
 *   [InputSink]; when the service isn't connected the calls are silent no-ops.
 */
@Singleton
class InputDispatcher @Inject constructor() {

    private val _compiledConfig = MutableStateFlow(CompiledConfig.EMPTY)
    val compiledConfig: StateFlow<CompiledConfig> = _compiledConfig.asStateFlow()

    private val _remapEnabled = MutableStateFlow(true)
    val remapEnabled: StateFlow<Boolean> = _remapEnabled.asStateFlow()

    private val _overlayFocus = MutableStateFlow(OverlayFocusKind.NONE)
    val overlayFocus: StateFlow<OverlayFocusKind> = _overlayFocus.asStateFlow()

    /**
     * Listen-for-press capture mode. When true, the accessibility service short-circuits
     * its normal evaluation: physical button DOWN edges are forwarded to
     * [capturedInputs] instead of going through the remap evaluator. Used by the chord
     * partner picker (Brick 3.3.e) to let the user pick a partner by pressing it. Cleared
     * by the picker when it pops back.
     */
    private val _captureMode = MutableStateFlow(false)
    val captureMode: StateFlow<Boolean> = _captureMode.asStateFlow()

    /**
     * Captured physical-input DOWN edges emitted while [captureMode] is true. Single-shot
     * conflated buffer — if multiple presses arrive before the consumer reads, only the
     * most recent survives. The picker collects the first emission and exits capture mode.
     */
    private val _capturedInputs = MutableSharedFlow<InputAddress>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val capturedInputs: SharedFlow<InputAddress> = _capturedInputs.asSharedFlow()

    // ── Toolbar gamepad navigation (Implementation B — OVERLAY_TOOLBAR_PLAN.md, Brick 3) ──
    //
    // The toolbar overlay is FLAG_NOT_FOCUSABLE so gamepad input flows to the game; it can
    // never receive key events through the window system. So the accessibility service drives
    // a selection cursor over it, and the toolbar UI publishes its topology + renders the
    // highlight + runs the activated action. The contract: **service = cursor + activation
    // signal; UI = topology + actions + highlight** (mirrors [overlayFocus]).

    /** True while physical input is driving the toolbar (service-owned). */
    private val _toolbarNavActive = MutableStateFlow(false)
    val toolbarNavActive: StateFlow<Boolean> = _toolbarNavActive.asStateFlow()

    /** Selected target index within [toolbarTargets] (service-owned). */
    private val _toolbarSelection = MutableStateFlow(0)
    val toolbarSelection: StateFlow<Int> = _toolbarSelection.asStateFlow()

    /** Ordered focusable target ids, published by the toolbar UI (UI-owned). */
    private val _toolbarTargets = MutableStateFlow<List<String>>(emptyList())
    val toolbarTargets: StateFlow<List<String>> = _toolbarTargets.asStateFlow()

    /** One-shot "A pressed on the selected index" signal, service → UI. */
    private val _activateToolbar = MutableSharedFlow<Int>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val activateToolbar: SharedFlow<Int> = _activateToolbar.asSharedFlow()

    /**
     * One-shot "B pressed" signal, service → UI. The UI owns the nav stack, so it decides what
     * back means: ascend out of an open menu (republishing the top-level targets) or, at the top
     * level, [exitToolbarNav]. The service no longer exits nav directly on B.
     */
    private val _backToolbar = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val backToolbar: SharedFlow<Unit> = _backToolbar.asSharedFlow()

    /**
     * UI publishes its current ordered targets. A topology change (menu open/close) resets the
     * selection to 0; an unchanged republish (recomposition) leaves it where it is. An empty list
     * (toolbar torn down) force-exits nav.
     */
    fun setToolbarTargets(targets: List<String>) {
        if (targets == _toolbarTargets.value) return
        _toolbarTargets.value = targets
        _toolbarSelection.value = 0
        if (targets.isEmpty()) _toolbarNavActive.value = false
    }

    /**
     * Service: enter toolbar gamepad-nav mode. Sets [overlayFocus] = TOOLBAR so `onKeyEvent`
     * translates A → ENTER / B → BACK; the toolbar UI makes its window focusable so the platform's
     * focus traversal drives the stick / D-pad (MotionEvents the service can't see). Flipped
     * synchronously so the mode is live on the very next key.
     */
    fun requestEnterToolbarNavWhenReady() {
        _toolbarNavActive.value = true
        _overlayFocus.value = OverlayFocusKind.TOOLBAR
    }

    /** End toolbar nav (B at the top level, activation that navigates away, or teardown). */
    fun exitToolbarNav() {
        _toolbarNavActive.value = false
        if (_overlayFocus.value == OverlayFocusKind.TOOLBAR) _overlayFocus.value = OverlayFocusKind.NONE
    }

    /** Service: move the cursor by [delta], clamped to the published targets. */
    fun moveToolbarSelection(delta: Int) {
        val size = _toolbarTargets.value.size
        if (size == 0) return
        _toolbarSelection.value = (_toolbarSelection.value + delta).coerceIn(0, size - 1)
    }

    /** Service: signal "activate the current selection" to the UI. */
    fun activateToolbarSelection() {
        val idx = _toolbarSelection.value
        if (idx in _toolbarTargets.value.indices) _activateToolbar.tryEmit(idx)
    }

    /** Service: signal "back" (B) to the UI, which decides ascend-menu vs. exit-nav. */
    fun signalToolbarBack() {
        _backToolbar.tryEmit(Unit)
    }

    fun setCompiledConfig(config: CompiledConfig) {
        _compiledConfig.value = config
    }

    fun setRemapEnabled(enabled: Boolean) {
        _remapEnabled.value = enabled
    }

    fun setOverlayFocus(kind: OverlayFocusKind) {
        _overlayFocus.value = kind
    }

    fun setCaptureMode(enabled: Boolean) {
        _captureMode.value = enabled
    }

    /** Service-side: emit a captured physical-input DOWN edge while capture mode is on. */
    fun emitCapturedInput(address: InputAddress) {
        _capturedInputs.tryEmit(address)
    }

    @Volatile private var sink: InputSink? = null

    /** Service calls this in `onServiceConnected`; later VM/overlay actions reach the sink. */
    fun register(sink: InputSink) {
        this.sink = sink
    }

    /** Service calls this in `onUnbind`; subsequent action calls become no-ops. */
    fun unregister() {
        sink = null
    }

    val isReady: Boolean get() = sink != null

    fun injectKey(code: String): Boolean = sink?.injectKey(code) ?: false

    fun injectKeyDown(code: String): Boolean = sink?.injectKeyDown(code) ?: false

    fun injectKeyUp(code: String): Boolean = sink?.injectKeyUp(code) ?: false

    fun dispatchTargetAsClick(target: RemapTarget, sendAsGesture: Boolean = false) {
        sink?.dispatchTargetAsClick(target, sendAsGesture)
    }

    fun startMouseDrag() {
        sink?.startMouseDrag()
    }

    fun injectMouseMove(dx: Float, dy: Float) {
        sink?.injectMouseMove(dx, dy)
    }

    fun injectMouseMoveAbsoluteFraction(xFrac: Float, yFrac: Float) {
        sink?.injectMouseMoveAbsoluteFraction(xFrac, yFrac)
    }

    fun dispatchAbsoluteTouch(xFrac: Float, yFrac: Float) {
        sink?.dispatchAbsoluteTouch(xFrac, yFrac)
    }

    fun endMouseDrag() {
        sink?.endMouseDrag()
    }

    fun beginContinuousCursor() {
        sink?.beginContinuousCursor()
    }

    fun endContinuousCursor() {
        sink?.endContinuousCursor()
    }

    fun queryPrimaryDisplayForegroundPackage(): String? =
        sink?.queryPrimaryDisplayForegroundPackage()

    fun captureScreenshot(onResult: (android.graphics.Bitmap?) -> Unit) {
        val s = sink
        if (s == null) onResult(null) else s.captureScreenshot(onResult)
    }
}
