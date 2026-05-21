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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mapo.data.defaults.InputOption
import com.mapo.data.defaults.RemapInputOptions
import com.mapo.data.model.RemapTarget
import com.mapo.data.model.steam.BindingOutput
import kotlinx.collections.immutable.ImmutableList

/**
 * Full-screen picker for choosing a [BindingOutput]. Reached as a Navigation destination
 * from `InputEditorScreen` (Steam-Input remap world) and `ConfigureButtonScreen` (legacy
 * trackpad gestures — which convert the picked output back to [RemapTarget] at the boundary).
 *
 * Brick 4.5 generalized this from the previous `RemapTarget`-only picker. The Gamepad /
 * Keyboard / Mouse categories still produce the same legacy outputs (now wrapped as
 * [BindingOutput.fromRemapTarget]); a new **Controller → Switch Action Set** category
 * appears only when [availableActionSets] is non-empty, and emits a
 * `ControllerAction("CHANGE_PRESET", [setId])` binding.
 *
 * On selection the screen invokes [onSelect] with the chosen [BindingOutput]; the caller
 * routes it back to the previous destination's savedStateHandle and pops.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemapTargetPickerScreen(
    title: String,
    currentEncoded: String,
    onSelect: (BindingOutput) -> Unit,
    onBack: () -> Unit,
    availableActionSets: List<Pair<Long, String>> = emptyList(),
    availableLayers: List<Pair<Long, String>> = emptyList(),
) {
    val current = remember(currentEncoded) { BindingOutput.decode(currentEncoded) }
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
                    showSwitchActionSet = availableActionSets.isNotEmpty(),
                    showLayer = availableLayers.isNotEmpty(),
                    onUnbound = { onSelect(BindingOutput.Unbound) },
                    onPickGamepad = { pickerState = RemapPickerState.GamepadList() },
                    onPickKeyboard = { pickerState = RemapPickerState.KeyboardList() },
                    onPickMouse = { pickerState = RemapPickerState.MouseList() },
                    onPickSwitchActionSet = { pickerState = RemapPickerState.SwitchActionSetList },
                    onPickLayer = { pickerState = RemapPickerState.LayerVerbList },
                )
                is RemapPickerState.GamepadList -> FilteredInputList(
                    options = RemapInputOptions.gamepadOptions,
                    filter = state.filter,
                    showFilter = true,
                    current = current,
                    onFilterChange = { pickerState = state.copy(filter = it) },
                    onSelect = { target -> onSelect(BindingOutput.fromRemapTarget(target)) },
                )
                is RemapPickerState.KeyboardList -> FilteredInputList(
                    options = RemapInputOptions.keyboardOptions,
                    filter = state.filter,
                    showFilter = true,
                    current = current,
                    onFilterChange = { pickerState = state.copy(filter = it) },
                    onSelect = { target -> onSelect(BindingOutput.fromRemapTarget(target)) },
                )
                is RemapPickerState.MouseList -> FilteredInputList(
                    options = RemapInputOptions.mouseOptions,
                    filter = state.filter,
                    showFilter = false,
                    current = current,
                    onFilterChange = {},
                    onSelect = { target -> onSelect(BindingOutput.fromRemapTarget(target)) },
                )
                is RemapPickerState.SwitchActionSetList -> SwitchActionSetList(
                    sets = availableActionSets,
                    current = current,
                    onSelect = { setId ->
                        onSelect(BindingOutput.ControllerAction("CHANGE_PRESET", listOf(setId.toString())))
                    },
                )
                is RemapPickerState.LayerVerbList -> LayerVerbList(
                    current = current,
                    onSelect = { verb -> pickerState = RemapPickerState.LayerSelectionList(verb) },
                )
                is RemapPickerState.LayerSelectionList -> LayerSelectionList(
                    verb = state.verb,
                    layers = availableLayers,
                    current = current,
                    onSelect = { layerId ->
                        onSelect(
                            BindingOutput.ControllerAction(
                                verb = state.verb,
                                args = listOf(layerId.toString()),
                            )
                        )
                    },
                )
            }
        }
    }
}

/** True when [current] matches a [BindingOutput] inhabitant of [category]. */
private fun matchesCategory(current: BindingOutput, category: PickerCategory): Boolean = when (category) {
    PickerCategory.Unbound          -> current is BindingOutput.Unbound
    PickerCategory.Gamepad          -> current is BindingOutput.XInputButton
    PickerCategory.Keyboard         -> current is BindingOutput.KeyPress
    PickerCategory.Mouse            -> current is BindingOutput.MouseButton || current is BindingOutput.MouseWheel
    PickerCategory.SwitchActionSet  -> current is BindingOutput.ControllerAction && current.verb == "CHANGE_PRESET"
    PickerCategory.Layer            -> current is BindingOutput.ControllerAction && current.verb in LAYER_VERBS
}

private enum class PickerCategory { Unbound, Gamepad, Keyboard, Mouse, SwitchActionSet, Layer }

/** Brick 5.6: layer-activation verbs the picker exposes. */
private val LAYER_VERBS = setOf("add_layer", "hold_layer", "remove_layer")

