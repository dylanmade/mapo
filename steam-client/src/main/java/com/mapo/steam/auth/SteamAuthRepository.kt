package com.mapo.steam.auth

import kotlinx.coroutines.flow.Flow

/**
 * Steam account authentication. Currently exposes Steam Guard QR
 * device-grant login as the only auth path — same flow GameNative uses,
 * same flow Steam's mobile app uses for "Approve from another device."
 *
 * The user opens the Steam mobile app, taps the QR scanner, points it
 * at the QR rendered by Mapo's [SteamSetupScreen], and approves.
 * Mapo never sees a password.
 */
interface SteamAuthRepository {
    /**
     * Start a fresh QR login. Cold flow — subscribing begins the Steam
     * connection + QR session; cancelling disconnects.
     *
     * The flow always begins with [QrLoginState.Connecting]; emits one
     * or more [QrLoginState.ChallengeUrl] values (Steam rotates the
     * challenge every ~30s to keep stale screenshots from working);
     * may emit [QrLoginState.AwaitingApproval] once the user's phone
     * has scanned but not yet tapped Approve; and terminates with
     * [QrLoginState.Success] or [QrLoginState.Error].
     *
     * @param deviceLabel Shown to the user inside their phone Steam app
     * when they approve the login (e.g. "Mapo (AYN Thor)").
     */
    fun startQrLogin(deviceLabel: String): Flow<QrLoginState>
}

/**
 * Sequential states emitted by [SteamAuthRepository.startQrLogin].
 */
sealed interface QrLoginState {
    /** Opening the Steam client connection. */
    data object Connecting : QrLoginState

    /**
     * A QR challenge is live. [url] encodes the Steam Guard payload —
     * render as a QR code for the user to scan with their phone Steam
     * app. Steam rotates the challenge every ~30s; the flow will emit
     * a fresh [ChallengeUrl] each time, and the UI should replace the
     * rendered QR on each emission.
     */
    data class ChallengeUrl(val url: String) : QrLoginState

    /**
     * User's phone has scanned the QR; Steam is waiting for them to
     * tap Approve. Some flows skip this state and go straight to
     * [Success].
     */
    data object AwaitingApproval : QrLoginState

    /** Login succeeded. Credentials are ready to persist. */
    data class Success(
        val credentials: SteamCredentials,
    ) : QrLoginState

    /**
     * Terminal failure. [reason] is a developer-facing string; the UI
     * should map to a user-facing message rather than displaying it raw.
     */
    data class Error(val reason: String) : QrLoginState
}
