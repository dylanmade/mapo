package com.mapo.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mapo.service.input.InputAddress
import kotlinx.coroutines.flow.SharedFlow

/**
 * Listen-for-press capture screen. Brick 3.3.e. While this screen is on top, the
 * accessibility service is in `captureMode=true`: physical button DOWN edges land in
 * [capturedInputs] instead of running through the evaluator's remap pipeline. The first
 * captured press calls [onPartnerCaptured]; back / cancel calls [onCancel] and exits
 * capture mode without selecting.
 *
 * Capture mode is set true on enter and false on dispose (whether via successful capture
 * or back). The DisposableEffect is the source of truth — even if the OS yanks the
 * screen unexpectedly, captureMode resets so remapping resumes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChordPartnerPickerScreen(
    capturedInputs: SharedFlow<InputAddress>,
    setCaptureMode: (Boolean) -> Unit,
    onPartnerCaptured: (InputAddress) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Capture-mode lifecycle owned by this screen. The flow subscription below races with
    // dispose; the LaunchedEffect cancels its own collection when the composition leaves
    // (the screen pops on capture or back), so a stray emission can't outlive us.
    DisposableEffect(Unit) {
        setCaptureMode(true)
        onDispose { setCaptureMode(false) }
    }

    LaunchedEffect(Unit) {
        capturedInputs.collect { address ->
            onPartnerCaptured(address)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Pick chord partner") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Press a button on your controller",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Whichever button you press will become the chord partner — the " +
                        "chord activator only fires when this partner is currently held.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    }
}
