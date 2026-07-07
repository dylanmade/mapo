package com.mappo.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mappo.R
import com.mappo.data.model.Profile
import com.mappo.data.repository.InstalledAppsRepository.InstalledApp

/**
 * Modal bottom sheet listing every launchable app on the device, filtered by a
 * text query, with multi-select checkboxes. Confirm binds every selected
 * package to [targetProfile] via [onConfirm]; existing bindings on those
 * packages are silently re-pointed (the ListItem supporting text warns the
 * user).
 *
 * Lifecycle: parent toggles [visible]; the sheet calls [onDismiss] when the
 * user swipes down or taps outside. Parent owns the "load installed apps"
 * trigger (it knows when the sheet is about to appear).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(
    visible: Boolean,
    targetProfile: Profile,
    installedApps: List<InstalledApp>,
    existingBindings: Map<String, Long>,
    profilesById: Map<Long, Profile>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selected = remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }

    // Reset selection when the sheet is (re)opened.
    LaunchedEffect(visible) {
        if (visible) {
            selected.value = emptySet()
            query = ""
        }
    }

    val filtered = remember(installedApps, query) {
        if (query.isBlank()) installedApps
        else installedApps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets.navigationBars },
        // surfaceContainerLow — modal sheets per M3 standards memo.
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp)
                .imePadding(),
        ) {
            Text(
                text = stringResource(R.string.auto_switch_picker_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Text(
                text = stringResource(
                    R.string.auto_switch_picker_subtitle,
                    targetProfile.name,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.auto_switch_picker_search_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 160.dp, max = 480.dp),
            ) {
                when {
                    installedApps.isEmpty() -> LoadingBlock()
                    filtered.isEmpty() -> EmptyBlock()
                    else -> AppList(
                        apps = filtered,
                        selected = selected.value,
                        existingBindings = existingBindings,
                        profilesById = profilesById,
                        targetProfileId = targetProfile.id,
                        onToggle = { pkg ->
                            selected.value = if (pkg in selected.value) {
                                selected.value - pkg
                            } else {
                                selected.value + pkg
                            }
                        },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_cancel))
                }
                Button(
                    enabled = selected.value.isNotEmpty(),
                    onClick = {
                        onConfirm(selected.value)
                        onDismiss()
                    },
                ) {
                    val n = selected.value.size
                    val label = if (n == 1) {
                        stringResource(R.string.auto_switch_picker_confirm_one)
                    } else {
                        stringResource(R.string.auto_switch_picker_confirm, n)
                    }
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 4.dp))
            Text(
                text = stringResource(R.string.auto_switch_picker_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyBlock() {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.auto_switch_picker_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppList(
    apps: List<InstalledApp>,
    selected: Set<String>,
    existingBindings: Map<String, Long>,
    profilesById: Map<Long, Profile>,
    targetProfileId: Long,
    onToggle: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(apps, key = { it.packageName }) { app ->
            val isSelected = app.packageName in selected
            val existingProfileId = existingBindings[app.packageName]
            val supporting: String? = when {
                existingProfileId == targetProfileId -> stringResource(R.string.auto_switch_picker_already_bound)
                existingProfileId != null -> {
                    val name = profilesById[existingProfileId]?.name
                        ?: stringResource(R.string.auto_switch_unknown_profile)
                    stringResource(R.string.auto_switch_picker_bound_elsewhere, name)
                }
                else -> app.packageName
            }
            ListItem(
                headlineContent = {
                    Text(
                        text = app.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = supporting?.let {
                    {
                        Text(
                            text = it,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                trailingContent = {
                    Checkbox(checked = isSelected, onCheckedChange = { onToggle(app.packageName) })
                },
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(app.packageName) },
                tonalElevation = 0.dp,
            )
        }
    }
}
