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
}
