package com.mappo.steam.library

/**
 * Reads game-library data from Steam. POC scope: just the owned-games
 * list, used by the config browser's per-game picker.
 */
interface SteamLibraryRepository {
    suspend fun fetchOwnedGames(): List<OwnedGame>
}

/**
 * One row in the user's owned-games list.
 *
 * [iconUrl] is the full CDN URL when an icon is available, or null
 * when Steam didn't return an `img_icon_url` hash for this game (some
 * older / tooling appids legitimately lack one).
 */
data class OwnedGame(
    val appId: Int,
    val name: String,
    val iconUrl: String?,
    val playtimeMinutes: Int,
)
