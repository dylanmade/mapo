package com.mappo.steam.session

import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import `in`.dragonbra.javasteam.rpc.service.Player
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient

/**
 * A connected + logged-on Steam session. Hands out the RPC service
 * stubs callers need to issue unified-messages requests. Must be
 * [close]d when done — disconnects from Steam, stops the callback pump.
 *
 * Open one via [SteamSessionFactory.open]. POC layer creates a fresh
 * session per browse action; a longer-lived pooled session lands later
 * once we know the access patterns.
 */
interface SteamSession : AutoCloseable {
    /** The signed-in user's SteamID64. Set after LoggedOnCallback OK. */
    val steamId64: Long

    /** Underlying client — exposed for unified-messages SendNotification calls etc. */
    val client: SteamClient

    /** PublishedFile RPC service — QueryFiles, GetUserFiles, etc. */
    val publishedFile: PublishedFile

    /** Player RPC service — GetOwnedGames, GetPlayNext, etc. */
    val player: Player
}
