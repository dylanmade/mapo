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

    fun dispatchTargetAsClick(target: RemapTarget)
    fun startMouseDrag()
    fun injectMouseMove(dx: Float, dy: Float)
    fun endMouseDrag()

    /**
     * Returns the package name of the foreground app on the device's primary (default)
     * display, or null if it can't be determined or matches Mapo itself. Implemented via
     * `getWindowsOnAllDisplays()` so it works across both screens of dual-display devices.
     */
    fun queryPrimaryDisplayForegroundPackage(): String?
}

/**
 * Hilt-managed bridge between the [com.mapo.service.InputAccessibilityService] and the
 * rest of the app. Replaces the previous static-singleton + static-mutable-field
 * coupling so the VM/overlay can be tested without touching the service class.
 *
 * - **State** (`compiledConfig`, `remapEnabled`, `overlayFocused`, `consumeSystemBack`)
 *   is published by the ViewModel/overlay and read by the service inline (e.g. in
 *   `onKeyEvent`). Read via `.value` for synchronous access on the main thread.
 * - **Actions** (key/gesture injection, drag) are forwarded to the registered
 *   [InputSink]; when the service isn't connected the calls are silent no-ops.
 */
@Singleton
class InputDispatcher @Inject constructor() {

    private val _compiledConfig = MutableStateFlow(CompiledConfig.EMPTY)
    val compiledConfig: StateFlow<CompiledConfig> = _compiledConfig.asStateFlow()

    private val _remapEnabled = MutableStateFlow(false)
    val remapEnabled: StateFlow<Boolean> = _remapEnabled.asStateFlow()

    private val _overlayFocused = MutableStateFlow(false)
    val overlayFocused: StateFlow<Boolean> = _overlayFocused.asStateFlow()

    /**
     * When true, the accessibility service consumes `KEYCODE_BACK` (returns true from
     * `onKeyEvent`) without dispatching it. Set by MainScreen when the user is on the
     * keyboard view with the drawer closed: `FLAG_NOT_FOCUSABLE` is on there (so unmapped
     * gamepad inputs flow to the game on the primary screen), which means the back key
     * has no focusable target and would otherwise hit the system's 5 s input-dispatch
     * timeout and ANR. Consuming it here ack's the press without acting on it — matching
     * the user-facing intent that back does nothing on the keyboard view.
     */
    private val _consumeSystemBack = MutableStateFlow(false)
    val consumeSystemBack: StateFlow<Boolean> = _consumeSystemBack.asStateFlow()

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

    fun setCompiledConfig(config: CompiledConfig) {
        _compiledConfig.value = config
    }

    fun setRemapEnabled(enabled: Boolean) {
        _remapEnabled.value = enabled
    }

    fun setOverlayFocused(focused: Boolean) {
        _overlayFocused.value = focused
    }

    fun setConsumeSystemBack(consume: Boolean) {
        _consumeSystemBack.value = consume
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

    fun dispatchTargetAsClick(target: RemapTarget) {
        sink?.dispatchTargetAsClick(target)
    }

    fun startMouseDrag() {
        sink?.startMouseDrag()
    }

    fun injectMouseMove(dx: Float, dy: Float) {
        sink?.injectMouseMove(dx, dy)
    }

    fun endMouseDrag() {
        sink?.endMouseDrag()
    }

    fun queryPrimaryDisplayForegroundPackage(): String? =
        sink?.queryPrimaryDisplayForegroundPackage()
}
