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

/**
 * One-time M3 dialog the first time a user picks an analog mode in the
 * Remap Controls mode picker. Explains the focus-side-effects cost the
 * motion-capture overlay imposes while the user is playing — IME, back
 * gesture, and app-switcher gesture are suspended while a bound game is
 * foregrounded with at least one analog mode configured.
 *
 * The ack persists via [com.mapo.data.settings.AnalogModePreferences];
 * subsequent analog-mode picks proceed silently.
 */
@Composable
fun AnalogModeTradeoffsDialog(
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.analog_mode_tradeoffs_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.analog_mode_tradeoffs_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.analog_mode_tradeoffs_list),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.analog_mode_tradeoffs_outro),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) {
                Text(stringResource(R.string.analog_mode_tradeoffs_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}
