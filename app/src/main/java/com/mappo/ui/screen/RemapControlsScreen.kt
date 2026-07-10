package com.mappo.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.mappo.ui.compact.CompactButton
import com.mappo.ui.compact.CompactButtonSize
import com.mappo.ui.compact.CompactFilledTonalButton
import com.mappo.ui.compact.CompactIconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.unit.sp
import com.mappo.data.model.steam.ActivatorType
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.BindingOutput
import com.mappo.data.model.steam.ControllerConfig
import com.mappo.data.model.steam.InputSource
import com.mappo.data.model.steam.displayLabel
import com.mappo.data.model.steam.displayName
import com.mappo.data.model.steam.displayNameFor
import androidx.compose.ui.res.stringResource
import com.mappo.R
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import com.mappo.data.model.steam.SourceModeShiftGraph
import com.mappo.service.input.modes.SourceModeCatalog
import com.mappo.data.model.steam.requiresShizuku as outputRequiresShizuku
import com.mappo.service.input.modes.requiresShizuku
import com.mappo.service.input.modes.requiresShizukuOnSource
import com.mappo.ui.component.NameableText
import com.mappo.ui.screen.remap.RemapPaneItem
import com.mappo.ui.screen.remap.RemapRail
import com.mappo.ui.screen.remap.RemapSections
import com.mappo.ui.screen.remap.settings.SourceModeSettingsSchema

