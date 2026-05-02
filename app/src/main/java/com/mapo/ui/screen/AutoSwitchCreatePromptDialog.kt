package com.mapo.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mapo.R

@Composable
fun AutoSwitchCreatePromptDialog(
    appLabel: String,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onNever: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text(stringResource(R.string.auto_switch_prompt_title, appLabel)) },
        text = { Text(stringResource(R.string.auto_switch_prompt_message)) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onNever,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.auto_switch_prompt_never)) }
                TextButton(
                    onClick = onNo,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text(stringResource(R.string.auto_switch_prompt_no)) }
                TextButton(onClick = onYes) {
                    Text(stringResource(R.string.auto_switch_prompt_yes))
                }
            }
        }
    )
}
