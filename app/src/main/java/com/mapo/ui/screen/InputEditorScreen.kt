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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.mapo.data.model.steam.ActivatorGraph
import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.data.model.steam.InputSource
import com.mapo.data.model.steam.displayLabel
import com.mapo.data.model.steam.findGroupInput

/**
 * Per-input activator editor. Reached from `RemapControlsScreen` by tapping any input row.
 *
 * One screen per `(inputSource, groupInputKey)` pair. The user sees every activator
 * configured on that input as its own row — `[type dropdown | ⚙ | ×]` on top, then a
 * list of command rows underneath (one per Binding), then `[+ Add Command]`. The whole
 * page ends with `[+ Add Activator]`.
 *
 * Per the user's M3 direction (multi-activator-per-input, Steam-faithful), the rows
 * sort by canonical activator order: Full / Soft / Long / Double / Start / Release / Chord.
 * Within the same type, by `orderIndex` (the user's insertion sequence).
 *
 * Brick 3.6 introduces the multi-command list per activator. The picker round-trip now
 * targets a specific bindingId (not activatorId) so each command row writes to its own
 * Binding row in the DB. Activators always have ≥1 command (enforced by hiding [×] on
 * the last one).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputEditorScreen(
    inputLabel: String,
    inputSource: InputSource,
    groupInputKey: String,
    config: ControllerConfig?,
    viewingActionSetId: Long? = null,
    viewingLayerId: Long? = null,
    /**
     * Phase 7 Brick B.6: when non-null, the editor resolves the binding group
     * via the named mode shift's target group instead of the source's preset.
     */
    modeShiftId: Long? = null,
    pickerResult: BindingOutput?,
    onConsumePickerResult: () -> Unit,
    onPickResult: (bindingId: Long, output: BindingOutput) -> Unit,
    onOpenPicker: (title: String, current: BindingOutput) -> Unit,
    onAddActivator: (groupInputId: Long, type: ActivatorType) -> Unit,
    onRemoveActivator: (activatorId: Long) -> Unit,
    onSetActivatorType: (activatorId: Long, type: ActivatorType) -> Unit,
    onOpenActivatorSettings: (activatorId: Long, label: String) -> Unit,
    onAddCommand: (activatorId: Long) -> Unit,
    onRemoveCommand: (bindingId: Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Survives the full-screen picker round-trip — picker pops back here and we apply
    // the result to whichever command was being edited.
    var editingBindingId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(pickerResult) {
        val output = pickerResult ?: return@LaunchedEffect
        val bindingId = editingBindingId
        if (bindingId != null) {
            onPickResult(bindingId, output)
        }
        editingBindingId = null
        onConsumePickerResult()
    }

    val groupInput = config?.findGroupInput(
        inputSource = inputSource,
        inputKey = groupInputKey,
        setId = viewingActionSetId,
        layerId = viewingLayerId,
        modeShiftId = modeShiftId,
    )
    val activators = groupInput?.activators.orEmpty().sortedWith(activatorRenderOrder)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(inputLabel) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (groupInput == null) {
            // Defensive: the route was reached for an input that isn't in the config
            // (e.g. a stale deep-link). Show a neutral placeholder rather than crashing.
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "This input isn't part of the active config.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            // ── Activator rows ─────────────────────────────────────────────────
            items(activators.size) { idx ->
                val graph = activators[idx]
                ActivatorRow(
                    graph = graph,
                    config = config,
                    onTapCommand = { bindingId, currentOutput ->
                        editingBindingId = bindingId
                        val title = "$inputLabel · ${graph.activator.type.displayLabel()}"
                        onOpenPicker(title, currentOutput)
                    },
                    onChangeType = { newType ->
                        onSetActivatorType(graph.activator.id, newType)
                    },
                    onOpenSettings = {
                        val label = "$inputLabel · ${graph.activator.type.displayLabel()}"
                        onOpenActivatorSettings(graph.activator.id, label)
                    },
                    onRemoveActivator = { onRemoveActivator(graph.activator.id) },
                    onAddCommand = { onAddCommand(graph.activator.id) },
                    onRemoveCommand = onRemoveCommand,
                    canRemoveActivator = activators.size > 1,
                )
                if (idx < activators.size - 1) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            // ── Add-activator action ───────────────────────────────────────────
            items(1) {
                Spacer(Modifier.height(16.dp))
                AddActivatorButton(
                    onAdd = { type -> onAddActivator(groupInput.input.id, type) },
                )
            }
        }
    }
}

/**
 * Order activator chips render in (and the picker dropdown lists in). SOFT_PRESS
 * is intentionally absent — Mapo expresses soft-pull behavior via the trigger's
 * dedicated `"soft_press"` sub-input row ("L2/R2 Soft Pull" in the UI), not via
 * an activator type. The enum value stays alive for VDF import only.
 */
private val ACTIVATOR_RENDER_ORDER = listOf(
    ActivatorType.FULL_PRESS,
    ActivatorType.LONG_PRESS,
    ActivatorType.DOUBLE_PRESS,
    ActivatorType.START_PRESS,
    ActivatorType.RELEASE_PRESS,
    ActivatorType.CHORDED_PRESS,
)

internal val activatorRenderOrder = compareBy<ActivatorGraph>(
    { ACTIVATOR_RENDER_ORDER.indexOf(it.activator.type).coerceAtLeast(0) },
    { it.activator.orderIndex },
    { it.activator.id },
)

