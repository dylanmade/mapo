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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Scaffold
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
import com.mapo.data.model.GridLayout
import com.mapo.ui.component.ColorPickerInDialog
import com.mapo.ui.component.ColorSlotGroup
import com.mapo.ui.components.SteppedSliderWithNumberInput
import com.mapo.ui.util.ResolvedLayoutColors
import com.mapo.ui.util.resolveAutoLayoutColors

/**
 * Full-screen instant-commit editor for one [GridLayout] (a keyboard). Mirrors
 * [ConfigureButtonScreen]'s shape: name + dimensions + four color slots
 * (fill, outline, bevel, shadow), plus a Reset button. Defaults: only fill is enabled,
 * so a brand-new keyboard paints exactly the M3 surface color — matching pre-refactor
 * visuals.
 *
 * The dimension sliders are the only fields that can produce a conflict (shrink that
 * would clip occupied cells). When [onTryResize] returns offending labels, this screen
 * opens an in-flight resize-conflict sub-dialog asking whether to auto-drop the
 * offending buttons; accept fires [onApplyResizeWithAutoFit].
 *
 * Color slots reuse the shared [ColorSlotGroup] from ConfigureButtonScreen so the two
 * configure surfaces stay visually identical — tweak the slot composable in one place
 * and both screens pick up the change.
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
    var editingSlot by remember { mutableStateOf<KeyboardColorSlot?>(null) }
    var pendingResize by remember { mutableStateOf<PendingResize?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    // Local name draft — committed via onUpdate on every keystroke (when non-blank), and
    // re-synced when the underlying layout name changes (e.g. after onReset).
    var nameDraft by remember(layout.id) { mutableStateOf(layout.name) }
    LaunchedEffect(layout.name) { nameDraft = layout.name }

    // Cols/rows reflect the live layout, EXCEPT while a resize conflict dialog is up:
    // then we render the user's attempted (escrow) value so the slider doesn't snap back
    // mid-question.
    val effectiveCols = pendingResize?.columns ?: layout.columns
    val effectiveRows = pendingResize?.rows ?: layout.rows

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
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Name ─────────────────────────────────────────────────────────────
            OutlinedTextField(
                value = nameDraft,
                onValueChange = { v ->
                    nameDraft = v
                    val trimmed = v.trim()
                    if (trimmed.isNotEmpty()) {
                        onUpdate(layout.copy(name = trimmed))
                    }
                },
                label = { Text("Name") },
                singleLine = true,
                trailingIcon = {
                    if (nameDraft.isNotEmpty()) {
                        IconButton(onClick = { nameDraft = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            // ── Size ─────────────────────────────────────────────────────────────
            Text(
                "Size",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SteppedSliderWithNumberInput(
                label = "Rows",
                value = effectiveRows,
                onValueChange = { newRows ->
                    val conflict = onTryResize(effectiveCols, newRows)
                    if (conflict != null) {
                        pendingResize = PendingResize(effectiveCols, newRows, conflict)
                    }
                },
                min = 1,
                max = 20,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SteppedSliderWithNumberInput(
                label = "Columns",
                value = effectiveCols,
                onValueChange = { newCols ->
                    val conflict = onTryResize(newCols, effectiveRows)
                    if (conflict != null) {
                        pendingResize = PendingResize(newCols, effectiveRows, conflict)
                    }
                },
                min = 1,
                max = 20,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            // ── Colors ───────────────────────────────────────────────────────────
            Text(
                "Colors",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            val resolved = resolveAutoLayoutColors(layout, themeFallback)
            KeyboardColorSlot.values().forEach { slot ->
                ColorSlotGroup(
                    title = slot.title,
                    description = slot.description,
                    enabled = slot.enabled(layout),
                    isAuto = slot.isAuto(layout),
                    resolvedColor = slot.resolvedColor(resolved),
                    onToggleEnabled = {
                        onUpdate(slot.setEnabled(layout, !slot.enabled(layout)))
                    },
                    onEditColor = { editingSlot = slot },
                    onToggleAuto = {
                        onUpdate(slot.setAuto(layout, !slot.isAuto(layout)))
                    },
                    onReset = { onUpdate(slot.reset(layout)) },
                )
            }

            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { confirmReset = true },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Icon(
                    Icons.Filled.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Reset Keyboard", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    editingSlot?.let { slot ->
        val resolved = resolveAutoLayoutColors(layout, themeFallback)
        ColorPickerInDialog(
            title = slot.title,
            initial = slot.resolvedColor(resolved),
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
    val defaultEnabled: Boolean,
) {
    Fill(
        title = "Keyboard fill",
        description = "Solid color filling the keyboard surface",
        defaultEnabled = true,
    ),
    Outline(
        title = "Keyboard outline",
        description = "Stroke around the keyboard edge",
        defaultEnabled = false,
    ),
    Bevel(
        title = "Keyboard bevel",
        description = "Darkened bottom edge for a 3D appearance",
        defaultEnabled = false,
    ),
    Shadow(
        title = "Keyboard shadow",
        description = "Soft drop shadow beneath the keyboard",
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
