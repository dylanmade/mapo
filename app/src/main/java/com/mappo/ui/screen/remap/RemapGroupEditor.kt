package com.mappo.ui.screen.remap

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mappo.data.model.steam.ActionLayerGraph
import com.mappo.data.model.steam.ActionSetGraph
import com.mappo.data.model.steam.ActivatorType
import com.mappo.data.model.steam.Binding
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.BindingOutput
import com.mappo.data.model.steam.ControllerConfig
import com.mappo.data.model.steam.GroupInputGraph
import com.mappo.data.model.steam.displayLabel
import com.mappo.data.model.steam.displayNameFor
import com.mappo.data.model.steam.InputSource
import com.mappo.service.input.modes.SourceModeCatalog
import com.mappo.ui.compact.CompactTextField
import com.mappo.ui.glyph.InputGlyphs
import com.mappo.ui.screen.activatorRenderOrder
import com.mappo.ui.screen.displayLabel as activatorDisplayLabel
import com.mappo.ui.screen.remap.settings.SourceModeSettingsSchema

/**
 * The expanded ("advanced") in-place editor a group box grows into. Sticky header — mode pill
 * (overline text) · kebab · close — over an inset divider, then scrollable command rows:
 * press-type pill · input glyph · inline label field · output button · kebab. Multiple commands
 * on one input repeat the glyph per row (no connector links).
 *
 * Base-set view edits inline; layer view resolves override→base, renders read-only, and routes
 * output taps to the full-screen editor (which materializes the override); override rows get a
 * Clear-override kebab.
 */
internal class RemapGroupEditorCallbacks(
    val onSetBindingGroupMode: (bindingGroupId: Long, mode: BindingMode) -> Unit,
    val onOpenModeSettings: (bindingGroupId: Long, source: InputSource) -> Unit,
    val onEditCommand: (bindingId: Long, current: BindingOutput, title: String) -> Unit,
    val onOpenInputEditor: (inputSource: InputSource, groupInputKey: String, label: String) -> Unit,
    val onClearOverride: (inputSource: InputSource, groupInputKey: String) -> Unit,
    val onAddInputRow: (groupInputId: Long, type: ActivatorType) -> Unit,
    val onSetPressType: (bindingId: Long, type: ActivatorType) -> Unit,
    val onSetLabel: (bindingId: Long, label: String) -> Unit,
    val onDeleteRow: (bindingId: Long) -> Unit,
    val onConfigure: (activatorId: Long, title: String) -> Unit,
)

