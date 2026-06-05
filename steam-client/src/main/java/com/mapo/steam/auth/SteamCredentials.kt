package com.mapo.steam.auth

/**
 * Persisted Steam session credentials. The refresh token is the
 * long-lived secret — exchanged at session-start for short-lived
 * access tokens. Treat it as password-equivalent (encrypted storage,
 * never logged, never leaves the device).
 *
 * [accountName] is the Steam login name (e.g. "dylanbperry"), captured
 * at QR-login time so the drawer / setup screen can show it without
 * re-querying Steam.
 */
data class SteamCredentials(
    val accountName: String,
    val refreshToken: String,
)
