package com.mapo.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapo.steam.library.OwnedGame
import com.mapo.steam.library.SteamLibraryRepository
import com.mapo.steam.workshop.WorkshopConfig
import com.mapo.steam.workshop.SteamWorkshopRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "MapoSteamBrowseVM"

@HiltViewModel
class SteamBrowseViewModel @Inject constructor(
    private val libraryRepository: SteamLibraryRepository,
    private val workshopRepository: SteamWorkshopRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<SteamBrowseState>(SteamBrowseState.LoadingGames)
    val state: StateFlow<SteamBrowseState> = _state.asStateFlow()

    private var inFlight: Job? = null

    init {
        loadGames()
    }

    fun retry() {
        when (val s = _state.value) {
            is SteamBrowseState.Configs -> selectGame(s.game)
            is SteamBrowseState.LoadingConfigs -> selectGame(s.game)
            else -> loadGames()
        }
    }

    fun setGameFilter(query: String) {
        val current = _state.value
        if (current is SteamBrowseState.Games) {
            _state.value = current.copy(filter = query)
        }
    }

    fun selectGame(game: OwnedGame) {
        inFlight?.cancel()
        _state.value = SteamBrowseState.LoadingConfigs(game)
        inFlight = viewModelScope.launch {
            val configs = runCatching { workshopRepository.queryConfigs(game.appId) }
                .onFailure { Log.e(TAG, "queryConfigs failed", it) }
                .getOrDefault(emptyList())
            _state.value = SteamBrowseState.Configs(game, configs)
        }
    }

    // selectConfig() removed — taps now navigate to the detail screen
    // via an onOpenConfig callback on the screen. The detail VM does
    // its own logging from the cached WorkshopConfig.

    fun backToGames() {
        inFlight?.cancel()
        loadGames()
    }

    private fun loadGames() {
        inFlight?.cancel()
        _state.value = SteamBrowseState.LoadingGames
        inFlight = viewModelScope.launch {
            val games = runCatching { libraryRepository.fetchOwnedGames() }
                .onFailure { Log.e(TAG, "fetchOwnedGames failed", it) }
                .getOrDefault(emptyList())
            // Sort alphabetically (case-insensitive) — most predictable
            // default for "find a game by name." Add a sort toggle later
            // if playtime-desc or last-played becomes a wanted alternative.
            _state.value = SteamBrowseState.Games(games.sortedBy { it.name.lowercase() })
        }
    }
}

sealed interface SteamBrowseState {
    data object LoadingGames : SteamBrowseState
    data class Games(
        val games: List<OwnedGame>,
        val filter: String = "",
    ) : SteamBrowseState {
        val visible: List<OwnedGame>
            get() = if (filter.isBlank()) games
            else games.filter { it.name.contains(filter.trim(), ignoreCase = true) }
    }
    data class LoadingConfigs(val game: OwnedGame) : SteamBrowseState
    data class Configs(val game: OwnedGame, val configs: List<WorkshopConfig>) : SteamBrowseState
}
