package com.mapo.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.data.model.steam.displayLabel
import com.mapo.data.model.steam.displayName
import com.mapo.ui.component.layout.SectionedListDetailPane
import com.mapo.ui.screen.remap.RemapPaneItem
import com.mapo.ui.screen.remap.RemapSections

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemapControlsScreen(
    config: ControllerConfig?,
    onOpenInputEditor: (inputSource: com.mapo.data.model.steam.InputSource, groupInputKey: String, label: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewingActionSetId: Long? = null,
    onSelectActionSet: (Long) -> Unit = {},
    onAddActionSet: (title: String, inheritFromSetId: Long?) -> Unit = { _, _ -> },
    onRenameActionSet: (actionSetId: Long, newTitle: String) -> Unit = { _, _ -> },
    onDuplicateActionSet: (sourceSetId: Long, newTitle: String) -> Unit = { _, _ -> },
    onDeleteActionSet: (actionSetId: Long) -> Unit = {},
    viewingLayerId: Long? = null,
    onSelectLayer: (Long?) -> Unit = {},
    onAddLayer: (actionSetId: Long, title: String) -> Unit = { _, _ -> },
    onRenameLayer: (layerId: Long, newTitle: String) -> Unit = { _, _ -> },
    onDuplicateLayer: (sourceLayerId: Long, newTitle: String) -> Unit = { _, _ -> },
    onDeleteLayer: (layerId: Long) -> Unit = {},
) {
    var selectedSectionId by rememberSaveable { mutableStateOf(RemapSections.SECTION_BUTTONS) }

    // Brick 4.4: which management dialog is currently open. Plain `remember` —
    // dialogs are short-lived; rotation-survival isn't worth a custom Saver.
    var dialog by remember { mutableStateOf<ActionSetDialogState>(ActionSetDialogState.None) }
    var layerDialog by remember { mutableStateOf<LayerDialogState>(LayerDialogState.None) }

    // Resolve which set is currently being viewed in the editor. The viewing pointer is
    // user-driven (tab tap); when null, fall back to the controller_profile default so the
    // screen always renders *something* sensible. Independent of the runtime active set —
    // that swaps via CHANGE_PRESET in the evaluator (Brick 4.2).
    val viewingSet = config?.let { cfg ->
        viewingActionSetId
            ?.let { id -> cfg.actionSets.firstOrNull { it.actionSet.id == id } }
            ?: cfg.activeActionSet
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Remap Controls") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                ActionSetAndLayersBar(
                    config = config,
                    viewingSetId = viewingSet?.actionSet?.id,
                    onSelectActionSet = onSelectActionSet,
                    onRequestAddSet = { dialog = ActionSetDialogState.Add },
                    onRequestRenameSet = { dialog = ActionSetDialogState.Rename },
                    onRequestDuplicateSet = { dialog = ActionSetDialogState.Duplicate },
                    onRequestDeleteSet = { dialog = ActionSetDialogState.Delete },
                    layers = viewingSet?.layers?.map { it.layer } ?: emptyList(),
                    viewingLayerId = viewingLayerId,
                    onSelectLayer = onSelectLayer,
                    onRequestAddLayer = { layerDialog = LayerDialogState.Add },
                    onRequestRenameLayer = { layerDialog = LayerDialogState.Rename },
                    onRequestDuplicateLayer = { layerDialog = LayerDialogState.Duplicate },
                    onRequestDeleteLayer = { layerDialog = LayerDialogState.Delete },
                )
            }
        },
    ) { innerPadding ->
        SectionedListDetailPane(
            sections = RemapSections.rail,
            selectedSectionId = selectedSectionId,
            onSectionSelected = { selectedSectionId = it },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { sectionId, firstRowFocusRequester ->
            RemapDetailPane(
                sectionId = sectionId,
                viewingSet = viewingSet,
                config = config,
                firstRowFocusRequester = firstRowFocusRequester,
                onOpenInputEditor = onOpenInputEditor,
            )
        }
    }

    // Brick 4.4: management dialogs. Rendered outside the Scaffold so they overlay
    // everything (M3 AlertDialog uses a Dialog window, but we still hoist the state
    // here so it survives Scaffold recompositions).
    val viewingActionSet = viewingSet?.actionSet
    when (dialog) {
        ActionSetDialogState.None -> Unit
        ActionSetDialogState.Add -> AddSetDialog(
            existingSets = config?.actionSets.orEmpty(),
            onConfirm = { title, inheritFromSetId ->
                onAddActionSet(title, inheritFromSetId)
                dialog = ActionSetDialogState.None
            },
            onDismiss = { dialog = ActionSetDialogState.None },
        )
        ActionSetDialogState.Rename -> viewingActionSet?.let { target ->
            RenameSetDialog(
                target = target,
                onConfirm = { newTitle ->
                    onRenameActionSet(target.id, newTitle)
                    dialog = ActionSetDialogState.None
                },
                onDismiss = { dialog = ActionSetDialogState.None },
            )
        } ?: run { dialog = ActionSetDialogState.None }
        ActionSetDialogState.Duplicate -> viewingActionSet?.let { source ->
            DuplicateSetDialog(
                source = source,
                onConfirm = { newTitle ->
                    onDuplicateActionSet(source.id, newTitle)
                    dialog = ActionSetDialogState.None
                },
                onDismiss = { dialog = ActionSetDialogState.None },
            )
        } ?: run { dialog = ActionSetDialogState.None }
        ActionSetDialogState.Delete -> viewingActionSet?.let { target ->
            DeleteSetConfirmDialog(
                target = target,
                onConfirm = {
                    onDeleteActionSet(target.id)
                    dialog = ActionSetDialogState.None
                },
                onDismiss = { dialog = ActionSetDialogState.None },
            )
        } ?: run { dialog = ActionSetDialogState.None }
    }

    // Brick 5.4: layer management dialogs. Same hoisting pattern as action sets.
    // Layer operations are scoped to the currently-focused layer (viewingLayerId)
    // within the currently-viewing set — the overflow button is disabled when no
    // layer is focused (handled in LayersPillRow), so a non-null viewing layer is
    // a precondition for Rename/Duplicate/Delete here.
    val viewingLayer = viewingSet?.layers?.firstOrNull { it.layer.id == viewingLayerId }?.layer
    when (layerDialog) {
        LayerDialogState.None -> Unit
        LayerDialogState.Add -> viewingActionSet?.let { parentSet ->
            AddLayerDialog(
                onConfirm = { title ->
                    onAddLayer(parentSet.id, title)
                    layerDialog = LayerDialogState.None
                },
                onDismiss = { layerDialog = LayerDialogState.None },
            )
        } ?: run { layerDialog = LayerDialogState.None }
        LayerDialogState.Rename -> viewingLayer?.let { target ->
            RenameLayerDialog(
                target = target,
                onConfirm = { newTitle ->
                    onRenameLayer(target.id, newTitle)
                    layerDialog = LayerDialogState.None
                },
                onDismiss = { layerDialog = LayerDialogState.None },
            )
        } ?: run { layerDialog = LayerDialogState.None }
        LayerDialogState.Duplicate -> viewingLayer?.let { source ->
            DuplicateLayerDialog(
                source = source,
                onConfirm = { newTitle ->
                    onDuplicateLayer(source.id, newTitle)
                    layerDialog = LayerDialogState.None
                },
                onDismiss = { layerDialog = LayerDialogState.None },
            )
        } ?: run { layerDialog = LayerDialogState.None }
        LayerDialogState.Delete -> viewingLayer?.let { target ->
            DeleteLayerConfirmDialog(
                target = target,
                onConfirm = {
                    onDeleteLayer(target.id)
                    layerDialog = LayerDialogState.None
                },
                onDismiss = { layerDialog = LayerDialogState.None },
            )
        } ?: run { layerDialog = LayerDialogState.None }
    }
}

