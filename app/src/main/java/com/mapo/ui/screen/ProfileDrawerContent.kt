package com.mapo.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SecurityUpdateGood
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mapo.R
import com.mapo.data.model.Profile

/**
 * The main Mapo drawer. Three zones:
 *  - **Top sticky** — current profile + a "Change profile" button.
 *  - **Middle scrollable** — navigation destinations.
 *  - **Bottom sticky** — the always-visible toggles (physical remap, button overlay).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileDrawerContent(
    activeProfile: Profile?,
    steamAccountName: String?,
    remapEnabled: Boolean,
    overlayShowing: Boolean,
    onOpenChangeProfile: () -> Unit,
    onOpenRemapControls: () -> Unit,
    onOpenAutoSwitch: () -> Unit,
    onOpenBlocklist: () -> Unit,
    onOpenThemeStudio: () -> Unit,
    onOpenShizukuSetup: () -> Unit,
    onOpenSteamSetup: () -> Unit,
    onOpenCompactGallery: () -> Unit,
    onEditOverlay: () -> Unit,
    onToggleRemap: () -> Unit,
    onToggleOverlay: () -> Unit,
) {
    // surfaceContainerHigh — drawer sheet (canonical M3 elevated container per Reply)
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            // ── Top sticky: current profile + Change profile ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Current profile",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = activeProfile?.name ?: "None",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                OutlinedButton(onClick = onOpenChangeProfile) {
                    Text("Change profile")
                }
            }
            HorizontalDivider()

            // ── Middle scrollable: navigation ──
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    NavigationDrawerItem(
                        label = { Text("Remap controls") },
                        selected = false,
                        onClick = onOpenRemapControls,
                        icon = { Icon(Icons.Default.SportsEsports, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.overlay_edit_drawer_label)) },
                        selected = false,
                        onClick = onEditOverlay,
                        icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.auto_switch_drawer_label)) },
                        selected = false,
                        onClick = onOpenAutoSwitch,
                        icon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.blocklist_drawer_label)) },
                        selected = false,
                        onClick = onOpenBlocklist,
                        icon = { Icon(Icons.Default.Block, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.theme_studio_drawer_label)) },
                        selected = false,
                        onClick = onOpenThemeStudio,
                        icon = { Icon(Icons.Default.Palette, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.shizuku_drawer_label)) },
                        selected = false,
                        onClick = onOpenShizukuSetup,
                        icon = { Icon(Icons.Default.SecurityUpdateGood, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    NavigationDrawerItem(
                        label = {
                            Text(
                                stringResource(
                                    if (steamAccountName != null) {
                                        R.string.steam_drawer_label_signed_in
                                    } else {
                                        R.string.steam_drawer_label_connect
                                    },
                                ),
                            )
                        },
                        selected = false,
                        onClick = onOpenSteamSetup,
                        icon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    NavigationDrawerItem(
                        label = { Text("Compact component gallery") },
                        selected = false,
                        onClick = onOpenCompactGallery,
                        icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }

            // ── Bottom sticky: always-visible toggles ──
            HorizontalDivider()
            ToggleRow(
                label = "Physical button remapping",
                checked = remapEnabled,
                onToggle = onToggleRemap,
            )
            ToggleRow(
                label = "Button overlay",
                checked = overlayShowing,
                onToggle = onToggleOverlay,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
