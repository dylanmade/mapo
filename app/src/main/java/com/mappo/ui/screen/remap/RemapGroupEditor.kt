package com.mappo.ui.screen.remap

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RestartAlt
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mappo.R
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
 * The expanded ("advanced") in-place editor a group box grows into. Sticky header — group
 * identity (overline glyph + label) · flow arrow · mode pill · configure/more/close — over an
 * inset divider, then scrollable command rows: input button (press type + glyph) · flow arrow ·
 * output button · label field · configure · more. A footer row offers "Add new input" /
 * "Import an input".
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
    // Press type has no direct control since the input button absorbed the press pill
    // (2026-07-12); retained for the Map flow, which will own press-type selection.
    val onSetPressType: (bindingId: Long, type: ActivatorType) -> Unit,
    val onSetLabel: (bindingId: Long, label: String) -> Unit,
    val onDeleteRow: (bindingId: Long) -> Unit,
    val onDuplicateRow: (bindingId: Long) -> Unit,
    val onResetRow: (bindingId: Long) -> Unit,
    val onResetGroup: (bindingGroupId: Long) -> Unit,
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
    // Landing spot for controller focus when the editor opens (and after a tap wipes focus):
    // the FIRST command row's input button when it exists and is enabled, falling back to the
    // always-present Close button. Directional focus can't step INTO an overlay from a focused
    // container that spatially contains everything, so the simple view requests focus here
    // explicitly.
    focusRequester: FocusRequester? = null,
) {
    // The header's mode pill edits the group's PRIMARY source (first row's source). Multi-source
    // groups (shoulder = trigger + bumper, utility = Start + Select) keep their secondary
    // sources' modes reachable through the rows' full-screen editor for now.
    val primarySource = group.rows.first().source
    val primaryGroup = viewingLayer?.presetFor(primarySource)?.group?.group
        ?: viewingSet?.presetFor(primarySource)?.group?.group
    val validModes = SourceModeCatalog.modesValidFor(primarySource)
    val modeName = primaryGroup?.mode?.displayNameFor(primarySource) ?: "mode"
    val editable = viewingLayer == null
    var headerMore by remember { mutableStateOf(false) }

    // Ephemeral blank rows appended by "Add new input": unassigned inputs (no glyph, default
    // Press). UI-sorting scaffolding — the data model has no unassigned-input owner yet, so
    // they live in local state until the Map flow can assign a button and persist them.
    var blankRows by remember(group, viewingSet?.actionSet?.id, viewingLayer?.layer?.id) {
        mutableIntStateOf(0)
    }

    // Default focus target: the first row's input button — but only when that button will
    // actually be focusable (enabled = editable, and empty seed rows render disabled). The
    // Close button is the fallback landing spot.
    val firstSpec = group.rows.first()
    val firstRowInput = viewingLayer?.presetFor(firstSpec.source)?.group?.inputByKey(firstSpec.subInputKey)
        ?: viewingSet?.presetFor(firstSpec.source)?.group?.inputByKey(firstSpec.subInputKey)
    val focusFirstRow = editable && firstRowInput.commandRows().isNotEmpty()

    // Escape hatch for d-pad UP from the top command row. The rows' LazyColumn is a focus
    // group whose bounds contain the rows — an unresolved UP search would pick the group
    // itself as "above" and re-enter it at its first focusable (the first row's input button),
    // trapping focus in the list. The top row instead routes UP explicitly to the header's
    // kebab; from the header, UP reaches the set/layer tabs normally.
    val headerFocus = remember { FocusRequester() }

    Column(modifier = modifier) {
        // ── Sticky header ─────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = EditorHeaderHeight)
                // Same horizontal inset as the command rows — the header's chrome must sit
                // flush with the rows' columns.
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Non-interactive group identity: hardware glyph + overline label. The Kenney
            // prompt is single-color, so it tints down to the overline treatment safely.
            Icon(
                InputGlyphs.sourcePainter(primarySource),
                contentDescription = null,
                modifier = Modifier.size(RemapPillIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(RemapGlyphLabelGap))
            Text(
                text = group.headerLabel().uppercase(),
                style = remapOverlineTextStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            EditorFlowArrow()
            Spacer(Modifier.width(8.dp))
            if (primaryGroup != null && validModes.isNotEmpty()) {
                ModePillDropdown(
                    source = primarySource,
                    currentMode = primaryGroup.mode,
                    validModes = validModes,
                    enabled = editable && validModes.size > 1,
                    onPick = { mode -> callbacks.onSetBindingGroupMode(primaryGroup.id, mode) },
                    overline = true,
                    elevated = true,
                )
            } else {
                Text(
                    text = "DEFAULT",
                    style = remapOverlineTextStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            RemapMiniIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = "Configure $modeName",
                onClick = { primaryGroup?.let { callbacks.onOpenModeSettings(it.id, primarySource) } },
                enabled = primaryGroup != null &&
                    SourceModeSettingsSchema.hasSettings(primarySource, primaryGroup.mode),
            )
            Box {
                RowKebab(
                    onClick = { headerMore = true },
                    contentDescription = "Group options",
                    modifier = Modifier.focusRequester(headerFocus),
                )
                DropdownMenu(expanded = headerMore, onDismissRequest = { headerMore = false }) {
                    RichMenuItem(
                        title = "Add new input",
                        helper = "Append a blank input row.",
                        icon = Icons.Filled.Add,
                        enabled = editable,
                        onClick = { headerMore = false; blankRows++ },
                    )
                    RichMenuItem(
                        title = "Import $modeName",
                        helper = "Bring in a mode and inputs from another profile.",
                        icon = Icons.Filled.Download,
                        // Future: profile import. Inert while the acquisition flow lands.
                        onClick = { headerMore = false },
                    )
                    RichMenuItem(
                        title = "Reset $modeName",
                        helper = "Return this group to its defaults.",
                        icon = Icons.Filled.RestartAlt,
                        enabled = editable && primaryGroup != null,
                        onClick = {
                            headerMore = false
                            primaryGroup?.let { callbacks.onResetGroup(it.id) }
                        },
                    )
                }
            }
            Spacer(Modifier.width(2.dp))
            RemapMiniIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "Close",
                onClick = onClose,
                modifier = if (focusRequester != null && !focusFirstRow) {
                    Modifier.focusRequester(focusRequester)
                } else Modifier,
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 8.dp))

        // ── Command rows ──────────────────────────────────────────────────
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
                            upFocus = headerFocus.takeIf { spec == firstSpec },
                            onTapOutput = {
                                callbacks.onOpenInputEditor(spec.source, spec.subInputKey, subLabel)
                            },
                            onConfigure = null,
                            menu = null,
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
                                inputFocusRequester = focusRequester.takeIf {
                                    focusFirstRow && spec == firstSpec && idx == 0
                                },
                                upFocus = headerFocus.takeIf { spec == firstSpec && idx == 0 },
                                onTapOutput = {
                                    if (editable) callbacks.onEditCommand(binding.id, output, title)
                                    else callbacks.onOpenInputEditor(spec.source, spec.subInputKey, subLabel)
                                },
                                onCommitLabel = { callbacks.onSetLabel(binding.id, it) },
                                onConfigure = if (editable) {
                                    { callbacks.onConfigure(binding.activatorId, title) }
                                } else null,
                                menu = when {
                                    editable -> EditorRowMenu.Editable(
                                        onAdd = {
                                            effective?.let { callbacks.onAddInputRow(it.input.id, ActivatorType.FULL_PRESS) }
                                        },
                                        onDuplicate = { callbacks.onDuplicateRow(binding.id) },
                                        onReset = { callbacks.onResetRow(binding.id) },
                                        // The last-input restriction is deliberately LIFTED:
                                        // deleting even the default row is how users null out
                                        // a button entirely.
                                        onDelete = { callbacks.onDeleteRow(binding.id) },
                                    )
                                    layerGroupInput != null && idx == 0 -> EditorRowMenu.ClearOverride(
                                        onClear = { callbacks.onClearOverride(spec.source, spec.subInputKey) },
                                    )
                                    else -> null
                                },
                            )
                        }
                    }
                }
            }
            // Ephemeral unassigned rows (see blankRows above).
            items(blankRows, key = { "blank-$it" }) {
                EditorCommandRow(
                    spec = null,
                    pressType = ActivatorType.FULL_PRESS,
                    label = "",
                    outputLabel = BindingOutput.Unbound.displayLabel(config),
                    editable = true,
                    onTapOutput = {}, // nothing to persist to until the Map flow assigns a button
                    onConfigure = null,
                    menu = EditorRowMenu.BlankRow(onDelete = { blankRows-- }),
                    onCommitLabel = {},
                )
            }
            if (editable) {
                item(key = "editor-footer") {
                    // Left-aligned on the rows' input-button column ("Import input" was cut
                    // 2026-07-13 — profile import returns with the acquisition flow).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = EditorRowHeight)
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EditorPillActionButton("Add input", Icons.Filled.Add) { blankRows++ }
                    }
                }
            }
        }
    }
}

