package com.mapo.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.mapo.data.model.ButtonRegion
import com.mapo.data.model.GridButton
import com.mapo.data.model.GridLayout
import com.mapo.data.model.RegionPosition
import com.mapo.data.model.defaultButtonTemplate
import com.mapo.data.model.withDefaultButtonFrom
import com.mapo.ui.component.ColorPickerInDialog
import com.mapo.ui.component.ColorSlotGroup
import com.mapo.ui.components.TwoNumberInputRow
import com.mapo.ui.util.ResolvedLayoutColors
import com.mapo.ui.util.resolveAutoColors
import com.mapo.ui.util.resolveAutoLayoutColors

/**
 * Two-tab full-screen editor for a [GridLayout]. The Keyboard tab covers name, size,
 * and the keyboard's own surface colors. The Buttons tab covers the per-keyboard
 * default-button template — size, color slots, and regions — used to seed newly-added
 * buttons in this keyboard. Existing buttons are not affected by changes on the
 * Buttons tab; defaults are template-only.
 *
 * Keyboard-size changes use the same overflow-conflict flow as before. Default-button
 * width/height are constrained to the keyboard's current size; resizing the keyboard
 * smaller auto-clamps the defaults in the ViewModel and surfaces a toast — this UI
 * just renders the already-clamped values.
 *
 * The Buttons tab reuses ConfigureButtonScreen's [ColorSlot], [RegionRowItem], and
 * [RegionEditDialog] (exposed as `internal`) by synthesizing a [GridButton] template
 * from the layout via [defaultButtonTemplate] and writing it back via
 * [withDefaultButtonFrom]. Keeps the two surfaces visually identical for free.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigureKeyboardScreen(
    layout: GridLayout,
    themeFallback: Color,
    onUpdate: (GridLayout) -> Unit,
    onTryResize: (columns: Int, rows: Int) -> List<String>?,
    onApplyResizeWithAutoFit: (columns: Int, rows: Int) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var editingSlot by remember { mutableStateOf<KeyboardColorSlot?>(null) }
    var editingButtonsSlot by remember { mutableStateOf<ColorSlot?>(null) }
    var editingRegion by remember { mutableStateOf<RegionPosition?>(null) }
    var pendingResize by remember { mutableStateOf<PendingResize?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    var nameDraft by remember(layout.id) { mutableStateOf(layout.name) }
    LaunchedEffect(layout.name) { nameDraft = layout.name }

    // While a resize conflict dialog is up, render the user's attempted (escrow) value
    // so the input doesn't snap back mid-question.
    val effectiveCols = pendingResize?.columns ?: layout.columns
    val effectiveRows = pendingResize?.rows ?: layout.rows

    // Synthesized template GridButton — read by the Buttons tab to drive the same
    // composables that ConfigureButtonScreen uses, then written back into the layout.
    val template = layout.defaultButtonTemplate()
    // Color resolution for default-button swatches uses the keyboard's resolved fill
    // as the "background" — same input the renderer uses for real buttons in this layout.
    val resolvedLayoutColors = resolveAutoLayoutColors(layout, themeFallback)
    val buttonsTabContextColor = resolvedLayoutColors.fill

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configure Keyboard") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("Keyboard") },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("Buttons") },
                )
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (tab) {
                    0 -> KeyboardTab(
                        layout = layout,
                        resolvedLayoutColors = resolvedLayoutColors,
                        nameDraft = nameDraft,
                        onNameDraftChange = { nameDraft = it },
                        effectiveCols = effectiveCols,
                        effectiveRows = effectiveRows,
                        onUpdate = onUpdate,
                        onTryResize = onTryResize,
                        onPendingResize = { pendingResize = it },
                        onEditSlot = { editingSlot = it },
                        onResetRequested = { confirmReset = true },
                    )
                    else -> ButtonsTab(
                        layout = layout,
                        template = template,
                        contextColor = buttonsTabContextColor,
                        onUpdate = onUpdate,
                        onEditSlot = { editingButtonsSlot = it },
                        onEditRegion = { editingRegion = it },
                    )
                }
            }
        }
    }

    // ── Keyboard-tab color picker ────────────────────────────────────────────────
    editingSlot?.let { slot ->
        ColorPickerInDialog(
            title = slot.title,
            initial = slot.resolvedColor(resolvedLayoutColors),
            onConfirm = { color ->
                onUpdate(slot.setManualColor(layout, color))
                editingSlot = null
            },
            onClear = {
                onUpdate(slot.setAuto(layout, true).let { slot.clearColor(it) })
                editingSlot = null
            },
            onDismiss = { editingSlot = null },
        )
    }

    // ── Buttons-tab color picker (operates on the synthesized template) ─────────
    editingButtonsSlot?.let { slot ->
        val resolved = resolveAutoColors(template, buttonsTabContextColor)
        ColorPickerInDialog(
            title = slot.title,
            initial = slot.resolvedColor(resolved),
            onConfirm = { color ->
                onUpdate(layout.withDefaultButtonFrom(slot.setManualColor(template, color)))
                editingButtonsSlot = null
            },
            onClear = {
                val next = slot.setAuto(template, true).let { slot.clearColor(it) }
                onUpdate(layout.withDefaultButtonFrom(next))
                editingButtonsSlot = null
            },
            onDismiss = { editingButtonsSlot = null },
        )
    }

    // ── Buttons-tab region edit (operates on the synthesized template) ──────────
    editingRegion?.let { pos ->
        val current = template.regions[pos.name] ?: ButtonRegion(sizeSp = pos.defaultSizeSp())
        // No onTap to derive a placeholder from — the template has no behavior wired up.
        // Placeholder text is empty; user types whatever they want.
        RegionEditDialog(
            position = pos,
            initial = current,
            labelPlaceholder = "",
            onConfirm = { updated ->
                val nextRegions = template.regions.toMutableMap()
                if (updated.isRegionEmpty()) nextRegions.remove(pos.name) else nextRegions[pos.name] = updated
                onUpdate(layout.withDefaultButtonFrom(template.copy(regions = nextRegions)))
                editingRegion = null
            },
            onClear = {
                val nextRegions = template.regions.toMutableMap()
                nextRegions.remove(pos.name)
                onUpdate(layout.withDefaultButtonFrom(template.copy(regions = nextRegions)))
                editingRegion = null
            },
            onDismiss = { editingRegion = null },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset \"${layout.name}\"?") },
            text = {
                Text(
                    "Reverts this keyboard to its original configuration. " +
                        "Any name, size, button, or appearance changes you've made will be lost."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        onReset()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmReset = false },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text("Cancel") }
            },
        )
    }

    pendingResize?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingResize = null },
            title = { Text("Resize would clip buttons") },
            text = {
                Text(
                    "These buttons don't fit in a ${p.columns}×${p.rows} grid: " +
                        "${p.offendingLabels.joinToString(", ")}.\n\nDrop them and resize anyway?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onApplyResizeWithAutoFit(p.columns, p.rows)
                        pendingResize = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Drop & resize") }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingResize = null },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text("Cancel") }
            },
        )
    }
}

// ── Keyboard tab ─────────────────────────────────────────────────────────────

@Composable
private fun KeyboardTab(
    layout: GridLayout,
    resolvedLayoutColors: ResolvedLayoutColors,
    nameDraft: String,
    onNameDraftChange: (String) -> Unit,
    effectiveCols: Int,
    effectiveRows: Int,
    onUpdate: (GridLayout) -> Unit,
    onTryResize: (columns: Int, rows: Int) -> List<String>?,
    onPendingResize: (PendingResize) -> Unit,
    onEditSlot: (KeyboardColorSlot) -> Unit,
    onResetRequested: () -> Unit,
) {
    Spacer(Modifier.height(4.dp))

    OutlinedTextField(
        value = nameDraft,
        onValueChange = { v ->
            onNameDraftChange(v)
            val trimmed = v.trim()
            if (trimmed.isNotEmpty()) {
                onUpdate(layout.copy(name = trimmed))
            }
        },
        label = { Text("Name") },
        singleLine = true,
        trailingIcon = {
            if (nameDraft.isNotEmpty()) {
                IconButton(onClick = { onNameDraftChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )

    Text(
        "Size",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    TwoNumberInputRow(
        label = "Keyboard size",
        width = effectiveCols,
        height = effectiveRows,
        onChange = { newCols, newRows ->
            val conflict = onTryResize(newCols, newRows)
            if (conflict != null) {
                onPendingResize(PendingResize(newCols, newRows, conflict))
            }
        },
        minWidth = 1, maxWidth = 20,
        minHeight = 1, maxHeight = 20,
        modifier = Modifier.padding(horizontal = 16.dp),
    )

    Text(
        "Colors",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    KeyboardColorSlot.values().forEach { slot ->
        ColorSlotGroup(
            title = slot.title,
            description = slot.description,
            colorLabel = slot.colorLabel,
            enabled = slot.enabled(layout),
            isAuto = slot.isAuto(layout),
            resolvedColor = slot.resolvedColor(resolvedLayoutColors),
            onToggleEnabled = { onUpdate(slot.setEnabled(layout, !slot.enabled(layout))) },
            onEditColor = { onEditSlot(slot) },
            onToggleAuto = { onUpdate(slot.setAuto(layout, !slot.isAuto(layout))) },
            onReset = { onUpdate(slot.reset(layout)) },
        )
    }

    Spacer(Modifier.height(4.dp))
    OutlinedButton(
        onClick = onResetRequested,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("Reset Keyboard", style = MaterialTheme.typography.labelLarge)
    }
    Spacer(Modifier.height(8.dp))
}

// ── Buttons tab ──────────────────────────────────────────────────────────────

@Composable
private fun ButtonsTab(
    layout: GridLayout,
    template: GridButton,
    contextColor: Color,
    onUpdate: (GridLayout) -> Unit,
    onEditSlot: (ColorSlot) -> Unit,
    onEditRegion: (RegionPosition) -> Unit,
) {
    Spacer(Modifier.height(4.dp))

    // Default button size — first item, capped at the keyboard's current dimensions.
    Text(
        "Size",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    TwoNumberInputRow(
        label = "Default button size",
        width = template.colSpan,
        height = template.rowSpan,
        onChange = { newCs, newRs ->
            onUpdate(
                layout.withDefaultButtonFrom(
                    template.copy(colSpan = newCs, rowSpan = newRs)
                )
            )
        },
        minWidth = 1, maxWidth = layout.columns.coerceAtLeast(1),
        minHeight = 1, maxHeight = layout.rows.coerceAtLeast(1),
        modifier = Modifier.padding(horizontal = 16.dp),
    )

    Text(
        "Colors",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    val resolved = resolveAutoColors(template, contextColor)
    ColorSlot.values().forEach { slot ->
        ColorSlotGroup(
            title = slot.title,
            description = slot.description,
            colorLabel = slot.colorLabel,
            enabled = slot.enabled(template),
            isAuto = slot.isAuto(template),
            resolvedColor = slot.resolvedColor(resolved),
            onToggleEnabled = {
                onUpdate(layout.withDefaultButtonFrom(slot.setEnabled(template, !slot.enabled(template))))
            },
            onEditColor = { onEditSlot(slot) },
            onToggleAuto = {
                onUpdate(layout.withDefaultButtonFrom(slot.setAuto(template, !slot.isAuto(template))))
            },
            onReset = {
                onUpdate(layout.withDefaultButtonFrom(slot.reset(template)))
            },
        )
        // Match the Animation slot's Motion sub-row from ConfigureButtonScreen so the
        // Buttons-tab defaults expose the same control surface.
        if (slot == ColorSlot.Animation && template.animationEnabled) {
            MotionCheckboxRow(
                checked = template.animationMotionEnabled,
                bevelEnabled = template.bevelEnabled,
                onToggle = {
                    onUpdate(layout.withDefaultButtonFrom(
                        template.copy(animationMotionEnabled = !template.animationMotionEnabled)
                    ))
                },
            )
        }
    }

    Spacer(Modifier.height(4.dp))
    Text(
        "Regions",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    RegionPosition.values().forEach { pos ->
        RegionRowItem(
            position = pos,
            region = template.regions[pos.name],
            onTapPreview = "",
            onClick = { onEditRegion(pos) },
        )
    }

    Spacer(Modifier.height(4.dp))
    OutlinedButton(
        onClick = {
            // Reset only the default-button fields to the GridLayout-constructor defaults
            // (which themselves mirror GridButton's constructor defaults: fill+bevel+shadow
            // on, outline off, all auto, size 1×1, no regions). Existing buttons are not
            // touched — defaults are template-only.
            val fresh = GridLayout(name = "", columns = 1, rows = 1, buttons = emptyList())
            onUpdate(layout.withDefaultButtonFrom(fresh.defaultButtonTemplate()))
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("Reset Button Defaults", style = MaterialTheme.typography.labelLarge)
    }
    Spacer(Modifier.height(8.dp))
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun ButtonRegion.isRegionEmpty(): Boolean =
    icon == null && label == null && labelColorArgb == null && iconColorArgb == null

private data class PendingResize(
    val columns: Int,
    val rows: Int,
    val offendingLabels: List<String>,
)

/**
 * The four color slots on a [GridLayout]'s outer surface. Mirrors the structure of
 * `ColorSlot` in ConfigureButtonDialog.kt but operates on layouts instead of buttons.
 *
 * Default-enabled state mirrors [GridLayout]'s field defaults: only Fill on by default
 * so a brand-new keyboard paints the M3 surface and nothing else, matching pre-refactor
 * visuals.
 */
