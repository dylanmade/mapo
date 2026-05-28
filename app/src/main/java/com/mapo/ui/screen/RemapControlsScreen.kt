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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
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
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.data.model.steam.InputSource
import com.mapo.data.model.steam.displayLabel
import com.mapo.data.model.steam.displayName
import com.mapo.data.model.steam.displayNameFor
import androidx.compose.ui.res.stringResource
import com.mapo.R
import com.mapo.service.input.modes.SourceModeCatalog
import com.mapo.service.input.modes.requiresMotionCapture
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
    onClearLayerOverride: (layerId: Long, inputSource: com.mapo.data.model.steam.InputSource, groupInputKey: String) -> Unit = { _, _, _ -> },
    onSetBindingGroupMode: (bindingGroupId: Long, mode: BindingMode) -> Unit = { _, _ -> },
    shizukuRequiredAcknowledged: Boolean = true,
    shizukuReady: Boolean = true,
    shizukuState: com.mapo.service.shizuku.ShizukuState = com.mapo.service.shizuku.ShizukuState.Granted,
    onAcknowledgeShizukuRequired: () -> Unit = {},
    onOpenShizukuSetup: () -> Unit = {},
) {
    var selectedSectionId by rememberSaveable { mutableStateOf(RemapSections.SECTION_BUTTONS) }

    // Brick 4.4: which management dialog is currently open. Plain `remember` —
    // dialogs are short-lived; rotation-survival isn't worth a custom Saver.
    var dialog by remember { mutableStateOf<ActionSetDialogState>(ActionSetDialogState.None) }
    var layerDialog by remember { mutableStateOf<LayerDialogState>(LayerDialogState.None) }

    // Brick G: stash an analog-mode pick if Shizuku isn't ready AND the
    // explainer hasn't been acknowledged. `Pair(bindingGroupId, mode)`. The
    // dialog renders below when this is non-null. Once Shizuku is Granted OR
    // the user has acked, picks proceed silently.
    var pendingAnalogPick by remember { mutableStateOf<Pair<Long, BindingMode>?>(null) }

    val gatedSetBindingGroupMode: (Long, BindingMode) -> Unit = { bindingGroupId, mode ->
        if (mode.requiresMotionCapture() && !shizukuReady && !shizukuRequiredAcknowledged) {
            pendingAnalogPick = bindingGroupId to mode
        } else {
            onSetBindingGroupMode(bindingGroupId, mode)
        }
    }

    // Resolve which set is currently being viewed in the editor. The viewing pointer is
    // user-driven (tab tap); when null, fall back to the controller_profile default so the
    // screen always renders *something* sensible. Independent of the runtime active set —
    // that swaps via CHANGE_PRESET in the evaluator (Brick 4.2).
    val viewingSet = config?.let { cfg ->
        viewingActionSetId
            ?.let { id -> cfg.actionSets.firstOrNull { it.actionSet.id == id } }
            ?: cfg.activeActionSet
    }
    // Brick 5.5.c: resolve the focused layer (when present). The detail pane reads from
    // this in overlay mode — ghost rows fall back to the parent set's binding.
    val viewingLayer = viewingSet?.layers?.firstOrNull { it.layer.id == viewingLayerId }

    // Brick 5.5.c: "Show all / Only overrides" toggle state. Visible only in overlay
    // mode. `rememberSaveable` survives recomposition but intentionally not nav — the
    // user reopening Remap Controls starts from "Show all" each time.
    var onlyOverrides by rememberSaveable { mutableStateOf(false) }
    // Drop "only overrides" automatically when the user leaves overlay mode — toggling
    // it on, then deselecting the layer, would otherwise leave a stale filter applied
    // when the next overlay session starts.
    if (viewingLayer == null && onlyOverrides) onlyOverrides = false

    // Brick G follow-up: walk the current config for any analog-mode binding.
    // If one exists AND Shizuku isn't ready, the banner below surfaces the gap
    // inline — covers the case where the user previously applied analog modes
    // (so the first-time dialog won't re-fire) and then Shizuku flipped away
    // from Granted, leaving those bindings silently inert.
    val hasAnalogModeInConfig = config?.actionSets?.any { set ->
        set.preset.any { it.group.group.mode.requiresMotionCapture() } ||
            set.layers.any { layer ->
                layer.bindingGroups.any { it.group.mode.requiresMotionCapture() }
            }
    } == true

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
                if (hasAnalogModeInConfig && !shizukuReady) {
                    ShizukuUnavailableBanner(onOpenSetup = onOpenShizukuSetup)
                }
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
                viewingLayer = viewingLayer,
                onlyOverrides = onlyOverrides,
                onSetOnlyOverrides = { onlyOverrides = it },
                config = config,
                firstRowFocusRequester = firstRowFocusRequester,
                onOpenInputEditor = onOpenInputEditor,
                onClearOverride = { inputSource, groupInputKey ->
                    val layerId = viewingLayer?.layer?.id ?: return@RemapDetailPane
                    onClearLayerOverride(layerId, inputSource, groupInputKey)
                },
                onSetBindingGroupMode = gatedSetBindingGroupMode,
            )
        }
    }

    val pendingPick = pendingAnalogPick
    if (pendingPick != null) {
        com.mapo.ui.screen.dialog.ShizukuRequiredDialog(
            shizukuState = shizukuState,
            onSetUp = {
                // Apply the mode + ack + navigate to Setup. The user is on a
                // path to make Shizuku work; the binding will activate when
                // they finish. If they bail mid-setup, the binding stays —
                // the ShizukuKeyInjector gate keeps it inert until Granted.
                onSetBindingGroupMode(pendingPick.first, pendingPick.second)
                onAcknowledgeShizukuRequired()
                pendingAnalogPick = null
                onOpenShizukuSetup()
            },
            onDismiss = { pendingAnalogPick = null },
        )
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
    val viewingLayerEntity = viewingLayer?.layer
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
        LayerDialogState.Rename -> viewingLayerEntity?.let { target ->
            RenameLayerDialog(
                target = target,
                onConfirm = { newTitle ->
                    onRenameLayer(target.id, newTitle)
                    layerDialog = LayerDialogState.None
                },
                onDismiss = { layerDialog = LayerDialogState.None },
            )
        } ?: run { layerDialog = LayerDialogState.None }
        LayerDialogState.Duplicate -> viewingLayerEntity?.let { source ->
            DuplicateLayerDialog(
                source = source,
                onConfirm = { newTitle ->
                    onDuplicateLayer(source.id, newTitle)
                    layerDialog = LayerDialogState.None
                },
                onDismiss = { layerDialog = LayerDialogState.None },
            )
        } ?: run { layerDialog = LayerDialogState.None }
        LayerDialogState.Delete -> viewingLayerEntity?.let { target ->
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
    viewingLayer: com.mapo.data.model.steam.ActionLayerGraph?,
    onlyOverrides: Boolean,
    onSetOnlyOverrides: (Boolean) -> Unit,
    config: ControllerConfig?,
    firstRowFocusRequester: FocusRequester,
    onOpenInputEditor: (inputSource: com.mapo.data.model.steam.InputSource, groupInputKey: String, label: String) -> Unit,
    onClearOverride: (inputSource: com.mapo.data.model.steam.InputSource, groupInputKey: String) -> Unit,
    onSetBindingGroupMode: (bindingGroupId: Long, mode: BindingMode) -> Unit,
) {
    val rawItems = RemapSections.contentBySection[sectionId]

    if (rawItems == null) {
        // Gyro and any future un-implemented sections route here.
        DetailPlaceholder(RemapSections.GYRO_PLACEHOLDER)
        return
    }

    // Brick 5.5.c: when "Only overrides" is on, drop binding rows without an override
    // — and any subheader whose children all dropped. Disabled rows always hide in
    // overrides-only (they can't be overridden anyway).
    val items = remember(rawItems, onlyOverrides, viewingLayer) {
        if (!onlyOverrides || viewingLayer == null) rawItems
        else filterToOverrides(rawItems, viewingLayer)
    }
    val isOverlayMode = viewingLayer != null

    Column(modifier = Modifier.fillMaxSize()) {
        if (isOverlayMode) {
            OverridesFilterToggle(
                onlyOverrides = onlyOverrides,
                onChange = onSetOnlyOverrides,
            )
        }
        if (isOverlayMode && onlyOverrides && items.none { it is RemapPaneItem.BindingRow }) {
            // Common edge case: user selected a fresh layer + filtered to overrides.
            // Without an empty-state hint the pane would just be blank.
            DetailPlaceholder("No overrides in this section yet.")
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            // First focusable row gets the cross-pane focus requester so D-pad Right
            // from the rail lands on the first interactable element of this section.
            val firstBindingRowKey = items.firstOrNull { it is RemapPaneItem.BindingRow }?.key
            items(items = items, key = { it.key }) { item ->
                val focusModifier = if (item.key == firstBindingRowKey) {
                    Modifier.focusRequester(firstRowFocusRequester)
                } else Modifier
                when (item) {
                    is RemapPaneItem.Subheader -> SubheaderRow(
                        item = item,
                        viewingSet = viewingSet,
                        viewingLayer = viewingLayer,
                        onSetBindingGroupMode = onSetBindingGroupMode,
                    )
                    is RemapPaneItem.BindingRow -> BindingRowItem(
                        item = item,
                        viewingSet = viewingSet,
                        viewingLayer = viewingLayer,
                        config = config,
                        modifier = focusModifier,
                        onOpenInputEditor = onOpenInputEditor,
                        onClearOverride = onClearOverride,
                    )
                    is RemapPaneItem.DisabledRow -> DisabledRowItem(item)
                }
            }
        }
    }
}

/**
 * Brick 5.5.c: filter a section's items down to rows that have an override on
 * [layer], dropping any subheader whose entire group of binding rows is filtered out.
 * Disabled rows are hidden — they can't be overridden anyway, so leaving them in
 * "Only overrides" would be misleading.
 *
 * Implementation: single linear pass over [items]. A subheader is queued until at
 * least one of its child binding rows survives, then emitted once before the
 * survivor. If the next subheader arrives before any survivors emit, the queued one
 * is discarded.
 */
internal fun filterToOverrides(
    items: List<RemapPaneItem>,
    layer: com.mapo.data.model.steam.ActionLayerGraph,
): List<RemapPaneItem> {
    val result = mutableListOf<RemapPaneItem>()
    var pendingSubheader: RemapPaneItem.Subheader? = null
    for (item in items) {
        when (item) {
            is RemapPaneItem.Subheader -> pendingSubheader = item
            is RemapPaneItem.BindingRow -> {
                val hasOverride = layer.presetFor(item.inputSource)
                    ?.group?.inputByKey(item.groupInputKey) != null
                if (hasOverride) {
                    pendingSubheader?.let { result += it }
                    pendingSubheader = null
                    result += item
                }
            }
            is RemapPaneItem.DisabledRow -> Unit  // hidden in overrides-only
        }
    }
    return result
}

@Composable
private fun OverridesFilterToggle(
    onlyOverrides: Boolean,
    onChange: (Boolean) -> Unit,
) {
    // M3 segmented buttons would be the conventional choice but they're heavy in the
    // tab-row real estate; a pair of FilterChips reads cleaner here and matches the
    // pill aesthetic of the layer row above.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.FilterChip(
            selected = !onlyOverrides,
            onClick = { onChange(false) },
            label = { Text("Show all", style = MaterialTheme.typography.labelLarge) },
        )
        androidx.compose.material3.FilterChip(
            selected = onlyOverrides,
            onClick = { onChange(true) },
            label = { Text("Only overrides", style = MaterialTheme.typography.labelLarge) },
        )
    }
    HorizontalDivider()
}

