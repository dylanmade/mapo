package com.mapo.ui.screen.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mapo.R
import com.mapo.service.shizuku.ShizukuState

/**
 * **Brick G.** Shown the first time the user picks an analog mode in the Remap
 * Controls dropdown while Shizuku is NOT ready. Explains that analog input
 * needs Shizuku and offers a path to the setup screen.
 *
 * State-aware copy: the body text matches where the user is in the Shizuku
 * setup chain (`NotInstalled` / `InstalledNotRunning` / `RunningNotGranted`).
 * If Shizuku state changes while the dialog is open (e.g. the user already had
 * Shizuku running in another app while this dialog opened with stale state),
 * the body recomposes.
 *
 * **Ack semantics.** Confirming routes the user to [com.mapo.ui.screen.ShizukuSetupScreen]
 * AND persists the ack (via [com.mapo.data.settings.ShizukuRequiredPreferences])
 * so subsequent analog-mode picks proceed silently — the user is now on the
 * Shizuku path and the persistent health notification + Setup screen are the
 * durable surfaces. Cancelling drops the pending mode pick and leaves the ack
 * untouched (next pick re-prompts).
 */
@Composable
fun ShizukuRequiredDialog(
    shizukuState: ShizukuState,
    onSetUp: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.shizuku_required_dialog_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.shizuku_required_dialog_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(shizukuState.dialogStateCopyRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.shizuku_required_dialog_outro),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSetUp) {
                Text(stringResource(R.string.shizuku_required_dialog_cta_set_up))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/**
 * State-specific body copy. `Granted` never triggers the dialog (the gate
 * filters it out) but we include a fallback string so a transient race
 * doesn't show an empty body — the user just sees "Shizuku is ready" and
 * dismisses.
 */
private fun ShizukuState.dialogStateCopyRes(): Int = when (this) {
    ShizukuState.NotInstalled -> R.string.shizuku_required_dialog_state_not_installed
    ShizukuState.InstalledNotRunning -> R.string.shizuku_required_dialog_state_installed_not_running
    ShizukuState.RunningNotGranted -> R.string.shizuku_required_dialog_state_running_not_granted
    ShizukuState.Granted -> R.string.shizuku_required_dialog_state_granted
}