/** Header identity label for a group. User-specified wording; Title Case is the deliberate
 *  exception to the sentence-case doctrine (hardware names read as proper nouns). */
internal fun RemapSimpleGroup.headerLabel(): String = when (this) {
    RemapSimpleGroup.LEFT_SHOULDER -> "Left Trigger"
    RemapSimpleGroup.LEFT_STICK -> "Left Joystick"
    RemapSimpleGroup.DPAD -> "Directional Pad"
    // "Button Pad" (not "Face Buttons") — matches the mode name of the same concept.
    RemapSimpleGroup.FACE -> "Button Pad"
    RemapSimpleGroup.RIGHT_SHOULDER -> "Right Trigger"
    RemapSimpleGroup.RIGHT_STICK -> "Right Joystick"
    RemapSimpleGroup.UTILITY -> "Utility Buttons"
}

/** Resolve a group input into its display rows: one (binding, pressType) per command. */
private fun GroupInputGraph?.commandRows(): List<Pair<Binding, ActivatorType>> =
    this?.activators
        ?.sortedWith(activatorRenderOrder)
        ?.flatMap { ag -> ag.bindings.map { b -> b to ag.activator.type } }
        .orEmpty()

/** What the row's More menu offers. Null hides the kebab (a spacer keeps the grid aligned). */
private sealed interface EditorRowMenu {
    class Editable(
        val onAdd: () -> Unit,
        val onDuplicate: () -> Unit,
        val onReset: () -> Unit,
        val onDelete: () -> Unit,
    ) : EditorRowMenu