@Composable
private fun SubheaderRow(
    item: RemapPaneItem.Subheader,
    viewingSet: com.mapo.data.model.steam.ActionSetGraph?,
    viewingLayer: com.mapo.data.model.steam.ActionLayerGraph?,
    onSetBindingGroupMode: (bindingGroupId: Long, mode: BindingMode) -> Unit,
) {
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
        val source = item.inputSource
        if (source != null && viewingSet != null) {
            // Phase 6 Brick 1: real mode dropdown. Resolves the effective mode from
            // (a) the viewing layer's override, falling back to (b) the base set.
            // Layer-mode-override editing isn't exposed yet — the picker is read-only
            // when a layer is being viewed and the source has no layer override; the
            // user changes mode by switching back to the base set's view.
            val setBindingGroup = viewingSet.presetFor(source)?.group?.group
            val layerBindingGroup = viewingLayer?.presetFor(source)?.group?.group
            val effectiveGroup = layerBindingGroup ?: setBindingGroup
            val validModes = SourceModeCatalog.modesValidFor(source)
            if (effectiveGroup != null && validModes.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                val pickerEnabled = viewingLayer == null && validModes.size > 1
                ModePicker(
                    source = source,
                    currentMode = effectiveGroup.mode,
                    validModes = validModes,
                    enabled = pickerEnabled,
                    onPick = { mode -> onSetBindingGroupMode(effectiveGroup.id, mode) },
                )
            }
        }
    }
}

