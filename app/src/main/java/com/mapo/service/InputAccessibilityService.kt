package com.mapo.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
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
import com.mapo.service.shizuku.ShizukuKeyInjector
import com.mapo.service.shizuku.ShizukuMotionCoordinator
import com.mapo.service.shizuku.ShizukuMouseInjector
import com.mapo.service.shizuku.ShizukuStylusInjector
import com.mapo.shizuku.LinuxInputConstants
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class InputAccessibilityService : AccessibilityService(), InputSink {

    @Inject lateinit var foregroundAppMonitor: ForegroundAppMonitor
    @Inject lateinit var dispatcher: InputDispatcher
    @Inject lateinit var evaluator: InputEvaluator
    @Inject lateinit var shizukuMotionCoordinator: ShizukuMotionCoordinator
    @Inject lateinit var shizukuKeyInjector: ShizukuKeyInjector
    @Inject lateinit var shizukuMouseInjector: ShizukuMouseInjector
    @Inject lateinit var shizukuStylusInjector: ShizukuStylusInjector
    @Inject lateinit var toolbarOverlayManager: com.mapo.service.overlay.element.ToolbarOverlayManager

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
            KeyEvent.KEYCODE_BUTTON_L2     to InputAddress(InputSource.LEFT_TRIGGER, "full_pull"),
            KeyEvent.KEYCODE_BUTTON_R2     to InputAddress(InputSource.RIGHT_TRIGGER, "full_pull"),
            KeyEvent.KEYCODE_BUTTON_THUMBL to InputAddress(InputSource.LEFT_JOYSTICK, "click"),
            KeyEvent.KEYCODE_BUTTON_THUMBR to InputAddress(InputSource.RIGHT_JOYSTICK, "click"),
            KeyEvent.KEYCODE_DPAD_UP       to InputAddress(InputSource.DPAD, "dpad_up"),
            KeyEvent.KEYCODE_DPAD_DOWN     to InputAddress(InputSource.DPAD, "dpad_down"),
            KeyEvent.KEYCODE_DPAD_LEFT     to InputAddress(InputSource.DPAD, "dpad_left"),
            KeyEvent.KEYCODE_DPAD_RIGHT    to InputAddress(InputSource.DPAD, "dpad_right"),
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

        /**
         * Name prefix of Mapo's own virtual uinput devices (gamepad / mouse / stylus —
         * all "Mapo Virtual …"). Key events from these are skipped in [onKeyEvent] to
         * break the MVG-output → onKeyEvent → re-fire feedback loop. Mirrors the
         * `MAPO_VIRTUAL_DEVICE_PREFIX` the Shizuku UserService uses to skip them on the
         * /dev/input read side.
         */
        private const val MAPO_VIRTUAL_DEVICE_PREFIX = "Mapo Virtual"

        /** Cursor-bounds margins. See field-decl comment for per-edge rationale. */
        private const val CURSOR_MARGIN_SIDE = 30
        private const val CURSOR_MARGIN_TOP = 50
        private const val CURSOR_MARGIN_BOTTOM = 80
    }

    // ── Cursor tracking ───────────────────────────────────────────────────────

    // Virtual gesture anchor — NOT the visible system cursor position.
    // Bounds reserve thin margins around the four edges to avoid tripping
    // system-level gesture detectors with synthetic touches:
    //   - Top (~50 px): notification panel pull-down on swipe from the top edge.
    //   - Bottom (~80 px): home / recents pill swipe-up. Slightly wider because
    //     the navigation pill itself is ~30 px and we want headroom.
    //   - Left/right (~30 px each): back gesture swipe-in from the side edge.
    // These margins are MUCH tighter than the 100/80/100/250 px the trackpad
    // path originally reserved — the bigger margins clipped off game HUDs in
    // the bottom of the screen. Conservatively-tuned 2026-05-25 after the
    // zero-margin revision triggered notification-panel pulls on stick motion
    // toward the top edge.
    private var cursorX = 540f
    private var cursorY = 960f
    private var cursorBoundsL = 0f
    private var cursorBoundsT = 0f
    private var cursorBoundsR = 1920f
    private var cursorBoundsB = 1080f

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

    /**
     * Toolbar-nav chord state (OVERLAY_TOOLBAR_PLAN.md, Brick 3): tracks whether Select is
     * currently held so a following A press can enter gamepad nav of the toolbar overlay.
     * Brick-3 MVP; Brick 5 migrates the trigger to the configurable activator flow.
     */
    private var selectHeld = false

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
        // Conservative margins to avoid tripping system gesture detectors with
        // synthetic touches. See field-decl comment for per-edge rationale.
        cursorBoundsL = CURSOR_MARGIN_SIDE.toFloat()
        cursorBoundsT = CURSOR_MARGIN_TOP.toFloat()
        cursorBoundsR = (w - CURSOR_MARGIN_SIDE).toFloat()
        cursorBoundsB = (h - CURSOR_MARGIN_BOTTOM).toFloat()
        cursorX = w / 2f
        cursorY = h / 2f
        Log.i(TAG, "Service connected — display=${w}x${h} cursorBounds=[$cursorBoundsL,$cursorBoundsT,$cursorBoundsR,$cursorBoundsB]")

        // Seed the injection target display so the very first remap fires on the right
        // screen even before any window-state-change has arrived since service connect.
        focusedAppDisplayId = computeFocusedAppDisplayId().takeIf { it >= 0 } ?: Display.DEFAULT_DISPLAY
        Log.i(TAG, "Service connected — focusedAppDisplayId=$focusedAppDisplayId")
        dumpAllDisplays("onServiceConnected")

        // Brick F: ShizukuMotionCoordinator drives the Shizuku UserService's
        // /dev/input enumeration off the gating predicate. Analog input arrives
        // via ShizukuMotionStream → InputEvaluator.handleAnalogReadings, not
        // through any AccessibilityService-side motion callback.
        shizukuMotionCoordinator.start()
        // Brick C.5 follow-up: InputEvaluator's mode-change watcher needs an
        // explicit lifecycle hook so tests don't get an unkillable
        // forever-collecting coroutine parented to their TestScope.
        evaluator.start()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shizukuMotionCoordinator.stop()
        evaluator.stop()
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
        val eventPkg = event.packageName?.toString() ?: return
        val eventDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            event.displayId else Display.DEFAULT_DISPLAY
        Log.d(TAG, "window state changed → pkg=$eventPkg className=${event.className} eventDisplayId=$eventDisplayId")
        // Don't trust the event's packageName as the "foreground app." Transient
        // overlays — systemui notification panel, status bar, IME, accessibility
        // popups — fire WINDOW_STATE_CHANGED while the actual top TYPE_APPLICATION
        // window doesn't change at all. Using the event package blindly would
        // (a) flip our tracked foreground to systemui mid-game, then (b) never
        // recover because the systemui dismiss fires no follow-up event for the
        // unchanged gamenative window. Bug observed on-device 2026-05-25 with
        // GameNative and a SystemUI transient: predicate flipped false, Shizuku
        // enumeration stopped, never resumed until app-switch round-trip.
        val realForegroundPkg = queryPrimaryDisplayForegroundPackage()
        foregroundAppMonitor.reportForegroundPackage(realForegroundPkg)
        refreshFocusedDisplay("windowStateChange/$eventPkg")
        if (eventPkg != packageName) dumpAllDisplays("windowStateChange/$eventPkg")
    }

    /**
     * TYPE_WINDOWS_CHANGED fires on adds/removes/bounds-changes/etc. across the whole
     * window list — crucially, it captures the AYN Thor's "quick switch" path where the
     * user taps an already-running app's icon on the other screen, and the OS migrates
     * the window between displays *without* emitting a TYPE_WINDOW_STATE_CHANGED.
     * Without this hook, the focused-display cache would stay pointed at the old screen
     * and remap output would land there.
     *
     * Also pumps the foreground-app cache via [queryPrimaryDisplayForegroundPackage] —
     * same systemui-transient recovery rationale as in [handleWindowStateChanged].
     * Without this, dismissing a transient overlay (which fires WINDOWS_CHANGED but
     * not STATE_CHANGED for the underlying gamenative window) wouldn't restore the
     * predicate.
     */
    private fun handleWindowsChanged(event: AccessibilityEvent) {
        val eventDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            event.displayId else Display.DEFAULT_DISPLAY
        Log.d(TAG, "windows changed → eventDisplayId=$eventDisplayId changeTypes=0x${event.windowChanges.toString(16)}")
        refreshFocusedDisplay("windowsChanged/$eventDisplayId")
        foregroundAppMonitor.reportForegroundPackage(queryPrimaryDisplayForegroundPackage())
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

    /**
     * Capture the default display via `AccessibilityService.takeScreenshot` (API 30+) and
     * hand back a software [Bitmap]. Used as a frozen game backdrop for the overlay editor.
     * Below API 30 (or on any failure) returns null — the editor falls back to a plain
     * backdrop. The callback is delivered on the main thread.
     */
    override fun captureScreenshot(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val bitmap = try {
                            val hardware = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace,
                            )
                            // Copy to a software bitmap so it survives buffer.close().
                            val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                            hardware?.recycle()
                            software
                        } catch (e: Exception) {
                            Log.w(TAG, "screenshot buffer → bitmap failed", e)
                            null
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                        onResult(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed: errorCode=$errorCode")
                        onResult(null)
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot threw", e)
            onResult(null)
        }
    }

    override fun onInterrupt() = Unit

    // ── Physical button interception (remap) ──────────────────────────────────

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Feedback-loop guard: ignore key events that originate from Mapo's OWN virtual
        // gamepad (or any "Mapo Virtual …" uinput device). When a binding outputs a
        // gamepad button, the MVG emits it and the OS dispatches that synthetic press
        // right back into this accessibility key filter — which would re-run the same
        // mapping and fire the MVG again, looping forever (e.g. mapping a face button to
        // "gamepad B" → endless B presses, observed 2026-06-10). The Shizuku raw reader
        // already skips these devices by this same name prefix; the accessibility filter
        // must too. Return false so the event still reaches the game (which reads the MVG
        // as its controller) — we simply don't re-process Mapo's own output.
        val sourceDeviceName = event.device?.name
        if (sourceDeviceName != null && sourceDeviceName.startsWith(MAPO_VIRTUAL_DEVICE_PREFIX)) {
            return false
        }
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

        // Track Select (the chord modifier) FIRST — before the PROMPT/TOOLBAR branch below, which
        // returns early while navigating. If we tracked it after, the Select-UP that arrives during
        // nav would be swallowed and selectHeld would stay stuck true, so a bare A would re-trigger
        // the chord after exiting nav (observed on-device 2026-06-19).
        val downEdge = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            selectHeld = downEdge
        }

        // PROMPT and the toolbar's gamepad-nav mode (TOOLBAR) are handled identically: the overlay
        // window is focusable, so the platform's focus traversal drives the stick / D-pad (which are
        // MotionEvents the service never sees), and we only translate gamepad A → ENTER and B → BACK
        // so Compose's native key-activation fires on the focused element. Everything else passes to
        // the focused overlay window unchanged. (OVERLAY_TOOLBAR_PLAN.md, Brick 5 — transient focus.)
        val focusKind = dispatcher.overlayFocus.value
        if (focusKind == OverlayFocusKind.PROMPT || focusKind == OverlayFocusKind.TOOLBAR) {
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

        // ── Toolbar nav ENTER trigger (OVERLAY_TOOLBAR_PLAN.md) ──
        // Select held + A summons the toolbar (if needed) and enters gamepad-nav mode, which flips
        // the overlay focusable + sets overlayFocus = TOOLBAR (handled above on subsequent keys).
        // Placed BEFORE the remap-enabled gate so the toolbar is reachable even with remap off.
        // Brick-5 MVP chord detected inline; migrates to the configurable activator flow later.
        if (selectHeld && event.keyCode == KeyEvent.KEYCODE_BUTTON_A && downEdge && event.repeatCount == 0 &&
            !dispatcher.toolbarNavActive.value
        ) {
            // Reveal the toolbar if it isn't up (showForNav returns false when overlay perm is
            // missing → let A reach the game). Already-shown (dev/QS) toolbars enter nav in place.
            if (toolbarOverlayManager.isShowing() || toolbarOverlayManager.showForNav()) {
                Log.i(TAG, "onKeyEvent: Select+A → reveal/enter toolbar nav")
                dispatcher.requestEnterToolbarNavWhenReady()
                return true
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
    override fun dispatchTargetAsClick(target: RemapTarget, sendAsGesture: Boolean) {
        Log.d(TAG, "dispatchTargetAsClick target=$target sendAsGesture=$sendAsGesture")
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
                "MOUSE_LEFT"    -> injectMouseTap(sendAsGesture)
                "MOUSE_RIGHT"   -> injectMouseRightClick(sendAsGesture)
                "MOUSE_MIDDLE"  -> injectMouseMiddleClick(sendAsGesture)
                "MOUSE_BACK"    -> injectMouseBackClick(sendAsGesture)
                "MOUSE_FORWARD" -> injectMouseForwardClick(sendAsGesture)
                "SCROLL_UP"     -> injectMouseScroll(0f, 1f, sendAsGesture)
                "SCROLL_DOWN"   -> injectMouseScroll(0f, -1f, sendAsGesture)
                else -> Log.w(TAG, "dispatchTargetAsClick: unknown mouse code ${target.code}")
            }
        }
    }

    // ── Mouse gesture injection (called from ViewModel for trackpad) ──────────

    private var isDragging = false
    /**
     * Brick J: parallel drag-active flag for analog stick → mouse modes.
     * Both [isDragging] (trackpad finger-down) and [continuousCursorActive]
     * (stick deflected) need `dispatchGesture` segment chaining to keep
     * firing. The chain logic in [dispatchMoveSegment]'s callback OR-checks
     * both so trackpad and analog can coexist if they ever overlap.
     */
    private var continuousCursorActive = false
    private val anyCursorSessionActive: Boolean get() = isDragging || continuousCursorActive
    private var currentStroke: GestureDescription.StrokeDescription? = null
    private var segmentActive = false
    private var segEndX = 540f
    private var segEndY = 960f

    override fun startMouseDrag() {
        refreshFocusedDisplay("startMouseDrag")
        isDragging = true
        segmentActive = false
        currentStroke = null
        // Center-reset only matters for the dispatchGesture fallback (synthetic
        // touch is absolute-positioned). The uinput path is pure relative and
        // the OS owns the cursor position — center-resetting cursorX/Y here is
        // harmless (it's just our bookkeeping anchor if Shizuku dies mid-drag).
        cursorX = (cursorBoundsL + cursorBoundsR) / 2f
        cursorY = (cursorBoundsT + cursorBoundsB) / 2f
        segEndX = cursorX
        segEndY = cursorY
        Log.d(TAG, "startMouseDrag — display=$focusedAppDisplayId")
    }

    override fun injectMouseMove(dx: Float, dy: Float) {
        // Unified mouse-output path: when Shizuku/uinput is available, ALL
        // cursor motion — trackpad-finger-drag, analog-stick deflection —
        // routes through the OS-rendered cursor via the virtual uinput
        // SOURCE_MOUSE device. The OS owns position, bounding, and rendering;
        // we just push relative deltas. This bypasses Android's touch gesture
        // detectors (notification pull, back, home), which dispatchGesture
        // synthetic touch was contaminating.
        //
        // dispatchGesture remains as the no-Shizuku floor: same primitive the
        // pre-Shizuku trackpad has always used. Bounds margins kept for that
        // path because synthetic touch *does* go through gesture detectors.
        if (shizukuMouseInjector.tryInject(cursorX + dx, cursorY + dy, dx, dy, focusedAppDisplayId)) {
            // Keep cursorX/Y updated as the "where would we anchor if Shizuku
            // dies mid-drag" position for the fallback path. The OS owns the
            // visible cursor; this is just bookkeeping.
            val (w, h) = primaryDisplaySize()
            cursorX = (cursorX + dx).coerceIn(0f, w - 1f)
            cursorY = (cursorY + dy).coerceIn(0f, h - 1f)
            segEndX = cursorX
            segEndY = cursorY
            return
        }
        // Fallback: dispatchGesture path with bounds margins.
        cursorX = (cursorX + dx).coerceIn(cursorBoundsL, cursorBoundsR)
        cursorY = (cursorY + dy).coerceIn(cursorBoundsT, cursorBoundsB)
        if (!segmentActive) dispatchMoveSegment(willContinue = true)
    }

    override fun endMouseDrag() {
        isDragging = false
        if (!segmentActive && currentStroke != null) dispatchMoveSegment(willContinue = false)
    }

    override fun injectMouseMoveAbsoluteFraction(xFrac: Float, yFrac: Float) {
        val (w, h) = primaryDisplaySize()
        // Full-display bounds — the uinput path doesn't need the gesture-
        // margin clamp (the OS owns cursor bounds), and the user expects
        // Mouse Region's extents to reach the actual screen edges. The
        // dispatchGesture fallback path inside [injectMouseMove] still
        // re-clamps to cursor margins on its own.
        val targetX = (xFrac * w).coerceIn(0f, w - 1f)
        val targetY = (yFrac * h).coerceIn(0f, h - 1f)
        val dx = targetX - cursorX
        val dy = targetY - cursorY
        if (dx != 0f || dy != 0f) {
            injectMouseMove(dx, dy)
        }
    }

    override fun dispatchAbsoluteTouch(xFrac: Float, yFrac: Float) {
        val (w, h) = primaryDisplaySize()
        // Update our cursor bookkeeping first (used by the dispatchGesture
        // fallback path's segEnd logic if we drop through).
        cursorX = (xFrac * w).coerceIn(0f, w - 1f)
        cursorY = (yFrac * h).coerceIn(0f, h - 1f)
        // Stylus path: natively absolute on the kernel side; Wine /
        // GameNative path may honor stylus tool-type as absolute pen
        // positioning (vs. the relative model it uses for REL mouse + finger
        // touch). Tried first; falls back on SELinux-blocked /dev/uinput.
        if (shizukuStylusInjector.tryInject(xFrac, yFrac, w.toInt(), h.toInt())) {
            return
        }
        // Fallback: dispatchGesture finger-touch chain. Known limitation —
        // Wine treats touch as a relative touchpad, so cursor positioning
        // is approximate at best. Full-screen extent (no gesture-margin
        // clamp); fullscreen emulators in immersive mode suppress the
        // system gestures (notification pull / back / home pill) that
        // those margins would protect.
        if (!segmentActive) dispatchMoveSegment(willContinue = true)
    }

    override fun beginContinuousCursor() {
        refreshFocusedDisplay("beginContinuousCursor")
        // Cursor position persists across continuous-cursor sessions — never
        // teleport on session entry. Universal cursor convention: the cursor
        // stays where the user last left it. At a screen boundary, motion
        // toward the boundary is no-op (clamped); motion away resumes
        // immediately. The bounds margins set in onServiceConnected keep the
        // virtual touch out of system gesture trigger zones (notification
        // pull, back, home pill).
        Log.d(TAG, "beginContinuousCursor — resuming at ($cursorX,$cursorY), display=$focusedAppDisplayId")
        continuousCursorActive = true
        segEndX = cursorX
        segEndY = cursorY
    }

    override fun endContinuousCursor() {
        Log.d(TAG, "endContinuousCursor")
        continuousCursorActive = false
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
                        anyCursorSessionActive && (cursorX != segEndX || cursorY != segEndY) ->
                            dispatchMoveSegment(willContinue = true)
                        !anyCursorSessionActive && currentStroke != null ->
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

    fun injectMouseTap(sendAsGesture: Boolean = false) =
        injectMouseButtonClick(LinuxInputConstants.BTN_LEFT, "Left", sendAsGesture, ::dispatchSingleFingerTap)
    fun injectMouseRightClick(sendAsGesture: Boolean = false) =
        injectMouseButtonClick(LinuxInputConstants.BTN_RIGHT, "Right", sendAsGesture) { dispatchMultiFingerTap(2, "injectMouseRightClick") }
    fun injectMouseMiddleClick(sendAsGesture: Boolean = false) =
        injectMouseButtonClick(LinuxInputConstants.BTN_MIDDLE, "Middle", sendAsGesture) { dispatchMultiFingerTap(3, "injectMouseMiddleClick") }
    fun injectMouseBackClick(sendAsGesture: Boolean = false) =
        injectMouseButtonClick(LinuxInputConstants.BTN_SIDE, "Back", sendAsGesture) { Log.w(TAG, "Back click: no dispatchGesture fallback") }
    fun injectMouseForwardClick(sendAsGesture: Boolean = false) =
        injectMouseButtonClick(LinuxInputConstants.BTN_EXTRA, "Forward", sendAsGesture) { Log.w(TAG, "Forward click: no dispatchGesture fallback") }

    /**
     * Unified mouse-click entry point.
     *
     * When [sendAsGesture] is false (default), tries the Shizuku/uinput path
     * first — the click is a real `BTN_*` press+release that mouse-aware
     * apps respond to. Falls back to [touchFallback] if Shizuku isn't ready.
     *
     * When [sendAsGesture] is true, skips uinput entirely and goes straight
     * to [touchFallback] — emits a synthetic touch event via dispatchGesture.
     * Required for apps with their own input layers (RetroArch libretro
     * pointer cores, GameNative's touch wrapper) that consume synthetic
     * touch but ignore real mouse buttons. Set per-binding by the user via
     * the activator settings UI.
     */
    private inline fun injectMouseButtonClick(
        btnCode: Int,
        label: String,
        sendAsGesture: Boolean,
        touchFallback: () -> Unit,
    ) {
        refreshFocusedDisplay("injectMouse${label}Click")
        if (!sendAsGesture && shizukuMouseInjector.tryClick(btnCode)) {
            Log.d(TAG, "injectMouse${label}Click via uinput btnCode=0x${btnCode.toString(16)}")
            return
        }
        Log.d(TAG, "injectMouse${label}Click via dispatchGesture (sendAsGesture=$sendAsGesture)")
        touchFallback()
    }

    /**
     * Two-finger / vertical drag → scroll wheel. With Shizuku/uinput, sends
     * REL_WHEEL (and REL_HWHEEL for horizontal) directly — apps see a real
     * wheel event regardless of whether they have any touch-to-scroll
     * gesture handling. Falls back to two-finger dispatchGesture drag (the
     * old Wine-convention path).
     */
    fun injectMouseScroll(dx: Float, dy: Float, sendAsGesture: Boolean = false) {
        refreshFocusedDisplay("injectMouseScroll")
        if (!sendAsGesture) {
            // Translate continuous float "scroll intensity" into integer notch counts.
            // Caller passes 1.0 / -1.0 for one click; future smooth-scroll input could
            // pass smaller fractions which would be rounded but still register.
            val notchX = dx.toInt().let { if (it == 0 && dx != 0f) (if (dx > 0f) 1 else -1) else it }
            val notchY = dy.toInt().let { if (it == 0 && dy != 0f) (if (dy > 0f) 1 else -1) else it }
            if (shizukuMouseInjector.tryScroll(notchX, notchY)) {
                Log.d(TAG, "injectMouseScroll via uinput notch=($notchX,$notchY)")
                return
            }
        }
        Log.d(TAG, "injectMouseScroll via dispatchGesture (sendAsGesture=$sendAsGesture) dx=$dx dy=$dy")
        scrollFallbackDispatchGesture(dx, dy)
    }

    /** Single-finger dispatchGesture tap at the cursor — Shizuku-not-ready fallback for left-click. */
    private fun dispatchSingleFingerTap() {
        Log.d(TAG, "dispatchSingleFingerTap at ($cursorX,$cursorY) display=$focusedAppDisplayId")
        val path = Path().apply { moveTo(cursorX, cursorY) }
        // 50ms: registers as a tap, well under getTapTimeout() (~100ms) so doesn't read as long-press.
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val ok = dispatchGesture(
            buildGesture { addStroke(stroke) },
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { Log.d(TAG, "tap: completed") }
                override fun onCancelled(g: GestureDescription) { Log.w(TAG, "tap: cancelled") }
            }, null,
        )
        if (!ok) Log.w(TAG, "dispatchSingleFingerTap: dispatchGesture returned false")
    }

    /**
     * Multi-finger tap. Wine (and many touch-to-mouse layers) interpret 2-finger
     * tap as right-click and 3-finger as middle-click. Used only as the
     * Shizuku-not-ready fallback for right/middle clicks; uinput's real
     * BTN_RIGHT/MIDDLE is the primary path now.
     */
    private fun dispatchMultiFingerTap(fingerCount: Int, label: String) {
        Log.d(TAG, "$label at ($cursorX,$cursorY) display=$focusedAppDisplayId — $fingerCount-finger tap (fallback)")
        val spacing = 30f
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
            }, null,
        )
        if (!ok) Log.w(TAG, "$label: dispatchGesture returned false")
    }

    /** Wine-convention two-finger vertical drag as scroll. Shizuku-not-ready fallback. */
    private fun scrollFallbackDispatchGesture(dx: Float, dy: Float) {
        val spacing = 30f
        val dragDistance = 200f
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
                override fun onCompleted(g: GestureDescription) { Log.d(TAG, "scroll fallback: completed") }
                override fun onCancelled(g: GestureDescription) { Log.w(TAG, "scroll fallback: cancelled") }
            }, null,
        )
        if (!ok) Log.w(TAG, "scrollFallbackDispatchGesture: dispatchGesture returned false")
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

    /**
     * Cached default-display dimensions. Hot-path callers (`injectMouseMove`,
     * `injectMouseMoveAbsoluteFraction`) invoke `primaryDisplaySize()` per
     * integration step (~125 Hz with gyro / continuous-mouse modes). Without
     * caching this means a DisplayManager system-service lookup + a fresh
     * `DisplayMetrics` allocation + IPC every 8 ms — which under heavy load
     * (verified 2026-05-31 in GameNative + L4D2) starves the system_server's
     * input dispatcher and triggers downstream MotionEvent ANRs.
     *
     * Invalidate on orientation / display config changes via
     * [invalidateDisplaySizeCache]. Mapo's target hardware is landscape-locked
     * so the value is effectively constant for the session, but we wire the
     * invalidation hook anyway for correctness.
     */
    @Volatile
    private var cachedDisplaySize: Pair<Float, Float>? = null

    @Suppress("DEPRECATION")
    private fun primaryDisplaySize(): Pair<Float, Float> {
        cachedDisplaySize?.let { return it }
        val dm = getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            ?: return Pair(1080f, 1920f).also { Log.w(TAG, "primaryDisplaySize: no default display") }
        val metrics = android.util.DisplayMetrics()
        display.getRealMetrics(metrics)
        val result = Pair(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
        cachedDisplaySize = result
        Log.d(TAG, "primaryDisplaySize: displayId=${display.displayId} size=${metrics.widthPixels}x${metrics.heightPixels} (cached)")
        return result
    }

    private fun invalidateDisplaySizeCache() {
        cachedDisplaySize = null
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        invalidateDisplaySizeCache()
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

        // Reflection inject path — the no-Shizuku floor for digital remap. Sent
        // straight through InputManager.injectInputEvent on the calling thread.
        // The legacy detach-inject-reattach focus dance (used until Brick H
        // when a focused TYPE_APPLICATION_OVERLAY held motion-capture focus)
        // is gone — Shizuku is now the only motion-capture path, and shell-uid
        // inject is focus-bypassed.
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
