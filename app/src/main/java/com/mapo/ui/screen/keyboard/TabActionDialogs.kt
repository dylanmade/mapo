package com.mapo.ui.screen.keyboard

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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mapo.R
import com.mapo.data.model.GridLayout
import com.mapo.data.model.Profile
import com.mapo.data.model.TemplateRef
import com.mapo.ui.components.ColorSwatchPicker
import com.mapo.ui.components.SteppedSliderWithNumberInput
import androidx.compose.runtime.LaunchedEffect
import kotlinx.collections.immutable.ImmutableList

/**
 * Top-level dispatcher for the keyboard tab action dialog tree. MainScreen passes the current
 * state and a setter; this composable picks the appropriate dialog and renders it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TabActionDialogHost(
    state: TabActionDialog?,
    profileName: String,
    userTemplates: ImmutableList<TemplateRef.User>,
    allTemplates: ImmutableList<TemplateRef>,
    profiles: ImmutableList<Profile>,
    activeProfileId: Long?,
    onStateChange: (TabActionDialog?) -> Unit,
    onApplyConfigure: (layoutId: Long, name: String, cols: Int, rows: Int, bgColor: Int?) -> Unit,
    onApplyAutoResize: (layoutId: Long, name: String, cols: Int, rows: Int, bgColor: Int?) -> Unit,
    onConfirmReset: (layoutId: Long) -> Unit,
    onConfirmRemove: (layoutId: Long) -> Unit,
    onSaveAsNewTemplate: (layoutId: Long, templateName: String) -> Unit,
    onUpdateExistingTemplate: (layoutId: Long, target: TemplateRef.User) -> Unit,
    onTemplateSaveCanceled: () -> Unit,
    onAddBlankKeyboard: () -> Unit,
    onAddFromTemplate: (TemplateRef) -> Unit,
    onAddFromProfile: (sourceLayoutId: Long) -> Unit,
    fetchProfileLayouts: suspend (profileId: Long) -> List<GridLayout>,
    onConfirmDeleteButton: (buttonId: String) -> Unit
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
            title = { Text(stringResource(R.string.tab_dialog_reset_title, s.currentName)) },
            text = {
                Text(stringResource(R.string.tab_dialog_reset_text, s.currentName, s.originalName))
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
                ) { Text(stringResource(R.string.tab_dialog_reset_confirm, s.currentName)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { onStateChange(s.configDraft) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
        is TabActionDialog.RemoveConfirm -> AlertDialog(
            onDismissRequest = { onStateChange(null) },
            title = { Text(stringResource(R.string.tab_dialog_remove_title, s.name)) },
            text = {
                Text(stringResource(R.string.tab_dialog_remove_text, s.name, s.profileName))
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
                ) { Text(stringResource(R.string.tab_dialog_remove_confirm, s.name)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { onStateChange(null) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
        is TabActionDialog.ResizeConflict -> AlertDialog(
            onDismissRequest = { onStateChange(s.draft) },
            title = { Text(stringResource(R.string.tab_dialog_resize_conflict_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.tab_dialog_resize_conflict_text,
                        s.offendingLabels.joinToString(", "),
                    )
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
                ) { Text(stringResource(R.string.tab_dialog_resize_conflict_try)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { onStateChange(s.draft) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text(stringResource(R.string.tab_dialog_resize_conflict_cancel)) }
            }
        )
        is TabActionDialog.SaveTemplateChooser -> AlertDialog(
            onDismissRequest = { onTemplateSaveCanceled() },
            title = { Text(stringResource(R.string.tab_dialog_save_template_title)) },
            text = {
                Column {
                    ListItem(
                        onClick = {
                            onStateChange(
                                TabActionDialog.SaveAsNewTemplate(
                                    layoutId = s.layoutId,
                                    keyboardName = s.keyboardName,
                                    templateName = s.keyboardName
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    ) { Text(stringResource(R.string.tab_dialog_save_as_new_template)) }
                    ListItem(
                        onClick = {
                            onStateChange(
                                TabActionDialog.UpdateExistingTemplate(
                                    layoutId = s.layoutId,
                                    keyboardName = s.keyboardName,
                                    filter = s.keyboardName
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    ) { Text(stringResource(R.string.tab_dialog_update_existing_template)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { onTemplateSaveCanceled() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
        is TabActionDialog.SaveAsNewTemplate -> {
            var name by remember(s.layoutId) { mutableStateOf(s.templateName) }
            AlertDialog(
                onDismissRequest = { onTemplateSaveCanceled() },
                title = { Text(stringResource(R.string.tab_dialog_save_template_title)) },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.tab_dialog_template_name_label)) },
                        singleLine = true,
                        trailingIcon = {
                            if (name.isNotEmpty()) {
                                IconButton(onClick = { name = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.dialog_clear))
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
                    ) { Text(stringResource(R.string.tab_dialog_save_template_confirm, s.keyboardName)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { onTemplateSaveCanceled() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) { Text(stringResource(R.string.dialog_cancel)) }
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
                title = { Text(stringResource(R.string.tab_dialog_update_template_title)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text(stringResource(R.string.dialog_filter_label)) },
                            singleLine = true,
                            trailingIcon = {
                                if (filter.isNotEmpty()) {
                                    IconButton(onClick = { filter = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.dialog_clear))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        if (matches.isEmpty()) {
                            Text(
                                if (userTemplates.isEmpty()) stringResource(R.string.tab_dialog_no_saved_templates)
                                else stringResource(R.string.tab_dialog_no_templates_match, filter),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                                items(matches, key = { it.id }) { template ->
                                    ListItem(
                                        onClick = {
                                            onStateChange(
                                                TabActionDialog.UpdateTemplateConfirm(
                                                    layoutId = s.layoutId,
                                                    keyboardName = s.keyboardName,
                                                    target = template,
                                                    previous = s
                                                )
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        supportingContent = {
                                            Text(
                                                stringResource(
                                                    R.string.tab_dialog_template_dimensions,
                                                    template.columns,
                                                    template.rows,
                                                    template.buttons.size,
                                                )
                                            )
                                        },
                                    ) { Text(template.name) }
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
                    ) { Text(stringResource(R.string.dialog_cancel)) }
                }
            )
        }
        is TabActionDialog.UpdateTemplateConfirm -> AlertDialog(
            onDismissRequest = { onStateChange(s.previous) },
            title = { Text(stringResource(R.string.tab_dialog_update_confirm_title, s.target.name)) },
            text = {
                Text(stringResource(R.string.tab_dialog_update_confirm_text, s.target.name))
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
                ) { Text(stringResource(R.string.tab_dialog_update_confirm_button, s.target.name)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { onStateChange(s.previous) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text(stringResource(R.string.dialog_back)) }
            }
        )
        is TabActionDialog.TemplateNameConflict -> {
            val existingUser = s.existing as? TemplateRef.User
            AlertDialog(
                onDismissRequest = { onTemplateSaveCanceled() },
                title = { Text(stringResource(R.string.tab_dialog_name_conflict_title, s.templateName)) },
                text = {
                    Text(stringResource(R.string.tab_dialog_name_conflict_text, s.templateName))
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
                        ) { Text(stringResource(R.string.tab_dialog_name_conflict_choose_different)) }
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
                            ) {
                                Text(
                                    stringResource(
                                        R.string.tab_dialog_name_conflict_update_existing,
                                        s.templateName,
                                    )
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { onTemplateSaveCanceled() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) { Text(stringResource(R.string.tab_dialog_name_conflict_cancel)) }
                }
            )
        }
        is TabActionDialog.AddKeyboardChooser -> AlertDialog(
            onDismissRequest = { onStateChange(null) },
            title = { Text(stringResource(R.string.tab_dialog_add_keyboard_title)) },
            text = {
                Column {
                    ListItem(
                        onClick = {
                            onAddBlankKeyboard()
                            onStateChange(null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    ) { Text(stringResource(R.string.tab_dialog_add_blank)) }
                    ListItem(
                        onClick = { onStateChange(TabActionDialog.AddFromTemplate) },
                        modifier = Modifier.fillMaxWidth(),
                        leadingContent = { Icon(Icons.Default.Bookmark, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    ) { Text(stringResource(R.string.tab_dialog_add_from_template)) }
                    ListItem(
                        onClick = { onStateChange(TabActionDialog.AddFromProfile) },
                        modifier = Modifier.fillMaxWidth(),
                        leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    ) { Text(stringResource(R.string.tab_dialog_add_from_profile)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { onStateChange(null) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
        is TabActionDialog.AddFromTemplate -> {
            var filter by remember { mutableStateOf("") }
            val matches = remember(filter, allTemplates) {
                if (filter.isBlank()) allTemplates
                else allTemplates.filter { it.name.contains(filter, ignoreCase = true) }
            }
            AlertDialog(
                onDismissRequest = { onStateChange(null) },
                title = { Text(stringResource(R.string.tab_dialog_add_from_template_title)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text(stringResource(R.string.dialog_filter_label)) },
                            singleLine = true,
                            trailingIcon = {
                                if (filter.isNotEmpty()) {
                                    IconButton(onClick = { filter = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.dialog_clear))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        if (matches.isEmpty()) {
                            Text(
                                if (allTemplates.isEmpty()) stringResource(R.string.tab_dialog_no_templates_available)
                                else stringResource(R.string.tab_dialog_no_templates_match, filter),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                                items(matches, key = { templateKey(it) }) { template ->
                                    ListItem(
                                        onClick = {
                                            onAddFromTemplate(template)
                                            onStateChange(null)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        supportingContent = {
                                            Text(
                                                stringResource(
                                                    R.string.tab_dialog_template_dimensions,
                                                    template.columns,
                                                    template.rows,
                                                    template.buttons.size,
                                                )
                                            )
                                        },
                                    ) { Text(template.name) }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(
                        onClick = { onStateChange(null) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) { Text(stringResource(R.string.dialog_cancel)) }
                }
            )
        }
        is TabActionDialog.AddFromProfile -> {
            val otherProfiles = remember(profiles, activeProfileId) {
                profiles.filter { it.id != activeProfileId }
            }
            AlertDialog(
                onDismissRequest = { onStateChange(null) },
                title = { Text(stringResource(R.string.tab_dialog_pick_profile_title)) },
                text = {
                    if (otherProfiles.isEmpty()) {
                        Text(
                            stringResource(R.string.tab_dialog_no_other_profiles),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                            items(otherProfiles, key = { it.id }) { profile ->
                                ListItem(
                                    onClick = {
                                        onStateChange(
                                            TabActionDialog.AddFromProfileLayout(
                                                profileId = profile.id,
                                                profileName = profile.name
                                            )
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(profile.name) }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(
                        onClick = { onStateChange(null) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) { Text(stringResource(R.string.dialog_cancel)) }
                }
            )
        }
        is TabActionDialog.RemoveButtonConfirm -> AlertDialog(
            onDismissRequest = { onStateChange(null) },
            title = { Text(stringResource(R.string.tab_dialog_remove_button_title)) },
            text = {
                val fallback = stringResource(R.string.tab_dialog_remove_button_fallback)
                val label = s.buttonLabel.ifBlank { fallback }
                Text(stringResource(R.string.tab_dialog_remove_button_text, label))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmDeleteButton(s.buttonId)
                        onStateChange(null)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.tab_dialog_remove_button_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { onStateChange(null) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text(stringResource(R.string.dialog_cancel)) }
            }
        )
        is TabActionDialog.AddFromProfileLayout -> {
            var profileLayouts by remember(s.profileId) { mutableStateOf<List<GridLayout>>(emptyList()) }
            LaunchedEffect(s.profileId) {
                profileLayouts = fetchProfileLayouts(s.profileId)
            }
            AlertDialog(
                onDismissRequest = { onStateChange(null) },
                title = { Text(stringResource(R.string.tab_dialog_pick_keyboard_title, s.profileName)) },
                text = {
                    if (profileLayouts.isEmpty()) {
                        Text(
                            stringResource(R.string.tab_dialog_no_keyboards_in_profile),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                            items(profileLayouts, key = { it.id }) { layout ->
                                ListItem(
                                    onClick = {
                                        onAddFromProfile(layout.id)
                                        onStateChange(null)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                R.string.tab_dialog_template_dimensions,
                                                layout.columns,
                                                layout.rows,
                                                layout.buttons.size,
                                            )
                                        )
                                    },
                                ) { Text(layout.name) }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(
                        onClick = { onStateChange(TabActionDialog.AddFromProfile) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) { Text(stringResource(R.string.dialog_back)) }
                }
            )
        }
    }
}

private fun templateKey(ref: TemplateRef): String = when (ref) {
    is TemplateRef.BuiltIn -> "builtin:${ref.key}"
    is TemplateRef.User -> "user:${ref.id}"
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
        title = { Text(stringResource(R.string.tab_dialog_configure_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.tab_dialog_configure_name_label)) },
                    singleLine = true,
                    trailingIcon = {
                        if (name.isNotEmpty()) {
                            IconButton(onClick = { name = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.dialog_clear))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.tab_dialog_configure_background_color),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ColorSwatchPicker(
                        selectedArgb = bgColor,
                        onSelect = { bgColor = it }
                    )
                }
                SteppedSliderWithNumberInput(
                    label = stringResource(R.string.tab_dialog_configure_rows),
                    value = rows,
                    onValueChange = { rows = it },
                    min = 1,
                    max = 20
                )
                SteppedSliderWithNumberInput(
                    label = stringResource(R.string.tab_dialog_configure_columns),
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
                        Text(stringResource(R.string.tab_dialog_configure_reset), fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onApply(name.trim(), cols, rows, bgColor) }
            ) { Text(stringResource(R.string.tab_dialog_configure_apply)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) { Text(stringResource(R.string.dialog_cancel)) }
        }
    )
}
