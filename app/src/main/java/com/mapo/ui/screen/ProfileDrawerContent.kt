package com.mapo.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
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
import com.mapo.data.model.Profile

@Composable
fun ProfileDrawerContent(
    profiles: List<Profile>,
    activeProfile: Profile?,
    onSelectProfile: (Profile) -> Unit,
    onAddProfile: (String) -> Unit,
    onDuplicateProfile: (Profile) -> Unit,
    onDeleteProfile: (Profile) -> Unit,
    onOpenRemapControls: () -> Unit
) {
    var showProfileSelector by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New Profile") },
            text = {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text("Profile Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newProfileName.isNotBlank()) {
                        onAddProfile(newProfileName.trim())
                        newProfileName = ""
                        showAddDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = {
                    newProfileName = ""
                    showAddDialog = false
                }) { Text("Cancel") }
            }
        )
    }

    ModalDrawerSheet {
        if (showProfileSelector) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 16.dp, top = 4.dp, bottom = 4.dp)
            ) {
                IconButton(onClick = { showProfileSelector = false }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "Change Profile",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            HorizontalDivider()
            LazyColumn {
                items(profiles, key = { it.id }) { profile ->
                    NavigationDrawerItem(
                        label = { Text(profile.name) },
                        selected = profile.id == activeProfile?.id,
                        onClick = { onSelectProfile(profile) },
                        badge = {
                            Row {
                                IconButton(
                                    onClick = { onDuplicateProfile(profile) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Duplicate",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                if (!profile.isDefault) {
                                    IconButton(
                                        onClick = { onDeleteProfile(profile) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                item {
                    NavigationDrawerItem(
                        label = { Text("Add Profile") },
                        selected = false,
                        onClick = {
                            newProfileName = ""
                            showAddDialog = true
                        },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        } else {
            Text(
                text = "Current Profile",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)
            )
            Text(
                text = activeProfile?.name ?: "None",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 8.dp)
            )
            HorizontalDivider()
            LazyColumn {
                item {
                    NavigationDrawerItem(
                        label = { Text("Change Profile") },
                        selected = false,
                        onClick = { showProfileSelector = true },
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                        badge = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                item {
                    NavigationDrawerItem(
                        label = { Text("Remap Controls") },
                        selected = false,
                        onClick = { onOpenRemapControls() },
                        icon = { Icon(Icons.Default.SportsEsports, contentDescription = null) },
                        badge = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}
