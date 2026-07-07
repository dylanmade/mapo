package com.mappo.steam.session

import android.util.Log
import com.mappo.steam.auth.SteamCredentialStore
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import `in`.dragonbra.javasteam.rpc.service.Player
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class JavaSteamSessionFactory(
    private val credentialStore: SteamCredentialStore,
) : SteamSessionFactory {

    override suspend fun open(): SteamSession? {
        val creds = credentialStore.get()
        if (creds == null) {
            Log.w(TAG, "open() called with no saved credentials")
            return null
        }

        val steamClient = SteamClient()
        val callbackManager = CallbackManager(steamClient)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val connectedSignal = CompletableDeferred<Boolean>()
        val logonSignal = CompletableDeferred<EResult>()

        val subscriptions = mutableListOf<Closeable>()
        subscriptions += callbackManager.subscribe(ConnectedCallback::class.java) { _ ->
            Log.i(TAG, "Connected callback received")
            if (!connectedSignal.isCompleted) connectedSignal.complete(true)
        }
        subscriptions += callbackManager.subscribe(DisconnectedCallback::class.java) { _ ->
            Log.w(TAG, "Disconnected callback received")
            if (!connectedSignal.isCompleted) connectedSignal.complete(false)
            if (!logonSignal.isCompleted) logonSignal.complete(EResult.Cancelled)
        }
        subscriptions += callbackManager.subscribe(LoggedOnCallback::class.java) { cb ->
            Log.i(TAG, "LoggedOn callback received: ${cb.result}")
            if (!logonSignal.isCompleted) logonSignal.complete(cb.result)
        }

        val pumpJob: Job = scope.launch {
            while (isActive) {
                runCatching { callbackManager.runWaitCallbacks(1_000L) }
                    .onFailure { Log.w(TAG, "runWaitCallbacks error", it) }
            }
        }

        fun teardown() {
            pumpJob.cancel()
            scope.cancel()
            subscriptions.forEach { runCatching { it.close() } }
            runCatching { steamClient.disconnect() }
        }

        Log.i(TAG, "Connecting Steam client (session resume for account='${creds.accountName}')")
        withContext(Dispatchers.IO) { steamClient.connect() }

        val connected = withTimeoutOrNull(15_000) { connectedSignal.await() } ?: false
        if (!connected) {
            Log.w(TAG, "Connect timed out / failed")
            teardown()
            return null
        }

        val steamUser = steamClient.getHandler(SteamUser::class.java)
        if (steamUser == null) {
            Log.e(TAG, "SteamUser handler missing")
            teardown()
            return null
        }

        steamUser.logOn(
            LogOnDetails().apply {
                username = creds.accountName
                accessToken = creds.refreshToken
                shouldRememberPassword = true
            },
        )

        val logonResult = withTimeoutOrNull(15_000) { logonSignal.await() } ?: EResult.Timeout
        if (logonResult != EResult.OK) {
            Log.w(TAG, "Logon failed: $logonResult")
            teardown()
            return null
        }

        val steamId64 = steamClient.steamID?.convertToUInt64()
        if (steamId64 == null) {
            Log.e(TAG, "Logon succeeded but steamID is null")
            teardown()
            return null
        }

        val unified = steamClient.getHandler(SteamUnifiedMessages::class.java)
        if (unified == null) {
            Log.e(TAG, "SteamUnifiedMessages handler missing")
            teardown()
            return null
        }

        val publishedFileService = unified.createService(PublishedFile::class.java)
        val playerService = unified.createService(Player::class.java)

        Log.i(TAG, "Session ready for steamId64=$steamId64")
        return JavaSteamSession(
            steamId64 = steamId64,
            client = steamClient,
            publishedFile = publishedFileService,
            player = playerService,
            teardown = ::teardown,
        )
    }

    private class JavaSteamSession(
        override val steamId64: Long,
        override val client: SteamClient,
        override val publishedFile: PublishedFile,
        override val player: Player,
        private val teardown: () -> Unit,
    ) : SteamSession {
        override fun close() {
            Log.i(TAG, "Closing session for steamId64=$steamId64")
            teardown()
        }
    }

    private companion object {
        const val TAG = "MappoSteamSession"
    }
}
