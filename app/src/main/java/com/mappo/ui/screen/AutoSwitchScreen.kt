package com.mappo.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.mappo.R
import com.mappo.data.model.AppProfileBinding
import com.mappo.data.model.Profile
import com.mappo.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoSwitchScreen(
    onBack: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val enabled by viewModel.autoSwitchEnabled.collectAsStateWithLifecycle()
    val autoCreateEnabled by viewModel.autoCreateProfilesEnabled.collectAsStateWithLifecycle()
    val bindings by viewModel.appProfileBindings.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val appLabels by viewModel.appLabels.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val profilesById = remember(profiles) { profiles.associateBy { it.id } }
    // package → profileId index for the picker's "already bound" / "bound elsewhere" hint.
    val bindingsByPackage = remember(bindings) {
        bindings.associate { it.packageName to it.profileId }
    }

    var pickerTargetProfile by remember { mutableStateOf<Profile?>(null) }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.auto_switch_screen_title))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.auto_switch_back),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
        ) {
            // Settings-row treatment (row-doctrine #4): ListItem with trailing Switch,
            // whole-row clickable so the tap target matches M3 settings idiom.
            ListItem(
                headlineContent = {
                    Text(text = stringResource(R.string.auto_switch_setting_label))
                },
                trailingContent = {
                    Switch(checked = enabled, onCheckedChange = null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setAutoSwitchEnabled(!enabled) },
            )
            HorizontalDivider()

            ListItem(
                headlineContent = {
                    Text(text = stringResource(R.string.auto_create_profiles_setting_label))
                },
                trailingContent = {
                    Switch(
                        enabled = enabled,
                        checked = autoCreateEnabled,
                        onCheckedChange = null,
                    )
                },
                colors = ListItemDefaults.colors(
                    headlineColor = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) {
                        viewModel.setAutoCreateProfilesEnabled(!autoCreateEnabled)
                    },
            )
            HorizontalDivider()

            Text(
                text = stringResource(R.string.auto_switch_bindings_header),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (profiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.auto_switch_bindings_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        ProfileBindingSection(
                            profile = profile,
                            bindings = bindings.filter { it.profileId == profile.id },
                            appLabels = appLabels,
                            onAddClick = { pickerTargetProfile = profile },
                            onDelete = { binding ->
                                viewModel.deleteBinding(binding.packageName, binding.subId)
                            },
                        )
                    }
                }
            }
        }
    }

    val target = pickerTargetProfile
    if (target != null) {
        // Trigger a fresh PackageManager pass each time the picker opens —
        // cheap (off the IO dispatcher) and dodges stale state if the user
        // installed/uninstalled something between sessions.
        LaunchedEffect(target.id) { viewModel.loadInstalledApps() }

        AppPickerSheet(
            visible = true,
            targetProfile = target,
            installedApps = installedApps,
            existingBindings = bindingsByPackage,
            profilesById = profilesById,
            onConfirm = { picked ->
                viewModel.bindAppsToProfile(target.id, picked)
            },
            onDismiss = { pickerTargetProfile = null },
        )
    }
}

@Composable
private fun ProfileBindingSection(
    profile: Profile,
    bindings: List<AppProfileBinding>,
    appLabels: Map<String, String>,
    onAddClick: () -> Unit,
    onDelete: (AppProfileBinding) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAddClick) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.auto_switch_add_app),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        if (bindings.isEmpty()) {
            // Helper subtext per list-item-helper-subtext memo — supports
            // tutorialization for users staring at a fresh profile.
            Text(
                text = stringResource(R.string.auto_switch_profile_section_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                bindings.forEach { binding ->
                    val appLabel = appLabels[binding.packageName] ?: binding.packageName
                    // surfaceVariant — list-row container per Reply convention (unselected list items)
                    ListItem(
                        headlineContent = { Text(appLabel) },
                        supportingContent = { Text(binding.packageName) },
                        trailingContent = {
                            IconButton(
                                onClick = { onDelete(binding) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.auto_switch_delete_binding),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                    )
                }
            }
        }
    }
}
