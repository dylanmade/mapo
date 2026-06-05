package com.mapo.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapo.R
import com.mapo.ui.component.QrCodeImage
import com.mapo.ui.viewmodel.SteamSetupMode
import com.mapo.ui.viewmodel.SteamSetupViewModel

private const val QR_SIZE_DP = 240

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SteamSetupScreen(
    onBack: () -> Unit,
    onOpenBrowse: () -> Unit,
    viewModel: SteamSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (state.mode) {
                                is SteamSetupMode.SignedIn -> R.string.steam_screen_title_signed_in
                                else -> R.string.steam_screen_title_connect
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.steam_back),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val mode = state.mode) {
                SteamSetupMode.Connecting -> ConnectingBlock()
                is SteamSetupMode.AwaitingScan -> AwaitingScanBlock(qrUrl = mode.qrUrl)
                SteamSetupMode.AwaitingApproval -> AwaitingApprovalBlock()
                is SteamSetupMode.SignedIn -> SignedInBlock(
                    accountName = mode.accountName,
                    onBrowse = onOpenBrowse,
                    onSignOut = viewModel::signOut,
                )
                is SteamSetupMode.Error -> ErrorBlock(
                    reason = mode.reason,
                    onRetry = viewModel::retry,
                )
            }
        }
    }
}

@Composable
private fun ConnectingBlock() {
    CenteredCallout {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.steam_state_connecting),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun AwaitingScanBlock(qrUrl: String) {
    // surfaceContainer — callout / hero block per M3 standards memo.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.steam_qr_headline),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // White surface behind the QR — improves scan reliability under
            // dark theme (some camera apps struggle with low-contrast frames).
            Surface(
                color = androidx.compose.ui.graphics.Color.White,
                shape = MaterialTheme.shapes.small,
            ) {
                Box(modifier = Modifier.padding(8.dp)) {
                    QrCodeImage(
                        content = qrUrl,
                        sizePx = (QR_SIZE_DP * android.content.res.Resources.getSystem().displayMetrics.density).toInt(),
                        modifier = Modifier.size(QR_SIZE_DP.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.steam_qr_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AwaitingApprovalBlock() {
    CenteredCallout {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.steam_state_awaiting_approval),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.steam_state_awaiting_approval_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SignedInBlock(accountName: String, onBrowse: () -> Unit, onSignOut: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.steam_signed_in_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = accountName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    Button(onClick = onBrowse) {
        Text(stringResource(R.string.steam_browse_button))
    }
    OutlinedButton(onClick = onSignOut) {
        Text(stringResource(R.string.steam_sign_out_button))
    }
}

@Composable
private fun ErrorBlock(reason: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.steam_error_headline),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
    Button(onClick = onRetry) {
        Text(stringResource(R.string.steam_retry_button))
    }
}

@Composable
private fun CenteredCallout(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}
