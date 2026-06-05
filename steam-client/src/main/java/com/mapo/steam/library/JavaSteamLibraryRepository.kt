package com.mapo.steam.library

import android.util.Log
import com.mapo.steam.session.SteamSessionFactory
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient.CPlayer_GetOwnedGames_Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class JavaSteamLibraryRepository(
    private val sessionFactory: SteamSessionFactory,
) : SteamLibraryRepository {

    override suspend fun fetchOwnedGames(): List<OwnedGame> = withContext(Dispatchers.IO) {
        val session = sessionFactory.open()
        if (session == null) {
            Log.w(TAG, "fetchOwnedGames: no session — caller should re-auth")
            return@withContext emptyList()
        }

        session.use {
            val request = CPlayer_GetOwnedGames_Request.newBuilder().apply {
                steamid = session.steamId64
                includeAppinfo = true
                includePlayedFreeGames = true
                language = "english"
            }.build()

            Log.i(TAG, "GetOwnedGames for steamId64=${session.steamId64}")
            val response = withTimeoutOrNull(15_000L) {
                session.player.getOwnedGames(request).toFuture().await()
            } ?: run {
                Log.w(TAG, "GetOwnedGames timed out")
                return@use emptyList()
            }

            if (response.result != EResult.OK) {
                Log.w(TAG, "GetOwnedGames failed: ${response.result}")
                return@use emptyList()
            }

            val body = response.body.build()
            Log.i(TAG, "GetOwnedGames returned ${body.gamesCount} games (total=${body.gameCount})")

            body.gamesList.map { game ->
                OwnedGame(
                    appId = game.appid,
                    name = game.name.ifEmpty { "App ${game.appid}" },
                    iconUrl = game.imgIconUrl.takeIf { it.isNotEmpty() }?.let { hash ->
                        "https://media.steampowered.com/steamcommunity/public/images/apps/${game.appid}/$hash.jpg"
                    },
                    playtimeMinutes = game.playtimeForever,
                )
            }
        }
    }

    private companion object {
        const val TAG = "MapoSteamLibrary"
    }
}