@Composable
internal fun RemapGroupEditor(
    group: RemapSimpleGroup,
    viewingSet: ActionSetGraph?,
    viewingLayer: ActionLayerGraph?,
    config: ControllerConfig?,
    callbacks: RemapGroupEditorCallbacks,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The header's mode pill edits the group's PRIMARY source (first row's source). Multi-source
    // groups (shoulder = trigger + bumper, utility = Start + Select) keep their secondary
    // sources' modes reachable through the rows' full-screen editor for now.
    val primarySource = group.rows.first().source
    val primaryGroup = viewingLayer?.presetFor(primarySource)?.group?.group
        ?: viewingSet?.presetFor(primarySource)?.group?.group
    val validModes = SourceModeCatalog.modesValidFor(primarySource)
    var headerKebab by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        // ── Sticky header ─────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = EditorHeaderHeight)
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (primaryGroup != null && validModes.isNotEmpty()) {
                ModePillDropdown(
                    source = primarySource,
                    currentMode = primaryGroup.mode,
                    validModes = validModes,
                    enabled = viewingLayer == null && validModes.size > 1,
                    onPick = { mode -> callbacks.onSetBindingGroupMode(primaryGroup.id, mode) },
                    overline = true,
                )
            } else {
                Text(
                    text = "DEFAULT",
                    style = remapOverlineTextStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Box {
                RowKebab(onClick = { headerKebab = true }, contentDescription = "Group options")
                DropdownMenu(expanded = headerKebab, onDismissRequest = { headerKebab = false }) {
                    RichMenuItem(
                        title = "Configure ${primaryGroup?.mode?.displayNameFor(primarySource) ?: "mode"}",
                        helper = "Deadzones, curves, and other tuning.",
                        icon = Icons.Filled.Settings,
                        enabled = primaryGroup != null &&
                            SourceModeSettingsSchema.hasSettings(primarySource, primaryGroup.mode),
                        onClick = {
                            headerKebab = false
                            primaryGroup?.let { callbacks.onOpenModeSettings(it.id, primarySource) }
                        },
                    )
                }
            }
            Spacer(Modifier.width(2.dp))
            RemapMiniIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "Close",
                onClick = onClose,
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 8.dp))

        // ── Command rows ──────────────────────────────────────────────────
        val editable = viewingLayer == null
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("group-editor-rows"),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            group.rows.forEach { spec ->
                val subLabel = RemapSections.labelFor(spec.source, spec.subInputKey)
                val layerGroupInput = viewingLayer?.presetFor(spec.source)?.group?.inputByKey(spec.subInputKey)
                val baseGroupInput = viewingSet?.presetFor(spec.source)?.group?.inputByKey(spec.subInputKey)
                val effective = layerGroupInput ?: baseGroupInput
                val rows = effective.commandRows()
                if (rows.isEmpty()) {
                    item(key = "${spec.source.name}.${spec.subInputKey}.empty") {
                        EditorCommandRow(
                            spec = spec,
                            pressType = ActivatorType.FULL_PRESS,
                            label = "",
                            outputLabel = BindingOutput.Unbound.displayLabel(config),
                            editable = false,
                            onTapOutput = {
                                callbacks.onOpenInputEditor(spec.source, spec.subInputKey, subLabel)
                            },
                            kebab = null,
                        )
                    }
                } else {
                    rows.forEachIndexed { idx, (binding, type) ->
                        item(key = "${spec.source.name}.${spec.subInputKey}.${binding.id}") {
                            val output = BindingOutput.fromEntity(binding.outputType, binding.args)
                            val title = "$subLabel · ${type.activatorDisplayLabel()}"
                            EditorCommandRow(
                                spec = spec,
                                pressType = type,
                                label = binding.label.orEmpty(),
                                outputLabel = output.displayLabel(config),
                                editable = editable,
                                onTapOutput = {
                                    if (editable) callbacks.onEditCommand(binding.id, output, title)
                                    else callbacks.onOpenInputEditor(spec.source, spec.subInputKey, subLabel)
                                },
                                onSetPressType = { t -> callbacks.onSetPressType(binding.id, t) },
                                onCommitLabel = { callbacks.onSetLabel(binding.id, it) },
                                kebab = when {
                                    editable -> EditorKebab.Editable(
                                        onConfigure = { callbacks.onConfigure(binding.activatorId, title) },
                                        onAddAnother = {
                                            effective?.let { callbacks.onAddInputRow(it.input.id, ActivatorType.FULL_PRESS) }
                                        },
                                        onDelete = if (rows.size > 1) {
                                            { callbacks.onDeleteRow(binding.id) }
                                        } else null,
                                    )
                                    layerGroupInput != null && idx == 0 -> EditorKebab.ClearOverride(
                                        onClear = { callbacks.onClearOverride(spec.source, spec.subInputKey) },
                                    )
                                    else -> null
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Resolve a group input into its display rows: one (binding, pressType) per command. */
private fun GroupInputGraph?.commandRows(): List<Pair<Binding, ActivatorType>> =
    this?.activators
        ?.sortedWith(activatorRenderOrder)
        ?.flatMap { ag -> ag.bindings.map { b -> b to ag.activator.type } }
        .orEmpty()

/** What the row's kebab offers. Null hides the kebab (a spacer keeps the grid aligned). */
private sealed interface EditorKebab {
    class Editable(
        val onConfigure: () -> Unit,
        val onAddAnother: () -> Unit,
        val onDelete: (() -> Unit)?,
    ) : EditorKebab

    class ClearOverride(val onClear: () -> Unit) : EditorKebab
}

/** One command row: press pill · glyph · label field · output button · kebab. */
@Composable
private fun EditorCommandRow(
    spec: SimpleRowSpec,
    pressType: ActivatorType,
    label: String,
    outputLabel: String,
    editable: Boolean,
    onTapOutput: () -> Unit,
    kebab: EditorKebab?,
    onSetPressType: ((ActivatorType) -> Unit)? = null,
    onCommitLabel: ((String) -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = EditorRowHeight)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        PressPillDropdown(
            current = pressType,
            enabled = editable && onSetPressType != null,
            onPick = { onSetPressType?.invoke(it) },
        )
        InputGlyphs.SubInputGlyph(spec.source, spec.subInputKey, size = 14.dp)
        LabelPillField(
            value = label,
            enabled = editable && onCommitLabel != null,
            onCommit = { onCommitLabel?.invoke(it) },
            modifier = Modifier.width(EditorLabelWidth),
        )
        Spacer(Modifier.weight(1f))
        RemapMiniPillButton(
            text = outputLabel,
            onClick = onTapOutput,
            filled = true,
            modifier = Modifier.widthIn(max = EditorOutputMaxWidth),
        )
        when (kebab) {
            null -> Spacer(Modifier.size(20.dp)) // kebab footprint, keeps rows aligned
            is EditorKebab.ClearOverride -> Box {
                var open by remember { mutableStateOf(false) }
                RowKebab(onClick = { open = true }, contentDescription = "Override actions")
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text("Clear override") },
                        onClick = { open = false; kebab.onClear() },
                    )
                }
            }
            is EditorKebab.Editable -> Box {
                var open by remember { mutableStateOf(false) }
                RowKebab(onClick = { open = true })
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    RichMenuItem(
                        title = "Configure input",
                        helper = "Cycle, turbo, long-press time, delays, chord.",
                        icon = Icons.Filled.Settings,
                        onClick = { open = false; kebab.onConfigure() },
                    )
                    RichMenuItem(
                        title = "Add another input",
                        helper = "Bind another command to this input.",
                        icon = Icons.Filled.Add,
                        onClick = { open = false; kebab.onAddAnother() },
                    )
                    kebab.onDelete?.let { delete ->
                        RichMenuItem(
                            title = "Delete input",
                            helper = "Remove this input row.",
                            icon = Icons.Filled.Delete,
                            onClick = { open = false; delete() },
                        )
                    }
                }
            }
        }
    }
}

/** The press-type pill dropdown — same chrome as [ModePillDropdown], press-type vocabulary. */
@Composable
private fun PressPillDropdown(
    current: ActivatorType,
    enabled: Boolean,
    onPick: (ActivatorType) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        // surfaceContainerHigh — pill-style dropdown button, matching the mode pills.
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .heightIn(min = RemapPillHeight)
                .then(if (enabled) Modifier.clickable { open = true } else Modifier.alpha(0.6f)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 3.dp),
            ) {
                Text(
                    text = current.shortLabel(),
                    style = remapMiniTextStyle(),
                    maxLines = 1,
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = "Change press type",
                    modifier = Modifier.size(13.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.outline,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            pressTypeOrder.forEach { t ->
                RichMenuItem(
                    title = t.shortLabel(),
                    helper = t.helperText(),
                    icon = t.pressIcon(),
                    selected = t == current,
                    onClick = { open = false; if (t != current) onPick(t) },
                )
            }
        }
    }
}

/**
 * Tap-to-edit label pill. Inline IME editing here proved unusable — the keyboard overlays the
 * bottom-half editor rows (per the app-wide "IME never moves layout" policy), hiding the field
 * being typed into. Instead the pill shows the label and opens a small edit dialog (the
 * rename-action-set pattern, the user's preferred tap-to-edit treatment); the dialog floats
 * clear of the keyboard.
 */
@Composable
private fun LabelPillField(
    value: String,
    enabled: Boolean,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    // surfaceContainerHigh — pill plane matching the dropdowns.
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .height(RemapPillHeight)
            .then(if (enabled) Modifier.clickable { editing = true } else Modifier.alpha(0.6f)),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = value.ifEmpty { "Label" },
                style = remapMiniTextStyle(),
                color = if (value.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (editing) {
        var text by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Input label") },
            text = {
                // CompactTextField carries the app-wide IME policy; no auto-focus (IME doctrine).
                CompactTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = "Label",
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { onCommit(text); editing = false }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("Cancel") }
            },
        )
    }
}

// ── Shared press-type vocabulary (moved from the retired detail-pane editor) ─────────────────

/** Press types offered in the press-type menu (SOFT_PRESS is a sub-input, not offered here). */
internal val pressTypeOrder = listOf(
    ActivatorType.FULL_PRESS,
    ActivatorType.LONG_PRESS,
    ActivatorType.DOUBLE_PRESS,
    ActivatorType.START_PRESS,
    ActivatorType.RELEASE_PRESS,
    ActivatorType.CHORDED_PRESS,
)

/**
 * Short label for the press-type pill. NB: START_PRESS reads "Down" and RELEASE_PRESS reads
 * "Up" (Mappo wording — fires on the down / up edge). VDF import/export must map Steam's
 * "Start Press" ↔ "Down" and "Release Press" ↔ "Up".
 */
internal fun ActivatorType.shortLabel(): String = when (this) {
    ActivatorType.FULL_PRESS -> "Press"
    ActivatorType.LONG_PRESS -> "Long"
    ActivatorType.DOUBLE_PRESS -> "Double"
    ActivatorType.START_PRESS -> "Down"
    ActivatorType.RELEASE_PRESS -> "Up"
    ActivatorType.CHORDED_PRESS -> "Chord"
    ActivatorType.SOFT_PRESS -> "Soft"
}

internal fun ActivatorType.helperText(): String = when (this) {
    ActivatorType.FULL_PRESS -> "Fires on a normal press."
    ActivatorType.LONG_PRESS -> "Fires when held past the long-press time."
    ActivatorType.DOUBLE_PRESS -> "Fires on two quick presses."
    ActivatorType.START_PRESS -> "Fires the instant the button goes down."
    ActivatorType.RELEASE_PRESS -> "Fires when the button is let go."
    ActivatorType.CHORDED_PRESS -> "Fires only while another button is held."
    ActivatorType.SOFT_PRESS -> "Fires on a soft (partial) pull."
}

internal fun ActivatorType.pressIcon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    ActivatorType.FULL_PRESS -> Icons.Filled.TouchApp
    ActivatorType.LONG_PRESS -> Icons.Filled.Timer
    ActivatorType.DOUBLE_PRESS -> Icons.Filled.Repeat
    ActivatorType.START_PRESS -> Icons.Filled.Bolt
    ActivatorType.RELEASE_PRESS -> Icons.AutoMirrored.Filled.Logout
    ActivatorType.CHORDED_PRESS -> Icons.Filled.Link
    ActivatorType.SOFT_PRESS -> Icons.Filled.Adjust
}

/** The shared kebab ("more" button) used by editor rows and the editor header. */
@Composable
internal fun RowKebab(onClick: () -> Unit, contentDescription: String = "Options") {
    RemapMiniIconButton(
        icon = Icons.Filled.MoreHoriz,
        contentDescription = contentDescription,
        onClick = onClick,
    )
}

/** A `DropdownMenuItem` with a leading icon and two-line title + helper text. */
@Composable
internal fun RichMenuItem(
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
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(helper, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else null,
        onClick = onClick,
    )
}

private val EditorHeaderHeight = 30.dp
private val EditorRowHeight = 26.dp
private val EditorLabelWidth = 64.dp
private val EditorOutputMaxWidth = 52.dp