/** True if any binding in [group] has a Shizuku-requiring output (e.g. analog stick directions). */
private fun shizukuOutputInGroup(group: com.mappo.data.model.steam.BindingGroupGraph): Boolean =
    group.inputs.any { gi ->
        gi.activators.any { ag ->
            ag.bindings.any { b -> BindingOutput.fromEntity(b.outputType, b.args).outputRequiresShizuku() }
        }
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    // Phase 2: inline input-assignment editor. The command picker still navigates away (full
    // screen) and pops back here, delivering its result via [pickerResult]; the rest are the
    // activator/command callbacks the inline editor invokes (formerly InputEditorScreen's).
    pickerResult: BindingOutput? = null,
    onConsumePickerResult: () -> Unit = {},
    onPickResult: (bindingId: Long, output: BindingOutput) -> Unit = { _, _ -> },
    onOpenPicker: (title: String, current: BindingOutput) -> Unit = { _, _ -> },
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
    // Physical/gesture back navigates home (the rail's back affordance was removed; this keeps
    // the Thor hardware back button + the touch back gesture working). Open menus/dialogs install
    // their own (more-recent) back handlers, so this only fires when nothing else is dismissable.
    BackHandler { onBack() }

    var selectedSectionId by rememberSaveable { mutableStateOf(RemapSections.SECTION_BUTTONS) }

    // Whether the advanced editor dialog (rail + detail pane) is open. rememberSaveable so the
    // dialog survives the full-screen command-picker round-trip and restores on pop-back.
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

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

    // Phase 2 inline editor: which command (Binding) is awaiting a picker result. Survives the
    // full-screen picker round-trip (rememberSaveable) the same way InputEditorScreen did.
    var editingBindingId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(pickerResult) {
        val output = pickerResult ?: return@LaunchedEffect
        editingBindingId?.let { onPickResult(it, output) }
        editingBindingId = null
        onConsumePickerResult()
    }
    // Tapping a command row opens the picker for that binding.
    val onEditCommand: (Long, BindingOutput, String) -> Unit = { bindingId, current, title ->
        editingBindingId = bindingId
        onOpenPicker(title, current)
    }
    // Which binding rows are collapsed (default = expanded, per design). Keyed by row key.
    val collapsedRows = remember { mutableStateListOf<String>() }

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

    Scaffold(
        modifier = modifier,
        topBar = {
            // The action-set manager: back + one tab per set (layers as subordinate tabs) +
            // add-set, on the shared ReorderableTabBar. Replaces the old profile-title app bar
            // AND the rail's scope fly-out as the set/layer selection surface.
            com.mappo.ui.screen.remap.RemapTopBar(
                config = config,
                viewingSetId = viewingSet?.actionSet?.id,
                viewingLayerId = viewingLayerId,
                onSelectActionSet = onSelectActionSet,
                onSelectLayer = onSelectLayer,
                onBack = onBack,
                actions = com.mappo.ui.screen.remap.RemapScopeTabActions(
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (hasAnalogModeInConfig && !shizukuReady) {
                ShizukuUnavailableBanner(onOpenSetup = onOpenShizukuSetup)
            }
            // The simplified ("basic") view: group boxes around the controller diagram. Tapping
            // a box opens the advanced editor dialog below on that group's section.
            com.mappo.ui.screen.remap.RemapSimpleView(
                viewingSet = viewingSet,
                viewingLayer = viewingLayer,
                config = config,
                onMap = { /* input-mapping wizard — UI-only CTA for now */ },
                onOpenGroup = { group ->
                    selectedSectionId = group.sectionId
                    advancedOpen = true
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            com.mappo.ui.screen.remap.RemapBottomRow(
                viewingSet = viewingSet,
                viewingLayerSelected = viewingLayer != null,
                onSetGyroMode = gatedSetBindingGroupMode,
                onOpenModeSettings = onOpenModeSettings,
            )
        }
    }

    // Advanced editor: the pre-existing rail + detail-pane assignment UI, hosted in a
    // near-fullscreen dialog opened from a group box (the "advanced" half of the basic/advanced
    // split; the wizard becomes the primary flow later).
    if (advancedOpen) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { advancedOpen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // The dialog window hands initial focus to the first focusable — the rail's first
            // item — whose select-on-focus would clobber the section this dialog was opened on.
            // An invisible focus anchor takes that initial focus instead (same idiom as
            // HomeFlower's focus holder); the first real D-pad move then enters the rail.
            val dialogFocusAnchor = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { runCatching { dialogFocusAnchor.requestFocus() } }
            // surfaceContainerHigh — dialog plane hosting the advanced editor.
            Surface(
                modifier = Modifier.fillMaxSize().padding(10.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .focusRequester(dialogFocusAnchor)
                        .focusable(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Advanced controls",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { advancedOpen = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                    HorizontalDivider()
                    RemapRail(
                        sections = RemapSections.rail,
                        selectedSectionId = selectedSectionId,
                        onSectionSelected = { selectedSectionId = it },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
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
                    collapsedRows = collapsedRows,
                    onToggleRowCollapsed = { key ->
                        if (collapsedRows.contains(key)) collapsedRows.remove(key)
                        else collapsedRows.add(key)
                    },
                    onEditCommand = onEditCommand,
                    onAddActivator = onAddActivator,
                    onRemoveActivator = onRemoveActivator,
                    onSetActivatorType = onSetActivatorType,
                    onOpenActivatorSettings = onOpenActivatorSettings,
                    onAddCommand = onAddCommand,
                    onRemoveCommand = onRemoveCommand,
                    onAddInputRow = onAddInputRow,
                    onSetInputRowPressType = onSetInputRowPressType,
                    onSetInputRowLabel = onSetInputRowLabel,
                    onDeleteInputRow = onDeleteInputRow,
                )
                    }
                }
            }
        }
    }

    val pendingPick = pendingAnalogPick
    if (pendingPick != null) {
        com.mappo.ui.screen.dialog.ShizukuRequiredDialog(
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

/** Resolve an [com.mappo.data.model.steam.ActionSet] entity by id across the config. */
private fun setEntityById(config: ControllerConfig?, id: Long) =
    config?.actionSets?.firstOrNull { it.actionSet.id == id }?.actionSet

/** Resolve an [com.mappo.data.model.steam.ActionLayer] entity by id across all sets. */
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
    viewingSet: com.mappo.data.model.steam.ActionSetGraph?,
    viewingLayer: com.mappo.data.model.steam.ActionLayerGraph?,
    onlyOverrides: Boolean,
    onSetOnlyOverrides: (Boolean) -> Unit,
    config: ControllerConfig?,
    firstRowFocusRequester: FocusRequester,
    onOpenInputEditor: (inputSource: com.mappo.data.model.steam.InputSource, groupInputKey: String, label: String) -> Unit,
    onClearOverride: (inputSource: com.mappo.data.model.steam.InputSource, groupInputKey: String) -> Unit,
    onSetBindingGroupMode: (bindingGroupId: Long, mode: BindingMode) -> Unit,
    onOpenModeSettings: (bindingGroupId: Long, source: InputSource) -> Unit,
    onAddModeShift: (ownerSource: InputSource) -> Unit,
    onRemoveModeShift: (modeShiftId: Long) -> Unit,
    onSetModeShiftTrigger: (modeShiftId: Long, triggerSource: InputSource?, triggerSubInput: String?) -> Unit,
    onOpenModeShiftInputEditor: (modeShiftId: Long, ownerSource: InputSource, groupInputKey: String, label: String) -> Unit,
    collapsedRows: List<String>,
    onToggleRowCollapsed: (key: String) -> Unit,
    onEditCommand: (bindingId: Long, current: BindingOutput, title: String) -> Unit,
    onAddActivator: (groupInputId: Long, type: ActivatorType) -> Unit,
    onRemoveActivator: (activatorId: Long) -> Unit,
    onSetActivatorType: (activatorId: Long, type: ActivatorType) -> Unit,
    onOpenActivatorSettings: (activatorId: Long, label: String) -> Unit,
    onAddCommand: (activatorId: Long) -> Unit,
    onRemoveCommand: (bindingId: Long) -> Unit,
    onAddInputRow: (groupInputId: Long, type: ActivatorType) -> Unit,
    onSetInputRowPressType: (bindingId: Long, type: ActivatorType) -> Unit,
    onSetInputRowLabel: (bindingId: Long, label: String) -> Unit,
    onDeleteInputRow: (bindingId: Long) -> Unit,
) {
    // Phase 6: base set renders the new Card/input-row UI. Layer (overlay) mode keeps the legacy
    // item-list rendering below (override ghosts + materialize-on-tap), unchanged.
    if (viewingLayer == null) {
        BaseModeContent(
            sectionId = sectionId,
            viewingSet = viewingSet,
            config = config,
            firstRowFocusRequester = firstRowFocusRequester,
            onSetBindingGroupMode = onSetBindingGroupMode,
            onOpenModeSettings = onOpenModeSettings,
            onAddModeShift = onAddModeShift,
            onRemoveModeShift = onRemoveModeShift,
            onSetModeShiftTrigger = onSetModeShiftTrigger,
            onEditCommand = onEditCommand,
            onAddInputRow = onAddInputRow,
            onSetPressType = onSetInputRowPressType,
            onSetLabel = onSetInputRowLabel,
            onDelete = onDeleteInputRow,
            onConfigure = onOpenActivatorSettings,
        )
        return
    }

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
                    is RemapPaneItem.BindingRow -> if (item.inputSource in RemapSections.OTHER_BUTTON_SOURCES && viewingLayer == null) {
                        OtherButtonRow(
                            item = item,
                            viewingSet = viewingSet,
                            config = config,
                            modifier = focusModifier,
                            onSetBindingGroupMode = onSetBindingGroupMode,
                            onEditCommand = onEditCommand,
                            onAddActivator = onAddActivator,
                            onRemoveActivator = onRemoveActivator,
                            onSetActivatorType = onSetActivatorType,
                            onOpenActivatorSettings = onOpenActivatorSettings,
                            onAddCommand = onAddCommand,
                            onRemoveCommand = onRemoveCommand,
                        )
                    } else {
                        BindingRowItem(
                            item = item,
                            viewingSet = viewingSet,
                            viewingLayer = viewingLayer,
                            config = config,
                            modifier = focusModifier,
                            collapsed = collapsedRows.contains(item.key),
                            onToggleCollapsed = { onToggleRowCollapsed(item.key) },
                            onEditCommand = onEditCommand,
                            onAddActivator = onAddActivator,
                            onRemoveActivator = onRemoveActivator,
                            onSetActivatorType = onSetActivatorType,
                            onOpenActivatorSettings = onOpenActivatorSettings,
                            onAddCommand = onAddCommand,
                            onRemoveCommand = onRemoveCommand,
                            onOpenInputEditor = onOpenInputEditor,
                            onClearOverride = onClearOverride,
                        )
                    }
                    is RemapPaneItem.DisabledRow -> DisabledRowItem(item)
                    is RemapPaneItem.ModeShiftHeader -> ModeShiftHeaderRow(
                        item = item,
                        viewingSet = viewingSet,
                        viewingLayer = viewingLayer,
                        onSetBindingGroupMode = onSetBindingGroupMode,
                        onRemoveModeShift = onRemoveModeShift,
                        onSetModeShiftTrigger = onSetModeShiftTrigger,
                        onOpenModeSettings = onOpenModeSettings,
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
    layer: com.mappo.data.model.steam.ActionLayerGraph,
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
 * currently-resolved [com.mappo.data.model.steam.BindingMode]. Walks [items]
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
    viewingSet: com.mappo.data.model.steam.ActionSetGraph?,
    viewingLayer: com.mappo.data.model.steam.ActionLayerGraph?,
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
                ?: com.mappo.data.model.steam.BindingMode.DEVICE_DEFAULT
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
    viewingSet: com.mappo.data.model.steam.ActionSetGraph?,
    viewingLayer: com.mappo.data.model.steam.ActionLayerGraph?,
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

// ════════════════════════════════════════════════════════════════════════════
// Phase 6: Card-based detail content (base set). Each input mode is a Card of always-visible
// "input rows"; each row = one command (Binding) bucketed under a press-type Activator.
// ════════════════════════════════════════════════════════════════════════════

/** Fixed width for the press-type button, sized to the widest label ("Double") for uniformity. */
private val PressButtonWidth = 84.dp

/** Press types offered in the press-type menu, in display order (SOFT_PRESS is a sub-input, not here). */
private val pressTypeOrder = listOf(
    ActivatorType.FULL_PRESS,
    ActivatorType.LONG_PRESS,
    ActivatorType.DOUBLE_PRESS,
    ActivatorType.START_PRESS,
    ActivatorType.RELEASE_PRESS,
    ActivatorType.CHORDED_PRESS,
)

/**
 * Short label for the press-type button. NB: START_PRESS reads "Down" and RELEASE_PRESS reads "Up"
 * (Mappo wording — fires on the down / up edge). VDF import/export must map Steam's "Start Press" ↔
 * "Down" and "Release Press" ↔ "Up". "Double" is the widest label — the press buttons are sized to it.
 */
private fun ActivatorType.shortLabel(): String = when (this) {
    ActivatorType.FULL_PRESS -> "Press"
    ActivatorType.LONG_PRESS -> "Long"
    ActivatorType.DOUBLE_PRESS -> "Double"
    ActivatorType.START_PRESS -> "Down"
    ActivatorType.RELEASE_PRESS -> "Up"
    ActivatorType.CHORDED_PRESS -> "Chord"
    ActivatorType.SOFT_PRESS -> "Soft"
}

private fun ActivatorType.helperText(): String = when (this) {
    ActivatorType.FULL_PRESS -> "Fires on a normal press."
    ActivatorType.LONG_PRESS -> "Fires when held past the long-press time."
    ActivatorType.DOUBLE_PRESS -> "Fires on two quick presses."
    ActivatorType.START_PRESS -> "Fires the instant the button goes down."
    ActivatorType.RELEASE_PRESS -> "Fires when the button is let go."
    ActivatorType.CHORDED_PRESS -> "Fires only while another button is held."
    ActivatorType.SOFT_PRESS -> "Fires on a soft (partial) pull."
}

private fun ActivatorType.pressIcon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    ActivatorType.FULL_PRESS -> Icons.Filled.TouchApp
    ActivatorType.LONG_PRESS -> Icons.Filled.Timer
    ActivatorType.DOUBLE_PRESS -> Icons.Filled.Repeat
    ActivatorType.START_PRESS -> Icons.Filled.Bolt
    ActivatorType.RELEASE_PRESS -> Icons.AutoMirrored.Filled.Logout
    ActivatorType.CHORDED_PRESS -> Icons.Filled.Link
    ActivatorType.SOFT_PRESS -> Icons.Filled.Adjust
}

/** Short identifier for an "Other buttons" source (glyphs are generic for these). */
private fun otherButtonName(source: InputSource): String = when (source) {
    InputSource.LEFT_BUMPER -> "L1"
    InputSource.RIGHT_BUMPER -> "R1"
    InputSource.SWITCH_START -> "Start"
    InputSource.SWITCH_SELECT -> "Select"
    else -> source.displayName()
}

/** A dotted/dashed outlined "+ <text>" button used for the add-input / add-mode affordances. */
@Composable
private fun DashedAddButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outline
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable { onClick() }
            .drawBehind {
                drawRoundRect(
                    color = color,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    ),
                )
            }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

/**
 * The shared kebab (vertical "more" button) used by both the input rows and the mode-card header —
 * a slim compact icon button with the 48dp min-interactive reservation suppressed so it packs
 * tightly against its neighbors. (Whole rows/cards remain comfortable tap targets.)
 */
@Composable
private fun RowKebab(onClick: () -> Unit, contentDescription: String = "Options") {
    CompositionLocalProvider(
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified,
    ) {
        CompactIconButton(
            icon = Icons.Filled.MoreHoriz,
            contentDescription = contentDescription,
            onClick = onClick,
            size = CompactButtonSize.Slim,
        )
    }
}

/** A `DropdownMenuItem` with a leading icon and two-line title + helper text. */
@Composable
private fun RichMenuItem(
    title: String,
    helper: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    DropdownMenuItem(
        enabled = enabled,
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint) },
        text = {
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(helper, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingIcon = if (selected) { { Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) } } else null,
        onClick = onClick,
    )
}

/**
 * One input row: leading glyph + (empty-by-default) user label, then a split button [designation |
 * press type] and an options kebab. The designation opens the command picker; the press-type side
 * opens a rich menu; the kebab offers Configure / Edit label / Delete.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun InputRow(
    source: InputSource,
    subInputKey: String,
    subInputLabel: String,
    binding: com.mappo.data.model.steam.Binding,
    pressType: ActivatorType,
    config: ControllerConfig?,
    canDelete: Boolean,
    onEditCommand: (bindingId: Long, current: BindingOutput, title: String) -> Unit,
    onSetPressType: (bindingId: Long, type: ActivatorType) -> Unit,
    onSetLabel: (bindingId: Long, label: String) -> Unit,
    onDelete: (bindingId: Long) -> Unit,
    onConfigure: (activatorId: Long, title: String) -> Unit,
    onAddAnother: () -> Unit,
) {
    val output = BindingOutput.fromEntity(binding.outputType, binding.args)
    val title = "$subInputLabel · ${pressType.displayLabel()}"
    var pressMenu by remember { mutableStateOf(false) }
    var kebab by remember { mutableStateOf(false) }
    var editingLabel by remember { mutableStateOf(false) }

    Row(
        // Left padding bumped (start=16) so glyphs aren't crammed against the card edge. No vertical
        // padding — the card body's spacedBy controls inter-row spacing uniformly.
        modifier = Modifier.fillMaxWidth().heightIn(min = InputRowMinHeight).padding(start = InputRowStartPadding, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        com.mappo.ui.glyph.InputGlyphs.SubInputGlyph(source, subInputKey)
        Text(
            text = binding.label.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Designation — compact filled button → command picker.
        CompactButton(onClick = { onEditCommand(binding.id, output, title) }, size = CompactButtonSize.Slim) {
            Text(
                text = output.displayLabel(config),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 104.dp),
            )
        }
        // Press type — compact tonal button → press-type menu. Fixed width sized to the widest
        // label ("Double") so every press button is uniform; no dropdown arrow.
        Box {
            CompactFilledTonalButton(
                onClick = { pressMenu = true },
                size = CompactButtonSize.Slim,
                modifier = Modifier.width(PressButtonWidth),
            ) {
                Text(pressType.shortLabel(), maxLines = 1)
            }
            DropdownMenu(expanded = pressMenu, onDismissRequest = { pressMenu = false }) {
                pressTypeOrder.forEach { t ->
                    RichMenuItem(
                        title = t.shortLabel(), helper = t.helperText(), icon = t.pressIcon(),
                        selected = t == pressType,
                        onClick = { pressMenu = false; if (t != pressType) onSetPressType(binding.id, t) },
                    )
                }
            }
        }
        // Options kebab — same compact slim icon button as the card header's, with the 48dp
        // min-interactive reservation suppressed so its gap to the press button matches the
        // designation↔press gap (uniform 8dp).
        Box {
            RowKebab(onClick = { kebab = true })
            DropdownMenu(expanded = kebab, onDismissRequest = { kebab = false }) {
                RichMenuItem(
                    title = "Configure input",
                    helper = "Cycle, turbo, long-press time, delays, chord.",
                    icon = Icons.Filled.Settings,
                    onClick = { kebab = false; onConfigure(binding.activatorId, title) },
                )
                RichMenuItem(
                    title = "Edit input label",
                    helper = "Give this input a name (optional).",
                    icon = Icons.Filled.Edit,
                    onClick = { kebab = false; editingLabel = true },
                )
                // "Add another <glyph> input" — the sub-input glyph stands in for the input name.
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    text = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Add another ", style = MaterialTheme.typography.bodyLarge)
                                com.mappo.ui.glyph.InputGlyphs.SubInputGlyph(source, subInputKey, size = 16.dp)
                                Text(" input", style = MaterialTheme.typography.bodyLarge)
                            }
                            Text("Bind another command to this input.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { kebab = false; onAddAnother() },
                )
                // Delete only when removable — no dead "can't remove the last input" entry.
                if (canDelete) {
                    RichMenuItem(
                        title = "Delete input",
                        helper = "Remove this input row.",
                        icon = Icons.Filled.Delete,
                        onClick = { kebab = false; onDelete(binding.id) },
                    )
                }
            }
        }
    }

    if (editingLabel) {
        InputLabelDialog(
            current = binding.label.orEmpty(),
            onConfirm = { onSetLabel(binding.id, it); editingLabel = false },
            onDismiss = { editingLabel = false },
        )
    }
}

/** Small dialog to edit an input row's label (no auto-IME-focus per UI doctrine). */
@Composable
private fun InputLabelDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Input label") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Label") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The input rows for one sub-input (e.g. A): one row per command across the sub-input's activators
 * (ordered by press type). A single inset divider trails the group (unless it's the card's last
 * group) so the whole group reads as one physical input, matching the bumper card's dividers.
 * "Add another …" lives in each row's kebab (no standalone button).
 */
@Composable
private fun SubInputGroup(
    source: InputSource,
    subInputKey: String,
    subInputLabel: String,
    groupInput: com.mappo.data.model.steam.GroupInputGraph?,
    config: ControllerConfig?,
    onEditCommand: (Long, BindingOutput, String) -> Unit,
    onAddInputRow: (groupInputId: Long, type: ActivatorType) -> Unit,
    onSetPressType: (Long, ActivatorType) -> Unit,
    onSetLabel: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onConfigure: (Long, String) -> Unit,
) {
    val rows = groupInput?.activators
        ?.sortedWith(activatorRenderOrder)
        ?.flatMap { ag -> ag.bindings.map { b -> b to ag.activator.type } }
        .orEmpty()
    val canDelete = rows.size > 1
    val connected = rows.size > 1
    // No dividers, no inter-row spacing — rows are flush; their fixed height supplies uniform air,
    // and the card body's edge padding matches it (see CardBodyGap). When an input has multiple
    // rows (added inputs), a thin vertical line links their glyphs to show they're one input.
    val connectorColor = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    Column(
        modifier = if (!connected) Modifier else Modifier.drawBehind {
            val rowH = InputRowMinHeight.toPx()
            val glyphHalf = with(density) { com.mappo.ui.glyph.InputGlyphs.GlyphSize.toPx() } / 2f
            val cx = with(density) { (InputRowStartPadding + com.mappo.ui.glyph.InputGlyphs.GlyphSize / 2).toPx() }
            val stroke = with(density) { 1.dp.toPx() }
            // One segment per adjacent glyph pair: glyph i's lower edge → glyph i+1's upper edge
            // (rows are flush, so the row pitch is just the row height).
            for (i in 0 until rows.size - 1) {
                val y1 = i * rowH + rowH / 2f + glyphHalf
                val y2 = (i + 1) * rowH + rowH / 2f - glyphHalf
                drawLine(
                    color = connectorColor,
                    start = Offset(cx, y1),
                    end = Offset(cx, y2),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        },
    ) {
        rows.forEach { (binding, type) ->
            InputRow(
                source = source,
                subInputKey = subInputKey,
                subInputLabel = subInputLabel,
                binding = binding,
                pressType = type,
                config = config,
                canDelete = canDelete,
                onEditCommand = onEditCommand,
                onSetPressType = onSetPressType,
                onSetLabel = onSetLabel,
                onDelete = onDelete,
                onConfigure = onConfigure,
                onAddAnother = { groupInput?.let { onAddInputRow(it.input.id, ActivatorType.FULL_PRESS) } },
            )
        }
    }
}

/** Uniform vertical gap inside a card: body edge padding, between-group spacing, and intra-group spacing. */
private val CardBodyGap = 6.dp
/** Input-row height; the mode-card header matches it so the header reads as a peer row. */
private val InputRowMinHeight = 48.dp
/** Leading inset of an input row's glyph (kept in sync with the connector-line geometry). */
private val InputRowStartPadding = 16.dp

/**
 * Banner image for an input mode header — a cropped vector that fills the header's top-left corner
 * (placeholder art for now). No clip: the [androidx.compose.material3.OutlinedCard] clips it to the
 * card's rounded top-start corner, and it bleeds flush to the card outline on the left/top and down
 * to the header divider.
 */
@Composable
private fun ModeBanner(mode: BindingMode, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(com.mappo.R.drawable.mode_banner_placeholder),
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = modifier.fillMaxHeight().width(72.dp),
    )
}

/**
 * One input mode as a Card. The header row carries a banner image + the mode name with a trailing
 * dropdown arrow (taps open a full-card-width mode menu that drops over the card) + a kebab
 * (Configure / Delete-if-added), with a full-width divider beneath. Body = the mode's sub-input
 * groups. `surfaceContainerLow` per the role table.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ModeCard(
    source: InputSource,
    groupGraph: com.mappo.data.model.steam.BindingGroupGraph,
    config: ControllerConfig?,
    validModes: List<BindingMode>,
    isAdded: Boolean,
    shift: SourceModeShiftGraph?,
    firstRowFocusRequester: FocusRequester?,
    onSetBindingGroupMode: (Long, BindingMode) -> Unit,
    onOpenModeSettings: (Long, InputSource) -> Unit,
    onRemoveModeShift: (Long) -> Unit,
    onSetModeShiftTrigger: (Long, InputSource?, String?) -> Unit,
    onEditCommand: (Long, BindingOutput, String) -> Unit,
    onAddInputRow: (Long, ActivatorType) -> Unit,
    onSetPressType: (Long, ActivatorType) -> Unit,
    onSetLabel: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onConfigure: (Long, String) -> Unit,
) {
    val group = groupGraph.group
    val mode = group.mode
    val modeName = mode.displayNameFor(source)
    var showAddedSettings by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var kebab by remember { mutableStateOf(false) }
    var cardWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val canChangeMode = validModes.size > 1

    // OutlinedCard — outlined variant grouping one input mode's inputs.
    androidx.compose.material3.OutlinedCard(
        modifier = Modifier.onGloballyPositioned { cardWidthPx = it.size.width },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Header row ──────────────────────────────────────────────────────
            // No padding on the row: the banner bleeds to the card edges; the title + kebab take
            // their own insets. Height is fixed so the banner fills it corner-to-divider.
            Box {
                Row(
                    modifier = Modifier.fillMaxWidth().height(InputRowMinHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModeBanner(mode)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(if (firstRowFocusRequester != null) Modifier.focusRequester(firstRowFocusRequester) else Modifier)
                            .clickable(enabled = canChangeMode) { modeMenu = true }
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                if (isAdded) "Added input mode" else "Input mode",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(modeName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        if (canChangeMode) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change input mode", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // end=12 matches the input rows' end padding so this kebab sits at the exact
                    // same x as the per-input kebabs below.
                    Box(modifier = Modifier.padding(end = 12.dp)) {
                        RowKebab(onClick = { kebab = true }, contentDescription = "Mode options")
                        DropdownMenu(expanded = kebab, onDismissRequest = { kebab = false }) {
                            RichMenuItem(
                                title = "Configure $modeName",
                                helper = if (isAdded) "Activation button + mode settings." else "Deadzones, curves, and other tuning.",
                                icon = Icons.Filled.Settings,
                                enabled = isAdded || SourceModeSettingsSchema.hasSettings(source, mode),
                                onClick = {
                                    kebab = false
                                    if (isAdded) showAddedSettings = true else onOpenModeSettings(group.id, source)
                                },
                            )
                            if (isAdded) {
                                RichMenuItem(
                                    title = "Delete $modeName",
                                    helper = "Remove this added input mode.",
                                    icon = Icons.Filled.Delete,
                                    onClick = { kebab = false; shift?.let { onRemoveModeShift(it.shift.id) } },
                                )
                            }
                        }
                    }
                }
                // Mode-selection menu: full card width, drops over the card.
                DropdownMenu(
                    expanded = modeMenu,
                    onDismissRequest = { modeMenu = false },
                    modifier = Modifier.width(with(density) { cardWidthPx.toDp() }),
                ) {
                    validModes.forEach { m ->
                        DropdownMenuItem(
                            leadingIcon = { Icon(com.mappo.ui.glyph.InputGlyphs.modeIcon(m), contentDescription = null) },
                            text = { Text(m.displayNameFor(source)) },
                            trailingIcon = if (m == mode) { { Icon(Icons.Filled.Check, contentDescription = null) } } else null,
                            onClick = { modeMenu = false; if (m != mode) onSetBindingGroupMode(group.id, m) },
                        )
                    }
                }
            }
            HorizontalDivider()

            if (isAdded && shift != null) {
                val src = shift.shift.triggerSource
                val sub = shift.shift.triggerSubInput
                val triggerLabel = if (src != null && sub != null) {
                    val match = RemapSections.TRIGGER_INPUT_CATALOG.firstOrNull { it.source == src && it.subInput == sub }
                    match?.let { "Active while holding ${it.label}" } ?: "Active while holding ${src.displayName()} / $sub"
                } else "No activation button — tap Configure to assign one"
                Text(
                    text = triggerLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (src == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // ── Body: sub-input groups for this mode ────────────────────────────
            // Rows are flush (no spacedBy); each row's fixed height centers its content, giving
            // symmetric air, and the body's edge padding (CardBodyGap) equals that air — so every
            // gap (header→first, row→row, group→group, last→bottom) reads as the same uniform span.
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = CardBodyGap)) {
                val subInputs = RemapSections.bindableSubInputsFor(source, mode)
                subInputs.forEach { (key, label) ->
                    SubInputGroup(
                        source = source,
                        subInputKey = key,
                        subInputLabel = label,
                        groupInput = groupGraph.inputByKey(key),
                        config = config,
                        onEditCommand = onEditCommand,
                        onAddInputRow = onAddInputRow,
                        onSetPressType = onSetPressType,
                        onSetLabel = onSetLabel,
                        onDelete = onDelete,
                        onConfigure = onConfigure,
                    )
                }
            }
        }
    }

    if (showAddedSettings && shift != null) {
        ModeShiftSettingsSheet(
            modeShiftId = shift.shift.id,
            modeBindingGroupId = group.id,
            ownerSource = source,
            mode = mode,
            currentTriggerSource = shift.shift.triggerSource,
            currentTriggerSubInput = shift.shift.triggerSubInput,
            onSetTrigger = onSetModeShiftTrigger,
            onOpenModeSettings = onOpenModeSettings,
            onDismiss = { showAddedSettings = false },
        )
    }
}

/**
 * Everything for one mode-aware source: its base-mode Card, an "Added mode" Card per mode shift,
 * and a dashed "+ Add additional input mode" button (for mode-shift-capable sources).
 */
@Composable
private fun SourceModeBlock(
    source: InputSource,
    viewingSet: com.mappo.data.model.steam.ActionSetGraph,
    config: ControllerConfig?,
    firstRowFocusRequester: FocusRequester?,
    onSetBindingGroupMode: (Long, BindingMode) -> Unit,
    onOpenModeSettings: (Long, InputSource) -> Unit,
    onAddModeShift: (InputSource) -> Unit,
    onRemoveModeShift: (Long) -> Unit,
    onSetModeShiftTrigger: (Long, InputSource?, String?) -> Unit,
    onEditCommand: (Long, BindingOutput, String) -> Unit,
    onAddInputRow: (Long, ActivatorType) -> Unit,
    onSetPressType: (Long, ActivatorType) -> Unit,
    onSetLabel: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onConfigure: (Long, String) -> Unit,
) {
    val baseGroup = viewingSet.presetFor(source)?.group ?: return
    val validModes = SourceModeCatalog.modesValidFor(source)
    // 12dp between the base card, added-mode cards, and the add button — matches the inter-card and
    // card-to-chrome padding so the whole stack breathes consistently.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModeCard(
            source = source, groupGraph = baseGroup, config = config, validModes = validModes,
            isAdded = false, shift = null, firstRowFocusRequester = firstRowFocusRequester,
            onSetBindingGroupMode = onSetBindingGroupMode, onOpenModeSettings = onOpenModeSettings,
            onRemoveModeShift = onRemoveModeShift, onSetModeShiftTrigger = onSetModeShiftTrigger,
            onEditCommand = onEditCommand, onAddInputRow = onAddInputRow, onSetPressType = onSetPressType,
            onSetLabel = onSetLabel, onDelete = onDelete, onConfigure = onConfigure,
        )
        viewingSet.modeShifts.filter { it.shift.ownerSource == source }.forEach { shift ->
            ModeCard(
                source = source, groupGraph = shift.group, config = config, validModes = validModes,
                isAdded = true, shift = shift, firstRowFocusRequester = null,
                onSetBindingGroupMode = onSetBindingGroupMode, onOpenModeSettings = onOpenModeSettings,
                onRemoveModeShift = onRemoveModeShift, onSetModeShiftTrigger = onSetModeShiftTrigger,
                onEditCommand = onEditCommand, onAddInputRow = onAddInputRow, onSetPressType = onSetPressType,
                onSetLabel = onSetLabel, onDelete = onDelete, onConfigure = onConfigure,
            )
        }
        if (source in RemapSections.MODE_SHIFT_OWNERS) {
            DashedAddButton(text = "Add input mode", onClick = { onAddModeShift(source) })
        }
    }
}

/**
 * The "Other buttons" Card (bumpers + Start/Select). Each is a single-button source rendered as an
 * ordinary input row — no switch UI. Binding a command auto-promotes the source to SINGLE_BUTTON
 * (digital remap, no Shizuku); leaving it unbound passes through to the system (the runtime treats
 * an all-Unbound SINGLE_BUTTON source as passthrough). Mode promotion/demotion is handled in the
 * repository on command change.
 */
@Composable
private fun OtherButtonsCard(
    sources: List<InputSource>,
    viewingSet: com.mappo.data.model.steam.ActionSetGraph,
    config: ControllerConfig?,
    onEditCommand: (Long, BindingOutput, String) -> Unit,
    onAddInputRow: (Long, ActivatorType) -> Unit,
    onSetPressType: (Long, ActivatorType) -> Unit,
    onSetLabel: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onConfigure: (Long, String) -> Unit,
) {
    // OutlinedCard — outlined variant grouping the single-button "other" inputs.
    androidx.compose.material3.OutlinedCard {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = CardBodyGap)) {
            sources.forEach { source ->
                val groupGraph = viewingSet.presetFor(source)?.group ?: return@forEach
                SubInputGroup(
                    source = source,
                    subInputKey = "click",
                    subInputLabel = otherButtonName(source),
                    groupInput = groupGraph.inputByKey("click"),
                    config = config,
                    onEditCommand = onEditCommand,
                    onAddInputRow = onAddInputRow,
                    onSetPressType = onSetPressType,
                    onSetLabel = onSetLabel,
                    onDelete = onDelete,
                    onConfigure = onConfigure,
                )
            }
        }
    }
}

