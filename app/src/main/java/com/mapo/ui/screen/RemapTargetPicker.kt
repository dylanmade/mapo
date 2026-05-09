package com.mapo.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mapo.data.defaults.InputOption
import com.mapo.data.defaults.RemapInputOptions
import kotlinx.collections.immutable.ImmutableList
import com.mapo.data.model.RemapTarget

/**
 * Full-screen picker for choosing a [RemapTarget]. Reached as a Navigation destination from
 * RemapControlsScreen (per-physical-button mappings) and ConfigureButtonScreen (per-virtual-
 * button trackpad gestures). The internal multi-step flow (category → filtered list) is
 * preserved from the previous AlertDialog version, just hosted in a Scaffold + TopAppBar.
 *
 * On selection the screen invokes [onSelect] with the chosen target; the caller is
 * responsible for routing it back to the previous destination's savedStateHandle and popping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemapTargetPickerScreen(
    title: String,
    currentEncoded: String,
    onSelect: (RemapTarget) -> Unit,
    onBack: () -> Unit,
) {
    val current = remember(currentEncoded) { RemapTarget.decode(currentEncoded) }
    var pickerState by remember { mutableStateOf<RemapPickerState>(RemapPickerState.CategorySelection) }
    val onCategoryNav: () -> Unit = { pickerState = RemapPickerState.CategorySelection }
    val isCategoryView = pickerState is RemapPickerState.CategorySelection

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = if (isCategoryView) onBack else onCategoryNav) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (isCategoryView) "Back" else "Back to categories",
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize(),
        ) {
            when (val state = pickerState) {
                is RemapPickerState.CategorySelection -> CategoryList(
                    current = current,
                    onUnbound = { onSelect(RemapTarget.Unbound) },
                    onPickGamepad = { pickerState = RemapPickerState.GamepadList() },
                    onPickKeyboard = { pickerState = RemapPickerState.KeyboardList() },
                    onPickMouse = { pickerState = RemapPickerState.MouseList() },
                )
                is RemapPickerState.GamepadList -> FilteredInputList(
                    options = RemapInputOptions.gamepadOptions,
                    filter = state.filter,
                    showFilter = true,
                    current = current,
                    onFilterChange = { pickerState = state.copy(filter = it) },
                    onSelect = onSelect,
                )
                is RemapPickerState.KeyboardList -> FilteredInputList(
                    options = RemapInputOptions.keyboardOptions,
                    filter = state.filter,
                    showFilter = true,
                    current = current,
                    onFilterChange = { pickerState = state.copy(filter = it) },
                    onSelect = onSelect,
                )
                is RemapPickerState.MouseList -> FilteredInputList(
                    options = RemapInputOptions.mouseOptions,
                    filter = state.filter,
                    showFilter = false,
                    current = current,
                    onFilterChange = {},
                    onSelect = onSelect,
                )
            }
        }
    }
}

@Composable
private fun CategoryList(
    current: RemapTarget,
    onUnbound: () -> Unit,
    onPickGamepad: () -> Unit,
    onPickKeyboard: () -> Unit,
    onPickMouse: () -> Unit,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CategoryRow(
    label: String,
    selected: Boolean,
    showChevron: Boolean = true,
    onClick: () -> Unit,
) {
    // primaryContainer when selected; transparent otherwise (lets the screen Scaffold's
    // surfaceContainerLowest show through)
    ListItem(
        onClick = onClick,
        trailingContent = if (showChevron) {
            { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
        } else null,
        leadingContent = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                             else Color.Transparent,
        ),
    ) { Text(label) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FilteredInputList(
    options: ImmutableList<InputOption>,
    filter: String,
    showFilter: Boolean,
    current: RemapTarget,
    onFilterChange: (String) -> Unit,
    onSelect: (RemapTarget) -> Unit,
) {
    val filtered = if (filter.isBlank()) options
    else options.filter { it.label.contains(filter, ignoreCase = true) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (showFilter) {
            OutlinedTextField(
                value = filter,
                onValueChange = onFilterChange,
                label = { Text("Filter") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(filtered) { index, option ->
                    val isSelected = option.target == current
                    ListItem(
                        onClick = { onSelect(option.target) },
                        leadingContent = if (isSelected) {
                            { Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                        colors = ListItemDefaults.colors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                             else Color.Transparent,
                        ),
                    ) { Text(option.label) }
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun BoxScope.ScrollFade(visible: Boolean, alignment: Alignment, fromTop: Boolean) {
    if (!visible) return
    val surface = MaterialTheme.colorScheme.surfaceContainerLowest
    Box(
        modifier = Modifier
            .align(alignment)
            .fillMaxWidth()
            .height(16.dp)
            .background(
                Brush.verticalGradient(
                    colors = if (fromTop) listOf(surface, surface.copy(alpha = 0f))
                             else listOf(surface.copy(alpha = 0f), surface),
                ),
            ),
    )
}
