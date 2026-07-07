package com.mappo.steam.auth

import android.util.Log
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.steam.authentication.AuthPollResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
import `in`.dragonbra.javasteam.steam.authentication.IChallengeUrlChanged
import `in`.dragonbra.javasteam.steam.authentication.QrAuthSession
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * JavaSteam-backed [SteamAuthRepository]. Each call to [startQrLogin]
 * spins up a fresh [SteamClient], runs the QR auth dance, then tears
 * everything down on flow cancellation or terminal state. We don't
 * hold a long-lived Steam connection here — that comes later when
 * the browser PR needs IPublishedFile.QueryFiles + CDN fetch.
 */
class JavaSteamAuthRepository : SteamAuthRepository {

    override fun startQrLogin(deviceLabel: String): Flow<QrLoginState> = callbackFlow {
        Log.i(TAG, "QR login starting (device='$deviceLabel')")
        trySend(QrLoginState.Connecting)

        val steamClient = SteamClient()
        val callbackManager = CallbackManager(steamClient)
        val connectedSignal = CompletableDeferred<Boolean>()

        val connectedSub = callbackManager.subscribe(ConnectedCallback::class.java) { _ ->
            Log.i(TAG, "Connected callback received")
            if (!connectedSignal.isCompleted) connectedSignal.complete(true)
        }
        val disconnectedSub = callbackManager.subscribe(DisconnectedCallback::class.java) { _ ->
            Log.w(TAG, "Disconnected callback received")
            if (!connectedSignal.isCompleted) connectedSignal.complete(false)
            else trySend(QrLoginState.Error("Disconnected from Steam"))
        }

        // CallbackManager dispatches Steam events only while runWaitCallbacks
        // is being pumped. Without this loop, ConnectedCallback never fires.
        val pumpJob = launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { callbackManager.runWaitCallbacks(1_000L) }
                    .onFailure { Log.w(TAG, "runWaitCallbacks error", it) }
            }
        }

        // Connect + poll runs in its own coroutine so the lambda can end
        // with awaitClose() (which suspends until cancelled and would
        // otherwise block all production logic from running).
        val workJob = launch {
            Log.i(TAG, "Calling steamClient.connect()")
            withContext(Dispatchers.IO) { steamClient.connect() }

            val connected = withTimeoutOrNull(15_000) { connectedSignal.await() } ?: false
            if (!connected) {
                Log.w(TAG, "Connect timed out after 15s")
                trySend(QrLoginState.Error("Could not connect to Steam"))
                close()
                return@launch
            }

            val authDetails = AuthSessionDetails().apply {
                this.deviceFriendlyName = deviceLabel
                // Mirror GameNative — Steam appears to treat the QR flow
                // identically across OS types, but we don't want to be the
                // first app to find out it doesn't. Revisit if Steam ever
                // exposes an Android-native EOSType.
                this.clientOSType = EOSType.WinUnknown
                this.persistentSession = true
            }

            val authSession: QrAuthSession = try {
                steamClient.authentication.beginAuthSessionViaQR(authDetails).await()
            } catch (e: Exception) {
                Log.e(TAG, "beginAuthSessionViaQR failed", e)
                trySend(QrLoginState.Error(e.message ?: "Could not start QR session"))
                close()
                return@launch
            }

            Log.i(TAG, "QR session opened, polling every ${authSession.pollingInterval}ms")
            authSession.challengeUrlChanged = IChallengeUrlChanged { qr ->
                qr?.challengeUrl?.let {
                    Log.i(TAG, "QR challenge rotated")
                    trySend(QrLoginState.ChallengeUrl(it))
                }
            }
            trySend(QrLoginState.ChallengeUrl(authSession.challengeUrl))

            var pollResult: AuthPollResult? = null
            while (isActive && pollResult == null) {
                try {
                    pollResult = authSession.pollAuthSessionStatus().await()
                } catch (e: AuthenticationException) {
                    Log.w(TAG, "Auth rejected", e)
                    trySend(QrLoginState.Error(e.result?.name ?: e.message ?: "Auth rejected"))
                    close()
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "Poll failed", e)
                    trySend(QrLoginState.Error(e.message ?: "Poll failed"))
                    close()
                    return@launch
                }
                if (pollResult == null) delay(authSession.pollingInterval.toLong())
            }

            pollResult?.let {
                Log.i(TAG, "QR login succeeded for account='${it.accountName}'")
                trySend(
                    QrLoginState.Success(
                        credentials = SteamCredentials(
                            accountName = it.accountName,
                            refreshToken = it.refreshToken,
                        ),
                    ),
                )
                close()
            }
        }

        awaitClose {
            Log.i(TAG, "QR login flow cancelled — cleaning up")
            workJob.cancel()
            pumpJob.cancel()
            connectedSub.close()
            disconnectedSub.close()
            runCatching { steamClient.disconnect() }
        }
    }

    private companion object {
        const val TAG = "MappoSteamAuth"
    }
}