/** Which management dialog is currently open. Hoisted in [RemapControlsScreen]. */
private sealed class ActionSetDialogState {
    object None : ActionSetDialogState()
    object Add : ActionSetDialogState()
    object Rename : ActionSetDialogState()
    object Duplicate : ActionSetDialogState()
    object Delete : ActionSetDialogState()
}

/** Layer-management dialog state. Parallel sealed class to [ActionSetDialogState]. */
private sealed class LayerDialogState {
    object None : LayerDialogState()
    object Add : LayerDialogState()
    object Rename : LayerDialogState()
    object Duplicate : LayerDialogState()
    object Delete : LayerDialogState()
}

/**
 * Top bar: action-set tabs + layer pill row. Brick 4.3 made the tabs live; Brick 5.4
 * makes the layer row live — tapping a pill flips the editor's focused layer (5.5
 * will fill in overlay-edit visuals). Re-tapping the focused pill drops back to base
 * (`viewingLayerId == null`). The runtime-active set/layer stack is *not* what these
 * controls touch — that's evaluator-side, swapped by `CHANGE_PRESET` / `add_layer`
 * bindings (Bricks 4.2, 5.1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionSetAndLayersBar(
    config: ControllerConfig?,
    viewingSetId: Long?,
    onSelectActionSet: (Long) -> Unit,
    onRequestAddSet: () -> Unit,
    onRequestRenameSet: () -> Unit,
    onRequestDuplicateSet: () -> Unit,
    onRequestDeleteSet: () -> Unit,
    layers: List<com.mapo.data.model.steam.ActionLayer>,
    viewingLayerId: Long?,
    onSelectLayer: (Long?) -> Unit,
    onRequestAddLayer: () -> Unit,
    onRequestRenameLayer: () -> Unit,
    onRequestDuplicateLayer: () -> Unit,
    onRequestDeleteLayer: () -> Unit,
) {
    val sets = config?.actionSets.orEmpty()
    val viewingIndex = sets.indexOfFirst { it.actionSet.id == viewingSetId }.coerceAtLeast(0)
    val viewingSet = sets.firstOrNull { it.actionSet.id == viewingSetId }
    val canDelete = sets.size > 1

    // M3 role: surfaceContainer — same plane as the rail below.
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            if (sets.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PrimaryTabRow(
                        selectedTabIndex = viewingIndex,
                        modifier = Modifier.weight(1f),
                    ) {
                        sets.forEach { setGraph ->
                            Tab(
                                selected = setGraph.actionSet.id == viewingSetId,
                                onClick = { onSelectActionSet(setGraph.actionSet.id) },
                                text = {
                                    Text(
                                        text = setGraph.actionSet.title,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                },
                            )
                        }
                    }
                    IconButton(
                        onClick = onRequestAddSet,
                        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add action set",
                            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    ActionSetOverflowMenu(
                        viewingSetTitle = viewingSet?.actionSet?.title,
                        canDelete = canDelete,
                        onRename = onRequestRenameSet,
                        onDuplicate = onRequestDuplicateSet,
                        onDelete = onRequestDeleteSet,
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            LayersPillRow(
                layers = layers,
                viewingLayerId = viewingLayerId,
                hasViewingSet = viewingSetId != null,
                onSelectLayer = onSelectLayer,
                onRequestAddLayer = onRequestAddLayer,
                onRequestRenameLayer = onRequestRenameLayer,
                onRequestDuplicateLayer = onRequestDuplicateLayer,
                onRequestDeleteLayer = onRequestDeleteLayer,
            )
        }
    }
}

/**
 * Horizontal pill row beside the action-set tabs (Brick 5.4).
 *
 * Empty-state ([layers] empty): shows "Layers: (none)" + a `[+]` button — keeps the
 * affordance discoverable even before any layers exist.
 *
 * Populated state: FilterChip per layer + `[+]` + an overflow `[⋮]` that operates on
 * the currently-focused layer. Mirrors the action-set row's deviation from the parity
 * plan's "long-press a pill" design — row-level overflow is more discoverable and
 * avoids gesture conflicts (the action-set tabs hit a `Tab` selectable wrapper issue
 * that doesn't apply to FilterChips, but consistency with the set row wins).
 *
 * Tapping the focused pill re-fires `onSelectLayer(null)` — explicit toggle back to
 * base-set editing. There's no separate "Base" pill (Steam doesn't have one; the set
 * is the base).
 */