    /** Ephemeral unassigned row — deletable only. */
    class BlankRow(val onDelete: () -> Unit) : EditorRowMenu

    class ClearOverride(val onClear: () -> Unit) : EditorRowMenu
}

/**
 * One command row: input button (press + glyph) · flow arrow · output button · label field ·
 * configure · more. The input and output buttons flex to content between a shared floor
 * ("Press" + a glyph — the glyph slot is reserved even for unassigned inputs) and a third of
 * the row; the label field takes whatever remains.
 */
@Composable
private fun EditorCommandRow(
    spec: SimpleRowSpec?,
    pressType: ActivatorType,
    label: String,
    outputLabel: String,
    editable: Boolean,
    onTapOutput: () -> Unit,
    onConfigure: (() -> Unit)?,
    menu: EditorRowMenu?,
    onCommitLabel: ((String) -> Unit)? = null,
    // Set on the FIRST row only: the editor's default controller-focus landing spot.
    inputFocusRequester: FocusRequester? = null,
    // Set on the FIRST row only: explicit d-pad UP destination (cascades to every control in
    // the row) — see the headerFocus comment in RemapGroupEditor.
    upFocus: FocusRequester? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val flexMax = maxWidth / 3
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = EditorRowHeight)
                .padding(horizontal = 8.dp)
                .then(
                    if (upFocus != null) Modifier.focusProperties { up = upFocus } else Modifier,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InputPillButton(
                pressType = pressType,
                spec = spec,
                enabled = editable,
                // Future home of the Map flow; inert while the UI settles.
                onClick = {},
                modifier = Modifier
                    .widthIn(min = EditorFlexPillMinWidth, max = flexMax)
                    .then(
                        if (inputFocusRequester != null) Modifier.focusRequester(inputFocusRequester)
                        else Modifier,
                    ),
            )
            // A hair of extra breathing room beyond the row's 6dp rhythm, both sides.
            EditorFlowArrow(Modifier.padding(horizontal = 2.dp))
            RemapMiniPillButton(
                text = outputLabel,
                onClick = onTapOutput,
                filled = true,
                elevated = true,
                modifier = Modifier.widthIn(min = EditorFlexPillMinWidth, max = flexMax),
            )
            LabelPillField(
                value = label,
                enabled = editable && onCommitLabel != null,
                onCommit = { onCommitLabel?.invoke(it) },
                modifier = Modifier.weight(1f).padding(start = EditorOutputLabelExtraGap),
            )
            // Trailing icon buttons sit ADJACENT (no gap), matching the header's cog+kebab —
            // the nested Row opts them out of the row's 6dp rhythm.
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemapMiniIconButton(
                    icon = Icons.Filled.Settings,
                    contentDescription = "Configure input",
                    onClick = onConfigure ?: {},
                    enabled = onConfigure != null,
                )
                RowKebabMenu(menu = menu, spec = spec)
            }
        }
    }
}