/** Phase 6 base-set detail content: a scroll of per-source mode Cards + the Other-buttons card. */
@Composable
private fun BaseModeContent(
    sectionId: String,
    viewingSet: com.mappo.data.model.steam.ActionSetGraph?,
    config: ControllerConfig?,
    firstRowFocusRequester: FocusRequester,
    onSetBindingGroupMode: (Long, BindingMode) -> Unit,
    onOpenModeSettings: (Long, InputSource) -> Unit,
    onAddModeShift: (InputSource) -> Unit,
    onRemoveModeShift: (Long) -> Unit,
    onSetModeShiftTrigger: (Long, InputSource?, String?) -> Unit,
    onEditCommand: (Long, BindingOutput, String) -> Unit,
    onAddInputRow: (Long, ActivatorType) -> Unit,
    onSetPressType: (Long, ActivatorType) -> Unit,
    onSetLabel: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onConfigure: (Long, String) -> Unit,
) {
    if (viewingSet == null) {
        DetailPlaceholder(RemapSections.UNIMPLEMENTED_SECTION_PLACEHOLDER)
        return
    }
    val sources = RemapSections.sectionSources(sectionId)
    val modeSources = sources.filter { it !in RemapSections.OTHER_BUTTON_SOURCES }
    val otherButtons = sources.filter { it in RemapSections.OTHER_BUTTON_SOURCES }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        modeSources.forEachIndexed { index, source ->
            SourceModeBlock(
                source = source,
                viewingSet = viewingSet,
                config = config,
                firstRowFocusRequester = if (index == 0) firstRowFocusRequester else null,
                onSetBindingGroupMode = onSetBindingGroupMode,
                onOpenModeSettings = onOpenModeSettings,
                onAddModeShift = onAddModeShift,
                onRemoveModeShift = onRemoveModeShift,
                onSetModeShiftTrigger = onSetModeShiftTrigger,
                onEditCommand = onEditCommand,
                onAddInputRow = onAddInputRow,
                onSetPressType = onSetPressType,
                onSetLabel = onSetLabel,
                onDelete = onDelete,
                onConfigure = onConfigure,
            )
        }
        if (otherButtons.isNotEmpty()) {
            OtherButtonsCard(
                sources = otherButtons,
                viewingSet = viewingSet,
                config = config,
                onEditCommand = onEditCommand,
                onAddInputRow = onAddInputRow,
                onSetPressType = onSetPressType,
                onSetLabel = onSetLabel,
                onDelete = onDelete,
                onConfigure = onConfigure,
            )
        }
    }
}

