package com.mapo.steam.workshop

import android.util.Log
import com.mapo.steam.session.SteamSessionFactory
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.enums.EWorkshopFileType
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class JavaSteamWorkshopRepository(
    private val sessionFactory: SteamSessionFactory,
) : SteamWorkshopRepository {

    override suspend fun queryConfigs(
        gameAppId: Int,
        pageSize: Int,
    ): List<ConfigSummary> = withContext(Dispatchers.IO) {
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

            body.publishedfiledetailsList.map { d ->
                ConfigSummary(
                    publishedFileId = d.publishedfileid,
                    title = d.title.ifEmpty { d.publishedfileid.toString() },
                    creatorSteamId64 = d.creator,
                    votesUp = d.voteData?.votesUp ?: 0,
                    votesDown = d.voteData?.votesDown ?: 0,
                    subscriptions = d.subscriptions,
                    timeUpdatedEpochSec = d.timeUpdated,
                    previewUrl = d.previewUrl.takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    private companion object {
        const val TAG = "MapoSteamWorkshop"
        const val STEAM_CONTROLLER_CONFIGS_APPID = 241100
    }
}
