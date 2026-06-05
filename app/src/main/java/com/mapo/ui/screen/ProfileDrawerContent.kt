package com.mapo.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SecurityUpdateGood
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mapo.R
import com.mapo.data.model.Profile

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileDrawerContent(
    activeProfile: Profile?,
    steamAccountName: String?,
    onOpenChangeProfile: () -> Unit,
    onOpenRemapControls: () -> Unit,
    onOpenAutoSwitch: () -> Unit,
    onOpenBlocklist: () -> Unit,
    onOpenThemeStudio: () -> Unit,
    onOpenShizukuSetup: () -> Unit,
    onOpenSteamSetup: () -> Unit,
    onToggleKeyboardOverlay: () -> Unit,
    onToggleOverlay: () -> Unit,
    onEditOverlay: () -> Unit,
) {
    // surfaceContainerHigh — drawer sheet (canonical M3 elevated container per Reply)
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = "Current Profile",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
        )
        Text(
            text = activeProfile?.name ?: "None",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 8.dp),
        )
        HorizontalDivider()
        LazyColumn {
            item {
                NavigationDrawerItem(
                    label = { Text("Change Profile") },
                    selected = false,
                    onClick = onOpenChangeProfile,
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text("Remap Controls") },
                    selected = false,
                    onClick = onOpenRemapControls,
                    icon = { Icon(Icons.Default.SportsEsports, contentDescription = null) },
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
            // Mirror of the Quick Settings tile. The tile is the primary activation
            // surface (system-wide reach without opening Mapo); this drawer entry is
            // a convenience for first-run setup / users who don't know about the tile.
            item {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.keyboard_overlay_drawer_label)) },
                    selected = false,
                    onClick = onToggleKeyboardOverlay,
                    icon = { Icon(Icons.Default.Keyboard, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            // Rebuilt free-positioned button overlay (one window per button). Coexists
            // with the legacy keyboard overlay above; see OVERLAY_REBUILD_PLAN.md.
            item {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.overlay_show_drawer_label)) },
                    selected = false,
                    onClick = onToggleOverlay,
                    icon = { Icon(Icons.Default.Gamepad, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            // Live on-overlay editor for the rebuilt button overlay (OVERLAY_REBUILD_PLAN.md).
            item {
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.overlay_edit_drawer_label)) },
                    selected = false,
                    onClick = onEditOverlay,
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}
