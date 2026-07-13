package com.mappo.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mappo.R
import com.mappo.data.model.steam.ActivatorType
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.BindingOutput
import com.mappo.data.model.steam.ControllerConfig
import com.mappo.data.model.steam.InputSource
import com.mappo.data.model.steam.displayName
import com.mappo.data.model.steam.displayNameFor
import com.mappo.data.model.steam.requiresShizuku as outputRequiresShizuku
import com.mappo.service.input.modes.requiresShizuku
import com.mappo.service.input.modes.requiresShizukuOnSource
import com.mappo.ui.screen.remap.RemapBottomRow
import com.mappo.ui.screen.remap.RemapGroupEditorCallbacks
import com.mappo.ui.screen.remap.RemapScopeTabActions
import com.mappo.ui.screen.remap.RemapSections
import com.mappo.ui.screen.remap.RemapSimpleView
import com.mappo.ui.screen.remap.RemapTopBar
import com.mappo.ui.screen.remap.settings.SourceModeSettingsSchema

/** True if any binding in [group] has a Shizuku-requiring output (e.g. analog stick directions). */
private fun shizukuOutputInGroup(group: com.mappo.data.model.steam.BindingGroupGraph): Boolean =
    group.inputs.any { gi ->
        gi.activators.any { ag ->
            ag.bindings.any { b -> BindingOutput.fromEntity(b.outputType, b.args).outputRequiresShizuku() }
        }
    }

