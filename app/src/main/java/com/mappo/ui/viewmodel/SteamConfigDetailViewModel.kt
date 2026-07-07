package com.mappo.ui.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.mappo.steam.workshop.SteamWorkshopRepository
import com.mappo.steam.workshop.WorkshopConfig
import com.mappo.ui.nav.MappoRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

private const val TAG = "MappoSteamConfigDetail"

@HiltViewModel
class SteamConfigDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    workshopRepository: SteamWorkshopRepository,
) : ViewModel() {

    val state: SteamConfigDetailState

    init {
        val publishedFileId: Long = savedStateHandle[MappoRoute.ARG_PUBLISHED_FILE_ID] ?: 0L
        val config = workshopRepository.getConfig(publishedFileId)
        state = if (config != null) {
            Log.i(TAG, "Loaded config from cache: id=$publishedFileId title='${config.title}' tags=${config.tags.size} kvtags=${config.kvTags.size} previews=${config.previews.size}")
            SteamConfigDetailState.Found(config)
        } else {
            Log.w(TAG, "Config $publishedFileId not in cache")
            SteamConfigDetailState.NotFound(publishedFileId)
        }
    }
}

sealed interface SteamConfigDetailState {
    data class Found(val config: WorkshopConfig) : SteamConfigDetailState
    data class NotFound(val publishedFileId: Long) : SteamConfigDetailState
}