@Composable
private fun LayersPillRow(
    layers: List<com.mapo.data.model.steam.ActionLayer>,
    viewingLayerId: Long?,
    hasViewingSet: Boolean,
    onSelectLayer: (Long?) -> Unit,
    onRequestAddLayer: () -> Unit,
    onRequestRenameLayer: () -> Unit,
    onRequestDuplicateLayer: () -> Unit,
    onRequestDeleteLayer: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Layers:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        if (layers.isEmpty()) {
            Text(
                text = "(none)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                layers.forEach { layer ->
                    val selected = layer.id == viewingLayerId
                    FilterChip(
                        selected = selected,
                        onClick = {
                            // Toggle: tapping the focused pill drops focus back to base.
                            onSelectLayer(if (selected) null else layer.id)
                        },
                        label = {
                            Text(
                                text = layer.title,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                    )
                }
            }
        }
        IconButton(
            onClick = onRequestAddLayer,
            enabled = hasViewingSet,
            modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Add layer",
                modifier = Modifier.size(IconButtonDefaults.smallIconSize),
            )
        }
        if (layers.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            LayerOverflowMenu(
                focusedLayerTitle = layers.firstOrNull { it.id == viewingLayerId }?.title,
                onRename = onRequestRenameLayer,
                onDuplicate = onRequestDuplicateLayer,
                onDelete = onRequestDeleteLayer,
            )
        }
        Spacer(Modifier.width(8.dp))
    }
}

