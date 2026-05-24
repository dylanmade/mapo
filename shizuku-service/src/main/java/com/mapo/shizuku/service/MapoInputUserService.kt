package com.mapo.shizuku.service

import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.mapo.shizuku.IMapoInputCallback
import com.mapo.shizuku.IMapoInputService
import com.mapo.shizuku.InjectKeyRequest
import com.mapo.shizuku.InputSourceId
import com.mapo.shizuku.LinuxInputConstants
import com.mapo.shizuku.LinuxInputConstants.EVENT_SIZE_BYTES
import com.mapo.shizuku.RawAnalogEvent
import com.mapo.shizuku.ShizukuServiceHealth
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
 * Mapo's Shizuku UserService — runs as UID 2000 (shell) in a separate process.
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
 *  - [injectKeyEvent] / [setEnumerationEnabled] remain stubs from Brick B.
 *
 * **Threading.** Two background threads owned by this service:
 *  - `mapo-input-reader`: blocks on `Os.poll` over all open device FDs, parses
 *    events, emits [RawAnalogEvent]s.
 *  - `mapo-input-watcher`: blocks on `WatchService.take`, signals the reader
 *    thread to refresh its FD list when `/dev/input/` changes.
 *
 * **No Hilt / Compose / Room.** This process boots fast; keep deps minimal.
 */
class MapoInputUserService : IMapoInputService.Stub() {

    private val callbacks = RemoteCallbackList<IMapoInputCallback>()

    /** Active devices. Keyed by deviceId (extracted from "/dev/input/eventN"). */
    private val devices = ConcurrentHashMap<Int, DeviceEntry>()

    /** Set when the reader thread should refresh its FD list (devices map changed). */
    private val devicesDirty = AtomicBoolean(false)

    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null
    private var watcherThread: Thread? = null

    /** Most recent monotonic timestamp emitted. Surfaced in [ShizukuServiceHealth]. */
    @Volatile
    private var lastEventNs: Long = 0L

    override fun getProtocolVersion(): Int = PROTOCOL_VERSION

    override fun registerCallback(cb: IMapoInputCallback?) {
        if (cb == null) return
        callbacks.register(cb)
        val count = callbacks.registeredCallbackCount
        Log.i(TAG, "callback registered; count=$count")
        if (count == 1 && running.compareAndSet(false, true)) {
            startThreads()
        }
    }

    override fun unregisterCallback(cb: IMapoInputCallback?) {
        if (cb == null) return
        callbacks.unregister(cb)
        val count = callbacks.registeredCallbackCount
        Log.i(TAG, "callback unregistered; count=$count")
        if (count == 0 && running.compareAndSet(true, false)) {
            stopThreads()
        }
    }

    override fun injectKeyEvent(req: InjectKeyRequest?): Boolean {
        // Brick E implements: reconstruct KeyEvent, stamp displayId via
        // HiddenApiBypass, reflective IInputManager.injectInputEvent call from
        // shell context.
        Log.d(TAG, "injectKeyEvent stub returning false (Brick E will implement): req=$req")
        return false
    }

    override fun setEnumerationEnabled(on: Boolean) {
        // Brick F's coordinator drives this off the gating predicate. Brick C's
        // lifecycle is tied to registerCallback count instead — for now this is
        // an observation hook only.
        Log.d(TAG, "setEnumerationEnabled stub (Brick F coordinator will drive this): on=$on")
    }

    override fun destroy() {
        Log.i(TAG, "destroy() — stopping threads + exiting shell-uid process")
        running.set(false)
        callbacks.kill()
        stopThreads()
        closeAllDevices()
        System.exit(0)
    }

    // ── Thread management ────────────────────────────────────────────────────

    private fun startThreads() {
        Log.i(TAG, "starting reader + watcher threads")
        scanInitialDevices()
        readerThread = thread(name = "mapo-input-reader", start = true, isDaemon = true) {
            runReaderLoop()
        }
        watcherThread = thread(name = "mapo-input-watcher", start = true, isDaemon = true) {
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
        Log.i(TAG, "opened $path (id=$deviceId, sniffing…)")
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
            // Brick C ignores EV_KEY and EV_SYN; existing AccessibilityService
            // captures KEY events (gamepad buttons) without Shizuku.
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
        val nowNs = SystemClock.elapsedRealtimeNanos()
        lastEventNs = nowNs
        broadcastAnalogEvent(
            RawAnalogEvent(
                sourceOrdinal = mapping.sourceId,
                x = axes[0],
                y = axes[1],
                timestampNs = nowNs,
            )
        )
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

    // ── Inner types ──────────────────────────────────────────────────────────

    private enum class DeviceClass { Unknown, Gamepad, Touchscreen }

    private class DeviceEntry(
        val deviceId: Int,
        val path: String,
        val fd: FileDescriptor,
        val openedAtNs: Long,
    ) {
        @Volatile var classification: DeviceClass = DeviceClass.Unknown

        /** Per-axis min/max learning (no EVIOCGABS in Brick C). */
        val calibrations: MutableMap<Int, AxisCalibration> = HashMap()

        /** Combined axis state per source so an ABS_X event can emit (x, lastY). */
        val sourceAxes: MutableMap<Int, FloatArray> = HashMap()
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

    companion object {
        private const val TAG = "MapoInputUserService"

        /** Bumped whenever the AIDL contract or wire format changes shape. */
        const val PROTOCOL_VERSION: Int = 1

        /** `Os.poll` timeout (ms) when we have devices. Lets the loop wake
         *  periodically to refresh its FD list after device add/remove. */
        private const val POLL_TIMEOUT_MS: Int = 250

        /** Sleep when no devices are open. */
        private const val POLL_TIMEOUT_MS_NO_DEVICES: Long = 1_000L

        /** Small wait after a WatchService CREATE event before opening — gives
         *  udev / kernel time to set permissions. Some devices have an EACCES
         *  window between the inode appearing and shell becoming able to read. */
        private const val DEVICE_OPEN_DELAY_MS: Long = 100L
    }
}
