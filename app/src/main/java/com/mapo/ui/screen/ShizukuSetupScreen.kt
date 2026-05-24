package com.mapo.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapo.R
import com.mapo.service.shizuku.ShizukuState
import com.mapo.ui.viewmodel.ShizukuSetupViewModel

/**
 * Brick A — minimal status surface for the Shizuku integration.
 *
 * Polished significantly in Brick G (M3 list-row doctrine, troubleshooting tips,
 * state-aware copy). Here the job is just to prove the state machine wires
 * through the UI: install Shizuku externally → state moves; start Shizuku → state
 * moves; grant Mapo → state moves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShizukuSetupScreen(
    onBack: () -> Unit,
    viewModel: ShizukuSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shizuku_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.shizuku_back),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.shizuku_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(state.statusStringRes()),
                style = MaterialTheme.typography.titleMedium,
            )

            when (state) {
                ShizukuState.NotInstalled -> {
                    Button(onClick = viewModel::installShizuku) {
                        Text(stringResource(R.string.shizuku_install_button))
                    }
                }
                ShizukuState.InstalledNotRunning -> {
                    Button(onClick = viewModel::openShizukuApp) {
                        Text(stringResource(R.string.shizuku_open_app_button))
                    }
                }
                ShizukuState.RunningNotGranted -> {
                    Button(onClick = viewModel::requestPermission) {
                        Text(stringResource(R.string.shizuku_grant_button))
                    }
                    OutlinedButton(onClick = viewModel::openShizukuApp) {
                        Text(stringResource(R.string.shizuku_open_app_button))
                    }
                }
                ShizukuState.Granted -> {
                    // No action needed; state line above already says "ready".
                }
            }
        }
    }
}

private fun ShizukuState.statusStringRes(): Int = when (this) {
    ShizukuState.NotInstalled -> R.string.shizuku_state_not_installed
    ShizukuState.InstalledNotRunning -> R.string.shizuku_state_installed_not_running
    ShizukuState.RunningNotGranted -> R.string.shizuku_state_running_not_granted
    ShizukuState.Granted -> R.string.shizuku_state_granted
}
