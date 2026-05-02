package com.mapo.service.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Snackbar-shaped informational message. Used for "Loaded profile X for Y".
 * Auto-dismisses after [autoDismissMs]; not focusable / non-interactive.
 *
 * The window is full screen width; this Box centers the snackbar horizontally
 * within that width and caps it at a reasonable max so it doesn't stretch on
 * very wide displays.
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
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .widthIn(min = 240.dp, max = 600.dp)
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
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Three-button create-profile prompt. Layout is a single Row with the message
 * taking weighted remaining space and the button group on the right. On narrow
 * displays the message ellipsizes rather than wrapping, keeping the layout stable.
 *
 * Focus visibility uses a per-button border bound to the button's own InteractionSource
 * so DPAD nav clearly highlights the focused button. Gamepad B (mapped to BACK by
 * InputAccessibilityService) dismisses as if "No" was tapped.
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Back) {
                    onNo(); true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 720.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Create profile for $appLabel?",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FocusableTextButton(
                    label = "Never for this app",
                    onClick = onNever,
                    contentColor = MaterialTheme.colorScheme.error
                )
                FocusableTextButton(
                    label = "No",
                    onClick = onNo,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FocusableTextButton(
                    label = "Yes",
                    onClick = onYes,
                    contentColor = MaterialTheme.colorScheme.primary,
                    focusRequester = yesRequester
                )
            }
        }
    }
}

@Composable
private fun FocusableTextButton(
    label: String,
    onClick: () -> Unit,
    contentColor: Color,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderColor = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent
    TextButton(
        onClick = onClick,
        interactionSource = interactionSource,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
        modifier = Modifier
            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(6.dp))
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
    ) { Text(label, fontSize = 13.sp) }
}