@Composable
private fun SubheaderRow(
    item: RemapPaneItem.Subheader,
    viewingSet: com.mappo.data.model.steam.ActionSetGraph?,
    viewingLayer: com.mappo.data.model.steam.ActionLayerGraph?,
    onSetBindingGroupMode: (bindingGroupId: Long, mode: BindingMode) -> Unit,
    onOpenModeSettings: (bindingGroupId: Long, source: InputSource) -> Unit,
    onAddModeShift: (ownerSource: InputSource) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    ) {
        // Section header. titleSmall is the M3 section-header role; M3 has no dedicated Header
        // component, so a styled Text + leading source glyph is the conventional treatment.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                com.mappo.ui.glyph.InputGlyphs.sourceGlyph(item.inputSource),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        val source = item.inputSource
        if (source != null && viewingSet != null) {
            // Resolve the effective mode from (a) the viewing layer's override, falling back to
            // (b) the base set. Layer-mode-override editing isn't exposed yet — the dropdown is
            // read-only when a layer is being viewed and the source has no layer override.
            val setBindingGroup = viewingSet.presetFor(source)?.group?.group
            val layerBindingGroup = viewingLayer?.presetFor(source)?.group?.group
            val effectiveGroup = layerBindingGroup ?: setBindingGroup
            val validModes = SourceModeCatalog.modesValidFor(source)
            if (effectiveGroup != null && validModes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val pickerEnabled = viewingLayer == null && validModes.size > 1
                // Cog opens the settings editor for (source, mode). Base-set view only for now.
                val showCog = viewingLayer == null &&
                    SourceModeSettingsSchema.hasSettings(source, effectiveGroup.mode)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InputModeDropdown(
                        source = source,
                        currentMode = effectiveGroup.mode,
                        validModes = validModes,
                        enabled = pickerEnabled,
                        onPick = { mode -> onSetBindingGroupMode(effectiveGroup.id, mode) },
                        modifier = Modifier.weight(1f),
                    )
                    if (showCog) {
                        IconButton(onClick = { onOpenModeSettings(effectiveGroup.id, source) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Mode settings")
                        }
                    }
                    // "Add mode" (replaces "+ Mode Shift"): adds another mode instance for this
                    // source. Generalized across all mode-capable sources in a later phase.
                    if (source in RemapSections.MODE_SHIFT_OWNERS) {
                        TextButton(onClick = { onAddModeShift(source) }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add mode", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The "Input mode" dropdown — a compact M3 outlined-label field ([CompactDropdownField]) whose
 * field + menu rows carry shared mode glyphs ([InputGlyphs.modeIcon]). Replaces the old home-spun
 * pill picker. Mode names are source-aware (`displayNameFor`).
 */
@Composable
private fun InputModeDropdown(
    source: InputSource,
    currentMode: BindingMode,
    validModes: List<BindingMode>,
    enabled: Boolean,
    onPick: (BindingMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    com.mappo.ui.compact.CompactDropdownField(
        label = "Input mode",
        selectedText = currentMode.displayNameFor(source),
        enabled = enabled,
        modifier = modifier,
        // surface — the detail pane plane the field sits on (so the notch masks the right color).
        labelBackground = MaterialTheme.colorScheme.surface,
        selectedLeadingIcon = {
            Icon(
                com.mappo.ui.glyph.InputGlyphs.modeIcon(currentMode),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        menuContent = { dismiss ->
            validModes.forEach { mode ->
                com.mappo.ui.compact.CompactDropdownMenuItem(
                    text = mode.displayNameFor(source),
                    leadingIcon = { Icon(com.mappo.ui.glyph.InputGlyphs.modeIcon(mode), contentDescription = null) },
                    trailingIcon = if (mode == currentMode) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.padding(start = 8.dp)) }
                    } else null,
                    onClick = { dismiss(); if (mode != currentMode) onPick(mode) },
                )
            }
        },
    )
}

/**
 * An **added mode** block (formerly the "mode shift" header). Visually a sibling of the base mode
 * block ([SubheaderRow]): an "Added mode" overline, the same [InputModeDropdown] picking the added
 * mode's behavior, a ⚙ settings button (folds in both the activation trigger and the mode's own
 * settings — see [ModeShiftSettingsSheet]), and a 🗑 Delete-mode button. A trigger summary line
 * shows how the added mode activates.
 *
 * Resolves the backing [SourceModeShiftGraph] from the viewing context; renders nothing if it's
 * gone (e.g. just deleted) — its absence is the deletion signal.
 */
@Composable
private fun ModeShiftHeaderRow(
    item: RemapPaneItem.ModeShiftHeader,
    viewingSet: com.mappo.data.model.steam.ActionSetGraph?,
    viewingLayer: com.mappo.data.model.steam.ActionLayerGraph?,
    onSetBindingGroupMode: (bindingGroupId: Long, mode: BindingMode) -> Unit,
    onRemoveModeShift: (modeShiftId: Long) -> Unit,
    onSetModeShiftTrigger: (modeShiftId: Long, triggerSource: InputSource?, triggerSubInput: String?) -> Unit,
    onOpenModeSettings: (bindingGroupId: Long, source: InputSource) -> Unit,
) {
    val shifts = viewingLayer?.modeShifts ?: viewingSet?.modeShifts ?: return
    val shift = shifts.firstOrNull { it.shift.id == item.modeShiftId } ?: return
    var showSettings by remember { mutableStateOf(false) }
    val source = item.ownerSource
    val mode = shift.group.group.mode
    val validModes = SourceModeCatalog.modesValidFor(source)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
    ) {
        // Overline marking this as an additional mode for the same input.
        Text(
            text = "Added mode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            InputModeDropdown(
                source = source,
                currentMode = mode,
                validModes = validModes,
                enabled = validModes.size > 1,
                onPick = { picked -> onSetBindingGroupMode(shift.group.group.id, picked) },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "Mode settings")
            }
            // Delete mode — only on added modes (the base mode block has no delete).
            IconButton(onClick = { onRemoveModeShift(item.modeShiftId) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete mode")
            }
        }
        Spacer(Modifier.height(4.dp))
        // How this added mode activates.
        val triggerLabel = remember(shift.shift.triggerSource, shift.shift.triggerSubInput) {
            val src = shift.shift.triggerSource
            val sub = shift.shift.triggerSubInput
            if (src != null && sub != null) {
                val match = RemapSections.TRIGGER_INPUT_CATALOG.firstOrNull {
                    it.source == src && it.subInput == sub
                }
                match?.let { "Active while holding ${it.label}" } ?: "Active while holding ${src.displayName()} / $sub"
            } else {
                "No activation button — tap settings to assign one"
            }
        }
        Text(
            text = triggerLabel,
            style = MaterialTheme.typography.bodySmall,
            color = if (shift.shift.triggerSource == null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showSettings) {
        ModeShiftSettingsSheet(
            modeShiftId = item.modeShiftId,
            modeBindingGroupId = shift.group.group.id,
            ownerSource = source,
            mode = mode,
            currentTriggerSource = shift.shift.triggerSource,
            currentTriggerSubInput = shift.shift.triggerSubInput,
            onSetTrigger = onSetModeShiftTrigger,
            onOpenModeSettings = onOpenModeSettings,
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
 * `MappoRoute.inputEditor(..., modeShiftId=...)`).
 *
 * Pre-materialization: a fresh mode-shift target group has no GroupInput rows;
 * the row renders as Unbound until the user taps it (which materializes via
 * [com.mappo.data.repository.ControllerConfigRepository.materializeModeShiftInput]
 * before navigation). After binding, the row updates to show the assigned
 * output the next time the config is observed.
 */
@Composable
private fun ModeShiftBindingRowItem(
    item: RemapPaneItem.ModeShiftBindingRow,
    viewingSet: com.mappo.data.model.steam.ActionSetGraph?,
    viewingLayer: com.mappo.data.model.steam.ActionLayerGraph?,
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
    val primary = activators.firstOrNull { it.activator.type == com.mappo.data.model.steam.ActivatorType.FULL_PRESS }
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
        com.mappo.ui.glyph.InputGlyphs.SubInputGlyph(item.ownerSource, item.groupInputKey)
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
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

/**
 * A row in the "Other buttons" section (bumpers + Start/Select) — single-button switch sources
 * with no behavioral modes. Instead of a mode dropdown, a **passthrough toggle** flips the source
 * between `(Device default)` (passes through to the OS — Switch off) and `SINGLE_BUTTON` (Mappo
 * intercepts — Switch on). When intercepting, the inline activator/command editor appears; leaving
 * a command Unbound under intercept silences the button (the "None" case). Base set only.
 */
@Composable
private fun OtherButtonRow(
    item: RemapPaneItem.BindingRow,
    viewingSet: com.mappo.data.model.steam.ActionSetGraph?,
    config: ControllerConfig?,
    modifier: Modifier = Modifier,
    onSetBindingGroupMode: (bindingGroupId: Long, mode: BindingMode) -> Unit,
    onEditCommand: (bindingId: Long, current: BindingOutput, title: String) -> Unit,
    onAddActivator: (groupInputId: Long, type: ActivatorType) -> Unit,
    onRemoveActivator: (activatorId: Long) -> Unit,
    onSetActivatorType: (activatorId: Long, type: ActivatorType) -> Unit,
    onOpenActivatorSettings: (activatorId: Long, label: String) -> Unit,
    onAddCommand: (activatorId: Long) -> Unit,
    onRemoveCommand: (bindingId: Long) -> Unit,
) {
    val preset = viewingSet?.presetFor(item.inputSource)?.group ?: return
    val group = preset.group
    val groupInput = preset.inputByKey(item.groupInputKey)
    val intercept = group.mode == BindingMode.SINGLE_BUTTON
    val activators = groupInput?.activators.orEmpty().sortedWith(activatorRenderOrder)

    Column(modifier = modifier.fillMaxWidth()) {
        // Settings-row treatment: label + passthrough state + trailing Switch.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            com.mappo.ui.glyph.InputGlyphs.SubInputGlyph(item.inputSource, item.groupInputKey)
            Column(modifier = Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = if (intercept) "Intercepted by Mappo" else "Passes through to the system",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = intercept,
                onCheckedChange = { on ->
                    onSetBindingGroupMode(group.id, if (on) BindingMode.SINGLE_BUTTON else BindingMode.DEVICE_DEFAULT)
                },
            )
        }
        if (intercept) {
            activators.forEachIndexed { idx, graph ->
                val title = "${item.label} · ${graph.activator.type.displayLabel()}"
                ActivatorRow(
                    graph = graph,
                    config = config,
                    onTapCommand = { bindingId, current -> onEditCommand(bindingId, current, title) },
                    onChangeType = { newType -> onSetActivatorType(graph.activator.id, newType) },
                    onOpenSettings = { onOpenActivatorSettings(graph.activator.id, title) },
                    onRemoveActivator = { onRemoveActivator(graph.activator.id) },
                    onAddCommand = { onAddCommand(graph.activator.id) },
                    onRemoveCommand = onRemoveCommand,
                    canRemoveActivator = activators.size > 1,
                )
                if (idx < activators.size - 1) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            groupInput?.let { gi ->
                AddActivatorButton(onAdd = { type -> onAddActivator(gi.input.id, type) })
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun BindingRowItem(
    item: RemapPaneItem.BindingRow,
    viewingSet: com.mappo.data.model.steam.ActionSetGraph?,
    viewingLayer: com.mappo.data.model.steam.ActionLayerGraph?,
    config: ControllerConfig?,
    modifier: Modifier = Modifier,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onEditCommand: (bindingId: Long, current: BindingOutput, title: String) -> Unit,
    onAddActivator: (groupInputId: Long, type: ActivatorType) -> Unit,
    onRemoveActivator: (activatorId: Long) -> Unit,
    onSetActivatorType: (activatorId: Long, type: ActivatorType) -> Unit,
    onOpenActivatorSettings: (activatorId: Long, label: String) -> Unit,
    onAddCommand: (activatorId: Long) -> Unit,
    onRemoveCommand: (bindingId: Long) -> Unit,
    onOpenInputEditor: (inputSource: com.mappo.data.model.steam.InputSource, groupInputKey: String, label: String) -> Unit,
    onClearOverride: (inputSource: com.mappo.data.model.steam.InputSource, groupInputKey: String) -> Unit,
) {
    // Resolve the effective group input: layer override wins, else the base set's row.
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

    // Layer (overlay) mode keeps the tap-to-navigate flow: inline editing of a layer override
    // would need explicit materialization (a ghost row has no real group input to edit yet), so
    // Phase 2 scopes inline editing to the base set. Ghost = base binding shown dimmed.
    if (viewingLayer != null) {
        val contentAlpha = if (isGhost) 0.5f else 1f
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clickable { onOpenInputEditor(item.inputSource, item.groupInputKey, item.label) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            com.mappo.ui.glyph.InputGlyphs.SubInputGlyph(
                item.inputSource, item.groupInputKey, modifier = Modifier.alpha(contentAlpha),
            )
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).alpha(contentAlpha),
            )
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.alpha(contentAlpha)) {
                Text(
                    text = output.displayLabel(config),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (output == BindingOutput.Unbound) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                )
                if (extraCount > 0) {
                    Text("+$extraCount more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (hasOverride) {
                BindingRowOverflowMenu(onClearOverride = { onClearOverride(item.inputSource, item.groupInputKey) })
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        return
    }

    // Base mode: inline, expand-on-tap activator/command editor (default expanded). Collapsed
    // shows a "<primary command> +N more" summary; the chevron flips the state.
    val sortedActivators = activators.sortedWith(activatorRenderOrder)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            // `modifier` carries the cross-pane focus requester; it must sit on the focusable
            // (clickable) header so D-pad Right from the rail lands here.
            modifier = modifier
                .fillMaxWidth()
                .clickable { onToggleCollapsed() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            com.mappo.ui.glyph.InputGlyphs.SubInputGlyph(item.inputSource, item.groupInputKey)
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (collapsed) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = output.displayLabel(config),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (output == BindingOutput.Unbound) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                    )
                    if (extraCount > 0) {
                        Text("+$extraCount more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Icon(
                imageVector = if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = if (collapsed) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!collapsed) {
            sortedActivators.forEachIndexed { idx, graph ->
                val title = "${item.label} · ${graph.activator.type.displayLabel()}"
                ActivatorRow(
                    graph = graph,
                    config = config,
                    onTapCommand = { bindingId, current -> onEditCommand(bindingId, current, title) },
                    onChangeType = { newType -> onSetActivatorType(graph.activator.id, newType) },
                    onOpenSettings = { onOpenActivatorSettings(graph.activator.id, title) },
                    onRemoveActivator = { onRemoveActivator(graph.activator.id) },
                    onAddCommand = { onAddCommand(graph.activator.id) },
                    onRemoveCommand = onRemoveCommand,
                    canRemoveActivator = sortedActivators.size > 1,
                )
                if (idx < sortedActivators.size - 1) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            effectiveGroupInput?.let { gi ->
                AddActivatorButton(onAdd = { type -> onAddActivator(gi.input.id, type) })
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
    }
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