/**
 * Display-name mapping for the activator-type dropdown. Matches Steam's labels with a
 * couple of small tweaks ("Regular Press" → "Regular" because it reads cleaner when
 * inline beside a key code).
 */
fun ActivatorType.displayLabel(): String = when (this) {
    ActivatorType.FULL_PRESS -> "Regular Press"
    ActivatorType.SOFT_PRESS -> "Soft Press"
    ActivatorType.LONG_PRESS -> "Long Press"
    ActivatorType.DOUBLE_PRESS -> "Double Press"
    ActivatorType.START_PRESS -> "Start Press"
    ActivatorType.RELEASE_PRESS -> "Release Press"
    ActivatorType.CHORDED_PRESS -> "Chord Press"
}

/**
 * Activators whose runtime impl hasn't landed yet. UI lets the user pick them so the
 * affordance is visible, but we surface a "Coming soon" hint.
 *
 * Empty as of Brick 5 (Phase 6) — SOFT_PRESS is now the first real analog activator,
 * routed via [TriggerMode][com.mapo.service.input.modes.TriggerMode]'s soft-threshold
 * detection on the focused motion-capture overlay. Re-populate if a later activator
 * type lands with deferred runtime.
 */
private val UNIMPLEMENTED_ACTIVATORS = emptySet<ActivatorType>()

@Composable
internal fun ActivatorRow(
    graph: ActivatorGraph,
    config: ControllerConfig?,
    onTapCommand: (bindingId: Long, current: BindingOutput) -> Unit,
    onChangeType: (ActivatorType) -> Unit,
    onOpenSettings: () -> Unit,
    onRemoveActivator: () -> Unit,
    onAddCommand: () -> Unit,
    onRemoveCommand: (bindingId: Long) -> Unit,
    canRemoveActivator: Boolean,
) {
    val unimplemented = graph.activator.type in UNIMPLEMENTED_ACTIVATORS
    val bindings = graph.bindings

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Type / settings / remove-activator row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActivatorTypeDropdown(
                selected = graph.activator.type,
                onSelected = onChangeType,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Activator settings")
            }
            IconButton(
                onClick = onRemoveActivator,
                enabled = canRemoveActivator,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Remove activator")
            }
        }

        // Commands list — one row per Binding. The activator always has ≥1 command (the
        // initial Unbound seed in addActivator); the [×] is only enabled when len > 1.
        bindings.forEach { binding ->
            val output = BindingOutput.fromEntity(binding.outputType, binding.args)
            CommandRow(
                output = output,
                config = config,
                unimplemented = unimplemented,
                canRemove = bindings.size > 1,
                onTap = { onTapCommand(binding.id, output) },
                onRemove = { onRemoveCommand(binding.id) },
            )
        }

        // [+ Add Command]
        TextButton(
            onClick = onAddCommand,
            enabled = !unimplemented,
            modifier = Modifier.padding(start = 4.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Add Command", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Single command row inside an activator. Tap the surface to open the picker for this
 * binding; tap the trailing [×] to remove it. The trailing icon button is only
 * interactive when removal is allowed (i.e., there's more than one command on the
 * activator) — at-least-one-command is an invariant maintained by [addActivator] and
 * the UI together.
 */
@Composable
private fun CommandRow(
    output: BindingOutput,
    config: ControllerConfig?,
    unimplemented: Boolean,
    canRemove: Boolean,
    onTap: () -> Unit,
    onRemove: () -> Unit,
) {
    // Settings-row treatment (row-doctrine #4): ListItem with the command label as
    // headline, helper as supporting text, and the remove [×] in the trailing slot.
    // Container retains the surfaceContainerHigh fill so it reads as a tappable card
    // distinct from the editor's surface plane behind it.
    ListItem(
        headlineContent = {
            Text(
                text = output.displayLabel(config),
                color = if (output == BindingOutput.Unbound)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
        },
        supportingContent = {
            Text(
                text = if (unimplemented)
                    "Runtime support coming soon — saved settings won't fire yet."
                else "Tap to choose what this command emits.",
            )
        },
        trailingContent = if (canRemove) {
            {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove command",
                        modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                    )
                }
            }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !unimplemented, onClick = onTap),
    )
}

@Composable
private fun ActivatorTypeDropdown(
    selected: ActivatorType,
    onSelected: (ActivatorType) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // surfaceContainerHigh — unified pill-dropdown container per row-doctrine memo.
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = { expanded = true },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected.displayLabel(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Pick activator type",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ACTIVATOR_RENDER_ORDER.forEach { type ->
                val unimplemented = type in UNIMPLEMENTED_ACTIVATORS
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(text = type.displayLabel())
                            if (unimplemented) {
                                Text(
                                    text = "Coming soon",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelected(type)
                    },
                )
            }
        }
    }
}

@Composable
internal fun AddActivatorButton(
    onAdd: (ActivatorType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        FilledTonalButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Add Activator", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ACTIVATOR_RENDER_ORDER.forEach { type ->
                val unimplemented = type in UNIMPLEMENTED_ACTIVATORS
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(text = type.displayLabel())
                            if (unimplemented) {
                                Text(
                                    text = "Coming soon",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onAdd(type)
                    },
                )
            }
        }
    }
}
