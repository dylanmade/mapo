package com.mapo.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapo.R
import com.mapo.steam.library.OwnedGame
import com.mapo.steam.workshop.ConfigSummary
import com.mapo.ui.viewmodel.SteamBrowseState
import com.mapo.ui.viewmodel.SteamBrowseViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SteamBrowseScreen(
    onBack: () -> Unit,
    viewModel: SteamBrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Hardware back: collapse Configs → Games before exiting the screen.
    BackHandler {
        when (state) {
            is SteamBrowseState.Configs, is SteamBrowseState.LoadingConfigs -> viewModel.backToGames()
            else -> onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (val s = state) {
                            is SteamBrowseState.LoadingConfigs ->
                                stringResource(R.string.steam_browse_title_configs, s.game.name)
                            is SteamBrowseState.Configs ->
                                stringResource(R.string.steam_browse_title_configs, s.game.name)
                            else -> stringResource(R.string.steam_browse_title_games)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when (state) {
                            is SteamBrowseState.Configs, is SteamBrowseState.LoadingConfigs ->
                                viewModel.backToGames()
                            else -> onBack()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.steam_back),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
            when (val s = state) {
                SteamBrowseState.LoadingGames -> CenteredSpinner(R.string.steam_browse_loading_games)
                is SteamBrowseState.Games -> GamesList(
                    state = s,
                    onFilterChange = viewModel::setGameFilter,
                    onPick = viewModel::selectGame,
                )
                is SteamBrowseState.LoadingConfigs -> CenteredSpinner(R.string.steam_browse_loading_configs)
                is SteamBrowseState.Configs -> ConfigsList(configs = s.configs, onPick = viewModel::selectConfig)
            }
        }
    }
}

@Composable
private fun CenteredSpinner(labelRes: Int) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun GamesList(
    state: SteamBrowseState.Games,
    onFilterChange: (String) -> Unit,
    onPick: (OwnedGame) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Not auto-focused — would fullscreen-spawn the IME and cover
        // the list on entry (see feedback_no_keyboard_autospawn).
        OutlinedTextField(
            value = state.filter,
            onValueChange = onFilterChange,
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.steam_browse_filter_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val visible = state.visible
        if (state.games.isEmpty()) {
            EmptyState(R.string.steam_browse_empty_games)
            return@Column
        }
        if (visible.isEmpty()) {
            EmptyState(R.string.steam_browse_empty_filtered)
            return@Column
        }
        GamesListBody(games = visible, onPick = onPick)
    }
}

@Composable
private fun GamesListBody(games: List<OwnedGame>, onPick: (OwnedGame) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(games, key = { it.appId }) { game ->
            // "Browse" list-row treatment per the M3 row doctrine —
            // headline + minutes-played subtext, whole row tappable.
            ListItem(
                headlineContent = {
                    Text(
                        text = game.name,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                supportingContent = {
                    Text(
                        text = stringResource(
                            R.string.steam_browse_game_subtext,
                            game.appId,
                            game.playtimeMinutes,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(game) }
                    .padding(horizontal = 4.dp),
                colors = ListItemDefaults.colors(),
            )
        }
    }
}

@Composable
private fun ConfigsList(configs: List<ConfigSummary>, onPick: (ConfigSummary) -> Unit) {
    if (configs.isEmpty()) {
        EmptyState(R.string.steam_browse_empty_configs)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(configs, key = { it.publishedFileId }) { config ->
            ListItem(
                headlineContent = {
                    Text(
                        text = config.title,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                supportingContent = {
                    Text(
                        text = stringResource(
                            R.string.steam_browse_config_subtext,
                            config.votesUp,
                            config.subscriptions,
                            config.creatorSteamId64,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(config) }
                    .padding(horizontal = 4.dp),
                colors = ListItemDefaults.colors(),
            )
        }
    }
}

@Composable
private fun EmptyState(labelRes: Int) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
