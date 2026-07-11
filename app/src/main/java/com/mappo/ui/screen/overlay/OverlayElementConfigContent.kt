package com.mappo.ui.screen.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mappo.data.model.OverlayElement
import com.mappo.data.model.OverlayGesture
import com.mappo.data.model.RemapTarget
import com.mappo.data.model.displayLabel
import com.mappo.data.model.targetFor
import com.mappo.data.model.withTarget
import com.mappo.ui.component.ColorPicker
import kotlin.math.roundToInt
import com.mappo.ui.imeActivation
import com.mappo.ui.mappoKeyboardOptions

/**
 * Full per-button editor for the live overlay's config side-drawer (Brick D, "+ light
 * appearance"). Sections: label, commands (tap / double-tap / hold), geometry (size +
 * position), and appearance (shape / opacity / fill + text color). Instant-commit:
 * every change calls [onChange] with the updated element — the live button reflects it
 * immediately (WYSIWYG). The label field is not auto-focused (feedback_no_keyboard_autospawn).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverlayElementConfigContent(
    element: OverlayElement,
    onChange: (OverlayElement) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Draft seeded by element identity; edits mutate it + commit. (Sole editor while open.)
    var draft by remember(element.id) { mutableStateOf(element) }
    fun commit(updated: OverlayElement) {
        draft = updated
        onChange(updated)
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Edit button", style = MaterialTheme.typography.titleLarge)

        // ── Label ──
        OutlinedTextField(
            value = draft.label,
            onValueChange = { commit(draft.copy(label = it)) },
            label = { Text("Label (optional)") },
            singleLine = true,
            keyboardOptions = mappoKeyboardOptions(androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done)),
            modifier = Modifier.fillMaxWidth().imeActivation(),
        )

        // ── Commands ──
        HorizontalDivider()
        SectionLabel("Command")
        var gesture by remember { mutableStateOf(OverlayGesture.TAP) }
        val gestures = listOf(
            OverlayGesture.TAP to "Tap",
            OverlayGesture.DOUBLE_TAP to "Double-tap",
            OverlayGesture.HOLD to "Hold",
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            gestures.forEachIndexed { i, (g, label) ->
                SegmentedButton(
                    selected = gesture == g,
                    onClick = { gesture = g },
                    shape = SegmentedButtonDefaults.itemShape(i, gestures.size),
                ) { Text(label) }
            }
        }
        val current = draft.targetFor(gesture)
        Text(
            "Fires: ${current.displayLabel()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = current is RemapTarget.Unbound,
                onClick = { commit(draft.withTarget(gesture, RemapTarget.Unbound)) },
                label = { Text("None") },
            )
            OverlayCommonCommands.forEach { code ->
                val codeTarget = RemapTarget.fromCode(code)
                FilterChip(
                    selected = current == codeTarget,
                    onClick = { commit(draft.withTarget(gesture, codeTarget)) },
                    label = { Text(code) },
                )
            }
        }

        // ── Geometry ──
        HorizontalDivider()
        SectionLabel("Size & position")
        PercentSlider("Width", draft.width, 0.04f..1f) {
            commit(draft.copy(width = it, x = draft.x.coerceAtMost(1f - it)))
        }
        PercentSlider("Height", draft.height, 0.04f..1f) {
            commit(draft.copy(height = it, y = draft.y.coerceAtMost(1f - it)))
        }
        PercentSlider("X", draft.x, 0f..1f) {
            commit(draft.copy(x = it.coerceAtMost(1f - draft.width)))
        }
        PercentSlider("Y", draft.y, 0f..1f) {
            commit(draft.copy(y = it.coerceAtMost(1f - draft.height)))
        }

        // ── Appearance ──
        HorizontalDivider()
        SectionLabel("Appearance")
        val shapes = listOf(
            OverlayElement.SHAPE_ROUNDED to "Rounded",
            OverlayElement.SHAPE_CIRCLE to "Circle",
            OverlayElement.SHAPE_RECTANGLE to "Square",
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            shapes.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = draft.shape == value,
                    onClick = { commit(draft.copy(shape = value)) },
                    shape = SegmentedButtonDefaults.itemShape(i, shapes.size),
                ) { Text(label) }
            }
        }
        PercentSlider("Opacity", draft.opacity, 0.2f..1f) { commit(draft.copy(opacity = it)) }

        // One inline HSL picker, retargeted between fill and text by a toggle.
        var editingFill by remember { mutableStateOf(true) }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = editingFill,
                onClick = { editingFill = true },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Fill color") }
            SegmentedButton(
                selected = !editingFill,
                onClick = { editingFill = false },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Text color") }
        }
        val defaultFill = MaterialTheme.colorScheme.secondaryContainer
        val defaultText = MaterialTheme.colorScheme.onSecondaryContainer
        val shownColor = if (editingFill) {
            draft.fillColorArgb?.let { Color(it) } ?: defaultFill
        } else {
            draft.contentColorArgb?.let { Color(it) } ?: defaultText
        }
        ColorPicker(
            color = shownColor,
            onChange = { c ->
                commit(
                    if (editingFill) draft.copy(fillColorArgb = c.toArgb())
                    else draft.copy(contentColorArgb = c.toArgb()),
                )
            },
            onClearOverride = {
                commit(
                    if (editingFill) draft.copy(fillColorArgb = null)
                    else draft.copy(contentColorArgb = null),
                )
            },
            pickerKey = editingFill,
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Actions ──
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Text("  Delete")
            }
            FilledTonalButton(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PercentSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(
            "$label  ${(value * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/**
 * A small starter palette of common commands for the config chips, classified via
 * [RemapTarget.fromCode]. The full key/mouse/gamepad picker (`remapTargetPicker`) can be
 * wired in once the binding model migrates.
 */
val OverlayCommonCommands: List<String> = listOf(
    "ENTER", "ESCAPE", "SPACE", "TAB",
    "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT",
    "A", "B", "X", "Y",
    "MOUSE_LEFT", "MOUSE_RIGHT", "SCROLL_UP", "SCROLL_DOWN",
)
