package com.mapo.shizuku;

import com.mapo.shizuku.IMapoInputCallback;
import com.mapo.shizuku.InjectKeyRequest;

/**
 * Binder contract for Mapo's Shizuku UserService. The service runs as UID 2000
 * (shell) in a separate process; :app calls these methods via the binder Shizuku
 * hands back from `Shizuku.bindUserService`.
 *
 * Transaction codes are pinned so adding a method doesn't renumber existing
 * ones. `destroy()` at 16777114 is Shizuku's reserved transaction — Shizuku
 * invokes it on unbindUserService(remove=true) so the service can clean up
 * file descriptors etc. before its process exits.
 */
interface IMapoInputService {
    /**
     * Reserved Shizuku UserService teardown hook. Implementations should close
     * any open `/dev/input/event*` file descriptors and call `System.exit(0)`
     * (Shizuku's convention) so the process is gone for good.
     */
    void destroy() = 16777114;

    /**
     * Service-binary version. The app checks this against its expected
     * PROTOCOL_VERSION on bind; mismatch means a stale UserService binary
     * was launched and the service must be rebuilt (Shizuku auto-restarts on
     * `version()` change in UserServiceArgs).
     */
    int getProtocolVersion() = 1;

    void registerCallback(IMapoInputCallback cb) = 2;
    void unregisterCallback(IMapoInputCallback cb) = 3;

    /**
     * Sync method — caller needs the success boolean to decide whether to
     * fall back to the in-process reflection inject (Brick E pattern).
     */
    boolean injectKeyEvent(in InjectKeyRequest req) = 4;

    /**
     * Pause/resume the `/dev/input/event*` read loop. Coordinator (Brick F)
     * drives this off the gating predicate so we close file descriptors when
     * no analog mode is active (battery + FD-pressure win).
     */
    void setEnumerationEnabled(boolean on) = 5;

    /**
     * **Spike (post-Brick-J).** Inject a SOURCE_MOUSE motion event so the
     * foreground app sees pointer motion instead of synthetic touch. Caller
     * tracks cursor position; service constructs the MotionEvent and routes
     * it through `IInputManager.injectInputEvent` from the shell-uid process.
     *
     * Why this exists: synthetic touch (dispatchGesture) routes through the
     * touch dispatcher, which runs gesture detectors (notification pull-down,
     * back, home pill) before delivering to the app. Mouse motion routes
     * through the pointer dispatcher and bypasses those detectors entirely —
     * the same path a real USB mouse uses. Removes the entire "where to put
     * the bounds margins" class of problems and the no-teleport vs
     * relative-emulator-cursor tension.
     *
     * Returns true iff the inject succeeded (IInputManager.injectInputEvent
     * returned true). Caller's fallback path (dispatchGesture-based touch)
     * runs on false.
     */
    boolean injectMouseMotion(float absX, float absY, float relDx, float relDy, int displayId) = 6;

    /**
     * Press or release a mouse button on the virtual uinput device. `btnCode`
     * is one of the `LinuxInputConstants.BTN_*` values (LEFT/RIGHT/MIDDLE/
     * SIDE/EXTRA). For a click, caller invokes this with `pressed=true` then
     * `pressed=false` after a short interval (or back-to-back; modern apps
     * tolerate either). Returns true iff the inject was dispatched.
     */
    boolean injectMouseButton(int btnCode, boolean pressed) = 7;

    /**
     * Emit a scroll-wheel event. `dx`/`dy` are integer notch counts
     * (1 = one detent up/right, -1 = down/left). Apps respond to `dy`
     * primarily; horizontal scroll is supported but used less often.
     * Routed to REL_WHEEL / REL_HWHEEL on the virtual uinput device.
     */
    boolean injectMouseScroll(int dx, int dy) = 8;
}
