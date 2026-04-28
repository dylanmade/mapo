package com.pcpad.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pcpad.data.defaults.InputOption
import com.pcpad.data.defaults.RemapInputOptions
import com.pcpad.data.model.DeviceButton
import com.pcpad.data.model.RemapTarget
import com.pcpad.data.model.displayLabel

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
    var pickerState by remember { mutableStateOf<RemapPickerState>(RemapPickerState.CategorySelection) }

    if (editingButton != null) {
        val btn = editingButton!!
        AlertDialog(
            onDismissRequest = { editingButton = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pickerState !is RemapPickerState.CategorySelection) {
                        IconButton(onClick = { pickerState = RemapPickerState.CategorySelection }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                    Text("Remap: ${btn.displayName}")
                }
            },
            text = {
                when (val state = pickerState) {
                    is RemapPickerState.CategorySelection -> {
                        Column {
                            TextButton(onClick = {
                                draft[btn] = RemapTarget.Unbound
                                editingButton = null
                                pickerState = RemapPickerState.CategorySelection
                            }, modifier = Modifier.fillMaxWidth()) { Text("Unbound / None") }
                            TextButton(
                                onClick = { pickerState = RemapPickerState.GamepadList() },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Gamepad Inputs") }
                            TextButton(
                                onClick = { pickerState = RemapPickerState.KeyboardList() },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Keyboard Inputs") }
                            TextButton(
                                onClick = { pickerState = RemapPickerState.MouseList() },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Mouse Inputs") }
                        }
                    }
                    is RemapPickerState.GamepadList -> {
                        FilteredInputList(
                            options = RemapInputOptions.gamepadOptions,
                            filter = state.filter,
                            showFilter = true,
                            onFilterChange = { pickerState = state.copy(filter = it) },
                            onSelect = { target ->
                                draft[btn] = target
                                editingButton = null
                                pickerState = RemapPickerState.CategorySelection
                            }
                        )
                    }
                    is RemapPickerState.KeyboardList -> {
                        FilteredInputList(
                            options = RemapInputOptions.keyboardOptions,
                            filter = state.filter,
                            showFilter = true,
                            onFilterChange = { pickerState = state.copy(filter = it) },
                            onSelect = { target ->
                                draft[btn] = target
                                editingButton = null
                                pickerState = RemapPickerState.CategorySelection
                            }
                        )
                    }
                    is RemapPickerState.MouseList -> {
                        FilteredInputList(
                            options = RemapInputOptions.mouseOptions,
                            filter = state.filter,
                            showFilter = false,
                            onFilterChange = {},
                            onSelect = { target ->
                                draft[btn] = target
                                editingButton = null
                                pickerState = RemapPickerState.CategorySelection
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    editingButton = null
                    pickerState = RemapPickerState.CategorySelection
                }) { Text("Cancel") }
            }
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
            items(DeviceButton.entries) { btn ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = btn.displayName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(
                        onClick = {
                            editingButton = btn
                            pickerState = RemapPickerState.CategorySelection
                        }
                    ) {
                        Text((draft[btn] ?: RemapTarget.Unbound).displayLabel())
                    }
                }
            }
        }
    }
}

@Composable
private fun FilteredInputList(
    options: List<InputOption>,
    filter: String,
    showFilter: Boolean,
    onFilterChange: (String) -> Unit,
    onSelect: (RemapTarget) -> Unit
) {
    val filtered = if (filter.isBlank()) options
    else options.filter { it.label.contains(filter, ignoreCase = true) }

    Column {
        if (showFilter) {
            OutlinedTextField(
                value = filter,
                onValueChange = onFilterChange,
                label = { Text("Filter") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            )
        }
        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
            items(filtered) { option ->
                Text(
                    text = option.label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option.target) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