/**
 * Overflow menu for the layer row (Brick 5.4). Operates on the currently-focused
 * layer; disabled when none is focused (the items themselves are gated separately
 * for redundancy). Parallel to `ActionSetOverflowMenu`.
 */
@Composable
private fun LayerOverflowMenu(
    focusedLayerTitle: String?,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val titleSuffix = focusedLayerTitle?.let { " \"$it\"" }.orEmpty()
    val enabled = focusedLayerTitle != null
    IconButton(
        onClick = { expanded = true },
        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
        enabled = enabled,
    ) {
        Icon(
            Icons.Filled.MoreVert,
            contentDescription = "Layer actions",
            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Rename$titleSuffix") },
            enabled = enabled,
            onClick = { expanded = false; onRename() },
        )
        DropdownMenuItem(
            text = { Text("Duplicate$titleSuffix") },
            enabled = enabled,
            onClick = { expanded = false; onDuplicate() },
        )
        DropdownMenuItem(
            text = { Text("Delete$titleSuffix") },
            enabled = enabled,
            onClick = { expanded = false; onDelete() },
        )
    }
}

/**
 * Trailing overflow menu on the action-set tab row (Brick 4.4). Operates on the
 * **currently-viewing** set. Item titles include the set name to make the target
 * unambiguous ("Delete 'Menu'"); "Delete" is disabled when only one set remains
 * (the repo refuses to delete the last set anyway, but disabling the affordance is
 * the M3-conventional way to communicate that).
 *
 * Long-press on individual tabs was the design proposed in the parity plan but
 * conflicts with `Tab`'s built-in selectable wrapper and is poorly discoverable on
 * M3; an explicit overflow is more discoverable and avoids gesture conflicts.
 *
 * Brick 4.4.1: no "Set as default" — Steam has no exposed default concept; the
 * starting set is just the first set by orderIndex (creation order).
 */
