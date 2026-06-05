package com.mapo.steam.workshop

/**
 * Reads Steam Input controller-config Workshop items. POC scope: list
 * by most-popular for a given game appid. Body download + import live
 * in the next brick.
 */
interface SteamWorkshopRepository {
    /**
     * Fetch up to [pageSize] community controller configs for the given
     * [gameAppId], ranked by lifetime unique subscriptions. Returns
     * empty on failure (logged under tag `MapoSteamWorkshop`).
     */
    suspend fun queryConfigs(gameAppId: Int, pageSize: Int = 30): List<ConfigSummary>
}

/**
 * One row in the browse list. Just the fields the POC UI renders —
 * extended details (description, preview image, tags, file URLs) come
 * later when we wire up the download flow.
 */
data class ConfigSummary(
    val publishedFileId: Long,
    val title: String,
    val creatorSteamId64: Long,
    val votesUp: Int,
    val votesDown: Int,
    val subscriptions: Int,
    val timeUpdatedEpochSec: Int,
    val previewUrl: String?,
)
