package com.mappo.shizuku.service

import android.os.Build
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.mappo.shizuku.IMappoInputCallback
import com.mappo.shizuku.IMappoInputService
import com.mappo.shizuku.InjectKeyRequest
import com.mappo.shizuku.InputSourceId
import com.mappo.shizuku.LinuxInputConstants
import com.mappo.shizuku.LinuxInputConstants.EVENT_SIZE_BYTES
import com.mappo.shizuku.RawAnalogEvent
import com.mappo.shizuku.ShizukuServiceHealth
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Mappo's Shizuku UserService — runs as UID 2000 (shell) in a separate process.
 *
 * **Brick C scope (this file).**
 *  - First [registerCallback] call starts the read + watch threads.
 *  - Last [unregisterCallback] stops them.
 *  - Read loop opens every `/dev/input/event*`, parses raw `input_event` records,
 *    classifies devices in a 200ms sniff window (axis-code based, no JNI), and
 *    broadcasts [RawAnalogEvent]s to all registered callbacks.
 *  - Watch thread (Java `WatchService` on `/dev/input/`) wakes the read loop
 *    when devices are hot-plugged or removed.
 *
 * **Brick C non-goals.**
 *  - No EVIOCGABS ioctl-based range query (would need JNI). Axis normalization
 *    uses observed min/max per axis (self-calibrating once the user moves the
 *    stick to extremes). Brick D may revisit if device-specific tuning is needed.
 *
 * **Brick E.** [injectKeyEvent] is live: reconstructs a `KeyEvent` from
 * [InjectKeyRequest], stamps `displayId` via HiddenApiBypass reflection, calls
 * `IInputManager.injectInputEvent` from this shell-uid context (focus-bypassed).
 * [setEnumerationEnabled] still a stub — Brick F's coordinator will drive it.
 *
 * **Threading.** Two background threads owned by this service:
 *  - `mappo-input-reader`: blocks on `Os.poll` over all open device FDs, parses
 *    events, emits [RawAnalogEvent]s.
 *  - `mappo-input-watcher`: blocks on `WatchService.take`, signals the reader
 *    thread to refresh its FD list when `/dev/input/` changes.
 *
 * **No Hilt / Compose / Room.** This process boots fast; keep deps minimal.
 */
class MappoInputUserService : IMappoInputService.Stub() {

    init {
        installHiddenApiExemptions()
    }

    private val callbacks = RemoteCallbackList<IMappoInputCallback>()

    /** Active devices. Keyed by deviceId (extracted from "/dev/input/eventN"). */
    private val devices = ConcurrentHashMap<Int, DeviceEntry>()

    /** Set when the reader thread should refresh its FD list (devices map changed). */
    private val devicesDirty = AtomicBoolean(false)

    /**
     * Effective run-gate. Threads run iff (callbacks registered AND
     * [enumerationEnabled]). Tracked as an [AtomicBoolean] for the thread-safe
     * compareAndSet-driven start/stop transition.
     */
    private val running = AtomicBoolean(false)

    /**
     * Did at least one client register a callback. Flipped by [registerCallback]
     * and [unregisterCallback]; never directly drives [running] — the gate
     * recompute does.
     */
    private val callbacksActive = AtomicBoolean(false)

    /**
     * **Brick F.** Coordinator-driven master switch. When the Shizuku motion
     * coordinator's predicate goes false (no analog mode in scope, foreground
     * app not bound, etc.), it calls `setEnumerationEnabled(false)` and the
     * reader/watcher threads stop + FDs close — saves battery from per-event
     * polling when nothing in :app cares about analog input. Default `true`
     * so Brick C / D / E device-verification paths (which talk to the service
     * directly without a coordinator) keep working.
     */
    @Volatile
    private var enumerationEnabled: Boolean = true

    /**
     * Whether `EVIOCGRAB` should be held on every classified-gamepad
     * `/dev/input/event*` device. Driven by `GyroLifecycleCoordinator`'s
     * "gyro→stick mode in scope" predicate (Brick D follow-up). When true,
     * the OS InputReader stops getting events from the physical controller
     * — Mappo's virtual gamepad becomes the sole path that reaches the game.
     *
     * Toggled via [setGrabPhysicalControllers]. Independent of
     * [enumerationEnabled] — the reader threads can run without grab held
     * (the normal Mappo-managed-but-no-stick-mode case), but grab can only
     * be active when the reader is running too (since classification depends
     * on the reader observing events).
     */
    private val grabPhysicalControllers = java.util.concurrent.atomic.AtomicBoolean(false)

