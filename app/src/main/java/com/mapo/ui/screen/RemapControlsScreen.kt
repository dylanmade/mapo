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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import com.mapo.data.model.steam.SourceModeShiftGraph
import com.mapo.service.input.modes.SourceModeCatalog
import com.mapo.data.model.steam.requiresShizuku as outputRequiresShizuku
import com.mapo.service.input.modes.requiresShizuku
import com.mapo.service.input.modes.requiresShizukuOnSource
import com.mapo.ui.screen.remap.RemapPaneItem
import com.mapo.ui.screen.remap.RemapRail
import com.mapo.ui.screen.remap.RemapSections
import com.mapo.ui.screen.remap.settings.SourceModeSettingsSchema

/** True if any binding in [group] has a Shizuku-requiring output (e.g. analog stick directions). */
private fun shizukuOutputInGroup(group: com.mapo.data.model.steam.BindingGroupGraph): Boolean =
    group.inputs.any { gi ->
        gi.activators.any { ag ->
            ag.bindings.any { b -> BindingOutput.fromEntity(b.outputType, b.args).outputRequiresShizuku() }
        }
    }

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
    onOpenModeSettings: (bindingGroupId: Long, source: InputSource) -> Unit = { _, _ -> },
    onAddModeShift: (actionSetId: Long?, actionLayerId: Long?, ownerSource: InputSource) -> Unit = { _, _, _ -> },
    onRemoveModeShift: (modeShiftId: Long) -> Unit = {},
    onSetModeShiftTrigger: (modeShiftId: Long, triggerSource: InputSource?, triggerSubInput: String?) -> Unit = { _, _, _ -> },
    onOpenModeShiftInputEditor: (modeShiftId: Long, ownerSource: InputSource, groupInputKey: String, label: String) -> Unit = { _, _, _, _ -> },
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
        // Resolve the source for this binding group so the gate can be
        // source-aware: NONE on a stick / trigger / dpad needs Shizuku
        // (EVIOCGRAB silences); NONE on a button doesn't (handleDigital
        // silences). Walk both base preset entries and layer-owned binding
        // groups — either may contain the target group depending on
        // whether the user's editing the set or a layer overlay.
        val source: InputSource? = config?.actionSets?.firstNotNullOfOrNull { set ->
            set.preset.firstOrNull { it.group.group.id == bindingGroupId }?.inputSource
                ?: set.layers.firstNotNullOfOrNull { layer ->
                    layer.preset.firstOrNull { it.group.group.id == bindingGroupId }?.inputSource
                }
        }
        val needsShizuku = if (source != null) {
            mode.requiresShizukuOnSource(source)
        } else {
            mode.requiresShizuku()
        }
        if (needsShizuku && !shizukuReady && !shizukuRequiredAcknowledged) {
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

    // Brick G follow-up: walk the current config for any binding whose
    // (source, mode) pair requires Shizuku. If one exists AND Shizuku isn't
    // ready, the banner below surfaces the gap inline — covers the case where
    // the user previously applied an affected mode (so the first-time dialog
    // won't re-fire) and then Shizuku flipped away from Granted, leaving
    // those bindings silently inert.
    //
    // Source-aware (Brick C.5 follow-up 2026-06-03): NONE on a stick / trigger
    // / dpad needs Shizuku (EVIOCGRAB silences); NONE on a button doesn't.
    // The plain `requiresShizuku()` check would miss NONE-on-analog
    // configurations and the user would have no in-screen signal that their
    // intended silence isn't actually taking effect.
    val hasAnalogModeInConfig = config?.actionSets?.any { set ->
        set.preset.any { it.group.group.mode.requiresShizukuOnSource(it.inputSource) || shizukuOutputInGroup(it.group) } ||
            set.layers.any { layer ->
                layer.preset.any { it.group.group.mode.requiresShizukuOnSource(it.inputSource) || shizukuOutputInGroup(it.group) }
            }
    } == true

    Scaffold(modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (hasAnalogModeInConfig && !shizukuReady) {
                ShizukuUnavailableBanner(onOpenSetup = onOpenShizukuSetup)
            }
            RemapRail(
                sections = RemapSections.rail,
                selectedSectionId = selectedSectionId,
                onSectionSelected = { selectedSectionId = it },
                onBack = onBack,
                scopeLabel = viewingLayer?.layer?.title
                    ?: viewingSet?.actionSet?.title
                    ?: "—",
                config = config,
                viewingSetId = viewingSet?.actionSet?.id,
                viewingLayerId = viewingLayerId,
                canDeleteSet = (config?.actionSets?.size ?: 0) > 1,
                onSelectActionSet = onSelectActionSet,
                onSelectLayer = onSelectLayer,
                onAddSet = { dialog = ActionSetDialogState.Add },
                onAddLayer = { setId -> layerDialog = LayerDialogState.Add(setId) },
                onRenameSet = { setId -> dialog = ActionSetDialogState.Rename(setId) },
                onDuplicateSet = { setId -> dialog = ActionSetDialogState.Duplicate(setId) },
                onDeleteSet = { setId -> dialog = ActionSetDialogState.Delete(setId) },
                onRenameLayer = { layerId -> layerDialog = LayerDialogState.Rename(layerId) },
                onDuplicateLayer = { layerId -> layerDialog = LayerDialogState.Duplicate(layerId) },
                onDeleteLayer = { layerId -> layerDialog = LayerDialogState.Delete(layerId) },
                modifier = Modifier.fillMaxSize(),
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
                    onOpenModeSettings = onOpenModeSettings,
                    onAddModeShift = { ownerSource ->
                        val layerId = viewingLayer?.layer?.id
                        val setId = viewingSet?.actionSet?.id
                        if (layerId != null) {
                            onAddModeShift(null, layerId, ownerSource)
                        } else if (setId != null) {
                            onAddModeShift(setId, null, ownerSource)
                        }
                    },
                    onRemoveModeShift = onRemoveModeShift,
                    onSetModeShiftTrigger = onSetModeShiftTrigger,
                    onOpenModeShiftInputEditor = onOpenModeShiftInputEditor,
                )
            }
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

    // Management dialogs. Rendered outside the Scaffold so they overlay everything (M3
    // AlertDialog uses a Dialog window, but we still hoist the state here so it survives
    // Scaffold recompositions). Each dialog state carries the target id, so the rail's
    // per-row kebab can rename/duplicate/delete *any* set or layer, not just the viewed one.
    when (val d = dialog) {
        ActionSetDialogState.None -> Unit
        ActionSetDialogState.Add -> AddSetDialog(
            existingSets = config?.actionSets.orEmpty(),
            onConfirm = { title, inheritFromSetId ->
                onAddActionSet(title, inheritFromSetId)
                dialog = ActionSetDialogState.None
            },
            onDismiss = { dialog = ActionSetDialogState.None },
        )
        is ActionSetDialogState.Rename -> setEntityById(config, d.setId)?.let { target ->
            RenameSetDialog(
                target = target,
                onConfirm = { newTitle ->
                    onRenameActionSet(target.id, newTitle)
                    dialog = ActionSetDialogState.None
                },
                onDismiss = { dialog = ActionSetDialogState.None },
            )
        } ?: run { dialog = ActionSetDialogState.None }
        is ActionSetDialogState.Duplicate -> setEntityById(config, d.setId)?.let { source ->
            DuplicateSetDialog(
                source = source,
                onConfirm = { newTitle ->
                    onDuplicateActionSet(source.id, newTitle)
                    dialog = ActionSetDialogState.None
                },
                onDismiss = { dialog = ActionSetDialogState.None },
            )
        } ?: run { dialog = ActionSetDialogState.None }
        is ActionSetDialogState.Delete -> setEntityById(config, d.setId)?.let { target ->
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

    // Layer management dialogs. Same hoisting + id-targeting pattern as action sets.
    when (val d = layerDialog) {
        LayerDialogState.None -> Unit
        is LayerDialogState.Add -> setEntityById(config, d.parentSetId)?.let { parentSet ->
            AddLayerDialog(
                onConfirm = { title ->
                    onAddLayer(parentSet.id, title)
                    layerDialog = LayerDialogState.None
                },
                onDismiss = { layerDialog = LayerDialogState.None },
            )
        } ?: run { layerDialog = LayerDialogState.None }
        is LayerDialogState.Rename -> layerEntityById(config, d.layerId)?.let { target ->
            RenameLayerDialog(
                target = target,
                onConfirm = { newTitle ->
                    onRenameLayer(target.id, newTitle)
                    layerDialog = LayerDialogState.None
                },
                onDismiss = { layerDialog = LayerDialogState.None },
            )
        } ?: run { layerDialog = LayerDialogState.None }
        is LayerDialogState.Duplicate -> layerEntityById(config, d.layerId)?.let { source ->
            DuplicateLayerDialog(
                source = source,
                onConfirm = { newTitle ->
                    onDuplicateLayer(source.id, newTitle)
                    layerDialog = LayerDialogState.None
                },
                onDismiss = { layerDialog = LayerDialogState.None },
            )
        } ?: run { layerDialog = LayerDialogState.None }
        is LayerDialogState.Delete -> layerEntityById(config, d.layerId)?.let { target ->
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

/** Resolve an [com.mapo.data.model.steam.ActionSet] entity by id across the config. */
private fun setEntityById(config: ControllerConfig?, id: Long) =
    config?.actionSets?.firstOrNull { it.actionSet.id == id }?.actionSet

/** Resolve an [com.mapo.data.model.steam.ActionLayer] entity by id across all sets. */
private fun layerEntityById(config: ControllerConfig?, id: Long) =
    config?.actionSets?.firstNotNullOfOrNull { s -> s.layers.firstOrNull { it.layer.id == id }?.layer }

/** Which management dialog is currently open. Hoisted in [RemapControlsScreen]; carries the
 *  target set id so the rail's per-row kebab can act on any set. */
private sealed class ActionSetDialogState {
    object None : ActionSetDialogState()
    object Add : ActionSetDialogState()
    data class Rename(val setId: Long) : ActionSetDialogState()
    data class Duplicate(val setId: Long) : ActionSetDialogState()
    data class Delete(val setId: Long) : ActionSetDialogState()
}

/** Layer-management dialog state. Parallel to [ActionSetDialogState]; Add carries the parent
 *  set id, the rest the target layer id. */
private sealed class LayerDialogState {
    object None : LayerDialogState()
    data class Add(val parentSetId: Long) : LayerDialogState()
    data class Rename(val layerId: Long) : LayerDialogState()
    data class Duplicate(val layerId: Long) : LayerDialogState()
    data class Delete(val layerId: Long) : LayerDialogState()
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
    onOpenModeSettings: (bindingGroupId: Long, source: InputSource) -> Unit,
    onAddModeShift: (ownerSource: InputSource) -> Unit,
    onRemoveModeShift: (modeShiftId: Long) -> Unit,
    onSetModeShiftTrigger: (modeShiftId: Long, triggerSource: InputSource?, triggerSubInput: String?) -> Unit,
    onOpenModeShiftInputEditor: (modeShiftId: Long, ownerSource: InputSource, groupInputKey: String, label: String) -> Unit,
) {
    val rawItems = RemapSections.contentBySection[sectionId]

    if (rawItems == null) {
        // Defensive: any future un-implemented section routes here.
        DetailPlaceholder(RemapSections.UNIMPLEMENTED_SECTION_PLACEHOLDER)
        return
    }

    // Phase 7 follow-up: mode-aware sources (face buttons, dpad, joysticks,
    // triggers) have their sub-input rows generated dynamically from the
    // currently-resolved mode. Must run BEFORE the overrides filter so
    // filterToOverrides sees the full mode-appropriate row set.
    val dynamicallyExpandedItems = remember(rawItems, viewingSet, viewingLayer) {
        expandWithDynamicBaseRows(rawItems, viewingSet, viewingLayer)
    }

    // Brick 5.5.c: when "Only overrides" is on, drop binding rows without an override
    // — and any subheader whose children all dropped. Disabled rows always hide in
    // overrides-only (they can't be overridden anyway).
    val filteredItems = remember(dynamicallyExpandedItems, onlyOverrides, viewingLayer) {
        if (!onlyOverrides || viewingLayer == null) dynamicallyExpandedItems
        else filterToOverrides(dynamicallyExpandedItems, viewingLayer)
    }
    val isOverlayMode = viewingLayer != null

    // Phase 7 Brick B.6: interleave mode-shift sections after each source's
    // binding rows. Layer-view shows only layer-owned shifts; base view shows
    // only set-owned shifts.
    val items = remember(filteredItems, viewingSet, viewingLayer) {
        expandWithModeShifts(filteredItems, viewingSet, viewingLayer)
    }

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
                        onOpenModeSettings = onOpenModeSettings,
                        onAddModeShift = onAddModeShift,
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
                    is RemapPaneItem.ModeShiftHeader -> ModeShiftHeaderRow(
                        item = item,
                        viewingSet = viewingSet,
                        viewingLayer = viewingLayer,
                        onSetBindingGroupMode = onSetBindingGroupMode,
                        onRemoveModeShift = onRemoveModeShift,
                        onSetModeShiftTrigger = onSetModeShiftTrigger,
                    )
                    is RemapPaneItem.ModeShiftBindingRow -> ModeShiftBindingRowItem(
                        item = item,
                        viewingSet = viewingSet,
                        viewingLayer = viewingLayer,
                        config = config,
                        modifier = focusModifier,
                        onOpenEditor = onOpenModeShiftInputEditor,
                    )
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
            // Phase 7 Brick B.6: mode-shift items don't appear in the static
            // input list — they're interleaved post-filter by expandWithModeShifts.
            // Defensive no-op so the when stays exhaustive.
            is RemapPaneItem.ModeShiftHeader,
            is RemapPaneItem.ModeShiftBindingRow -> Unit
        }
    }
    return result
}

/**
 * Phase 7 follow-up: render base source rows dynamically per the source's
 * currently-resolved [com.mapo.data.model.steam.BindingMode]. Walks [items]
 * and, after every [RemapPaneItem.Subheader] whose source is in
 * [RemapSections.MODE_AWARE_SOURCES], inserts one [RemapPaneItem.BindingRow]
 * per sub-input returned by `validInputsFor(source, effectiveMode)`.
 *
 * Effective mode is the layer's override (when in layer view + the layer has
 * a preset for this source) else the base set's mode. Same precedence the
 * SubheaderRow uses to pick which group's mode to drive the dropdown.
 *
 * Sources NOT in [RemapSections.MODE_AWARE_SOURCES] (bumpers, switches) keep
 * their static rows from the registry — they're single-button-only.
 */
internal fun expandWithDynamicBaseRows(
    items: List<RemapPaneItem>,
    viewingSet: com.mapo.data.model.steam.ActionSetGraph?,
    viewingLayer: com.mapo.data.model.steam.ActionLayerGraph?,
): List<RemapPaneItem> {
    val out = mutableListOf<RemapPaneItem>()
    for (item in items) {
        out += item
        if (item is RemapPaneItem.Subheader) {
            val source = item.inputSource ?: continue
            if (source !in RemapSections.MODE_AWARE_SOURCES) continue
            // Effective mode resolution: layer override > base set > DEVICE_DEFAULT
            // fallback (the seed default for unconfigured sources). The
            // DEVICE_DEFAULT path still produces canonical rows via
            // bindableSubInputsFor's empty-mode fallback so a fresh install
            // or test config without a preset entry still renders rows.
            val effectiveMode = viewingLayer?.presetFor(source)?.group?.group?.mode
                ?: viewingSet?.presetFor(source)?.group?.group?.mode
                ?: com.mapo.data.model.steam.BindingMode.DEVICE_DEFAULT
            for ((subInputKey, label) in RemapSections.bindableSubInputsFor(source, effectiveMode)) {
                out += RemapPaneItem.BindingRow(
                    key = "${item.key}.dyn.$subInputKey",
                    label = label,
                    inputSource = source,
                    groupInputKey = subInputKey,
                )
            }
        }
    }
    return out
}

/**
 * Phase 7 Brick B.6: walk [items] and inject mode-shift sections after each
 * source's binding rows. The shift items come from [viewingLayer]'s
 * `modeShifts` when in layer-view, or [viewingSet]'s when in base-view —
 * mode shifts are scoped to the viewing context, not inherited across.
 *
 * Implementation: scan the input list left-to-right. A Subheader "opens" a
 * source context. When the next Subheader arrives (or the list ends), flush
 * the prior source's shifts into the result.
 */
internal fun expandWithModeShifts(
    items: List<RemapPaneItem>,
    viewingSet: com.mapo.data.model.steam.ActionSetGraph?,
    viewingLayer: com.mapo.data.model.steam.ActionLayerGraph?,
): List<RemapPaneItem> {
    val shifts = viewingLayer?.modeShifts ?: viewingSet?.modeShifts ?: return items
    if (shifts.isEmpty()) return items

    fun shiftsFor(source: InputSource): List<SourceModeShiftGraph> =
        shifts.filter { it.shift.ownerSource == source }

    fun materialize(shift: SourceModeShiftGraph): List<RemapPaneItem> {
        val ownerSource = shift.shift.ownerSource
        val baseKey = "ms.${shift.shift.id}"
        val out = mutableListOf<RemapPaneItem>()
        out += RemapPaneItem.ModeShiftHeader(
            key = "$baseKey.header",
            modeShiftId = shift.shift.id,
            ownerSource = ownerSource,
        )
        for ((subInputKey, label) in RemapSections.bindableSubInputsFor(ownerSource, shift.group.group.mode)) {
            out += RemapPaneItem.ModeShiftBindingRow(
                key = "$baseKey.$subInputKey",
                modeShiftId = shift.shift.id,
                label = label,
                ownerSource = ownerSource,
                groupInputKey = subInputKey,
            )
        }
        return out
    }

    val result = mutableListOf<RemapPaneItem>()
    var currentSource: InputSource? = null
    for (item in items) {
        if (item is RemapPaneItem.Subheader) {
            // Flush prior source's shifts before opening a new source context.
            currentSource?.let { src ->
                for (s in shiftsFor(src)) result += materialize(s)
            }
            currentSource = item.inputSource
        }
        result += item
    }
    // Flush trailing source's shifts at end-of-list.
    currentSource?.let { src ->
        for (s in shiftsFor(src)) result += materialize(s)
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
    onOpenModeSettings: (bindingGroupId: Long, source: InputSource) -> Unit,
    onAddModeShift: (ownerSource: InputSource) -> Unit,
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
                // Mode picker + settings cog + (optionally) "+ Mode Shift" side by side.
                val pickerEnabled = viewingLayer == null && validModes.size > 1
                // Cog navigates to the full-screen settings editor for (source, mode).
                // Base-set view only for now — layer-override settings editing is a
                // later slice (mirrors the mode picker being base-only).
                val showCog = viewingLayer == null &&
                    SourceModeSettingsSchema.hasSettings(source, effectiveGroup.mode)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModePicker(
                        source = source,
                        currentMode = effectiveGroup.mode,
                        validModes = validModes,
                        enabled = pickerEnabled,
                        onPick = { mode -> onSetBindingGroupMode(effectiveGroup.id, mode) },
                    )
                    if (showCog) {
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = { onOpenModeSettings(effectiveGroup.id, source) },
                            modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
                        ) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Mode settings",
                                modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                            )
                        }
                    }
                    if (source in RemapSections.MODE_SHIFT_OWNERS) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { onAddModeShift(source) }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Mode Shift", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
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

/**
 * Phase 7 Brick B.6: header row for a mode-shift section. Renders the source's
 * display name with "(Mode Shift)" suffix, the shift's mode picker, a settings
 * cog (opens [ModeShiftSettingsSheet]), and a remove button.
 *
 * Resolves the shift's [SourceModeShiftGraph] from the viewing context. If the
 * shift can't be found (e.g. just deleted), the row renders nothing — its
 * absence is the deletion signal to the user.
 */
@Composable
private fun ModeShiftHeaderRow(
    item: RemapPaneItem.ModeShiftHeader,
    viewingSet: com.mapo.data.model.steam.ActionSetGraph?,
    viewingLayer: com.mapo.data.model.steam.ActionLayerGraph?,
    onSetBindingGroupMode: (bindingGroupId: Long, mode: BindingMode) -> Unit,
    onRemoveModeShift: (modeShiftId: Long) -> Unit,
    onSetModeShiftTrigger: (modeShiftId: Long, triggerSource: InputSource?, triggerSubInput: String?) -> Unit,
) {
    val shifts = viewingLayer?.modeShifts ?: viewingSet?.modeShifts ?: return
    val shift = shifts.firstOrNull { it.shift.id == item.modeShiftId } ?: return
    var showSettings by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${item.ownerSource.displayName()} (Mode Shift)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Mode shift settings")
            }
            IconButton(onClick = { onRemoveModeShift(item.modeShiftId) }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove mode shift")
            }
        }
        // Trigger summary
        val triggerLabel = remember(shift.shift.triggerSource, shift.shift.triggerSubInput) {
            val src = shift.shift.triggerSource
            val sub = shift.shift.triggerSubInput
            if (src != null && sub != null) {
                val match = RemapSections.TRIGGER_INPUT_CATALOG.firstOrNull {
                    it.source == src && it.subInput == sub
                }
                match?.let { "Triggered by ${it.label}" } ?: "Triggered by ${src.displayName()} / $sub"
            } else {
                "Trigger unassigned — tap settings to pick"
            }
        }
        Text(
            text = triggerLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = if (shift.shift.triggerSource == null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        val validModes = SourceModeCatalog.modesValidFor(item.ownerSource)
        if (validModes.isNotEmpty()) {
            ModePicker(
                source = item.ownerSource,
                currentMode = shift.group.group.mode,
                validModes = validModes,
                enabled = validModes.size > 1,
                onPick = { mode -> onSetBindingGroupMode(shift.group.group.id, mode) },
            )
        }
    }

    if (showSettings) {
        ModeShiftSettingsSheet(
            modeShiftId = item.modeShiftId,
            currentTriggerSource = shift.shift.triggerSource,
            currentTriggerSubInput = shift.shift.triggerSubInput,
            onSetTrigger = onSetModeShiftTrigger,
            onDismiss = { showSettings = false },
        )
    }
}

/**
 * Phase 7 Brick B.6: bindable row within a mode shift's target group. Mirrors
 * [BindingRowItem]'s layout exactly — same label + helper subtext + trailing
 * binding label — so mode-shift rows read as siblings of base rows. The lookup
 * walks the shift's target group instead of the source's preset.
 *
 * Tap routes through [onOpenEditor] with the [modeShiftId] so InputEditorScreen
 * resolves the binding via the shift's group (see [findGroupInput] +
 * `MapoRoute.inputEditor(..., modeShiftId=...)`).
 *
 * Pre-materialization: a fresh mode-shift target group has no GroupInput rows;
 * the row renders as Unbound until the user taps it (which materializes via
 * [com.mapo.data.repository.ControllerConfigRepository.materializeModeShiftInput]
 * before navigation). After binding, the row updates to show the assigned
 * output the next time the config is observed.
 */
@Composable
private fun ModeShiftBindingRowItem(
    item: RemapPaneItem.ModeShiftBindingRow,
    viewingSet: com.mapo.data.model.steam.ActionSetGraph?,
    viewingLayer: com.mapo.data.model.steam.ActionLayerGraph?,
    config: ControllerConfig?,
    modifier: Modifier = Modifier,
    onOpenEditor: (modeShiftId: Long, ownerSource: InputSource, groupInputKey: String, label: String) -> Unit,
) {
    // Resolve the shift's target group from whichever owner (set vs. layer)
    // matches the viewing context. expandWithModeShifts only materializes
    // shifts for the active context, so the lookup mirrors that.
    val shifts = viewingLayer?.modeShifts ?: viewingSet?.modeShifts ?: emptyList()
    val shift = shifts.firstOrNull { it.shift.id == item.modeShiftId }
    val groupInput = shift?.group?.inputByKey(item.groupInputKey)
    val activators = groupInput?.activators.orEmpty()
    val primary = activators.firstOrNull { it.activator.type == com.mapo.data.model.steam.ActivatorType.FULL_PRESS }
        ?: activators.firstOrNull()
    val output = primary?.primaryOutput ?: BindingOutput.Unbound
    val extraCount = (activators.size - 1).coerceAtLeast(0)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                onOpenEditor(item.modeShiftId, item.ownerSource, item.groupInputKey, item.label)
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
            Text(
                text = item.ownerSource.displayName() + " (Mode Shift) · " + item.groupInputKey,
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

/**
 * Phase 7 Brick B.6: bottom sheet for mode-shift settings. First (and currently
 * only) row is "Trigger input" — taps drill into the trigger picker. Designed
 * as a sheet so it's modal and the user returns directly to the
 * Remap Controls editor on dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeShiftSettingsSheet(
    modeShiftId: Long,
    currentTriggerSource: InputSource?,
    currentTriggerSubInput: String?,
    onSetTrigger: (modeShiftId: Long, triggerSource: InputSource?, triggerSubInput: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pickingTrigger by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = "Mode shift settings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider()
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pickingTrigger = true },
                headlineContent = { Text("Trigger input", style = MaterialTheme.typography.bodyLarge) },
                supportingContent = {
                    val label = if (currentTriggerSource != null && currentTriggerSubInput != null) {
                        val match = RemapSections.TRIGGER_INPUT_CATALOG.firstOrNull {
                            it.source == currentTriggerSource && it.subInput == currentTriggerSubInput
                        }
                        match?.label ?: "${currentTriggerSource.displayName()} / $currentTriggerSubInput"
                    } else "Not assigned"
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(0.dp),  // hidden — disclosure conveys tap
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            if (currentTriggerSource != null) {
                HorizontalDivider()
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSetTrigger(modeShiftId, null, null) },
                    headlineContent = {
                        Text(
                            "Clear trigger",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }

    if (pickingTrigger) {
        TriggerInputPickerSheet(
            currentSource = currentTriggerSource,
            currentSubInput = currentTriggerSubInput,
            onPick = { source, subInput ->
                onSetTrigger(modeShiftId, source, subInput)
                pickingTrigger = false
            },
            onDismiss = { pickingTrigger = false },
        )
    }
}

/**
 * Phase 7 Brick B.6: trigger input picker — a flat list of physical digital
 * sub-inputs from [RemapSections.TRIGGER_INPUT_CATALOG], grouped by category.
 * Selecting a row commits via [onPick] and dismisses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerInputPickerSheet(
    currentSource: InputSource?,
    currentSubInput: String?,
    onPick: (InputSource, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Pick trigger input",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            HorizontalDivider()
            val grouped = RemapSections.TRIGGER_INPUT_CATALOG.groupBy { it.groupTitle }
            for ((groupTitle, options) in grouped) {
                Text(
                    text = groupTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                for (opt in options) {
                    val isSelected = opt.source == currentSource && opt.subInput == currentSubInput
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(opt.source, opt.subInput) },
                        headlineContent = { Text(opt.label, style = MaterialTheme.typography.bodyLarge) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                             else Color.Transparent,
                        ),
                    )
                }
                HorizontalDivider()
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