/** The row's kebab + its dropdown, by menu flavor; null keeps the footprint as a spacer. */
@Composable
private fun RowKebabMenu(menu: EditorRowMenu?, spec: SimpleRowSpec?) {
    when (menu) {
        null -> Spacer(Modifier.size(RemapIconButtonSize)) // kebab footprint, keeps rows aligned
        is EditorRowMenu.ClearOverride -> Box {
            var open by remember { mutableStateOf(false) }
            RowKebab(onClick = { open = true }, contentDescription = "Override actions")
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("Clear override") },
                    onClick = { open = false; menu.onClear() },
                )
            }
        }
        is EditorRowMenu.BlankRow -> Box {
            var open by remember { mutableStateOf(false) }
            RowKebab(onClick = { open = true })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                RichMenuItem(
                    title = "Delete input",
                    helper = "Remove this input row.",
                    icon = Icons.Filled.Delete,
                    onClick = { open = false; menu.onDelete() },
                )
            }
        }
        is EditorRowMenu.Editable -> Box {
            var open by remember { mutableStateOf(false) }
            RowKebab(onClick = { open = true })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                RichMenuItem(
                    title = "Add new input",
                    helper = "Bind another command to this input.",
                    icon = Icons.Filled.Add,
                    titleContent = { GlyphMenuTitle("Add new", spec) },
                    onClick = { open = false; menu.onAdd() },
                )
                RichMenuItem(
                    title = "Duplicate input",
                    helper = "Copy this command and its settings.",
                    icon = Icons.Filled.ContentCopy,
                    titleContent = { GlyphMenuTitle("Duplicate", spec) },
                    onClick = { open = false; menu.onDuplicate() },
                )
                RichMenuItem(
                    title = "Reset input",
                    helper = "Back to a default Press of this input.",
                    icon = Icons.Filled.RestartAlt,
                    titleContent = { GlyphMenuTitle("Reset", spec) },
                    onClick = { open = false; menu.onReset() },
                )
                RichMenuItem(
                    title = "Delete input",
                    helper = "Remove this row — even the last one, to null the button.",
                    icon = Icons.Filled.Delete,
                    titleContent = { GlyphMenuTitle("Delete", spec) },
                    onClick = { open = false; menu.onDelete() },
                )
            }
        }
    }
}

/**
 * The row's input button: press-type word + button glyph in one elevated pill. Eventually
 * routes into the Map flow; inert for now. The glyph slot is reserved even when [spec] is null
 * (an unassigned input) so button widths read consistently.
 */
@Composable
private fun InputPillButton(
    pressType: ActivatorType,
    spec: SimpleRowSpec?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(50),
        color = RemapElevatedContainer,
        border = remapBevelBorder(RemapElevatedContainer, RemapPillHeight / 2),
        modifier = modifier
            .remapInteractiveScale(interaction)
            .heightIn(min = RemapPillHeight)
            .then(
                if (enabled) {
                    Modifier.clip(RoundedCornerShape(50)).clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClickLabel = "Change input",
                    ) { onClick() }
                } else Modifier.alpha(0.6f),
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = RemapPillContentPadding),
        ) {
            Text(
                text = pressType.shortLabel(),
                style = remapMiniTextStyle(),
                maxLines = 1,
            )
            Spacer(Modifier.width(RemapGlyphLabelGap))
            if (spec != null) {
                InputGlyphs.SubInputGlyph(spec.source, spec.subInputKey, size = EditorGlyphSize)
            } else {
                Spacer(Modifier.size(EditorGlyphSize))
            }
        }
    }
}