/**
 * The Remap Controls screen (2026-07): set/layer tabs on top, then the simplified view — group
 * boxes around the controller image, with the tapped box morphing in place into the advanced
 * group editor (`RemapGroupEditor`) — and the Gyro/Overlay strip at the bottom. The planned
 * mapping wizard (the "Map" CTA) becomes the primary mapping flow later.
 *
 * Command picking still navigates to the full-screen picker and pops back with [pickerResult];
 * binding edits ride the same callbacks the previous incarnations used.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemapControlsScreen(
    config: ControllerConfig?,
    onOpenInputEditor: (inputSource: com.mappo.data.model.steam.InputSource, groupInputKey: String, label: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    profileName: String? = null,
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
    onClearLayerOverride: (layerId: Long, inputSource: com.mappo.data.model.steam.InputSource, groupInputKey: String) -> Unit = { _, _, _ -> },
    onSetBindingGroupMode: (bindingGroupId: Long, mode: BindingMode) -> Unit = { _, _ -> },
    onOpenModeSettings: (bindingGroupId: Long, source: InputSource) -> Unit = { _, _ -> },
    onAddModeShift: (actionSetId: Long?, actionLayerId: Long?, ownerSource: InputSource) -> Unit = { _, _, _ -> },
    onRemoveModeShift: (modeShiftId: Long) -> Unit = {},
    onSetModeShiftTrigger: (modeShiftId: Long, triggerSource: InputSource?, triggerSubInput: String?) -> Unit = { _, _, _ -> },
    onOpenModeShiftInputEditor: (modeShiftId: Long, ownerSource: InputSource, groupInputKey: String, label: String) -> Unit = { _, _, _, _ -> },
    shizukuRequiredAcknowledged: Boolean = true,
    shizukuReady: Boolean = true,
    shizukuState: com.mappo.service.shizuku.ShizukuState = com.mappo.service.shizuku.ShizukuState.Granted,
    onAcknowledgeShizukuRequired: () -> Unit = {},
    onOpenShizukuSetup: () -> Unit = {},
    // Inline input-assignment editing. The command picker still navigates away (full screen) and
    // pops back here, delivering its result via [pickerResult].
    pickerResult: BindingOutput? = null,
    onConsumePickerResult: () -> Unit = {},
    onPickResult: (bindingId: Long, output: BindingOutput) -> Unit = { _, _ -> },
    onOpenPicker: (title: String, current: BindingOutput) -> Unit = { _, _ -> },
    // Activator-level callbacks. The group editor drives the binding-level set below; these
    // remain in the signature because MainScreen wires them and future surfaces (the wizard,
    // mode-shift editing) will re-consume them.
    onAddActivator: (groupInputId: Long, type: ActivatorType) -> Unit = { _, _ -> },
    onRemoveActivator: (activatorId: Long) -> Unit = {},
    onSetActivatorType: (activatorId: Long, type: ActivatorType) -> Unit = { _, _ -> },
    onOpenActivatorSettings: (activatorId: Long, label: String) -> Unit = { _, _ -> },
    onAddCommand: (activatorId: Long) -> Unit = {},
    onRemoveCommand: (bindingId: Long) -> Unit = {},
    onAddInputRow: (groupInputId: Long, type: ActivatorType) -> Unit = { _, _ -> },
    onSetInputRowPressType: (bindingId: Long, type: ActivatorType) -> Unit = { _, _ -> },
    onSetInputRowLabel: (bindingId: Long, label: String) -> Unit = { _, _ -> },
    onDeleteInputRow: (bindingId: Long) -> Unit = {},
) {
    // Physical/gesture back navigates home. The expanded group editor installs its own
    // (more-recent) BackHandler while open, so this only fires when nothing else is dismissable.
    BackHandler { onBack() }

    // Which management dialog is currently open. Plain `remember` — dialogs are short-lived;
    // rotation-survival isn't worth a custom Saver.
    var dialog by remember { mutableStateOf<ActionSetDialogState>(ActionSetDialogState.None) }
    var layerDialog by remember { mutableStateOf<LayerDialogState>(LayerDialogState.None) }

    // Stash an analog-mode pick if Shizuku isn't ready AND the explainer hasn't been
    // acknowledged. `Pair(bindingGroupId, mode)`. Once Shizuku is Granted OR the user has acked,
    // picks proceed silently.
    var pendingAnalogPick by remember { mutableStateOf<Pair<Long, BindingMode>?>(null) }

    val gatedSetBindingGroupMode: (Long, BindingMode) -> Unit = { bindingGroupId, mode ->
        // Resolve the source for this binding group so the gate can be source-aware: NONE on a
        // stick / trigger / dpad needs Shizuku (EVIOCGRAB silences); NONE on a button doesn't.
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

    // Resolve which set is currently being viewed. The viewing pointer is user-driven (tab tap);
    // when null, fall back to the controller_profile default so the screen always renders
    // something sensible. Independent of the runtime active set.
    val viewingSet = config?.let { cfg ->
        viewingActionSetId
            ?.let { id -> cfg.actionSets.firstOrNull { it.actionSet.id == id } }
            ?: cfg.activeActionSet
    }
    val viewingLayer = viewingSet?.layers?.firstOrNull { it.layer.id == viewingLayerId }

    // Inline editor: which command (Binding) is awaiting a picker result. Survives the
    // full-screen picker round-trip.
    var editingBindingId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(pickerResult) {
        val output = pickerResult ?: return@LaunchedEffect
        editingBindingId?.let { onPickResult(it, output) }
        editingBindingId = null
        onConsumePickerResult()
    }
    val onEditCommand: (Long, BindingOutput, String) -> Unit = { bindingId, current, title ->
        editingBindingId = bindingId
        onOpenPicker(title, current)
    }

    // Walk the current config for any binding whose (source, mode) pair requires Shizuku. If one
    // exists AND Shizuku isn't ready, the banner surfaces the gap inline — covers configs whose
    // analog modes went inert after Shizuku flipped away from Granted.
    val hasAnalogModeInConfig = config?.actionSets?.any { set ->
        set.preset.any { it.group.group.mode.requiresShizukuOnSource(it.inputSource) || shizukuOutputInGroup(it.group) } ||
            set.layers.any { layer ->
                layer.preset.any { it.group.group.mode.requiresShizukuOnSource(it.inputSource) || shizukuOutputInGroup(it.group) }
            }
    } == true

    val editorCallbacks = RemapGroupEditorCallbacks(
        onSetBindingGroupMode = gatedSetBindingGroupMode,
        onOpenModeSettings = onOpenModeSettings,
        onEditCommand = onEditCommand,
        onOpenInputEditor = onOpenInputEditor,
        onClearOverride = { inputSource, groupInputKey ->
            viewingLayer?.layer?.id?.let { onClearLayerOverride(it, inputSource, groupInputKey) }
        },
        onAddInputRow = onAddInputRow,
        onSetPressType = onSetInputRowPressType,
        onSetLabel = onSetInputRowLabel,
        onDeleteRow = onDeleteInputRow,
        onConfigure = onOpenActivatorSettings,
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            // The action-set manager: one tab per set (layers as subordinate tabs) on the
            // shared ReorderableTabBar. Back/add-set buttons removed pending a new home.
            RemapTopBar(
                config = config,
                viewingSetId = viewingSet?.actionSet?.id,
                viewingLayerId = viewingLayerId,
                onSelectActionSet = onSelectActionSet,
                onSelectLayer = onSelectLayer,
                actions = RemapScopeTabActions(
                    onRenameSet = { dialog = ActionSetDialogState.Rename(it) },
                    onDuplicateSet = { dialog = ActionSetDialogState.Duplicate(it) },
                    onDeleteSet = { dialog = ActionSetDialogState.Delete(it) },
                    onAddLayer = { layerDialog = LayerDialogState.Add(it) },
                    onRenameLayer = { layerDialog = LayerDialogState.Rename(it) },
                    onDuplicateLayer = { layerDialog = LayerDialogState.Duplicate(it) },
                    onDeleteLayer = { layerDialog = LayerDialogState.Delete(it) },
                    onAddSet = { dialog = ActionSetDialogState.Add },
                ),
            )
        },
    ) { innerPadding ->
        // surface — the screen's content plane beneath the group boxes.
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (hasAnalogModeInConfig && !shizukuReady) {
                    ShizukuUnavailableBanner(onOpenSetup = onOpenShizukuSetup)
                }
                RemapSimpleView(
                    viewingSet = viewingSet,
                    viewingLayer = viewingLayer,
                    config = config,
                    onMap = { /* input-mapping wizard — UI-only CTA for now */ },
                    editorCallbacks = editorCallbacks,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    bottomContent = {
                        RemapBottomRow(
                            viewingSet = viewingSet,
                            viewingLayerSelected = viewingLayer != null,
                            onSetGyroMode = gatedSetBindingGroupMode,
                        )
                    },
                )
            }
        }
    }

    val pendingPick = pendingAnalogPick
    if (pendingPick != null) {
        com.mappo.ui.screen.dialog.ShizukuRequiredDialog(
            shizukuState = shizukuState,
            onSetUp = {
                // Apply the mode + ack + navigate to Setup. If the user bails mid-setup, the
                // binding stays — the ShizukuKeyInjector gate keeps it inert until Granted.
                onSetBindingGroupMode(pendingPick.first, pendingPick.second)
                onAcknowledgeShizukuRequired()
                pendingAnalogPick = null
                onOpenShizukuSetup()
            },
            onDismiss = { pendingAnalogPick = null },
        )
    }

    // Management dialogs. Each dialog state carries the target id, so a tab's long-press menu
    // can act on ANY set or layer.
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

