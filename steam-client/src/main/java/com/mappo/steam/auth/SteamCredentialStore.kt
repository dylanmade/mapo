package com.mappo.steam.auth

import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for [SteamCredentials]. The interface lives in
 * :steam-client so the auth repository can read existing credentials
 * (for silent-resume on app launch) without depending on Android storage
 * APIs. The actual EncryptedSharedPreferences-backed implementation
 * lives in :app where AndroidX security-crypto is on the classpath.
 */
interface SteamCredentialStore {
    /** Reactive read — emits the current credentials, or null if signed out. */
    val credentials: Flow<SteamCredentials?>

    /** Snapshot read. */
    suspend fun get(): SteamCredentials?

    suspend fun save(credentials: SteamCredentials)

    /** Wipe local credentials. Does not revoke the token with Steam. */
    suspend fun clear()
}
