package com.mappo.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mappo.data.model.steam.ActionSet
import com.mappo.data.model.steam.ActionSetGraph

/**
 * Action-set management dialogs for the `RemapControlsScreen` tab bar (Brick 4.4).
 *
 * Each dialog collects a user-facing **title** (e.g. "Menu"). The internal machine-name
 * field on [com.mappo.data.model.steam.ActionSet] is derived from the title by
 * [deriveActionSetName] — keeping the two distinct in the data model (Steam VDF parity)
 * but collapsing them in the UX. The user almost never cares about the slug.
 *
 * No `TextField` auto-focuses on mount (per feedback_no_keyboard_autospawn): a dialog
 * opening over content shouldn't trigger the soft keyboard before the user has had a
 * chance to look at the form. The user taps the field to bring up the IME.
 */

/**
 * Derive a machine-friendly [ActionSet.name] from a user-entered title.
 * "Menu" → "menu"; "Vehicle / Walking" → "vehicle_walking"; empty → "set".
 */
fun deriveActionSetName(title: String): String =
    title.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifEmpty { "set" }

@Composable
fun AddSetDialog(
    existingSets: List<ActionSetGraph>,
    onConfirm: (title: String, inheritFromSetId: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var inheritFrom: ActionSetGraph? by remember { mutableStateOf(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Action Set") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Shown on the Remap Controls tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (existingSets.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    InheritFromPicker(
                        existingSets = existingSets,
                        selected = inheritFrom,
                        onSelected = { inheritFrom = it },
                    )
                    Text(
                        text = if (inheritFrom == null)
                            "Starts blank — every input is unbound."
                        else "Copies every binding from \"${inheritFrom!!.actionSet.title}\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onConfirm(title.trim(), inheritFrom?.actionSet?.id) },
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun RenameSetDialog(
    target: ActionSet,
    onConfirm: (newTitle: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(target.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Action Set") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
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
fun DuplicateSetDialog(
    source: ActionSet,
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
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Copies every binding from the source set.",
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
fun DeleteSetConfirmDialog(
    target: ActionSet,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"${target.title}\"?") },
        text = {
            Text("This removes the action set and all its bindings. Other sets are unaffected.")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * M3 dropdown for picking an existing set to inherit from when creating a new set.
 * Uses a Surface-styled clickable row + DropdownMenu rather than
 * `ExposedDropdownMenuBox` so the dialog's IME-bypass behavior stays consistent —
 * `ExposedDropdownMenuBox` wraps an editable text field that can summon the IME.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InheritFromPicker(
    existingSets: List<ActionSetGraph>,
    selected: ActionSetGraph?,
    onSelected: (ActionSetGraph?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            text = "Inherit from",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Box {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = true }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = selected?.actionSet?.title ?: "(Blank — no inheritance)",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Open inherit menu")
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("(Blank — no inheritance)") },
                    onClick = {
                        onSelected(null)
                        expanded = false
                    },
                )
                existingSets.forEach { setGraph ->
                    DropdownMenuItem(
                        text = { Text(setGraph.actionSet.title) },
                        onClick = {
                            onSelected(setGraph)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
