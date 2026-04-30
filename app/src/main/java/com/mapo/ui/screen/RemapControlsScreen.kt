package com.mapo.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mapo.data.model.DeviceButton
import com.mapo.data.model.RemapTarget
import com.mapo.data.model.displayLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemapControlsScreen(
    initialMappings: Map<String, RemapTarget>,
    onSave: (Map<DeviceButton, RemapTarget>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val draft = remember {
        mutableStateMapOf<DeviceButton, RemapTarget>().also { map ->
            DeviceButton.entries.forEach { btn ->
                map[btn] = initialMappings[btn.name] ?: RemapTarget.Unbound
            }
        }
    }

    var editingButton by remember { mutableStateOf<DeviceButton?>(null) }

    if (editingButton != null) {
        val btn = editingButton!!
        RemapTargetPickerDialog(
            title = "Remap: ${btn.displayName}",
            current = draft[btn] ?: RemapTarget.Unbound,
            onSelect = { target ->
                draft[btn] = target
                editingButton = null
            },
            onDismiss = { editingButton = null }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Remap Controls") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onSave(draft.toMap()) }) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(contentPadding = innerPadding) {
            itemsIndexed(DeviceButton.entries) { index, btn ->
                val target = draft[btn] ?: RemapTarget.Unbound
                ListItem(
                    headlineContent = { Text(btn.displayName) },
                    supportingContent = {
                        Text(
                            target.displayLabel(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (target is RemapTarget.Unbound) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        OutlinedButton(
                            onClick = { editingButton = btn },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Edit")
                        }
                    },
                    modifier = Modifier.clickable { editingButton = btn },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
                )
                if (index < DeviceButton.entries.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}
