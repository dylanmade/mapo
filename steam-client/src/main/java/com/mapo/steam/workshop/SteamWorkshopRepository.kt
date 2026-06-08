package com.mapo.steam.workshop

/**
 * Reads Steam Input controller-config Workshop items. POC scope: list
 * by most-popular for a given game appid + look up the full details
 * of a single config (from an in-memory cache populated by the last
 * [queryConfigs] call). Body download + import live in the next brick.
 */
interface SteamWorkshopRepository {
    /**
     * Fetch up to [pageSize] community controller configs for the given
     * [gameAppId], ranked by lifetime votes-up. Returns empty on
     * failure (logged under tag `MapoSteamWorkshop`).
     *
     * Results are cached in-memory by publishedFileId — [getConfig] is
     * the lookup. The cache lives until the next [queryConfigs] call;
     * we don't persist anything to disk in the POC.
     */
    suspend fun queryConfigs(gameAppId: Int, pageSize: Int = 30): List<WorkshopConfig>

    /**
     * Returns the [WorkshopConfig] for [publishedFileId] if it's in the
     * cache (i.e. it was returned by a recent [queryConfigs] call).
     * Returns null otherwise — we don't currently issue a separate
     * `PublishedFile.GetDetails` round-trip on miss.
     */
    fun getConfig(publishedFileId: Long): WorkshopConfig?
}

/**
 * One Workshop item, with every field [PublishedFileDetails] returned —
 * the list UI shows a few; the detail UI shows them all.
 *
 * Field naming follows Kotlin convention (camelCase). Proto field-name
 * mappings noted in comments where the renaming isn't trivial.
 */
data class WorkshopConfig(
    // ── Identity / display ────────────────────────────────────────
    val publishedFileId: Long,
    val title: String,
    /** Long-form description; often contains Steam-flavored markdown. */
    val fileDescription: String,
    /** Single-line summary. Empty unless `return_short_description=true`. */
    val shortDescription: String,
    /** Steam language code the config was published in. */
    val language: Int,
    /** Convenience URL of the first preview, or null if there are none. */
    val previewUrl: String?,
    /** All previews (each may be image / video / external link). */
    val previews: List<Preview>,
    /** Header image URL (different from preview list). */
    val imageUrl: String?,
    val imageWidth: Int,
    val imageHeight: Int,

    // ── Authorship ────────────────────────────────────────────────
    /** Author's SteamID64. */
    val creatorSteamId64: Long,
    /** Workshop appid (always 241100 for Steam Input configs). */
    val creatorAppId: Int,
    /** Target game's appid (mirrors the `app` kvtag). */
    val consumerAppId: Int,
    /** Target game's display name. */
    val appName: String,

    // ── Popularity / social ───────────────────────────────────────
    val voteScore: Float,
    val votesUp: Int,
    val votesDown: Int,
    val subscriptions: Int,
    val lifetimeSubscriptions: Int,
    val favorited: Int,
    val lifetimeFavorited: Int,
    val followers: Int,
    val lifetimeFollowers: Int,
    val views: Int,
    val numCommentsPublic: Int,

    // ── Recency / freshness ───────────────────────────────────────
    /** Epoch seconds. */
    val timeCreatedEpochSec: Int,
    /** Epoch seconds — last revision. */
    val timeUpdatedEpochSec: Int,
    val revisionChangeNumber: Long,

    // ── Categorization ────────────────────────────────────────────
    /** User-facing category labels (e.g. "Gyro", "Trackpad Mouse"). */
    val tags: List<Tag>,
    /** Typed metadata (e.g. `app=550`, `controller_neptune=true`). */
    val kvTags: List<KvTag>,
    val flags: Int,
    /** 0=public, 1=friends-only, 2=private. */
    val visibility: Int,
    val maybeInappropriateSex: Boolean,
    val maybeInappropriateViolence: Boolean,
    val spoilerTag: Boolean,

    // ── File info ─────────────────────────────────────────────────
    val fileName: String,
    val fileSizeBytes: Long,
    /** Direct URL to the VDF body when Steam returns one; else empty. */
    val fileUrl: String,
    /** CDN content handle for depot fetch when fileUrl is empty. */
    val hcontentFile: Long,

    // ── Misc ──────────────────────────────────────────────────────
    /** Arbitrary string the creator can set (often empty). */
    val metadata: String,
    val numChildren: Int,
    /** Child publishedFileIds (for collections). */
    val children: List<Long>,
)

data class Preview(
    val previewId: Long,
    val sortOrder: Int,
    val url: String,
    val sizeBytes: Int,
    val filename: String,
    val youtubeVideoId: String,
    /** Steam preview-type enum — image/video/external etc. */
    val previewType: Int,
    val externalReference: String,
)

data class Tag(
    /** Internal tag name. */
    val tag: String,
    /** User-facing localized label. */
    val displayName: String,
    /** True when only Workshop admins can apply this tag. */
    val adminOnly: Boolean,
)

data class KvTag(
    val key: String,
    val value: String,
)
