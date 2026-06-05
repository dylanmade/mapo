package com.mapo.ui.screen.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mapo.data.model.RemapTarget

/**
 * Shared "configure this overlay button" content used by BOTH editor prototypes
 * (Brick C): inside a `ModalBottomSheet` for the in-app canvas editor, and inside a
 * focusable overlay window for the live on-overlay editor. Keeping the config UI shared
 * means the prototype comparison is about the *placement* UX, not the binding UX.
 *
 * Instant-commit (matches the rest of the app): every label / command change calls
 * [onSave] immediately — no explicit save button. The label field is NOT auto-focused
 * (see feedback_no_keyboard_autospawn).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverlayElementConfigContent(
    initialLabel: String,
    initialTarget: RemapTarget,
    onSave: (label: String, target: RemapTarget) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
) {
    var label by remember { mutableStateOf(initialLabel) }
    var target by remember { mutableStateOf(initialTarget) }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Configure button",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = label,
            onValueChange = {
                label = it
                onSave(label, target)
            },
            label = { Text("Label (optional)") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Command",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayCommonCommands.forEach { code ->
                val codeTarget = RemapTarget.fromCode(code)
                FilterChip(
                    selected = target == codeTarget,
                    onClick = {
                        target = codeTarget
                        onSave(label, target)
                    },
                    label = { Text(code) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Text("  Delete button")
            }
            if (onDone != null) {
                FilledTonalButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

/**
 * A small starter palette of common commands for the prototype config UI. Classified via
 * [RemapTarget.fromCode]. The full key/mouse/gamepad picker (`remapTargetPicker`) can be
 * wired in once the editor direction is chosen.
 */
val OverlayCommonCommands: List<String> = listOf(
    "ENTER", "ESCAPE", "SPACE", "TAB",
    "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT",
    "A", "B", "X", "Y",
    "MOUSE_LEFT", "MOUSE_RIGHT", "SCROLL_UP", "SCROLL_DOWN",
)
