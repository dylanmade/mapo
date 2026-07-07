package com.mappo.data.steam

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mappo.steam.auth.SteamCredentials
import com.mappo.steam.auth.SteamCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * [SteamCredentialStore] backed by [EncryptedSharedPreferences] —
 * AES256-GCM encryption at rest via the AndroidX security-crypto
 * library, keyed off a hardware-backed master key when available.
 *
 * In-process consumers observe changes via [credentials]; the
 * [MutableStateFlow] is updated synchronously with the on-disk write
 * so a save/clear is reflected immediately.
 */
class EncryptedPrefsSteamCredentialStore(context: Context) : SteamCredentialStore {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val state = MutableStateFlow(readFromPrefs())
    override val credentials: Flow<SteamCredentials?> = state.asStateFlow()

    override suspend fun get(): SteamCredentials? = state.value

    override suspend fun save(credentials: SteamCredentials) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_ACCOUNT_NAME, credentials.accountName)
            .putString(KEY_REFRESH_TOKEN, credentials.refreshToken)
            .apply()
        state.value = credentials
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(KEY_ACCOUNT_NAME)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
        state.value = null
    }

    private fun readFromPrefs(): SteamCredentials? {
        val accountName = prefs.getString(KEY_ACCOUNT_NAME, null) ?: return null
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return null
        return SteamCredentials(accountName = accountName, refreshToken = refreshToken)
    }

    private companion object {
        const val FILE_NAME = "steam_credentials"
        const val KEY_ACCOUNT_NAME = "account_name"
        const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}