private enum class KeyboardColorSlot(
    val title: String,
    val description: String,
    val colorLabel: String,
    val defaultEnabled: Boolean,
) {
    Fill(
        title = "Keyboard fill",
        description = "Solid color filling the keyboard surface",
        colorLabel = "Keyboard fill color",
        defaultEnabled = true,
    ),
    Outline(
        title = "Keyboard outline",
        description = "Stroke around the keyboard edge",
        colorLabel = "Keyboard outline color",
        defaultEnabled = false,
    ),
    Bevel(
        title = "Keyboard bevel",
        description = "Darkened bottom edge for a 3D appearance",
        colorLabel = "Keyboard bevel color",
        defaultEnabled = false,
    ),
    Shadow(
        title = "Keyboard shadow",
        description = "Soft drop shadow beneath the keyboard",
        colorLabel = "Keyboard shadow color",
        defaultEnabled = false,
    );

    fun enabled(l: GridLayout): Boolean = when (this) {
        Fill    -> l.fillEnabled
        Outline -> l.outlineEnabled
        Bevel   -> l.bevelEnabled
        Shadow  -> l.shadowEnabled
    }

    fun isAuto(l: GridLayout): Boolean = when (this) {
        Fill    -> l.fillIsAuto
        Outline -> l.outlineIsAuto
        Bevel   -> l.bevelIsAuto
        Shadow  -> l.shadowIsAuto
    }

    fun setEnabled(l: GridLayout, v: Boolean): GridLayout = when (this) {
        Fill    -> l.copy(fillEnabled = v)
        Outline -> l.copy(outlineEnabled = v)
        Bevel   -> l.copy(bevelEnabled = v)
        Shadow  -> l.copy(shadowEnabled = v)
    }

    fun setAuto(l: GridLayout, v: Boolean): GridLayout = when (this) {
        Fill    -> l.copy(fillIsAuto = v)
        Outline -> l.copy(outlineIsAuto = v)
        Bevel   -> l.copy(bevelIsAuto = v)
        Shadow  -> l.copy(shadowIsAuto = v)
    }

    fun setManualColor(l: GridLayout, c: Color): GridLayout {
        val argb = c.toArgb()
        return when (this) {
            Fill    -> l.copy(fillColorArgb = argb, fillIsAuto = false)
            Outline -> l.copy(outlineColorArgb = argb, outlineIsAuto = false)
            Bevel   -> l.copy(bevelColorArgb = argb, bevelIsAuto = false)
            Shadow  -> l.copy(shadowColorArgb = argb, shadowIsAuto = false)
        }
    }

    fun clearColor(l: GridLayout): GridLayout = when (this) {
        Fill    -> l.copy(fillColorArgb = null)
        Outline -> l.copy(outlineColorArgb = null)
        Bevel   -> l.copy(bevelColorArgb = null)
        Shadow  -> l.copy(shadowColorArgb = null)
    }

    fun reset(l: GridLayout): GridLayout = when (this) {
        Fill    -> l.copy(fillEnabled = defaultEnabled, fillIsAuto = true, fillColorArgb = null)
        Outline -> l.copy(outlineEnabled = defaultEnabled, outlineIsAuto = true, outlineColorArgb = null)
        Bevel   -> l.copy(bevelEnabled = defaultEnabled, bevelIsAuto = true, bevelColorArgb = null)
        Shadow  -> l.copy(shadowEnabled = defaultEnabled, shadowIsAuto = true, shadowColorArgb = null)
    }

    fun resolvedColor(resolved: ResolvedLayoutColors): Color = when (this) {
        Fill    -> resolved.fill
        Outline -> resolved.outline
        Bevel   -> resolved.bevel
        Shadow  -> resolved.shadow
    }
}