@Composable
private fun CategoryList(
    current: BindingOutput,
    showSwitchActionSet: Boolean,
    showLayer: Boolean,
    onUnbound: () -> Unit,
    onPickGamepad: () -> Unit,
    onPickKeyboard: () -> Unit,
    onPickMouse: () -> Unit,
    onPickSwitchActionSet: () -> Unit,
    onPickLayer: () -> Unit,
) {
    Column {
        CategoryRow("[Device default]", matchesCategory(current, PickerCategory.Unbound), showChevron = false, onClick = onUnbound)
        HorizontalDivider()
        CategoryRow("Gamepad", matchesCategory(current, PickerCategory.Gamepad), onClick = onPickGamepad)
        HorizontalDivider()
        CategoryRow("Keyboard", matchesCategory(current, PickerCategory.Keyboard), onClick = onPickKeyboard)
        HorizontalDivider()
        CategoryRow("Mouse", matchesCategory(current, PickerCategory.Mouse), onClick = onPickMouse)
        if (showSwitchActionSet) {
            HorizontalDivider()
            CategoryRow(
                label = "Switch Action Set",
                selected = matchesCategory(current, PickerCategory.SwitchActionSet),
                onClick = onPickSwitchActionSet,
            )
        }
        if (showLayer) {
            HorizontalDivider()
            CategoryRow(
                label = "Layer",
                selected = matchesCategory(current, PickerCategory.Layer),
                onClick = onPickLayer,
            )
        }
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
        modifier = Modifier.testTag("category_$label"),
    ) { Text(label) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FilteredInputList(
    options: ImmutableList<InputOption>,
    filter: String,
    showFilter: Boolean,
    current: BindingOutput,
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
                    val asOutput = BindingOutput.fromRemapTarget(option.target)
                    val isSelected = asOutput == current
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

/**
 * Brick 4.5: list of action sets in the current controller_profile. Selecting one emits a
 * `CHANGE_PRESET` controller_action. The currently-bound set (if any) shows a check tick.
 * No filter affordance — the typical user has 2-5 sets.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwitchActionSetList(
    sets: List<Pair<Long, String>>,
    current: BindingOutput,
    onSelect: (Long) -> Unit,
) {
    val currentSetId = (current as? BindingOutput.ControllerAction)
        ?.takeIf { it.verb == "CHANGE_PRESET" }
        ?.args?.firstOrNull()?.toLongOrNull()
    Column(modifier = Modifier.fillMaxSize()) {
        if (sets.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No other action sets available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(sets) { index, (setId, title) ->
                val isSelected = setId == currentSetId
                ListItem(
                    onClick = { onSelect(setId) },
                    leadingContent = if (isSelected) {
                        { Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    colors = ListItemDefaults.colors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                         else Color.Transparent,
                    ),
                    supportingContent = {
                        Text(
                            "Switches the active set at runtime",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                ) { Text(title) }
                if (index < sets.lastIndex) HorizontalDivider()
            }
        }
    }
}

/**
 * Brick 5.6: pick a layer-activation verb. Three options:
 *  - **Add Layer** (sticky) — `add_layer`. Activates the layer; remains active until
 *    a `Remove Layer` binding deactivates it or the action set changes.
 *  - **Hold Layer** (while held) — `hold_layer`. Single-binding while-held variant —
 *    activates on press, releases on the matching up event.
 *  - **Remove Layer** — `remove_layer`. Deactivates a layer (paired with sticky Add).
 *
 * The "explicit pair" Steam form (FULL_PRESS add_layer + RELEASE_PRESS remove_layer)
 * is intentionally not exposed here — `Hold Layer` covers the same use case as a
 * single verb with less wiring for the user. The pair form can be authored manually
 * by adding a Release activator if needed; full UI support can land later.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LayerVerbList(
    current: BindingOutput,
    onSelect: (verb: String) -> Unit,
) {
    val currentVerb = (current as? BindingOutput.ControllerAction)
        ?.takeIf { it.verb in LAYER_VERBS }
        ?.verb
    val verbs = listOf(
        VerbOption("add_layer", "Add Layer (sticky)", "Activates the layer until another binding deactivates it."),
        VerbOption("hold_layer", "Hold Layer (while held)", "Activates while this input is held; releases on up."),
        VerbOption("remove_layer", "Remove Layer", "Deactivates a layer that's currently active."),
    )
    Column(modifier = Modifier.fillMaxSize()) {
        verbs.forEachIndexed { idx, verb ->
            val isSelected = verb.verbId == currentVerb
            ListItem(
                onClick = { onSelect(verb.verbId) },
                leadingContent = if (isSelected) {
                    { Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) }
                } else null,
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                colors = ListItemDefaults.colors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                     else Color.Transparent,
                ),
                supportingContent = {
                    Text(verb.subtext, style = MaterialTheme.typography.bodySmall)
                },
                modifier = Modifier.testTag("layer_verb_${verb.verbId}"),
            ) { Text(verb.label) }
            if (idx < verbs.lastIndex) HorizontalDivider()
        }
    }
}

private data class VerbOption(val verbId: String, val label: String, val subtext: String)

/**
 * Brick 5.6: list of layers in the editing context's action set. Selecting one emits
 * a `ControllerAction(verb, [layerId])` binding. The currently-bound layer (if any)
 * shows a check tick.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LayerSelectionList(
    verb: String,
    layers: List<Pair<Long, String>>,
    current: BindingOutput,
    onSelect: (Long) -> Unit,
) {
    val currentLayerId = (current as? BindingOutput.ControllerAction)
        ?.takeIf { it.verb == verb }
        ?.args?.firstOrNull()?.toLongOrNull()
    Column(modifier = Modifier.fillMaxSize()) {
        if (layers.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No layers in this action set yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(layers) { index, (layerId, title) ->
                val isSelected = layerId == currentLayerId
                ListItem(
                    onClick = { onSelect(layerId) },
                    leadingContent = if (isSelected) {
                        { Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    colors = ListItemDefaults.colors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                         else Color.Transparent,
                    ),
                ) { Text(title) }
                if (index < layers.lastIndex) HorizontalDivider()
            }
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
