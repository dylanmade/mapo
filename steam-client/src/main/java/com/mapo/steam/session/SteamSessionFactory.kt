package com.mapo.steam.session

/**
 * Opens authenticated [SteamSession]s for callers that need to make
 * unified-messages RPC calls (workshop browse, owned games, etc.).
 */
interface SteamSessionFactory {
    /**
     * Connect a fresh SteamClient and log on with the saved refresh
     * token. Returns null if no credentials are saved or the logon
     * fails — caller should surface "sign in first" / "session
     * expired" appropriately. Failures are logged under tag
     * `MapoSteamSession`.
     */
    suspend fun open(): SteamSession?
}