@Composable
private fun ActionSetOverflowMenu(
    viewingSetTitle: String?,
    canDelete: Boolean,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val titleSuffix = viewingSetTitle?.let { " \"$it\"" }.orEmpty()
    IconButton(
        onClick = { expanded = true },
        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
        enabled = viewingSetTitle != null,
    ) {
        Icon(
            Icons.Filled.MoreVert,
            contentDescription = "Action set actions",
            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Rename$titleSuffix") },
            onClick = { expanded = false; onRename() },
        )
        DropdownMenuItem(
            text = { Text("Duplicate$titleSuffix") },
            onClick = { expanded = false; onDuplicate() },
        )
        DropdownMenuItem(
            text = { Text("Delete$titleSuffix") },
            enabled = canDelete,
            onClick = { expanded = false; onDelete() },
        )
    }
}

@Composable
private fun RemapDetailPane(
    sectionId: String,
    viewingSet: com.mapo.data.model.steam.ActionSetGraph?,
    config: ControllerConfig?,
    firstRowFocusRequester: FocusRequester,
    onOpenInputEditor: (inputSource: com.mapo.data.model.steam.InputSource, groupInputKey: String, label: String) -> Unit,
) {
    val items = RemapSections.contentBySection[sectionId]

    if (items == null) {
        // Gyro and any future un-implemented sections route here.
        DetailPlaceholder(RemapSections.GYRO_PLACEHOLDER)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        // First focusable row gets the cross-pane focus requester so D-pad Right from the
        // rail lands on the first interactable element of this section.
        val firstBindingRowKey = items.firstOrNull { it is RemapPaneItem.BindingRow }?.key
        items(items = items, key = { it.key }) { item ->
            val focusModifier = if (item.key == firstBindingRowKey) {
                Modifier.focusRequester(firstRowFocusRequester)
            } else Modifier
            when (item) {
                is RemapPaneItem.Subheader -> SubheaderRow(item)
                is RemapPaneItem.BindingRow -> BindingRowItem(
                    item = item,
                    viewingSet = viewingSet,
                    config = config,
                    modifier = focusModifier,
                    onOpenInputEditor = onOpenInputEditor,
                )
                is RemapPaneItem.DisabledRow -> DisabledRowItem(item)
            }
        }
    }
}

@Composable
private fun SubheaderRow(item: RemapPaneItem.Subheader) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        // Disabled mode-dropdown affordance. Lands the visual real estate now; Phase 6
        // wires it to a real picker.
        DisabledModeDropdown(item.modeDropdownLabel)
    }
}

@Composable
private fun DisabledModeDropdown(label: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.alpha(0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Mode: $label",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BindingRowItem(
    item: RemapPaneItem.BindingRow,
    viewingSet: com.mapo.data.model.steam.ActionSetGraph?,
    config: ControllerConfig?,
    modifier: Modifier = Modifier,
    onOpenInputEditor: (inputSource: com.mapo.data.model.steam.InputSource, groupInputKey: String, label: String) -> Unit,
) {
    // The row preview shows the FULL_PRESS binding; the per-input editor (Brick 3.4) is
    // where the full activator list lives. When multiple activators are configured we
    // append "+N more" so the user can tell at a glance the row holds more than they see.
    val groupInput = viewingSet?.presetFor(item.inputSource)?.group?.inputByKey(item.groupInputKey)
    val activators = groupInput?.activators.orEmpty()
    val primary = activators.firstOrNull { it.activator.type == item.activatorType }
        ?: activators.firstOrNull()
    val output = primary?.primaryOutput ?: BindingOutput.Unbound
    val extraCount = (activators.size - 1).coerceAtLeast(0)
    val ready = groupInput != null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = ready) {
                onOpenInputEditor(item.inputSource, item.groupInputKey, item.label)
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Helper subtext per memory: short, one-line, tutorializing.
            Text(
                text = item.inputSource.displayName() + " · " + item.groupInputKey,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = output.displayLabel(config),
                style = MaterialTheme.typography.bodyMedium,
                color = if (output == BindingOutput.Unbound)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
            if (extraCount > 0) {
                Text(
                    text = "+$extraCount more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun DisabledRowItem(item: RemapPaneItem.DisabledRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0.38f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Coming soon",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun DetailPlaceholder(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