    private var readerThread: Thread? = null
    private var watcherThread: Thread? = null

    /** Most recent monotonic timestamp emitted. Surfaced in [ShizukuServiceHealth]. */
    @Volatile
    private var lastEventNs: Long = 0L

    /** Diagnostic — incremented every [injectMouseMotion] call; logged every Nth. */
    @Volatile
    private var mouseInjectCount: Long = 0L

    override fun getProtocolVersion(): Int = PROTOCOL_VERSION

    override fun registerCallback(cb: IMappoInputCallback?) {
        if (cb == null) return
        callbacks.register(cb)
        val count = callbacks.registeredCallbackCount
        Log.i(TAG, "callback registered; count=$count")
        if (count == 1) {
            callbacksActive.set(true)
            recomputeRunning()
        }
    }

    override fun unregisterCallback(cb: IMappoInputCallback?) {
        if (cb == null) return
        callbacks.unregister(cb)
        val count = callbacks.registeredCallbackCount
        Log.i(TAG, "callback unregistered; count=$count")
        if (count == 0) {
            callbacksActive.set(false)
            recomputeRunning()
        }
    }

    override fun injectKeyEvent(req: InjectKeyRequest?): Boolean {
        if (req == null) return false
        return try {
            val event = KeyEvent(
                /* downTime = */ req.eventTime,
                /* eventTime = */ req.eventTime,
                /* action = */ req.action,
                /* keyCode = */ req.keyCode,
                /* repeat = */ 0,
                /* metaState = */ 0,
                /* deviceId = */ KeyCharacterMap.VIRTUAL_KEYBOARD,
                /* scanCode = */ 0,
                /* flags = */ 0,
                /* source = */ InputDevice.SOURCE_KEYBOARD,
            )
            stampDisplayId(event, req.displayId)
            invokeInjectInputEvent(event)
        } catch (t: Throwable) {
            Log.w(TAG, "injectKeyEvent failed for req=$req", t)
            false
        }
    }

    /**
     * Stamp the target display onto [event]. The shell-uid process this code
     * runs in is generally exempt from hidden-API enforcement, but variations
     * across OEMs make installing the exemption explicit the only portable
     * approach — see [installHiddenApiExemptions]. The reflective method
     * reference is cached on first use.
     */
    private fun stampDisplayId(event: android.view.InputEvent, displayId: Int) {
        val m = setDisplayIdMethod ?: return
        try {
            m.invoke(event, displayId)
        } catch (t: Throwable) {
            Log.w(TAG, "setDisplayId($displayId) failed", t)
        }
    }

    /**
     * Reflectively call `IInputManager.injectInputEvent(event, ASYNC)`. Same
     * call site the legacy `:app`-process reflection inject uses — only the
     * calling UID differs (shell here vs. app there), and that UID difference
     * is what makes Shizuku's path focus-bypassed.
     */
    private fun invokeInjectInputEvent(event: android.view.InputEvent): Boolean {
        val imClass = Class.forName("android.hardware.input.InputManager")
        val im = imClass.getDeclaredMethod("getInstance").invoke(null)
        val result = imClass.getDeclaredMethod(
            "injectInputEvent",
            android.view.InputEvent::class.java,
            Int::class.javaPrimitiveType,
        ).invoke(im, event, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean ?: false
        if (!result) {
            Log.w(TAG, "IInputManager.injectInputEvent returned false (event=$event)")
        }
        return result
    }

    private val setDisplayIdMethod: java.lang.reflect.Method? by lazy {
        try {
            android.view.InputEvent::class.java
                .getMethod("setDisplayId", Int::class.javaPrimitiveType)
        } catch (t: Throwable) {
            Log.w(TAG, "InputEvent.setDisplayId reflection unavailable", t)
            null
        }
    }

    override fun injectMouseMotion(
        absX: Float,
        absY: Float,
        relDx: Float,
        relDy: Float,
        displayId: Int,
    ): Boolean {
        // SOURCE_MOUSE MotionEvent injection via IInputManager was tried first
        // (commit history pre-2026-05-25) and verified non-functional: the
        // events are accepted but there's no SOURCE_MOUSE InputDevice
        // registered with Android's InputReader, so no pointer controller
        // exists and the events deliver to nothing visible. We instead create
        // a kernel-level virtual mouse via /dev/uinput — the OS registers it
        // as a real InputDevice, spawns the pointer controller, and renders
        // the cursor itself. We just push REL_X/REL_Y deltas.
        //
        // absX/absY/displayId are unused under uinput — the OS owns cursor
        // tracking, bounding, and display routing. Signature kept stable so
        // we don't need an AIDL bump if we later add an alternate inject path.
        if (!UinputMouse.isReady && !UinputMouse.open()) {
            // SELinux or vendor-locked path. Caller should already be falling
            // back to dispatchGesture in that case.
            return false
        }
        return try {
            mouseInjectCount++
            if (mouseInjectCount % 120L == 1L) {
                Log.d(TAG, "injectMouseMotion #$mouseInjectCount rel=($relDx,$relDy)")
            }
            UinputMouse.move(relDx.toInt(), relDy.toInt())
            true
        } catch (t: Throwable) {
            Log.w(TAG, "injectMouseMotion failed rel=($relDx,$relDy)", t)
            false
        }
    }

    override fun injectMouseButton(btnCode: Int, pressed: Boolean): Boolean {
        if (!UinputMouse.isReady && !UinputMouse.open()) return false
        return try {
            UinputMouse.button(btnCode, pressed)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "injectMouseButton failed btnCode=0x${btnCode.toString(16)} pressed=$pressed", t)
            false
        }
    }

