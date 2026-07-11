package com.mappo.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import com.mappo.data.model.steam.ActionLayer
import com.mappo.ui.imeActivation
import com.mappo.ui.mappoKeyboardOptions

/**
 * Action-layer management dialogs for the `RemapControlsScreen` layer row (Brick 5.4).
 *
 * Mirrors `ActionSetManagementDialogs` shape but with a couple of layer-specific
 * deviations:
 *  - Add dialog has NO "inherit from" picker. Layers are overlay overrides by
 *    definition — every binding in a fresh layer is "no override" (falls through
 *    to the parent set). Cloning starter content from another layer is what
 *    `Duplicate` is for; "inherit from" would just confuse the mental model.
 *  - Delete confirmation copy explains the per-set scoping (sibling layers and
 *    the base set are unaffected) so the user understands the blast radius.
 *
 * Like `ActionSetManagementDialogs`, no `TextField` auto-focuses on mount (per
 * `feedback_no_keyboard_autospawn`).
 */

/** Derive a machine-friendly [ActionLayer.name] from a user-entered title. */
fun deriveActionLayerName(title: String): String =
    title.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifEmpty { "layer" }

@Composable
fun AddLayerDialog(
    onConfirm: (title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Layer") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().imeActivation(),
                    keyboardOptions = mappoKeyboardOptions(),
                )
                Text(
                    text = "Starts empty — bindings on the parent set show through until you override them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onConfirm(title.trim()) },
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun RenameLayerDialog(
    target: ActionLayer,
    onConfirm: (newTitle: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(target.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Layer") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().imeActivation(),
                keyboardOptions = mappoKeyboardOptions(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && title.trim() != target.title,
                onClick = { onConfirm(title.trim()) },
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun DuplicateLayerDialog(
    source: ActionLayer,
    onConfirm: (newTitle: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("${source.title} Copy") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Duplicate \"${source.title}\"") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().imeActivation(),
                    keyboardOptions = mappoKeyboardOptions(),
                )
                Text(
                    text = "Copies every override from the source layer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onConfirm(title.trim()) },
            ) { Text("Duplicate") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun DeleteLayerConfirmDialog(
    target: ActionLayer,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"${target.title}\"?") },
        text = {
            Text(
                "This removes the layer and its overrides. The parent action set and " +
                    "any other layers are unaffected."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
