package com.mapo.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.mapo.data.defaults.InputOption
import com.mapo.data.defaults.RemapInputOptions
import com.mapo.data.model.RemapTarget

/**
 * Shared picker dialog for choosing a RemapTarget. Used by physical-button remapping,
 * virtual-button edit, and trackpad gesture remapping. Single source of truth for the
 * category-then-filtered-list flow.
 */
@Composable
fun RemapTargetPickerDialog(
    title: String,
    current: RemapTarget,
    onSelect: (RemapTarget) -> Unit,
    onDismiss: () -> Unit
) {
    var pickerState by remember { mutableStateOf<RemapPickerState>(RemapPickerState.CategorySelection) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pickerState !is RemapPickerState.CategorySelection) {
                    IconButton(onClick = { pickerState = RemapPickerState.CategorySelection }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Text(title)
            }
        },
        text = {
            when (val state = pickerState) {
                is RemapPickerState.CategorySelection -> CategoryList(
                    current = current,
                    onUnbound = { onSelect(RemapTarget.Unbound) },
                    onPickGamepad  = { pickerState = RemapPickerState.GamepadList() },
                    onPickKeyboard = { pickerState = RemapPickerState.KeyboardList() },
                    onPickMouse    = { pickerState = RemapPickerState.MouseList() }
                )
                is RemapPickerState.GamepadList -> FilteredInputList(
                    options = RemapInputOptions.gamepadOptions,
                    filter = state.filter,
                    showFilter = true,
                    current = current,
                    onFilterChange = { pickerState = state.copy(filter = it) },
                    onSelect = onSelect
                )
                is RemapPickerState.KeyboardList -> FilteredInputList(
                    options = RemapInputOptions.keyboardOptions,
                    filter = state.filter,
                    showFilter = true,
                    current = current,
                    onFilterChange = { pickerState = state.copy(filter = it) },
                    onSelect = onSelect
                )
                is RemapPickerState.MouseList -> FilteredInputList(
                    options = RemapInputOptions.mouseOptions,
                    filter = state.filter,
                    showFilter = false,
                    current = current,
                    onFilterChange = {},
                    onSelect = onSelect
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CategoryList(
    current: RemapTarget,
    onUnbound: () -> Unit,
    onPickGamepad: () -> Unit,
    onPickKeyboard: () -> Unit,
    onPickMouse: () -> Unit
) {
    Column {
        CategoryRow("Unbound / None", current is RemapTarget.Unbound, showChevron = false, onClick = onUnbound)
        HorizontalDivider()
        CategoryRow("Gamepad", current is RemapTarget.Gamepad, onClick = onPickGamepad)
        HorizontalDivider()
        CategoryRow("Keyboard", current is RemapTarget.Keyboard, onClick = onPickKeyboard)
        HorizontalDivider()
        CategoryRow("Mouse", current is RemapTarget.Mouse, onClick = onPickMouse)
    }
}

@Composable
private fun CategoryRow(
    label: String,
    selected: Boolean,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = if (showChevron) {
            { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
        } else null,
        leadingContent = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) }
        } else null,
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun FilteredInputList(
    options: List<InputOption>,
    filter: String,
    showFilter: Boolean,
    current: RemapTarget,
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
                    .padding(bottom = 8.dp)
            )
        }
        Box(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(filtered) { index, option ->
                    val isSelected = option.target == current
                    ListItem(
                        headlineContent = { Text(option.label) },
                        leadingContent = if (isSelected) {
                            { Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                        modifier = Modifier.clickable { onSelect(option.target) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                             else MaterialTheme.colorScheme.surfaceContainer
                        )
                    )
                    if (index < filtered.lastIndex) HorizontalDivider()
                }
            }
            ScrollFade(visible = listState.canScrollBackward, alignment = Alignment.TopCenter, fromTop = true)
            ScrollFade(visible = listState.canScrollForward, alignment = Alignment.BottomCenter, fromTop = false)
        }
        if (filtered.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No matches",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}

@Composable
private fun BoxScope.ScrollFade(visible: Boolean, alignment: Alignment, fromTop: Boolean) {
    if (!visible) return
    val surface = MaterialTheme.colorScheme.surfaceContainer
    Box(
        modifier = Modifier
            .align(alignment)
            .fillMaxWidth()
            .height(16.dp)
            .background(
                Brush.verticalGradient(
                    colors = if (fromTop) listOf(surface, surface.copy(alpha = 0f))
                             else listOf(surface.copy(alpha = 0f), surface)
                )
            )
    )
}