    override fun injectMouseScroll(dx: Int, dy: Int): Boolean {
        if (!UinputMouse.isReady && !UinputMouse.open()) return false
        return try {
            UinputMouse.scroll(dx, dy)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "injectMouseScroll failed dx=$dx dy=$dy", t)
            false
        }
    }

    override fun setGamepadAxes(
        lx: Int, ly: Int,
        rx: Int, ry: Int,
        lt: Int, rt: Int,
        hatX: Int, hatY: Int,
    ): Boolean {
        if (!UinputGamepad.isReady && !UinputGamepad.open()) return false
        return try {
            UinputGamepad.writeAxes(lx, ly, rx, ry, lt, rt, hatX, hatY)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setGamepadAxes failed", t)
            false
        }
    }

    override fun setGamepadButton(btnCode: Int, pressed: Boolean): Boolean {
        if (!UinputGamepad.isReady && !UinputGamepad.open()) {
            Log.w(TAG, "setGamepadButton: gamepad not open (btn=0x${btnCode.toString(16)})")
            return false
        }
        Log.d(TAG, "setGamepadButton btn=0x${btnCode.toString(16)} pressed=$pressed → uinput")
        return try {
            UinputGamepad.writeButton(btnCode, pressed)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setGamepadButton failed btnCode=0x${btnCode.toString(16)} pressed=$pressed", t)
            false
        }
    }

    override fun injectStylusAbsolute(x: Int, y: Int, displayW: Int, displayH: Int): Boolean {
        // Lazy-open the virtual stylus with the live display dimensions.
        // The kernel locks absmin/absmax at create time so a display-rotation
        // / resolution change would need a recreate — [UinputStylus.open]
        // detects the dimension delta and recreates internally.
        if (!UinputStylus.open(displayW, displayH)) return false
        return try {
            val clampedX = x.coerceIn(0, displayW - 1)
            val clampedY = y.coerceIn(0, displayH - 1)
            UinputStylus.moveAbsolute(clampedX, clampedY)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "injectStylusAbsolute failed x=$x y=$y", t)
            false
        }
    }

    override fun setEnumerationEnabled(on: Boolean) {
        Log.i(TAG, "setEnumerationEnabled($on)")
        enumerationEnabled = on
        recomputeRunning()
    }

    /**
     * EVIOCGRAB toggle for every classified-gamepad `/dev/input/event*` device
     * the service has open. New devices that classify as Gamepad while
     * `grabPhysicalControllers` is true will get grabbed at classification
     * time too.
     *
     * Touchscreens + unknowns are deliberately skipped — touchscreen input
     * must keep flowing to the OS for system gestures + Mappo's own overlay,
     * and unclassified devices haven't earned a grab yet (waiting for the
     * sniff window's first axis event to upgrade them).
     */
    override fun setGrabPhysicalControllers(grabbed: Boolean) {
        Log.i(TAG, "setGrabPhysicalControllers($grabbed)")
        grabPhysicalControllers.set(grabbed)
        // Grabbing the physical controller(s) removes them from the OS entirely —
        // the virtual gamepad becomes the game's ONLY gamepad. Create + neutralize
        // it NOW, before the grab, rather than lazily on the first axis write. In a
        // trigger-only config the triggers inject KEYS and never touch the gamepad,
        // so without this the MVG wouldn't exist until some stick happened to move —
        // leaving a window where the physical pad has vanished and no centered
        // virtual replacement exists yet (observed as phantom stick deflection).
        // open() is idempotent; writeAxes(0…) centers every stick/trigger/hat.
        if (grabbed) {
            if (UinputGamepad.isReady || UinputGamepad.open()) {
                try {
                    UinputGamepad.writeAxes(0, 0, 0, 0, 0, 0, 0, 0)
                } catch (t: Throwable) {
                    Log.w(TAG, "neutralizing virtual gamepad on grab failed", t)
                }
            } else {
                Log.w(TAG, "could not open virtual gamepad to neutralize before grab")
            }
        }
        // Apply to every currently-open classified gamepad. Devices that
        // are still Unknown will be grabbed at classification time (see
        // handleAbsEvent's first-axis-upgrades-classification branch).
        for (entry in devices.values) {
            if (entry.classification == DeviceClass.Gamepad) {
                applyGrabState(entry, grabbed)
            }
        }
    }

    /**
     * Take or release EVIOCGRAB on a single device. Tracks per-entry grab
     * state so we don't double-grab or double-release (no kernel state but
     * keeps the log noise sane). Idempotent.
     */
    private fun applyGrabState(entry: DeviceEntry, shouldGrab: Boolean) {
        if (entry.grabbed == shouldGrab) return
        val rawFd = fdInt(entry.fd)
        if (rawFd < 0) {
            Log.w(TAG, "grab(${entry.path}): cannot extract raw fd; skipping")
            return
        }
        val ok = EvdevGrab.setGrabbed(rawFd, shouldGrab)
        if (ok) {
            entry.grabbed = shouldGrab
            Log.i(TAG, "grab(${entry.path}): ${if (shouldGrab) "acquired" else "released"} (fd=$rawFd)")
        } else {
            Log.w(TAG, "grab(${entry.path}, $shouldGrab) failed at the kernel ioctl level")
        }
    }

    /**
     * Apply the effective run-gate: threads run iff (callback registered AND
     * [enumerationEnabled]). Idempotent; starts/stops only on transition.
     */
    private fun recomputeRunning() {
        val shouldRun = callbacksActive.get() && enumerationEnabled
        if (shouldRun) {
            if (running.compareAndSet(false, true)) startThreads()
        } else {
            if (running.compareAndSet(true, false)) stopThreads()
        }
    }

    override fun destroy() {
        Log.i(TAG, "destroy() — stopping threads + exiting shell-uid process")
        running.set(false)
        callbacks.kill()
        stopThreads()
        closeAllDevices()
        UinputMouse.close()
        UinputGamepad.close()
        UinputStylus.close()
        System.exit(0)
    }

    // ── Thread management ────────────────────────────────────────────────────

    private fun startThreads() {
        Log.i(TAG, "starting reader + watcher threads")
        scanInitialDevices()
        readerThread = thread(name = "mappo-input-reader", start = true, isDaemon = true) {
            runReaderLoop()
        }
        watcherThread = thread(name = "mappo-input-watcher", start = true, isDaemon = true) {
            runWatcherLoop()
        }
    }

    private fun stopThreads() {
        Log.i(TAG, "stopping reader + watcher threads")
        readerThread?.interrupt()
        watcherThread?.interrupt()
        readerThread = null
        watcherThread = null
        closeAllDevices()
    }

    // ── Device discovery ─────────────────────────────────────────────────────

    private fun scanInitialDevices() {
        val dir = File("/dev/input")
        val entries = dir.listFiles { _, name -> name.startsWith("event") } ?: emptyArray()
        Log.i(TAG, "initial scan: ${entries.size} candidate(s) under /dev/input")
        for (entry in entries) {
            tryOpenDevice(entry.absolutePath)
        }
    }

    private fun tryOpenDevice(path: String) {
        val deviceId = parseDeviceId(path) ?: return
        if (devices.containsKey(deviceId)) return
        // Brick C feedback-loop fix: filter out Mappo's own virtual uinput devices
        // before opening. The kernel reflects every write we make to /dev/uinput
        // back through /dev/input/eventN, so without this filter the reader →
        // evaluator → gamepad writer → reader cycle would saturate the system
        // (verified 2026-05-28 on AYN Thor: console fans pegged, home launcher
        // unresponsive within seconds). Done via the sysfs name lookup so we
        // don't have to round-trip the kernel ioctl on every device-add.
        val deviceName = LinuxInputDeviceInfo.readDeviceName(path)
        if (deviceName != null && deviceName.startsWith(MAPPO_VIRTUAL_DEVICE_PREFIX)) {
            Log.i(TAG, "skipping $path: own virtual device \"$deviceName\"")
            return
        }
        val fd = try {
            Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EACCES) {
                Log.w(TAG, "open $path failed: EACCES (SELinux / permission restriction)")
            } else {
                Log.w(TAG, "open $path failed: errno=${e.errno} ${e.message}")
            }
            return
        } catch (e: Throwable) {
            Log.w(TAG, "open $path failed: ${e.javaClass.simpleName}", e)
            return
        }
        val entry = DeviceEntry(
            deviceId = deviceId,
            path = path,
            fd = fd,
            openedAtNs = SystemClock.elapsedRealtimeNanos(),
        )
        devices[deviceId] = entry
        devicesDirty.set(true)
        Log.i(TAG, "opened $path (id=$deviceId, name=\"${deviceName ?: "?"}\", sniffing…)")
        broadcastDeviceAdded(deviceId, path)
    }

    private fun closeDevice(deviceId: Int, reason: String) {
        val entry = devices.remove(deviceId) ?: return
        runCatching { Os.close(entry.fd) }
            .onFailure { Log.w(TAG, "close ${entry.path} failed", it) }
        devicesDirty.set(true)
        Log.i(TAG, "closed ${entry.path} (id=$deviceId, reason=$reason)")
        broadcastDeviceRemoved(deviceId)
    }

    private fun closeAllDevices() {
        for (id in devices.keys.toList()) closeDevice(id, "shutdown")
    }

    private fun parseDeviceId(path: String): Int? {
        val name = path.substringAfterLast('/')
        if (!name.startsWith("event")) return null
        return name.removePrefix("event").toIntOrNull()
    }

    // ── Reader thread ────────────────────────────────────────────────────────

    private fun runReaderLoop() {
        Log.i(TAG, "reader loop started")
        val parseBuf = ByteBuffer.allocate(EVENT_SIZE_BYTES * 32)
            .order(ByteOrder.LITTLE_ENDIAN)
        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val snapshot = devices.values.toList()
                if (snapshot.isEmpty()) {
                    Thread.sleep(POLL_TIMEOUT_MS_NO_DEVICES)
                    continue
                }
                val pollfds = Array(snapshot.size) { i ->
                    StructPollfd().apply {
                        fd = snapshot[i].fd
                        events = OsConstants.POLLIN.toShort()
                    }
                }
                val ready = try {
                    Os.poll(pollfds, POLL_TIMEOUT_MS)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    Log.w(TAG, "Os.poll failed", e)
                    continue
                }
                if (ready == 0) continue
                for (i in pollfds.indices) {
                    val revents = pollfds[i].revents.toInt()
                    if (revents == 0) continue
                    val entry = snapshot[i]
                    if (revents and (OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) {
                        closeDevice(entry.deviceId, "POLLHUP/POLLNVAL")
                        continue
                    }
                    if (revents and OsConstants.POLLIN != 0) {
                        if (!drainDevice(entry, parseBuf)) {
                            closeDevice(entry.deviceId, "drain failed")
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
            // expected on stopThreads()
        } catch (t: Throwable) {
            Log.e(TAG, "reader loop crashed", t)
        }
        Log.i(TAG, "reader loop exited")
    }

    /**
     * Read all available `input_event` records from [entry]'s file descriptor,
     * parse + dispatch. Returns `false` if the FD is in a non-recoverable state
     * (caller should close).
     */
    private fun drainDevice(entry: DeviceEntry, parseBuf: ByteBuffer): Boolean {
        while (true) {
            parseBuf.clear()
            val n = try {
                Os.read(entry.fd, parseBuf)
            } catch (e: ErrnoException) {
                return when (e.errno) {
                    OsConstants.EAGAIN -> true // no more data right now
                    OsConstants.EINTR -> true
                    OsConstants.ENODEV -> false // device unplugged
                    else -> {
                        Log.w(TAG, "read ${entry.path} failed: errno=${e.errno}", e)
                        false
                    }
                }
            }
            if (n <= 0) return n == 0 // EOF means closed cleanly
            parseBuf.flip()
            while (parseBuf.remaining() >= EVENT_SIZE_BYTES) {
                // Skip timeval (16 bytes); we use our own monotonic clock.
                parseBuf.position(parseBuf.position() + 16)
                val type = parseBuf.short.toInt() and 0xFFFF
                val code = parseBuf.short.toInt() and 0xFFFF
                val value = parseBuf.int
                handleEvent(entry, type, code, value)
            }
        }
    }

    private fun handleEvent(entry: DeviceEntry, type: Int, code: Int, value: Int) {
        when (type) {
            LinuxInputConstants.EV_ABS -> handleAbsEvent(entry, code, value)
            LinuxInputConstants.EV_SYN -> if (code == LinuxInputConstants.SYN_REPORT) {
                flushDirtySources(entry)
            }
            // EV_KEY — physical button press / release. Forwarded to :app via
            // `onRawKeyEvent` callback ONLY when the device is currently
            // grabbed; otherwise the OS still dispatches the events as
            // Android KeyEvents and :app's AccessibilityService handles them
            // through the normal onKeyEvent path. Forwarding both ways would
            // double-fire bindings (rumored in Brick C verification but never
            // a problem because EV_KEY was ignored entirely — until now).
            //
            // `value` semantics per linux/input.h: 0 = release, 1 = press,
            // 2 = autorepeat (we collapse autorepeats to "still pressed"
            // and rely on the activator engine for hold detection, so we
            // skip them).
            LinuxInputConstants.EV_KEY -> if (entry.grabbed && value != 2) {
                broadcastRawKey(code, pressed = value != 0)
            }
            else -> { /* no-op */ }
        }
    }

    private fun handleAbsEvent(entry: DeviceEntry, code: Int, value: Int) {
        // Touchscreens emit ABS_MT_* immediately on first touch; reject the
        // device. Cheaper than waiting for sniff to expire.
        if (LinuxInputConstants.isMultiTouchAbs(code)) {
            if (entry.classification != DeviceClass.Touchscreen) {
                Log.i(TAG, "device ${entry.path} classified as touchscreen (ABS_MT_*); will close")
                entry.classification = DeviceClass.Touchscreen
            }
            // Drain remaining events until the loop closes the FD.
            return
        }
        val mapping = LinuxInputConstants.mapAbsToSource(code) ?: return
        // First gamepad-like axis event upgrades classification.
        if (entry.classification == DeviceClass.Unknown) {
            entry.classification = DeviceClass.Gamepad
            Log.i(TAG, "device ${entry.path} classified as gamepad (first axis: code=0x${code.toString(16)})")
            // Apply current grab state to the just-classified gamepad —
            // covers the case where setGrabPhysicalControllers(true) was
            // called before this device finished sniffing.
            if (grabPhysicalControllers.get()) {
                applyGrabState(entry, true)
            }
        }
        if (entry.classification != DeviceClass.Gamepad) return
        // Self-calibrating min/max — Brick C doesn't have EVIOCGABS access without JNI.
        val cal = entry.calibrations.getOrPut(code) { AxisCalibration() }
        cal.observe(value)
        val normalized = cal.normalize(value)
        // Combine X+Y per source. For sources with only one axis (triggers),
        // axis[1] stays at 0.
        val axes = entry.sourceAxes.getOrPut(mapping.sourceId) { floatArrayOf(0f, 0f) }
        axes[mapping.axisIndex] = normalized
        // Mark source dirty; broadcast deferred to flushDirtySources on
        // SYN_REPORT so a stick's per-axis EV_ABS_X + EV_ABS_Y burst is
        // coalesced into a single RawAnalogEvent.
        entry.dirtySources += mapping.sourceId
    }

    /**
     * Emit a [RawAnalogEvent] per source updated since the last SYN_REPORT.
     * Called from [handleEvent] on EV_SYN / SYN_REPORT — the kernel's signal
     * that the current event group is complete and the (x, y) state is now
     * consistent.
     *
     * Defensive: also called on the periodic poll wake-up if the device is
     * idle long enough that a SYN never arrives (rare, but cheap to handle).
     */
    private fun flushDirtySources(entry: DeviceEntry) {
        if (entry.dirtySources.isEmpty()) return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        lastEventNs = nowNs
        for (sourceId in entry.dirtySources) {
            val axes = entry.sourceAxes[sourceId] ?: continue
            broadcastAnalogEvent(
                RawAnalogEvent(
                    sourceOrdinal = sourceId,
                    x = axes[0],
                    y = axes[1],
                    timestampNs = nowNs,
                )
            )
        }
        entry.dirtySources.clear()
    }

    // ── Watcher thread ───────────────────────────────────────────────────────

    private fun runWatcherLoop() {
        Log.i(TAG, "watcher loop started")
        val ws = try {
            FileSystems.getDefault().newWatchService()
        } catch (e: Throwable) {
            Log.w(TAG, "newWatchService failed; device hot-plug detection disabled", e)
            return
        }
        ws.use { watchService ->
            val devInput: Path = try {
                java.nio.file.Paths.get("/dev/input")
            } catch (e: Throwable) {
                Log.w(TAG, "/dev/input path resolution failed", e)
                return@use
            }
            try {
                devInput.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                )
            } catch (e: Throwable) {
                Log.w(TAG, "register /dev/input watch failed", e)
                return@use
            }
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val key: WatchKey = try {
                    watchService.take()
                } catch (_: InterruptedException) {
                    break
                }
                for (event in key.pollEvents()) {
                    val name = (event.context() as? Path)?.toString() ?: continue
                    if (!name.startsWith("event")) continue
                    val path = "/dev/input/$name"
                    when (event.kind()) {
                        StandardWatchEventKinds.ENTRY_CREATE -> {
                            Log.i(TAG, "watch: created $path")
                            // Small delay — kernel may not have set udev perms yet.
                            try {
                                Thread.sleep(DEVICE_OPEN_DELAY_MS)
                            } catch (_: InterruptedException) {
                                return@use
                            }
                            tryOpenDevice(path)
                        }
                        StandardWatchEventKinds.ENTRY_DELETE -> {
                            Log.i(TAG, "watch: deleted $path")
                            val id = parseDeviceId(path) ?: continue
                            closeDevice(id, "watch ENTRY_DELETE")
                        }
                    }
                }
                if (!key.reset()) break
            }
        }
        Log.i(TAG, "watcher loop exited")
    }

    // ── Callback broadcast ───────────────────────────────────────────────────

    private fun broadcastAnalogEvent(event: RawAnalogEvent) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    callbacks.getBroadcastItem(i).onAnalogEvent(event)
                } catch (_: RemoteException) {
                    // dead binder — RemoteCallbackList cleans up next cycle
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun broadcastDeviceAdded(deviceId: Int, path: String) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    callbacks.getBroadcastItem(i).onDeviceAdded(deviceId, path, 0)
                } catch (_: RemoteException) {
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun broadcastDeviceRemoved(deviceId: Int) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    callbacks.getBroadcastItem(i).onDeviceRemoved(deviceId)
                } catch (_: RemoteException) {
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun broadcastRawKey(linuxKeyCode: Int, pressed: Boolean) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    callbacks.getBroadcastItem(i).onRawKeyEvent(linuxKeyCode, pressed, nowNs)
                } catch (_: RemoteException) {
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    // ── Inner types ──────────────────────────────────────────────────────────

    private enum class DeviceClass { Unknown, Gamepad, Touchscreen }

    private class DeviceEntry(
        val deviceId: Int,
        val path: String,
        val fd: FileDescriptor,
        val openedAtNs: Long,
    ) {
        @Volatile var classification: DeviceClass = DeviceClass.Unknown

        /**
         * Whether this device currently has EVIOCGRAB held. Updated by
         * [applyGrabState] under the service's existing single-writer
         * invariant (only the reader / classification path mutates this).
         */
        @Volatile var grabbed: Boolean = false

        /** Per-axis min/max learning (no EVIOCGABS in Brick C). */
        val calibrations: MutableMap<Int, AxisCalibration> = HashMap()

        /** Combined axis state per source so an ABS_X event can emit (x, lastY). */
        val sourceAxes: MutableMap<Int, FloatArray> = HashMap()

        /**
         * Sources whose axes were updated since the last EV_SYN. Real input
         * drivers emit batched per-axis events (EV_ABS_X, EV_ABS_Y, ...)
         * followed by a single SYN_REPORT to mark "this position is now
         * complete." We coalesce: on each EV_ABS, mark the source dirty +
         * update its cached axes; on SYN_REPORT, broadcast one RawAnalogEvent
         * per dirty source. Without this batching, a 2-axis stick movement
         * arrives at consumers as TWO RawAnalogEvents (one per axis), which
         * the InputEvaluator + downstream double-counts (verified
         * 2026-05-28: home launcher's stick navigation moved two cells per
         * stick tilt in Joystick Move mode).
         */
        val dirtySources: MutableSet<Int> = HashSet()
    }

    /**
     * Self-calibrating axis normalizer. Watches raw values, tracks min/max
     * observed, and normalizes accordingly. Without EVIOCGABS we can't know
     * the true range up front — but on any real device the user will move
     * the stick to its extremes within seconds.
     *
     * Heuristics for the cold-start window (haven't seen extremes yet):
     *  - Bidirectional axis (any negative value observed): normalize to [-1, 1].
     *    Pessimistic estimate of `±max(|min|, |max|)` until both ends seen.
     *  - Unidirectional axis (only ≥ 0 values): normalize to [0, 1]. Use the
     *    larger of (observed max, conservative default of 255) as the divisor
     *    so early light trigger pulls don't accidentally hit ~1.0.
     */
    private class AxisCalibration {
        @Volatile private var minSeen: Int = Int.MAX_VALUE
        @Volatile private var maxSeen: Int = Int.MIN_VALUE

        fun observe(value: Int) {
            if (value < minSeen) minSeen = value
            if (value > maxSeen) maxSeen = value
        }

        fun normalize(value: Int): Float {
            if (minSeen == Int.MAX_VALUE) return 0f
            return if (minSeen < 0) {
                // Bidirectional: estimate symmetric range from the larger extreme.
                val span = max(abs(minSeen), abs(maxSeen))
                if (span == 0) 0f else (value.toFloat() / span).coerceIn(-1f, 1f)
            } else {
                // Unidirectional (trigger-style). Use a conservative default of 255
                // until we observe a larger value — prevents light pulls hitting 1.0.
                val maxEst = max(maxSeen, CONSERVATIVE_TRIGGER_MAX)
                (value.toFloat() / maxEst).coerceIn(0f, 1f)
            }
        }

        companion object {
            private const val CONSERVATIVE_TRIGGER_MAX = 255
        }

        /** For DPAD-style axes that emit −1/0/+1 directly. Used by callers that
         *  detect HAT codes and prefer raw mapping over calibration. */
        @Suppress("unused")
        fun normalizeAsHat(value: Int): Float = value.toFloat().coerceIn(-1f, 1f)
    }

    /**
     * Exempt `android.view.InputEvent`'s `setDisplayId(int)` from hidden-API
     * enforcement so [stampDisplayId] can route injects to a non-default
     * display (notably the AYN Thor's bottom screen, displayId=4). Mirrors the
     * `:app`-side exemption in `MappoApplication.installHiddenApiExemptions`,
     * scoped to this separate process. No-op on Android <9. Failures are
     * logged, not fatal — without the exemption injects still work on the
     * default display, just not the bottom screen.
     */
    private fun installHiddenApiExemptions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            // `Landroid/view/InputEvent;` covers stampDisplayId for cross-display
            // injects. `Ljava/io/FileDescriptor;` covers reading the integer
            // descriptor out of FileDescriptor for the EVIOCGRAB ioctl —
            // android.system.Os.open returns FileDescriptor, but the kernel
            // ioctl takes a raw int fd, and FileDescriptor's `descriptor`
            // field is hidden API since Android 9.
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/view/InputEvent;",
                "Ljava/io/FileDescriptor;",
            )
            Log.i(TAG, "HiddenApiBypass exemptions installed (InputEvent, FileDescriptor)")
        } catch (t: Throwable) {
            Log.w(TAG, "HiddenApiBypass install failed", t)
        }
    }

    /**
     * Read the underlying integer file descriptor out of a [FileDescriptor]
     * object. Needed for `ioctl(fd, EVIOCGRAB, ...)` in [EvdevGrab] since the
     * kernel call takes a raw fd, not a Java object.
     *
     * Uses reflection on the private `descriptor` field. Gated by the
     * HiddenApiBypass exemption installed at service init. Returns -1 on
     * failure (so callers can bail without throwing).
     */
    private fun fdInt(fd: FileDescriptor): Int = try {
        val field = FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        field.getInt(fd)
    } catch (t: Throwable) {
        Log.w(TAG, "fdInt reflection failed", t)
        -1
    }

    companion object {
        private const val TAG = "MappoInputUserService"

        /** Bumped whenever the AIDL contract or wire format changes shape. */
        const val PROTOCOL_VERSION: Int = 1

        /** `IInputManager#INJECT_INPUT_EVENT_MODE_ASYNC`. */
        private const val INJECT_INPUT_EVENT_MODE_ASYNC: Int = 0

        /** `Os.poll` timeout (ms) when we have devices. Lets the loop wake
         *  periodically to refresh its FD list after device add/remove. */
        private const val POLL_TIMEOUT_MS: Int = 250

        /** Sleep when no devices are open. */
        private const val POLL_TIMEOUT_MS_NO_DEVICES: Long = 1_000L

        /** Small wait after a WatchService CREATE event before opening — gives
         *  udev / kernel time to set permissions. Some devices have an EACCES
         *  window between the inode appearing and shell becoming able to read. */
        private const val DEVICE_OPEN_DELAY_MS: Long = 100L

        /**
         * Brick C: Mappo's own virtual uinput devices ([UinputMouse] /
         * [UinputGamepad]) have EVIOCGNAME values starting with this string —
         * [tryOpenDevice] filters them out so the kernel's echo of our own
         * writes doesn't feed back into the reader.
         */
        private const val MAPPO_VIRTUAL_DEVICE_PREFIX = "Mappo Virtual"
    }
}
