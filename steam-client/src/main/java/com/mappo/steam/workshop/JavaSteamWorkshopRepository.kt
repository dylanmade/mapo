package com.mappo.steam.workshop

import android.util.Log
import com.mappo.steam.session.SteamSessionFactory
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.enums.EWorkshopFileType
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Request
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class JavaSteamWorkshopRepository(
    private val sessionFactory: SteamSessionFactory,
) : SteamWorkshopRepository {

    // Cache of the last queryConfigs result, keyed by publishedFileId.
    // Lets the detail screen render without a second RPC. Replaced
    // wholesale on each queryConfigs call — we don't accumulate across
    // multiple games.
    private val cache = ConcurrentHashMap<Long, WorkshopConfig>()

    override fun getConfig(publishedFileId: Long): WorkshopConfig? = cache[publishedFileId]

    override suspend fun queryConfigs(
        gameAppId: Int,
        pageSize: Int,
    ): List<WorkshopConfig> = withContext(Dispatchers.IO) {
        val session = sessionFactory.open()
        if (session == null) {
            Log.w(TAG, "queryConfigs: no session — caller should re-auth")
            return@withContext emptyList()
        }

        session.use {
            val request = CPublishedFile_QueryFiles_Request.newBuilder().apply {
                // `appid` is the workshop the items live in (241100 =
                // Steam Controller Configs). The TARGET GAME filter is
                // expressed as a required KV tag `app=<gameappid>`, NOT
                // via the `appid` field — verified against gryffyn's
                // SCConfigDownloader (sccdownloader/main.cs L344-L365),
                // which is the canonical reference.
                appid = STEAM_CONTROLLER_CONFIGS_APPID
                // 11 = RankedByVotesUp — what SCConfigDownloader uses
                // for "Most Popular." See EPublishedFileQueryType.
                queryType = 11
                // GameManagedItem (15) is the workshop file type that
                // Steam Input configs are stored as. Filtering to it
                // here skips noise from other UGC types in 241100.
                filetype = EWorkshopFileType.GameManagedItem.code()
                numperpage = pageSize

                addRequiredKvTags(
                    CPublishedFile_QueryFiles_Request.KVTag.newBuilder()
                        .setKey("app").setValue(gameAppId.toString()).build(),
                )
                // Skip private / friends-only configs.
                addRequiredKvTags(
                    CPublishedFile_QueryFiles_Request.KVTag.newBuilder()
                        .setKey("visibility").setValue("public").build(),
                )

                returnVoteData = true
                returnTags = true
                returnKvTags = true
                returnPreviews = true
                returnMetadata = true
                returnShortDescription = true
                returnChildren = true
            }.build()

            Log.i(TAG, "QueryFiles for gameAppId=$gameAppId pageSize=$pageSize")
            val response = withTimeoutOrNull(30_000L) {
                session.publishedFile.queryFiles(request).toFuture().await()
            } ?: run {
                Log.w(TAG, "QueryFiles timed out for gameAppId=$gameAppId")
                return@use emptyList()
            }

            if (response.result != EResult.OK) {
                Log.w(TAG, "QueryFiles failed: ${response.result}")
                return@use emptyList()
            }

            val body = response.body.build()
            Log.i(TAG, "QueryFiles returned ${body.publishedfiledetailsCount} items (total=${body.total})")

            val configs = body.publishedfiledetailsList.map { d ->
                WorkshopConfig(
                    // Identity / display
                    publishedFileId = d.publishedfileid,
                    title = d.title.ifEmpty { d.publishedfileid.toString() },
                    fileDescription = d.fileDescription.orEmpty(),
                    shortDescription = d.shortDescription.orEmpty(),
                    language = d.language,
                    previewUrl = d.previewUrl.takeIf { it.isNotEmpty() },
                    previews = d.previewsList.map { p ->
                        Preview(
                            previewId = p.previewid,
                            sortOrder = p.sortorder,
                            url = p.url.orEmpty(),
                            sizeBytes = p.size,
                            filename = p.filename.orEmpty(),
                            youtubeVideoId = p.youtubevideoid.orEmpty(),
                            previewType = p.previewType,
                            externalReference = p.externalReference.orEmpty(),
                        )
                    },
                    imageUrl = d.imageUrl.takeIf { it.isNotEmpty() },
                    imageWidth = d.imageWidth,
                    imageHeight = d.imageHeight,
                    // Authorship
                    creatorSteamId64 = d.creator,
                    creatorAppId = d.creatorAppid,
                    consumerAppId = d.consumerAppid,
                    appName = d.appName.orEmpty(),
                    // Popularity / social
                    voteScore = d.voteData?.score ?: 0f,
                    votesUp = d.voteData?.votesUp ?: 0,
                    votesDown = d.voteData?.votesDown ?: 0,
                    subscriptions = d.subscriptions,
                    lifetimeSubscriptions = d.lifetimeSubscriptions,
                    favorited = d.favorited,
                    lifetimeFavorited = d.lifetimeFavorited,
                    followers = d.followers,
                    lifetimeFollowers = d.lifetimeFollowers,
                    views = d.views,
                    numCommentsPublic = d.numCommentsPublic,
                    // Recency
                    timeCreatedEpochSec = d.timeCreated,
                    timeUpdatedEpochSec = d.timeUpdated,
                    revisionChangeNumber = d.revisionChangeNumber,
                    // Categorization
                    tags = d.tagsList.map { t ->
                        Tag(
                            tag = t.tag.orEmpty(),
                            displayName = t.displayName.orEmpty().ifEmpty { t.tag.orEmpty() },
                            adminOnly = t.adminonly,
                        )
                    },
                    kvTags = d.kvtagsList.map { kv ->
                        KvTag(key = kv.key.orEmpty(), value = kv.value.orEmpty())
                    },
                    flags = d.flags,
                    visibility = d.visibility,
                    maybeInappropriateSex = d.maybeInappropriateSex,
                    maybeInappropriateViolence = d.maybeInappropriateViolence,
                    spoilerTag = d.spoilerTag,
                    // File info
                    fileName = d.filename.orEmpty(),
                    fileSizeBytes = d.fileSize,
                    fileUrl = d.fileUrl.orEmpty(),
                    hcontentFile = d.hcontentFile,
                    // Misc
                    metadata = d.metadata.orEmpty(),
                    numChildren = d.numChildren,
                    children = d.childrenList.map { it.publishedfileid },
                )
            }

            cache.clear()
            configs.forEach { cache[it.publishedFileId] = it }
            Log.i(TAG, "Cached ${configs.size} configs (cache size=${cache.size})")

            configs
        }
    }

    private companion object {
        const val TAG = "MappoSteamWorkshop"
        const val STEAM_CONTROLLER_CONFIGS_APPID = 241100
    }
}
