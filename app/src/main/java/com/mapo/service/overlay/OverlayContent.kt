package com.mapo.service.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Snackbar-shaped informational message. Used for "Loaded profile X for Y".
 * Auto-dismisses after [autoDismissMs]; not focusable / non-interactive.
 */
@Composable
fun OverlayToast(
    message: String,
    autoDismissMs: Long,
    onDismiss: () -> Unit
) {
    LaunchedEffect(message) {
        kotlinx.coroutines.delay(autoDismissMs)
        onDismiss()
    }
    Row(
        modifier = Modifier
            .widthIn(min = 240.dp, max = 480.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp
        )
    }
}

/**
 * Three-button create-profile prompt. Focusable so DPAD navigates between buttons,
 * and gamepad A is wired (in the AccessibilityService) to inject ENTER which
 * Compose treats as a click on the focused button.
 */
@Composable
fun OverlayCreatePrompt(
    appLabel: String,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onNever: () -> Unit
) {
    val yesRequester = remember { FocusRequester() }
    LaunchedEffect(appLabel) { yesRequester.requestFocus() }

    Column(
        modifier = Modifier
            .widthIn(min = 280.dp, max = 520.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Create profile for $appLabel?",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
        ) {
            TextButton(
                onClick = onNever,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.focusable()
            ) { Text("Never for this app", fontSize = 13.sp) }
            TextButton(
                onClick = onNo,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.focusable()
            ) { Text("No", fontSize = 13.sp) }
            TextButton(
                onClick = onYes,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .focusRequester(yesRequester)
                    .focusable()
            ) { Text("Yes", fontSize = 13.sp) }
        }
    }
}