/** Non-interactive input→output flow marker: a filled Lucide play triangle. */
@Composable
private fun EditorFlowArrow(modifier: Modifier = Modifier) {
    Icon(
        painterResource(R.drawable.lucide_play_filled),
        contentDescription = null,
        modifier = modifier.size(EditorFlowArrowSize),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Footer action in the SAME pill treatment as the rows' input buttons (elevated container +
 *  bevel), with a leading icon. */
@Composable
private fun EditorPillActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(50),
        color = RemapElevatedContainer,
        border = remapBevelBorder(RemapElevatedContainer, RemapPillHeight / 2),
        modifier = Modifier
            .remapInteractiveScale(interaction)
            .heightIn(min = RemapPillHeight)
            .clip(RoundedCornerShape(50))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = RemapPillContentPadding),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(RemapPillIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(RemapGlyphLabelGap))
            Text(
                text = text,
                style = remapMiniTextStyle(),
                maxLines = 1,
            )
        }
    }
}

/** Menu-item title with the row's button glyph inline: "Add new Ⓐ input". Falls back to plain
 *  text when the row has no assigned button. */
@Composable
private fun GlyphMenuTitle(prefix: String, spec: SimpleRowSpec?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$prefix ", style = MaterialTheme.typography.bodyLarge)
        if (spec != null) {
            InputGlyphs.SubInputGlyph(spec.source, spec.subInputKey, size = 15.dp)
            Text(" input", style = MaterialTheme.typography.bodyLarge)
        } else {
            Text("input", style = MaterialTheme.typography.bodyLarge)
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
    // Input-field well: darker than the editor card it sits on, and FLAT (no bevel) — it's a
    // field, not a button.
    val interaction = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(50),
        color = remapInputFieldContainer(),
        modifier = modifier
            .remapInteractiveScale(interaction)
            .height(RemapPillHeight)
            .then(
                if (enabled) {
                    Modifier.clip(RoundedCornerShape(50)).clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClickLabel = "Edit label",
                    ) { editing = true }
                } else Modifier.alpha(0.6f),
            ),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = RemapPillContentPadding),
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

/** Press types offered by the future Map flow (SOFT_PRESS is a sub-input, not offered here).
 *  The old per-row press pill retired 2026-07-12 when the input button absorbed it. */
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
internal fun RowKebab(
    onClick: () -> Unit,
    contentDescription: String = "Options",
    modifier: Modifier = Modifier,
) {
    RemapMiniIconButton(
        icon = Icons.Filled.MoreVert,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
    )
}

/** A `DropdownMenuItem` with a leading icon and two-line title + helper text. [titleContent]
 *  swaps in a custom title composable (e.g. an inline button glyph) — [title] still names the
 *  item for readers of the code. */
@Composable
internal fun RichMenuItem(
    title: String,
    helper: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    selected: Boolean = false,
    titleContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    DropdownMenuItem(
        enabled = enabled,
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint) },
        text = {
            Column {
                if (titleContent != null) {
                    titleContent()
                } else {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(helper, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else null,
        onClick = onClick,
    )
}

// Heights carry the rows' vertical breathing room (content is 24dp pills); +2dp each
// 2026-07-13 — the rows read cramped at 36/32.
private val EditorHeaderHeight = 38.dp
private val EditorRowHeight = 34.dp

/** GOVERNING VARIABLE for the input/output buttons' width floor (they flex from here up to a
 *  third of the row). Sized for "Press" plus a glyph, so even a glyphless unassigned input
 *  keeps the full footprint. */
private val EditorFlexPillMinWidth = 80.dp

/** Extra breathing room between the output button and the label field, on top of the row's
 *  6dp rhythm. */
private val EditorOutputLabelExtraGap = 2.dp

/** Button-glyph edge inside the input button. */
private val EditorGlyphSize = 17.dp

/** Edge of the filled-play flow arrows (header and rows). */
private val EditorFlowArrowSize = 10.dp