@Composable
private fun ModePicker(
    source: InputSource,
    currentMode: BindingMode,
    validModes: List<BindingMode>,
    enabled: Boolean,
    onPick: (BindingMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .alpha(if (enabled) 1.0f else 0.5f)
                .clickable(enabled = enabled) { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // Phase 7 Brick A: source-aware label — same BindingMode reads
                    // differently per source (e.g. SINGLE_BUTTON on a trigger source
                    // is "Trigger (Digital)", on a bumper it's "Single Button").
                    text = "Mode: ${currentMode.displayNameFor(source)}",
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
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            validModes.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.displayNameFor(source)) },
                    onClick = {
                        expanded = false
                        if (mode != currentMode) onPick(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun BindingRowItem(
    item: RemapPaneItem.BindingRow,
    viewingSet: com.mapo.data.model.steam.ActionSetGraph?,
    viewingLayer: com.mapo.data.model.steam.ActionLayerGraph?,
    config: ControllerConfig?,
    modifier: Modifier = Modifier,
    onOpenInputEditor: (inputSource: com.mapo.data.model.steam.InputSource, groupInputKey: String, label: String) -> Unit,
    onClearOverride: (inputSource: com.mapo.data.model.steam.InputSource, groupInputKey: String) -> Unit,
) {
    // Brick 5.5.c: overlay mode resolution.
    //  - Layer's groupInput (if any) wins → "override" visual (primary color, trailing
    //    [⋮] menu offering Clear Override).
    //  - Else fall through to the base set's groupInput → in overlay mode this renders
    //    as ghost text (alpha 0.5); in base mode it renders normally.
    val layerGroupInput =
        viewingLayer?.presetFor(item.inputSource)?.group?.inputByKey(item.groupInputKey)
    val baseGroupInput =
        viewingSet?.presetFor(item.inputSource)?.group?.inputByKey(item.groupInputKey)
    val effectiveGroupInput = layerGroupInput ?: baseGroupInput
    val hasOverride = layerGroupInput != null
    val isGhost = viewingLayer != null && !hasOverride

    val activators = effectiveGroupInput?.activators.orEmpty()
    val primary = activators.firstOrNull { it.activator.type == item.activatorType }
        ?: activators.firstOrNull()
    val output = primary?.primaryOutput ?: BindingOutput.Unbound
    val extraCount = (activators.size - 1).coerceAtLeast(0)
    // In overlay mode the row is always tappable (a tap on a ghost row materializes the
    // override). In base mode we still gate on `baseGroupInput != null` so we don't try
    // to edit a non-existent row.
    val ready = viewingLayer != null || baseGroupInput != null
    val contentAlpha = if (isGhost) 0.5f else 1f

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
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(contentAlpha),
        ) {
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
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.alpha(contentAlpha),
        ) {
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
        if (hasOverride) {
            BindingRowOverflowMenu(
                onClearOverride = {
                    onClearOverride(item.inputSource, item.groupInputKey)
                },
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

/**
 * Brick 5.5.c: trailing [⋮] menu for binding rows in overlay mode that have an
 * override. Currently exposes a single "Clear override" action; future per-row
 * actions can land here. Hidden on ghost rows (no override to clear) and in base
 * mode entirely.
 */
@Composable
private fun BindingRowOverflowMenu(
    onClearOverride: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = { expanded = true },
        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
    ) {
        Icon(
            Icons.Filled.MoreVert,
            contentDescription = "Override actions",
            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Clear override") },
            onClick = { expanded = false; onClearOverride() },
        )
    }
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

/**
 * Brick G follow-up: full-width banner under the TopAppBar surfacing the
 * "you have analog modes configured but Shizuku isn't ready" gap. Uses the
 * errorContainer tonal role — M3 reserves errorContainer for actionable broken
 * states, which matches: the user's analog bindings are functionally inert
 * until they fix Shizuku.
 *
 * Predicate is evaluated by the caller (`hasAnalogModeInConfig && !shizukuReady`);
 * this composable just renders when invoked.
 */
@Composable
private fun ShizukuUnavailableBanner(onOpenSetup: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.remap_shizuku_unavailable_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onOpenSetup,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(stringResource(R.string.remap_shizuku_unavailable_banner_cta))
            }
        }
    }
}
