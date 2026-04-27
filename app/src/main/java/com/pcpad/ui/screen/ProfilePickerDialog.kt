package com.pcpad.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.window.Dialog
import com.pcpad.data.model.Profile

@Composable
fun ProfilePickerDialog(
    profiles: List<Profile>,
    activeProfileId: Long?,
    onDismiss: () -> Unit,
    onSelect: (Profile) -> Unit,
    onAdd: (String) -> Unit,
    onDuplicate: (Profile) -> Unit,
    onDelete: (Profile) -> Unit
) {
    var selectedProfileId by remember { mutableStateOf(activeProfileId) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }

    val filteredProfiles = remember(profiles, searchQuery) {
        if (searchQuery.isBlank()) profiles
        else profiles.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }

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
                TextButton(
                    onClick = {
                        if (newProfileName.isNotBlank()) {
                            onAdd(newProfileName.trim())
                            newProfileName = ""
                            showAddDialog = false
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = {
                    newProfileName = ""
                    showAddDialog = false
                }) { Text("Cancel") }
            }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Profiles",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(onClick = {
                        newProfileName = ""
                        showAddDialog = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add profile")
                    }
                    IconButton(
                        onClick = { selectedProfile?.let(onDuplicate) },
                        enabled = selectedProfile != null
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate profile")
                    }
                    IconButton(
                        onClick = { selectedProfile?.let(onDelete) },
                        enabled = selectedProfile != null && selectedProfile.isDefault == false
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete profile")
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 300.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredProfiles, key = { it.id }) { profile ->
                        val isSelected = profile.id == selectedProfileId
                        Surface(
                            onClick = { selectedProfileId = profile.id },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                            border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                     else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = profile.name,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { selectedProfile?.let { onSelect(it) } },
                        enabled = selectedProfile != null
                    ) { Text("Select") }
                }
            }
        }
    }
}
