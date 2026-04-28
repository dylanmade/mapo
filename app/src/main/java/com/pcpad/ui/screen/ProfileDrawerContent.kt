package com.pcpad.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pcpad.data.model.Profile

@Composable
fun ProfileDrawerContent(
    profiles: List<Profile>,
    activeProfile: Profile?,
    onSelectProfile: (Profile) -> Unit,
    onAddProfile: (String) -> Unit,
    onDuplicateProfile: (Profile) -> Unit,
    onDeleteProfile: (Profile) -> Unit
) {
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
        Text(
            text = "Profiles",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
        )
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
    }
}
