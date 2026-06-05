package com.mapo.ui.viewmodel

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapo.steam.auth.QrLoginState
import com.mapo.steam.auth.SteamAuthRepository
import com.mapo.steam.auth.SteamCredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SteamSetupViewModel @Inject constructor(
    private val authRepository: SteamAuthRepository,
    private val credentialStore: SteamCredentialStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SteamSetupUiState())
    val uiState: StateFlow<SteamSetupUiState> = _uiState.asStateFlow()

    private var loginJob: Job? = null

    init {
        viewModelScope.launch {
            credentialStore.credentials.collect { creds ->
                if (creds != null) {
                    loginJob?.cancel()
                    loginJob = null
                    _uiState.update { it.copy(mode = SteamSetupMode.SignedIn(creds.accountName)) }
                } else if (_uiState.value.mode !is SteamSetupMode.SignedIn) {
                    // Only auto-start if we weren't already mid-flow (e.g. on first
                    // entry, or after sign-out). Don't trigger from a Success →
                    // store-save → creds-emit chain that we already kicked off.
                    if (loginJob == null) beginLogin()
                }
            }
        }
    }

    fun retry() {
        if (loginJob?.isActive == true) return
        beginLogin()
    }

    fun signOut() {
        viewModelScope.launch {
            credentialStore.clear()
        }
    }

    private fun beginLogin() {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            authRepository.startQrLogin(deviceLabel = deviceLabel()).collect { state ->
                when (state) {
                    QrLoginState.Connecting ->
                        _uiState.update { it.copy(mode = SteamSetupMode.Connecting) }
                    is QrLoginState.ChallengeUrl ->
                        _uiState.update { it.copy(mode = SteamSetupMode.AwaitingScan(state.url)) }
                    QrLoginState.AwaitingApproval ->
                        _uiState.update { it.copy(mode = SteamSetupMode.AwaitingApproval) }
                    is QrLoginState.Success ->
                        credentialStore.save(state.credentials)
                    is QrLoginState.Error ->
                        _uiState.update { it.copy(mode = SteamSetupMode.Error(state.reason)) }
                }
            }
        }
    }

    private fun deviceLabel(): String = "Mapo (${Build.MODEL})"
}

data class SteamSetupUiState(
    val mode: SteamSetupMode = SteamSetupMode.Connecting,
)

sealed interface SteamSetupMode {
    data object Connecting : SteamSetupMode
    data class AwaitingScan(val qrUrl: String) : SteamSetupMode
    data object AwaitingApproval : SteamSetupMode
    data class SignedIn(val accountName: String) : SteamSetupMode
    data class Error(val reason: String) : SteamSetupMode
}
