package com.mapo.ui.screen.keyboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mapo.data.model.TemplateRef
import com.mapo.ui.components.ColorSwatchPicker
import com.mapo.ui.components.SteppedSliderWithNumberInput

/**
 * Top-level dispatcher for the keyboard tab action dialog tree. MainScreen passes the current
 * state and a setter; this composable picks the appropriate dialog and renders it.
 */
@Composable
fun TabActionDialogHost(
    state: TabActionDialog?,
    profileName: String,
    userTemplates: List<TemplateRef.User>,
    onStateChange: (TabActionDialog?) -> Unit,
    onApplyConfigure: (layoutId: Long, name: String, cols: Int, rows: Int, bgColor: Int?) -> Unit,
    onApplyAutoResize: (layoutId: Long, name: String, cols: Int, rows: Int, bgColor: Int?) -> Unit,
    onConfirmReset: (layoutId: Long) -> Unit,
    onConfirmRemove: (layoutId: Long) -> Unit,
    onSaveAsNewTemplate: (layoutId: Long, templateName: String) -> Unit,
    onUpdateExistingTemplate: (layoutId: Long, target: TemplateRef.User) -> Unit,
    onTemplateSaveCanceled: () -> Unit
) {
    when (val s = state) {
        null -> Unit
        is TabActionDialog.Configure -> ConfigureKeyboardDialog(
            state = s,
            onDismiss = { onStateChange(null) },
            onApply = { name, cols, rows, bgColor ->
                onApplyConfigure(s.layoutId, name, cols, rows, bgColor)
                onStateChange(null)
            },
            onResetTapped = { name, cols, rows, bgColor ->
                val draft = s.copy(name = name, cols = cols, rows = rows, bgColor = bgColor)
                onStateChange(
                    TabActionDialog.ResetConfirm(
                        layoutId = s.layoutId,
                        currentName = name,
                        originalName = s.originalName ?: name,
                        configDraft = draft
                    )
                )
            }
        )
        is TabActionDialog.ResetConfirm -> AlertDialog(
            onDismissRequest = { onStateChange(s.configDraft) },
            title = { Text("Reset \"${s.currentName}\"?") },
            text = {
                Text(
                    "\"${s.currentName}\" will revert to its original layout and settings " +
                            "(\"${s.originalName}\"). This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmReset(s.layoutId)
                        onStateChange(null)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Reset \"${s.currentName}\"") }
            },
            dismissButton = {
                TextButton(
                    onClick = { onStateChange(s.configDraft) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text("Cancel") }
            }
        )
        is TabActionDialog.RemoveConfirm -> AlertDialog(
            onDismissRequest = { onStateChange(null) },
            title = { Text("Remove \"${s.name}\"?") },
            text = {
                Text(
                    "The \"${s.name}\" keyboard will be removed from the \"${s.profileName}\" " +
                            "profile. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmRemove(s.layoutId)
                        onStateChange(null)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Remove \"${s.name}\"") }
            },
            dismissButton = {
                TextButton(
                    onClick = { onStateChange(null) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text("Cancel") }
            }
        )
        is TabActionDialog.ResizeConflict -> AlertDialog(
            onDismissRequest = { onStateChange(s.draft) },
            title = { Text("New keyboard size won't fit some buttons") },
            text = {
                Text(
                    "The new keyboard size will not fit the following buttons: " +
                            s.offendingLabels.joinToString(", ") + ". What would you like to do?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onApplyAutoResize(
                            s.draft.layoutId,
                            s.draft.name,
                            s.draft.cols,
                            s.draft.rows,
                            s.draft.bgColor
                        )
                        onStateChange(null)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Try auto-resizing") }
            },
            dismissButton = {
                TextButton(
                    onClick = { onStateChange(s.draft) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text("Cancel resizing") }
            }
        )
        is TabActionDialog.SaveTemplateChooser -> AlertDialog(
            onDismissRequest = { onTemplateSaveCanceled() },
            title = { Text("Save keyboard template") },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("Save as new keyboard template") },
                        leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.fillMaxWidth().clickableListItem {
                            onStateChange(
                                TabActionDialog.SaveAsNewTemplate(
                                    layoutId = s.layoutId,
                                    keyboardName = s.keyboardName,
                                    templateName = s.keyboardName
                                )
                            )
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Update existing keyboard template") },
                        leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.fillMaxWidth().clickableListItem {
                            onStateChange(
                                TabActionDialog.UpdateExistingTemplate(
                                    layoutId = s.layoutId,
                                    keyboardName = s.keyboardName,
                                    filter = s.keyboardName
                                )
                            )
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { onTemplateSaveCanceled() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text("Cancel") }
            }
        )
        is TabActionDialog.SaveAsNewTemplate -> {
            var name by remember(s.layoutId) { mutableStateOf(s.templateName) }
            AlertDialog(
                onDismissRequest = { onTemplateSaveCanceled() },
                title = { Text("Save keyboard template") },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Template name") },
                        singleLine = true,
                        trailingIcon = {
                            if (name.isNotEmpty()) {
                                IconButton(onClick = { name = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            onSaveAsNewTemplate(s.layoutId, name.trim())
                        }
                    ) { Text("Save \"${s.keyboardName}\"") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { onTemplateSaveCanceled() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) { Text("Cancel") }
                }
            )
        }
        is TabActionDialog.UpdateExistingTemplate -> {
            var filter by remember(s.layoutId) { mutableStateOf(s.filter) }
            val matches = remember(filter, userTemplates) {
                if (filter.isBlank()) userTemplates
                else userTemplates.filter { it.name.contains(filter, ignoreCase = true) }
            }
            AlertDialog(
                onDismissRequest = { onTemplateSaveCanceled() },
                title = { Text("Update keyboard template") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text("Filter") },
                            singleLine = true,
                            trailingIcon = {
                                if (filter.isNotEmpty()) {
                                    IconButton(onClick = { filter = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        if (matches.isEmpty()) {
                            Text(
                                if (userTemplates.isEmpty()) "No saved templates yet."
                                else "No templates match \"$filter\".",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                                items(matches, key = { it.id }) { template ->
                                    ListItem(
                                        headlineContent = { Text(template.name) },
                                        supportingContent = {
                                            Text("${template.columns}×${template.rows}, ${template.buttons.size} buttons")
                                        },
                                        modifier = Modifier.fillMaxWidth().clickableListItem {
                                            onStateChange(
                                                TabActionDialog.UpdateTemplateConfirm(
                                                    layoutId = s.layoutId,
                                                    keyboardName = s.keyboardName,
                                                    target = template,
                                                    previous = s
                                                )
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(
                        onClick = { onTemplateSaveCanceled() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) { Text("Cancel") }
                }
            )
        }
        is TabActionDialog.UpdateTemplateConfirm -> AlertDialog(
            onDismissRequest = { onStateChange(s.previous) },
            title = { Text("Update \"${s.target.name}\" template?") },
            text = {
                Text(
                    "The current \"${s.target.name}\" template will be replaced. " +
                            "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdateExistingTemplate(s.layoutId, s.target)
                        onStateChange(null)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Update \"${s.target.name}\"") }
            },
            dismissButton = {
                TextButton(
                    onClick = { onStateChange(s.previous) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text("Back") }
            }
        )
        is TabActionDialog.TemplateNameConflict -> {
            val existingUser = s.existing as? TemplateRef.User
            AlertDialog(
                onDismissRequest = { onTemplateSaveCanceled() },
                title = { Text("\"${s.templateName}\" template already exists") },
                text = {
                    Text(
                        "A keyboard template with the name \"${s.templateName}\" already " +
                                "exists. What would you like to do?"
                    )
                },
                confirmButton = {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        TextButton(
                            onClick = {
                                onStateChange(
                                    TabActionDialog.SaveAsNewTemplate(
                                        layoutId = s.layoutId,
                                        keyboardName = s.keyboardName,
                                        templateName = s.templateName
                                    )
                                )
                            }
                        ) { Text("Choose a different name") }
                        if (existingUser != null) {
                            TextButton(
                                onClick = {
                                    onStateChange(
                                        TabActionDialog.UpdateTemplateConfirm(
                                            layoutId = s.layoutId,
                                            keyboardName = s.keyboardName,
                                            target = existingUser,
                                            previous = s
                                        )
                                    )
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("Update \"${s.templateName}\" template") }
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { onTemplateSaveCanceled() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) { Text("Cancel saving") }
                }
            )
        }
    }
}

@Composable
private fun ConfigureKeyboardDialog(
    state: TabActionDialog.Configure,
    onDismiss: () -> Unit,
    onApply: (name: String, cols: Int, rows: Int, bgColor: Int?) -> Unit,
    onResetTapped: (name: String, cols: Int, rows: Int, bgColor: Int?) -> Unit
) {
    var name by remember(state.layoutId) { mutableStateOf(state.name) }
    var cols by remember(state.layoutId) { mutableStateOf(state.cols.coerceIn(1, 20)) }
    var rows by remember(state.layoutId) { mutableStateOf(state.rows.coerceIn(1, 20)) }
    var bgColor by remember(state.layoutId) { mutableStateOf(state.bgColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configure keyboard") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    trailingIcon = {
                        if (name.isNotEmpty()) {
                            IconButton(onClick = { name = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Background color",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ColorSwatchPicker(
                        selectedArgb = bgColor,
                        onSelect = { bgColor = it }
                    )
                }
                SteppedSliderWithNumberInput(
                    label = "Rows",
                    value = rows,
                    onValueChange = { rows = it },
                    min = 1,
                    max = 20
                )
                SteppedSliderWithNumberInput(
                    label = "Columns",
                    value = cols,
                    onValueChange = { cols = it },
                    min = 1,
                    max = 20
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { onResetTapped(name.trim().ifBlank { state.name }, cols, rows, bgColor) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Reset keyboard", fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onApply(name.trim(), cols, rows, bgColor) }
            ) { Text("Apply") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) { Text("Cancel") }
        }
    )
}

private fun Modifier.clickableListItem(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
