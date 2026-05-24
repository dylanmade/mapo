package com.mapo.service.shizuku

/**
 * Coarse state of the Shizuku integration. Polled + listener-driven from
 * [ShizukuConnection]; observed by UI (Setup screen, mode-pick gate dialog,
 * health notification) and by [com.mapo.service.shizuku.ShizukuConnection.isReadyFlow]
 * which downstream callers read at inject time.
 *
 * Ordering reflects the user-facing setup funnel: install → start → grant.
 */
sealed class ShizukuState {
    /** Shizuku Manager app not installed on the device. */
    object NotInstalled : ShizukuState()

    /** Shizuku Manager is installed but the service binder is not alive (user
     *  hasn't started Shizuku via wireless debugging or the start.sh script). */
    object InstalledNotRunning : ShizukuState()

    /** Shizuku binder is alive but Mapo lacks `MANAGE_APP_OPS_TARGETS` (the
     *  permission Shizuku Manager hands out). User needs to grant. */
    object RunningNotGranted : ShizukuState()

    /** Granted + binder alive. Analog modes can light up. */
    object Granted : ShizukuState()
}
