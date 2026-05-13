package com.mapo.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/**
 * One toggleable color slot rendered as a master M3 list item plus three sub-list-items
 * (Color, Auto, Reset) revealed when [enabled] is true. Used by both ConfigureButtonScreen
 * and ConfigureKeyboardScreen so the two configure surfaces stay visually identical —
 * adjust this composable and both screens pick up the change.
 *
 * Layout:
 *
 *   [ <title>                                 ●─ ]   master row, tap-to-toggle
 *   [   Color           #FF8888              ▣  ]   sub-row, opens color picker
 *   [   Auto            <autoDescription>   [✓] ]   sub-row, toggles auto-derive
 *   [   Reset to default <resetDescription> ↻  ]   sub-row, resets this slot only
 *
 * Disabling the master switch preserves the slot's stored color + auto state so flipping
 * it back on restores the previous configuration. Sub-rows indent via `start = 24.dp` on
 * top of M3 ListItem's internal padding.
 */
@Composable
fun ColorSlotGroup(
    title: String,
    description: String,
    enabled: Boolean,
    isAuto: Boolean,
    resolvedColor: Color,
    onToggleEnabled: () -> Unit,
    onEditColor: () -> Unit,
    onToggleAuto: () -> Unit,
    onReset: () -> Unit,
    autoDescription: String = "Derive color from the theme and fill",
    resetDescription: String = "Restore the default settings for this slot",
) {
    Column {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(description) },
            trailingContent = {
                Switch(
                    checked = enabled,
                    // Parent ListItem (via the clickable modifier) owns the toggle so
                    // the whole row is tappable; null prevents the Switch from also
                    // competing for click handling.
                    onCheckedChange = null,
                )
            },
            modifier = Modifier.clickable { onToggleEnabled() },
        )
        if (enabled) {
            ListItem(
                headlineContent = { Text("Color") },
                supportingContent = { Text("#%08X".format(resolvedColor.toArgb())) },
                trailingContent = { ColorSlotSwatch(argb = resolvedColor.toArgb()) },
                modifier = Modifier
                    .padding(start = 24.dp)
                    .clickable { onEditColor() },
            )
            ListItem(
                headlineContent = { Text("Auto") },
                supportingContent = { Text(autoDescription) },
                trailingContent = {
                    Checkbox(
                        checked = isAuto,
                        onCheckedChange = null,
                    )
                },
                modifier = Modifier
                    .padding(start = 24.dp)
                    .clickable { onToggleAuto() },
            )
            ListItem(
                headlineContent = { Text("Reset to default") },
                supportingContent = { Text(resetDescription) },
                trailingContent = {
                    Icon(
                        Icons.Filled.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                },
                modifier = Modifier
                    .padding(start = 24.dp)
                    .clickable { onReset() },
            )
        }
    }
}

/**
 * Read-only swatch shown next to a slot's hex value. Outer chip is the same checker-board
 * baseplate the swatch uses elsewhere; inner rounded fill is the resolved color.
 */
@Composable
private fun ColorSlotSwatch(argb: Int?) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(Color(0xFFCCCCCC), RoundedCornerShape(6.dp))
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (argb != null) Color(argb) else Color.Transparent)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
        )
    }
}

/**
 * AlertDialog wrapping the [ColorPicker] component for use inside Configure* screens.
 * [onClear] (if provided) renders a "Clear" button that means "go back to auto" —
 * restoring the auto-derived color and clearing the stored manual choice.
 */
@Composable
fun ColorPickerInDialog(
    title: String,
    initial: Color,
    onConfirm: (Color) -> Unit,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            ColorPicker(
                color = picked,
                onChange = { picked = it },
                onClearOverride = onClear,
                pickerKey = title,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
