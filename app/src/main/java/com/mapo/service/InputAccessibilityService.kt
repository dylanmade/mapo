package com.mapo.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.mapo.data.model.DeviceButton
import com.mapo.data.model.RemapTarget
import com.mapo.data.model.steam.InputSource
import com.mapo.service.foreground.ForegroundAppMonitor
import com.mapo.service.input.InputAddress
import com.mapo.service.input.InputDispatcher
import com.mapo.service.input.InputEvaluator
import com.mapo.service.input.InputSink
import com.mapo.service.input.OverlayFocusKind
import com.mapo.service.input.capture.MotionCaptureCoordinator
import com.mapo.service.input.capture.MotionCaptureOverlayManager
import com.mapo.service.shizuku.ShizukuKeyInjector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class InputAccessibilityService : AccessibilityService(), InputSink {

    @Inject lateinit var foregroundAppMonitor: ForegroundAppMonitor
    @Inject lateinit var dispatcher: InputDispatcher
    @Inject lateinit var evaluator: InputEvaluator
    @Inject lateinit var motionCaptureOverlayManager: MotionCaptureOverlayManager
    @Inject lateinit var motionCaptureCoordinator: MotionCaptureCoordinator
    @Inject lateinit var shizukuKeyInjector: ShizukuKeyInjector

    companion object {
        // Physical gamepad keycodes → DeviceButton enum
        private val GAMEPAD_KEYCODE_MAP: Map<Int, DeviceButton> = mapOf(
            KeyEvent.KEYCODE_BUTTON_A      to DeviceButton.BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B      to DeviceButton.BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X      to DeviceButton.BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y      to DeviceButton.BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1     to DeviceButton.BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1     to DeviceButton.BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2     to DeviceButton.AXIS_L2,
            KeyEvent.KEYCODE_BUTTON_R2     to DeviceButton.AXIS_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL to DeviceButton.BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR to DeviceButton.BUTTON_THUMBR,
            KeyEvent.KEYCODE_DPAD_UP       to DeviceButton.DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN     to DeviceButton.DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT     to DeviceButton.DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT    to DeviceButton.DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_START  to DeviceButton.BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT to DeviceButton.BUTTON_SELECT,
        )

        // Reverse map — DeviceButton → keycode, for gamepad→gamepad remapping
        private val DEVICE_BUTTON_TO_KEYCODE: Map<DeviceButton, Int> =
            GAMEPAD_KEYCODE_MAP.entries.associate { (k, v) -> v to k }

        /**
         * Physical Android gamepad keycode → Steam-schema [InputAddress]
         * ([InputSource] + sub-input key). Matches the default seed in
         * `ControllerConfigRepository.DEFAULT_INPUT_SOURCE_SEEDS` — face buttons are
         * sub-inputs of `BUTTON_DIAMOND`, the dpad's four directions live under
         * `DPAD`, every bumper/trigger/stick-click is `click` on its source.
         *
         * Triggers are mapped from their KEYCODE_BUTTON_L2/R2 forms here (digital threshold);
         * brick 2.3 will additionally consume the analog AXIS_LTRIGGER/AXIS_RTRIGGER values.
         */
        private val KEYCODE_TO_INPUT_ADDRESS: Map<Int, InputAddress> = mapOf(
            KeyEvent.KEYCODE_BUTTON_A      to InputAddress(InputSource.BUTTON_DIAMOND, "button_a"),
            KeyEvent.KEYCODE_BUTTON_B      to InputAddress(InputSource.BUTTON_DIAMOND, "button_b"),
            KeyEvent.KEYCODE_BUTTON_X      to InputAddress(InputSource.BUTTON_DIAMOND, "button_x"),
            KeyEvent.KEYCODE_BUTTON_Y      to InputAddress(InputSource.BUTTON_DIAMOND, "button_y"),
            KeyEvent.KEYCODE_BUTTON_L1     to InputAddress(InputSource.LEFT_BUMPER, "click"),
            KeyEvent.KEYCODE_BUTTON_R1     to InputAddress(InputSource.RIGHT_BUMPER, "click"),
            KeyEvent.KEYCODE_BUTTON_L2     to InputAddress(InputSource.LEFT_TRIGGER, "click"),
            KeyEvent.KEYCODE_BUTTON_R2     to InputAddress(InputSource.RIGHT_TRIGGER, "click"),
            KeyEvent.KEYCODE_BUTTON_THUMBL to InputAddress(InputSource.LEFT_JOYSTICK, "click"),
            KeyEvent.KEYCODE_BUTTON_THUMBR to InputAddress(InputSource.RIGHT_JOYSTICK, "click"),
            KeyEvent.KEYCODE_DPAD_UP       to InputAddress(InputSource.DPAD, "dpad_north"),
            KeyEvent.KEYCODE_DPAD_DOWN     to InputAddress(InputSource.DPAD, "dpad_south"),
            KeyEvent.KEYCODE_DPAD_LEFT     to InputAddress(InputSource.DPAD, "dpad_west"),
            KeyEvent.KEYCODE_DPAD_RIGHT    to InputAddress(InputSource.DPAD, "dpad_east"),
            KeyEvent.KEYCODE_BUTTON_START  to InputAddress(InputSource.SWITCH_START, "click"),
            KeyEvent.KEYCODE_BUTTON_SELECT to InputAddress(InputSource.SWITCH_SELECT, "click"),
        )

        // Maps our button code strings to Android KeyEvent keycodes.
        private val CODE_ALIASES = mapOf("BACKSPACE" to KeyEvent.KEYCODE_DEL)
        private val MOUSE_CODES = setOf(
            "MOUSE_LEFT", "MOUSE_MIDDLE", "MOUSE_RIGHT",
            "SCROLL_UP", "SCROLL_DOWN", "MOUSE_BACK", "MOUSE_FORWARD"
        )

        fun resolveKeyCode(code: String): Int? {
            CODE_ALIASES[code]?.let { return it }
            val kc = KeyEvent.keyCodeFromString("KEYCODE_$code")
            return if (kc != KeyEvent.KEYCODE_UNKNOWN) kc else null
        }

        private const val TAG = "InputAccessibilityService"
    }

    // ── Cursor tracking ───────────────────────────────────────────────────────

    // Virtual gesture anchor — NOT the visible system cursor position.
    // Gesture paths must stay within the safe zone to avoid system gesture zones
    // (back gesture on left/right edges, home gesture at bottom, notifications at top).
    private var cursorX = 540f
    private var cursorY = 960f
    private var safeL = 100f
    private var safeT = 80f
    private var safeR = 980f
    private var safeB = 1670f

    // ── Injection display routing ─────────────────────────────────────────────

    /**
     * Display ID injected input events are routed to. The KeyEvent constructor leaves
     * displayId unset, which the system input dispatcher treats as "default display" —
     * so on dual-display devices (AYN Thor) injected keys would always land on the top
     * screen, never reaching a game running on the bottom screen. Tracked here and
     * stamped onto each event in [injectRawKeyEvent].
     *
     * Single-screen devices stay on [Display.DEFAULT_DISPLAY] permanently.
     */
    @Volatile
    private var focusedAppDisplayId: Int = Display.DEFAULT_DISPLAY

    private val inputEventSetDisplayIdMethod: java.lang.reflect.Method? by lazy {
        try {
            android.view.InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .also { Log.i(TAG, "InputEvent.setDisplayId reflection OK") }
        } catch (e: Throwable) {
            Log.w(TAG, "InputEvent.setDisplayId unavailable — multi-display key injection disabled", e)
            null
        }
    }

    private val inputEventGetDisplayIdMethod: java.lang.reflect.Method? by lazy {
        try {
            android.view.InputEvent::class.java.getMethod("getDisplayId")
        } catch (e: Throwable) {
            Log.w(TAG, "InputEvent.getDisplayId unavailable — displayId verification disabled", e)
            null
        }
    }


    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        dispatcher.register(this)
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        setServiceInfo(info)
        val (w, h) = primaryDisplaySize()
        // Keep gesture paths well away from system gesture zones.
        // Left/right: ~100px avoids the back-gesture swipe zone.
        // Top: ~80px avoids notification pull-down.
        // Bottom: ~250px avoids home/recents swipe zone.
        safeL = 100f;       safeT = 80f
        safeR = w - 100f;   safeB = h - 250f
        cursorX = w / 2f
        cursorY = h / 2f
        Log.i(TAG, "Service connected — display=${w}x${h} safeZone=[$safeL,$safeT,$safeR,$safeB]")

        // Seed the injection target display so the very first remap fires on the right
        // screen even before any window-state-change has arrived since service connect.
        focusedAppDisplayId = computeFocusedAppDisplayId().takeIf { it >= 0 } ?: Display.DEFAULT_DISPLAY
        Log.i(TAG, "Service connected — focusedAppDisplayId=$focusedAppDisplayId")
        dumpAllDisplays("onServiceConnected")

        // Production motion-capture overlay (Brick 3). The manager owns the
        // window lifecycle; we just hand it the callback. The coordinator
        // (Brick 4) decides when to actually attach based on the
        // foreground-app × analog-mode-configured predicate.
        motionCaptureOverlayManager.setMotionCallback { event -> evaluator.handleMotion(event) }
        motionCaptureCoordinator.start()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        motionCaptureCoordinator.stop()
        dispatcher.unregister()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> handleWindowsChanged(event)
            else -> Unit
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val eventDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            event.displayId else Display.DEFAULT_DISPLAY
        Log.d(TAG, "window state changed → pkg=$pkg className=${event.className} eventDisplayId=$eventDisplayId")
        foregroundAppMonitor.reportForegroundPackage(pkg)
        refreshFocusedDisplay("windowStateChange/$pkg")
        if (pkg != packageName) dumpAllDisplays("windowStateChange/$pkg")
    }

    /**
     * TYPE_WINDOWS_CHANGED fires on adds/removes/bounds-changes/etc. across the whole
     * window list — crucially, it captures the AYN Thor's "quick switch" path where the
     * user taps an already-running app's icon on the other screen, and the OS migrates
     * the window between displays *without* emitting a TYPE_WINDOW_STATE_CHANGED.
     * Without this hook, the focused-display cache would stay pointed at the old screen
     * and remap output would land there.
     */
    private fun handleWindowsChanged(event: AccessibilityEvent) {
        val eventDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            event.displayId else Display.DEFAULT_DISPLAY
        Log.d(TAG, "windows changed → eventDisplayId=$eventDisplayId changeTypes=0x${event.windowChanges.toString(16)}")
        refreshFocusedDisplay("windowsChanged/$eventDisplayId")
    }

    private fun refreshFocusedDisplay(reason: String) {
        val previous = focusedAppDisplayId
        val refreshed = computeFocusedAppDisplayId()
        if (refreshed >= 0 && refreshed != previous) {
            Log.i(TAG, "focusedAppDisplayId: $previous → $refreshed ($reason)")
            focusedAppDisplayId = refreshed
        }
    }

    /**
     * Walk all displays and return the id of the first one whose currently-active
     * (or focused) application window belongs to something other than Mapo. Falls
     * back to [Display.DEFAULT_DISPLAY] on single-display devices or when no other
     * application window can be located. Called at service connect to seed
     * [focusedAppDisplayId]; steady-state updates happen in [onAccessibilityEvent].
     */
    /**
     * Dump the full multi-display window topology to logcat. One-shot diagnostic for
     * working out whether a given device actually exposes its secondary screens as
     * separate Android displays — which is what we depend on for cross-display
     * injection routing.
     */
    private fun dumpAllDisplays(reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(TAG, "dumpAllDisplays[$reason]: pre-R, single-display only")
            return
        }
        val all = try { windowsOnAllDisplays } catch (e: Exception) {
            Log.w(TAG, "dumpAllDisplays[$reason]: windowsOnAllDisplays threw", e); return
        }
        if (all.size() == 0) {
            Log.i(TAG, "dumpAllDisplays[$reason]: empty")
            return
        }
        val sb = StringBuilder("dumpAllDisplays[$reason]: displays=${all.size()}")
        for (i in 0 until all.size()) {
            val displayId = all.keyAt(i)
            val windows = all.valueAt(i)
            sb.append("\n  display=$displayId windows=${windows.size}")
            for (w in windows) {
                val pkg = w.root?.packageName?.toString() ?: "?"
                sb.append("\n    [type=${w.type} active=${w.isActive} focused=${w.isFocused} layer=${w.layer}] pkg=$pkg")
            }
        }
        Log.i(TAG, sb.toString())
    }

    /**
     * Walk every display and return the id of the one whose foregrounded application
     * is the user's intended target. Returns -1 ("no candidate found") when nothing
     * suitable exists — callers should fall back to the previously-cached value rather
     * than blindly defaulting to display 0.
     *
     * Per-display tier order:
     *   1. Match against `ForegroundAppMonitor.currentPackage` (the most-recent
     *      non-Mapo foreground report) — strongest signal of user intent.
     *   2. Else: any non-Mapo non-system TYPE_APPLICATION on a non-default display.
     *   3. Else: any non-Mapo non-system TYPE_APPLICATION on the default display.
     *
     * Permissiveness: we accept TYPE_APPLICATION windows even if neither isActive nor
     * isFocused. On the AYN Thor's quick-switch path (user taps a running app's icon on
     * the other screen), the migrated window's focus/active flags lag the move — strict
     * filtering would leave us blind to where the window actually went.
     */
    private fun computeFocusedAppDisplayId(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Display.DEFAULT_DISPLAY
        val all = try {
            windowsOnAllDisplays
        } catch (e: Exception) {
            Log.w(TAG, "computeFocusedAppDisplayId: windowsOnAllDisplays threw", e)
            return -1
        }

        val foregroundPkg = foregroundAppMonitor.currentPackage.value
        var nonDefaultCandidate: Int = -1
        var defaultCandidate: Int = -1
        for (i in 0 until all.size()) {
            val displayId = all.keyAt(i)
            val windows = all.valueAt(i)
            val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val candidate = apps.firstOrNull { it.isActive }
                ?: apps.firstOrNull { it.isFocused }
                ?: apps.firstOrNull()
                ?: continue
            val pkg = candidate.root?.packageName?.toString() ?: continue
            if (pkg.isBlank() || pkg == packageName) continue
            // System-UI filter MUST run before the foreground-match check. Otherwise
            // launcher window-state-change events during the AYN Thor's quick-switch
            // path pollute ForegroundAppMonitor.currentPackage with "com.android.launcher3";
            // the foreground-match then returns display 0 (where launcher lives) instead
            // of the display the user actually moved the app to.
            if (isSystemUiPackage(pkg)) continue
            if (foregroundPkg != null && pkg == foregroundPkg) return displayId
            if (displayId != Display.DEFAULT_DISPLAY && nonDefaultCandidate == -1) {
                nonDefaultCandidate = displayId
            } else if (displayId == Display.DEFAULT_DISPLAY && defaultCandidate == -1) {
                defaultCandidate = displayId
            }
        }
        return when {
            nonDefaultCandidate >= 0 -> nonDefaultCandidate
            defaultCandidate >= 0 -> defaultCandidate
            else -> -1
        }
    }

    /**
     * Packages that should not pull the injection target display around just because they
     * happen to have an active window. The launcher in particular is always the active app
     * on display 0 on a Thor when a game is on display 4 — without this filter, every
     * launcher window-state-change would steal the cache back to display 0.
     */
    private fun isSystemUiPackage(pkg: String): Boolean =
        pkg == "com.android.systemui" ||
        pkg == "com.android.launcher3" ||
        pkg == "com.android.settings"

    override fun queryPrimaryDisplayForegroundPackage(): String? {
        val primaryWindows: List<AccessibilityWindowInfo> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowsOnAllDisplays?.get(Display.DEFAULT_DISPLAY).orEmpty()
            } else {
                // Pre-API 30: getWindows() only ever returns the default display anyway.
                windows.orEmpty()
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryPrimaryDisplayForegroundPackage: window list query failed", e)
            return null
        }

        val apps = primaryWindows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        // Active = the window that currently has input focus on its display. On dual-display
        // devices the bottom screen's app is "active" only when its display is foregrounded;
        // when the user is looking at Mapo on the bottom screen, the primary display's
        // active window stays the game.
        val candidate = apps.firstOrNull { it.isActive }
            ?: apps.firstOrNull { it.isFocused }
            ?: apps.maxByOrNull { it.layer }
            ?: return null

        val pkg = candidate.root?.packageName?.toString()
        Log.d(TAG, "queryPrimaryDisplayForegroundPackage → $pkg (apps=${apps.size})")
        return pkg?.takeIf { it.isNotBlank() && it != packageName }
    }

    override fun onInterrupt() = Unit

    // ── Physical button interception (remap) ──────────────────────────────────

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Log the intercepted event's displayId for diagnostics. Cache refresh happens
        // in injectRawKeyEvent (and via TYPE_WINDOWS_CHANGED) so it also covers virtual
        // button taps, which bypass onKeyEvent entirely.
        val incomingDisplay = readEventDisplayIdOrInvalid(event)
        val topologyDisplay = computeFocusedAppDisplayId()
        Log.d(TAG, "onKeyEvent[displayId]: incoming=$incomingDisplay topology=$topologyDisplay cached=$focusedAppDisplayId keyCode=${event.keyCode}")
        // One-shot dump on every DOWN edge so we can see the actual window snapshot at
        // press time — necessary to verify whether windowsOnAllDisplays is fresh after
        // a Thor "quick switch" (which may not refresh the accessibility framework's
        // window cache through normal channels).
        if (event.action == KeyEvent.ACTION_DOWN) {
            dumpAllDisplays("onKeyEvent/keyCode=${event.keyCode}")
        }

        if (dispatcher.overlayFocus.value == OverlayFocusKind.PROMPT) {
            // Translate gamepad A/B into ENTER/BACK so DPAD-navigated overlay buttons
            // can be activated. DPAD events pass through unchanged so Compose's focus
            // traversal handles left/right/up/down naturally.
            val isDown = event.action == KeyEvent.ACTION_DOWN
            return when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (isDown) injectKeyDown(KeyEvent.KEYCODE_ENTER) else injectKeyUp(KeyEvent.KEYCODE_ENTER)
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (isDown) injectKeyDown(KeyEvent.KEYCODE_BACK) else injectKeyUp(KeyEvent.KEYCODE_BACK)
                    true
                }
                else -> false
            }
        }
        if (!dispatcher.remapEnabled.value) return false

        val address = KEYCODE_TO_INPUT_ADDRESS[event.keyCode] ?: return false
        val isDown = event.action == KeyEvent.ACTION_DOWN

        // Capture mode (Brick 3.3.e): when the chord-partner picker is open we hijack
        // physical inputs and emit them to the picker instead of running the evaluator.
        // We consume both edges so a partial press during capture can't leak the binding.
        if (dispatcher.captureMode.value) {
            if (isDown) {
                Log.d(TAG, "onKeyEvent[capture]: captured $address")
                dispatcher.emitCapturedInput(address)
            }
            return true
        }

        Log.d(TAG, "onKeyEvent: keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}) action=${event.action} address=$address")
        return evaluator.handleDigital(address, isDown)
    }

    // ── Virtual keyboard injection (called from ViewModel) ────────────────────

    /** Inject a full DOWN+UP cycle for the given button code string. */
    override fun injectKey(code: String): Boolean {
        if (code in MOUSE_CODES) return false
        val keyCode = resolveKeyCode(code) ?: run {
            Log.w(TAG, "Unknown key code: $code")
            return false
        }
        injectKeyDown(keyCode)
        injectKeyUp(keyCode)
        return true
    }

    /** DOWN edge of a key, by code string. Called from the runtime evaluator's press path. */
    override fun injectKeyDown(code: String): Boolean {
        if (code in MOUSE_CODES) return false
        val keyCode = resolveKeyCode(code) ?: run {
            Log.w(TAG, "injectKeyDown: unknown key code $code")
            return false
        }
        Log.d(TAG, "injectKeyDown code=$code keyCode=$keyCode")
        injectKeyDown(keyCode)
        return true
    }

    /** UP edge of a key, by code string. Paired with a prior [injectKeyDown]. */
    override fun injectKeyUp(code: String): Boolean {
        if (code in MOUSE_CODES) return false
        val keyCode = resolveKeyCode(code) ?: run {
            Log.w(TAG, "injectKeyUp: unknown key code $code")
            return false
        }
        Log.d(TAG, "injectKeyUp code=$code keyCode=$keyCode")
        injectKeyUp(keyCode)
        return true
    }

    /**
     * Dispatch a RemapTarget as a one-shot click — full down/up cycle for keyboard and gamepad
     * targets, the appropriate single gesture for mouse targets. Used by trackpad gesture
     * remapping and any other path that has a RemapTarget and wants to fire it as a tap.
     */
    override fun dispatchTargetAsClick(target: RemapTarget) {
        Log.d(TAG, "dispatchTargetAsClick target=$target")
        when (target) {
            is RemapTarget.Unbound -> { /* no-op */ }
            is RemapTarget.Keyboard -> {
                val kc = resolveKeyCode(target.code) ?: run {
                    Log.w(TAG, "dispatchTargetAsClick: unknown keyboard code ${target.code}")
                    return
                }
                injectKeyDown(kc); injectKeyUp(kc)
            }
            is RemapTarget.Gamepad -> {
                val btn = DeviceButton.entries.firstOrNull { it.name == target.button } ?: return
                val kc = DEVICE_BUTTON_TO_KEYCODE[btn] ?: return
                injectKeyDown(kc); injectKeyUp(kc)
            }
            is RemapTarget.Mouse -> when (target.code) {
                "MOUSE_LEFT"    -> injectMouseTap()
                "MOUSE_RIGHT"   -> injectMouseRightClick()
                "MOUSE_MIDDLE"  -> injectMouseMiddleClick()
                "MOUSE_BACK"    -> injectMouseBackClick()
                "MOUSE_FORWARD" -> injectMouseForwardClick()
                "SCROLL_UP"     -> injectMouseScroll(0f, 1f)
                "SCROLL_DOWN"   -> injectMouseScroll(0f, -1f)
                else -> Log.w(TAG, "dispatchTargetAsClick: unknown mouse code ${target.code}")
            }
        }
    }

    // ── Mouse gesture injection (called from ViewModel for trackpad) ──────────

    private var isDragging = false
    private var currentStroke: GestureDescription.StrokeDescription? = null
    private var segmentActive = false
    private var segEndX = 540f
    private var segEndY = 960f

    override fun startMouseDrag() {
        // Refresh once at drag start — the cached display will be used for every
        // segment of this drag (dispatchMoveSegment doesn't refresh per-segment).
        refreshFocusedDisplay("startMouseDrag")
        Log.d(TAG, "startMouseDrag — cursor reset to center, display=$focusedAppDisplayId")
        isDragging = true
        segmentActive = false
        currentStroke = null
        // Reset to safe-zone center on every new finger-touch so the virtual anchor
        // always has maximum headroom before approaching any system gesture zone.
        cursorX = (safeL + safeR) / 2f
        cursorY = (safeT + safeB) / 2f
        segEndX = cursorX
        segEndY = cursorY
    }

    override fun injectMouseMove(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(safeL, safeR)
        cursorY = (cursorY + dy).coerceIn(safeT, safeB)
        if (!segmentActive) dispatchMoveSegment(willContinue = true)
    }

    override fun endMouseDrag() {
        isDragging = false
        if (!segmentActive && currentStroke != null) dispatchMoveSegment(willContinue = false)
    }

    private fun dispatchMoveSegment(willContinue: Boolean) {
        val fromX = segEndX; val fromY = segEndY
        val toX = cursorX;   val toY = cursorY
        val path = Path().apply {
            moveTo(fromX, fromY)
            if (fromX != toX || fromY != toY) lineTo(toX, toY)
        }
        val stroke = if (currentStroke == null) {
            GestureDescription.StrokeDescription(path, 0L, 1L, willContinue)
        } else {
            currentStroke!!.continueStroke(path, 0L, 1L, willContinue)
        }
        currentStroke = if (willContinue) stroke else null
        val ok = dispatchGesture(
            buildGesture { addStroke(stroke) },
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) {
                    segmentActive = false
                    when {
                        isDragging && (cursorX != segEndX || cursorY != segEndY) ->
                            dispatchMoveSegment(willContinue = true)
                        !isDragging && currentStroke != null ->
                            dispatchMoveSegment(willContinue = false)
                    }
                }
                override fun onCancelled(g: GestureDescription) {
                    Log.w(TAG, "gesture cancelled — resetting segment, will retry on next move")
                    segmentActive = false
                    currentStroke = null
                    // Don't touch isDragging: finger is still down. Reset segEnd to current
                    // position so the next move starts a fresh gesture from here rather than
                    // trying to jump across the gap since the last dispatched segment.
                    segEndX = cursorX
                    segEndY = cursorY
                }
            }, null
        )
        if (ok) {
            segmentActive = true
            segEndX = toX
            segEndY = toY
        } else {
            Log.w(TAG, "dispatchGesture returned false — skipping segment")
        }
    }

    fun injectMouseTap() {
        refreshFocusedDisplay("injectMouseTap")
        Log.d(TAG, "injectMouseTap at ($cursorX,$cursorY) display=$focusedAppDisplayId")
        val path = Path().apply { moveTo(cursorX, cursorY) }
        // 50ms duration: long enough for DOSBox/RetroArch and other apps with stricter
        // touch handlers to register the touch as a real tap, while still well under
        // ViewConfiguration.getTapTimeout() (~100ms) so it doesn't read as a long press.
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val ok = dispatchGesture(
            buildGesture { addStroke(stroke) },
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { Log.d(TAG, "injectMouseTap: completed") }
                override fun onCancelled(g: GestureDescription) { Log.w(TAG, "injectMouseTap: cancelled") }
            }, null
        )
        if (!ok) Log.w(TAG, "injectMouseTap: dispatchGesture returned false")
    }

    /**
     * Multi-finger tap gestures. Wine (and many touch-to-mouse layers) natively interpret
     * 2-finger tap as right-click and 3-finger tap as middle-click. Single-finger tap stays
     * as injectMouseTap for left-click.
     *
     * Implemented via dispatchGesture with multiple simultaneous strokes — public API, no
     * reflection, no INJECT_EVENTS permission required. Same technique used by DS Keyboard.
     */
    private fun dispatchMultiFingerTap(fingerCount: Int, label: String) {
        refreshFocusedDisplay(label)
        Log.d(TAG, "$label at ($cursorX,$cursorY) display=$focusedAppDisplayId — $fingerCount-finger tap")
        val spacing = 30f  // px between fingers
        // Lay fingers symmetrically around the cursor anchor.
        val totalSpan = spacing * (fingerCount - 1)
        val startX = cursorX - totalSpan / 2f
        val gesture = buildGesture {
            for (i in 0 until fingerCount) {
                val x = startX + i * spacing
                val path = Path().apply { moveTo(x, cursorY) }
                addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
            }
        }
        val ok = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { Log.d(TAG, "$label: completed") }
                override fun onCancelled(g: GestureDescription) { Log.w(TAG, "$label: cancelled") }
            }, null
        )
        if (!ok) Log.w(TAG, "$label: dispatchGesture returned false")
    }

    fun injectMouseRightClick()  = dispatchMultiFingerTap(2, "injectMouseRightClick")
    fun injectMouseMiddleClick() = dispatchMultiFingerTap(3, "injectMouseMiddleClick")

    /**
     * Two-finger vertical drag. Wine/touch-to-mouse layers translate this into scroll wheel
     * events. dy > 0 → scroll up (fingers drag down, Android natural scroll convention).
     */
    fun injectMouseScroll(dx: Float, dy: Float) {
        refreshFocusedDisplay("injectMouseScroll")
        Log.d(TAG, "injectMouseScroll dx=$dx dy=$dy display=$focusedAppDisplayId — two-finger drag")
        val spacing = 30f
        val dragDistance = 200f
        // dy>0 means scroll up → fingers move down (positive Y in screen coords).
        val deltaY = if (dy > 0f) dragDistance else -dragDistance
        val deltaX = if (dx != 0f) (if (dx > 0f) dragDistance else -dragDistance) else 0f
        val gesture = buildGesture {
            listOf(-spacing / 2f, spacing / 2f).forEach { offsetX ->
                val path = Path().apply {
                    moveTo(cursorX + offsetX, cursorY)
                    lineTo(cursorX + offsetX + deltaX, cursorY + deltaY)
                }
                addStroke(GestureDescription.StrokeDescription(path, 0L, 200L))
            }
        }
        val ok = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { Log.d(TAG, "injectMouseScroll: completed") }
                override fun onCancelled(g: GestureDescription) { Log.w(TAG, "injectMouseScroll: cancelled") }
            }, null
        )
        if (!ok) Log.w(TAG, "injectMouseScroll: dispatchGesture returned false")
    }

    // No-op placeholders kept so dispatchTargetAsClick's match remains exhaustive and the picker
    // can still expose MOUSE_BACK/MOUSE_FORWARD even though no reliable cross-app injection
    // mechanism exists for them via accessibility gestures.
    fun injectMouseBackClick() {
        Log.w(TAG, "injectMouseBackClick: no reliable cross-app touch gesture exists for this — noop")
    }
    fun injectMouseForwardClick() {
        Log.w(TAG, "injectMouseForwardClick: no reliable cross-app touch gesture exists for this — noop")
    }

    /**
     * Build a [GestureDescription] targeted at [focusedAppDisplayId]. Without this the
     * default-display fallback inside `GestureDescription` would route every trackpad /
     * mouse tap to the top screen at the cursor anchor — visible as ghost taps near
     * the center of the wrong display whenever the user is playing on the secondary
     * screen of a dual-display device.
     */
    private inline fun buildGesture(strokes: GestureDescription.Builder.() -> Unit): GestureDescription {
        val builder = GestureDescription.Builder()
        builder.strokes()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                builder.setDisplayId(focusedAppDisplayId)
            } catch (e: Throwable) {
                Log.w(TAG, "GestureDescription.Builder.setDisplayId($focusedAppDisplayId) threw", e)
            }
        }
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun primaryDisplaySize(): Pair<Float, Float> {
        val dm = getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            ?: return Pair(1080f, 1920f).also { Log.w(TAG, "primaryDisplaySize: no default display") }
        val metrics = android.util.DisplayMetrics()
        display.getRealMetrics(metrics)
        Log.d(TAG, "primaryDisplaySize: displayId=${display.displayId} size=${metrics.widthPixels}x${metrics.heightPixels}")
        return Pair(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
    }

    // ── Low-level injection ───────────────────────────────────────────────────

    @SuppressLint("PrivateApi")
    private fun injectKeyDown(keyCode: Int) = injectRawKeyEvent(
        KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keyCode, 0)
    )

    @SuppressLint("PrivateApi")
    private fun injectKeyUp(keyCode: Int) = injectRawKeyEvent(
        KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
    )

    @SuppressLint("PrivateApi")
    private fun injectRawKeyEvent(event: KeyEvent) {
        // Refresh from window topology right before we inject. This is the single
        // chokepoint both physical-remap (onKeyEvent → evaluator → injectKeyDown/Up) and
        // virtual-button-tap (Compose → InputDispatcher.injectKey → injectKey/injectKeyDown/Up)
        // funnel through. Refreshing here means cross-display moves (notably the AYN
        // Thor's "quick switch" path that skips TYPE_WINDOW_STATE_CHANGED) are caught
        // at injection time even when no proactive event fired.
        refreshFocusedDisplay("pre-injection")
        setEventDisplayId(event, focusedAppDisplayId)
        injectInputEvent(event)
    }

    /**
     * Stamp the target display onto the event before injection. Required for the
     * AYN Thor's bottom screen — without it the system input dispatcher routes the
     * event to the default (top) display, where Mapo, not the game, has focus.
     */
    private fun setEventDisplayId(event: android.view.InputEvent, displayId: Int) {
        val method = inputEventSetDisplayIdMethod ?: return
        try {
            method.invoke(event, displayId)
        } catch (e: Throwable) {
            Log.w(TAG, "setEventDisplayId failed (displayId=$displayId)", e)
        }
    }

    @SuppressLint("PrivateApi")
    private fun injectInputEvent(event: android.view.InputEvent) {
        val displayId = readEventDisplayId(event)
        // Brick E: when Shizuku is ready AND we're inside an analog-mode window,
        // route through the shell-uid UserService. Shell UID inject is
        // focus-bypassed, so the legacy detach-inject-reattach dance below is
        // unnecessary on that path. tryInject returns false on RemoteException
        // (Shizuku crashed) so digital remap survives via the reflection
        // fallback below.
        if (event is KeyEvent && shizukuKeyInjector.tryInject(
                keyCode = event.keyCode,
                action = event.action,
                displayId = focusedAppDisplayId,
                eventTime = event.eventTime,
            )
        ) {
            return
        }

        val doInject: () -> Unit = {
            try {
                val imClass = Class.forName("android.hardware.input.InputManager")
                val im = imClass.getDeclaredMethod("getInstance").invoke(null)
                val result = imClass.getDeclaredMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType
                ).invoke(im, event, 0) as? Boolean ?: false // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
                Log.d(TAG, "injectInputEvent result=$result type=${event.javaClass.simpleName} source=0x${event.source.toString(16)} eventDisplay=$displayId cachedFocusedDisplay=$focusedAppDisplayId")
            } catch (e: Exception) {
                Log.e(TAG, "injectInputEvent failed: $event", e)
            }
        }
        // Brick 5 follow-up (Approach C): if the motion-capture overlay is
        // attached it holds key focus on the target display so it can read
        // gamepad MotionEvents — any KeyEvent we inject here would route to
        // the overlay instead of the foreground game. The overlay manager
        // toggles FLAG_NOT_FOCUSABLE for the duration of the inject so the
        // foreground window regains focus; flag restored before the next
        // motion event arrives.
        //
        // Brick H deletes the focused-overlay machinery entirely; until then
        // this wrapper stays as the fallback path for the (now rare) case where
        // Shizuku isn't gated in. No-op when the overlay isn't attached.
        if (event is KeyEvent) {
            motionCaptureOverlayManager.withFocusReleasedForInject(doInject)
        } else {
            doInject()
        }
    }

    /**
     * Returns the event's displayId via reflection, or [Display.INVALID_DISPLAY] if the
     * method is unavailable or threw. Caller is responsible for treating INVALID_DISPLAY
     * as "unknown" (don't propagate it as a routing target).
     */
    private fun readEventDisplayIdOrInvalid(event: android.view.InputEvent): Int {
        val m = inputEventGetDisplayIdMethod ?: return Display.INVALID_DISPLAY
        return try {
            (m.invoke(event) as? Int) ?: Display.INVALID_DISPLAY
        } catch (e: Throwable) {
            Display.INVALID_DISPLAY
        }
    }

    /** Same as [readEventDisplayIdOrInvalid] but formatted for logcat. */
    private fun readEventDisplayId(event: android.view.InputEvent): String {
        val m = inputEventGetDisplayIdMethod ?: return "?(no-method)"
        return try {
            (m.invoke(event) as? Int)?.toString() ?: "?(null)"
        } catch (e: Throwable) {
            "?(threw:${e.javaClass.simpleName})"
        }
    }
}