/** Resolve an [com.mappo.data.model.steam.ActionSet] entity by id across the config. */
private fun setEntityById(config: ControllerConfig?, id: Long) =
    config?.actionSets?.firstOrNull { it.actionSet.id == id }?.actionSet

/** Resolve an [com.mappo.data.model.steam.ActionLayer] entity by id across all sets. */
private fun layerEntityById(config: ControllerConfig?, id: Long) =
    config?.actionSets?.firstNotNullOfOrNull { s -> s.layers.firstOrNull { it.layer.id == id }?.layer }

/** Which management dialog is currently open. Hoisted in [RemapControlsScreen]; carries the
 *  target set id so the tab menus can act on any set. */
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

/**
 * Bottom sheet for an added mode's (mode shift's) settings. RETAINED-FOR-REUSE: mode-shift
 * editing lost its UI surface when the rail/detail advanced view was retired (2026-07-10);
 * this sheet + [TriggerInputPickerSheet] return with the wizard / group-editor mode-shift work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModeShiftSettingsSheet(
    modeShiftId: Long,
    modeBindingGroupId: Long,
    ownerSource: InputSource,
    mode: BindingMode,
    currentTriggerSource: InputSource?,
    currentTriggerSubInput: String?,
    onSetTrigger: (modeShiftId: Long, triggerSource: InputSource?, triggerSubInput: String?) -> Unit,
    onOpenModeSettings: (bindingGroupId: Long, source: InputSource) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pickingTrigger by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = "Mode settings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider()
            // Activation button — what the user holds to make this added mode active.
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pickingTrigger = true },
                headlineContent = { Text("Activation button", style = MaterialTheme.typography.bodyLarge) },
                supportingContent = {
                    val label = if (currentTriggerSource != null && currentTriggerSubInput != null) {
                        val match = RemapSections.TRIGGER_INPUT_CATALOG.firstOrNull {
                            it.source == currentTriggerSource && it.subInput == currentTriggerSubInput
                        }
                        match?.label ?: "${currentTriggerSource.displayName()} / $currentTriggerSubInput"
                    } else "Not assigned — the mode won't activate until you pick one"
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            // The added mode's own behavior settings (deadzones, curves…), when the mode has any.
            if (SourceModeSettingsSchema.hasSettings(ownerSource, mode)) {
                HorizontalDivider()
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismiss(); onOpenModeSettings(modeBindingGroupId, ownerSource) },
                    headlineContent = { Text("${mode.displayNameFor(ownerSource)} settings", style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { Text("Deadzones, curves, and other tuning for this mode.", style = MaterialTheme.typography.bodyMedium) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
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
 * Trigger input picker — a flat list of physical digital sub-inputs from
 * [RemapSections.TRIGGER_INPUT_CATALOG], grouped by category. Selecting a row commits via
 * [onPick] and dismisses. Retained-for-reuse alongside [ModeShiftSettingsSheet].
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

/**
 * Full-width banner under the top bar surfacing the "you have analog modes configured but
 * Shizuku isn't ready" gap. errorContainer — actionable broken state: those bindings are inert
 * until Shizuku is fixed.
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
