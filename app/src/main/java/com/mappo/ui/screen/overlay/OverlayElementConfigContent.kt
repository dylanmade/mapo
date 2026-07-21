package com.mappo.ui.screen.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mappo.data.model.OverlayElement
import com.mappo.data.model.OverlayGesture
import com.mappo.data.model.RemapTarget
import com.mappo.data.model.displayLabel
import com.mappo.data.model.overlay.AppearanceLayer
import com.mappo.data.model.overlay.CornerRadii
import com.mappo.data.model.overlay.ElementAppearance
import com.mappo.data.model.overlay.GradientStop
import com.mappo.data.model.overlay.LayerKind
import com.mappo.data.model.overlay.LayerPaint
import com.mappo.data.model.overlay.StrokeAlign
import com.mappo.data.model.overlay.StrokeGradientMode
import com.mappo.data.model.overlay.StrokeStyle
import com.mappo.data.model.overlay.decodeElementAppearance
import com.mappo.data.model.overlay.defaultFillLayer
import com.mappo.data.model.overlay.defaultStrokeLayer
import com.mappo.data.model.overlay.encode
import com.mappo.data.model.overlay.nextLayerId
import com.mappo.data.model.overlay.resolvedStops
import com.mappo.data.model.targetFor
import com.mappo.data.model.withTarget
import com.mappo.ui.component.ColorPicker
import com.mappo.ui.component.GradientEditor
import com.mappo.ui.component.colorpicker.ColorPickerButton
import com.mappo.ui.control.MappoIconButton
import com.mappo.ui.control.MappoPercentSlider
import com.mappo.ui.control.MappoPillButton
import com.mappo.ui.control.MappoPillDropdown
import com.mappo.ui.control.MappoSlider
import com.mappo.ui.control.mappoMiniTextStyle
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
            // Unbound means "fires nothing" here — overlay buttons have no device default.
            "Fires: ${if (current is RemapTarget.Unbound) "nothing" else current.displayLabel()}",
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
        MappoPercentSlider("Width", draft.width, valueRange = 0.04f..1f, onChange = {
            commit(draft.copy(width = it, x = draft.x.coerceAtMost(1f - it)))
        })
        MappoPercentSlider("Height", draft.height, valueRange = 0.04f..1f, onChange = {
            commit(draft.copy(height = it, y = draft.y.coerceAtMost(1f - it)))
        })
        MappoPercentSlider("X", draft.x, onChange = {
            commit(draft.copy(x = it.coerceAtMost(1f - draft.width)))
        })
        MappoPercentSlider("Y", draft.y, onChange = {
            commit(draft.copy(y = it.coerceAtMost(1f - draft.height)))
        })

        // ── Appearance (layered fills/strokes — see ElementAppearance) ──
        HorizontalDivider()
        SectionLabel("Appearance")
        MappoPercentSlider("Opacity", draft.opacity, valueRange = 0.2f..1f, onChange = {
            commit(draft.copy(opacity = it))
        })

        // Effective stack: the element's own layers, else a seed built from the legacy
        // light-appearance fields (one solid fill) so editing always starts from the
        // element's current look. First layer edit persists the stack to appearanceJson.
        val defaultFill = MaterialTheme.colorScheme.secondaryContainer
        val defaultText = MaterialTheme.colorScheme.onSecondaryContainer
        val appearance = remember(draft.appearanceJson, draft.shape, draft.fillColorArgb, defaultFill) {
            decodeElementAppearance(draft.appearanceJson) ?: legacyAppearance(draft, defaultFill)
        }
        fun commitAppearance(updated: ElementAppearance) =
            commit(draft.copy(appearanceJson = updated.encode()))
        fun updateLayer(updated: AppearanceLayer) = commitAppearance(
            appearance.copy(layers = appearance.layers.map { if (it.id == updated.id) updated else it }),
        )

        MappoPercentSlider("Corner radius", appearance.corners.average, onChange = {
            commitAppearance(appearance.copy(corners = CornerRadii.uniform(it)))
        })
        var perCorner by remember(element.id) { mutableStateOf(false) }
        MappoPillButton(
            text = if (perCorner) "Hide per-corner radii" else "Per-corner radii",
            onClick = { perCorner = !perCorner },
        )
        if (perCorner) {
            val c = appearance.corners
            MappoPercentSlider("Top left", c.topLeft, onChange = {
                commitAppearance(appearance.copy(corners = c.copy(topLeft = it)))
            })
            MappoPercentSlider("Top right", c.topRight, onChange = {
                commitAppearance(appearance.copy(corners = c.copy(topRight = it)))
            })
            MappoPercentSlider("Bottom left", c.bottomLeft, onChange = {
                commitAppearance(appearance.copy(corners = c.copy(bottomLeft = it)))
            })
            MappoPercentSlider("Bottom right", c.bottomRight, onChange = {
                commitAppearance(appearance.copy(corners = c.copy(bottomRight = it)))
            })
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SectionLabel("Layers")
            Spacer(Modifier.weight(1f))
            MappoPillButton(
                text = "+ Fill",
                onClick = {
                    commitAppearance(
                        appearance.copy(
                            layers = appearance.layers +
                                defaultFillLayer(nextLayerId(appearance.layers), defaultFill),
                        ),
                    )
                },
            )
            Spacer(Modifier.width(8.dp))
            MappoPillButton(
                text = "+ Stroke",
                onClick = {
                    commitAppearance(
                        appearance.copy(
                            layers = appearance.layers +
                                defaultStrokeLayer(nextLayerId(appearance.layers), Color.White),
                        ),
                    )
                },
            )
        }
        Text(
            "Layers stack bottom to top; tap one to edit it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var selectedLayerId by remember(element.id) { mutableStateOf<Long?>(null) }
        // Display top-first (like a layers panel); storage is bottom → top.
        appearance.layers.asReversed().forEach { layer ->
            val index = appearance.layers.indexOfFirst { it.id == layer.id }
            LayerRow(
                layer = layer,
                selected = selectedLayerId == layer.id,
                onClick = { selectedLayerId = if (selectedLayerId == layer.id) null else layer.id },
                canRaise = index < appearance.layers.lastIndex,
                canLower = index > 0,
                onRaise = { commitAppearance(appearance.copy(layers = appearance.layers.swapped(index, index + 1))) },
                onLower = { commitAppearance(appearance.copy(layers = appearance.layers.swapped(index, index - 1))) },
                onDelete = {
                    if (selectedLayerId == layer.id) selectedLayerId = null
                    commitAppearance(appearance.copy(layers = appearance.layers.filter { it.id != layer.id }))
                },
            )
            if (selectedLayerId == layer.id) {
                LayerControls(layer = layer, onChange = ::updateLayer)
            }
        }

        // ── Text color ──
        var pickingTextColor by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Text color",
                style = mappoMiniTextStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (draft.contentColorArgb != null) {
                MappoPillButton(text = "Reset", onClick = { commit(draft.copy(contentColorArgb = null)) })
                Spacer(Modifier.width(8.dp))
            }
            ColorPickerButton(
                color = draft.contentColorArgb?.let { Color(it) } ?: defaultText,
                onClick = { pickingTextColor = !pickingTextColor },
            )
        }
        if (pickingTextColor) {
            // INLINE picker — dialogs can't attach inside this overlay-hosted drawer.
            ColorPicker(
                color = draft.contentColorArgb?.let { Color(it) } ?: defaultText,
                onChange = { c -> commit(draft.copy(contentColorArgb = c.copy(alpha = 1f).toArgb())) },
                onClearOverride = { commit(draft.copy(contentColorArgb = null)) },
                pickerKey = "text-color",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ── Actions ──
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MappoPillButton(text = "Delete", onClick = onDelete)
            MappoPillButton(text = "Done", onClick = onDone, filled = true)
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

/** One row in the layers panel (top of the visual stack listed first). */
@Composable
private fun LayerRow(
    layer: AppearanceLayer,
    selected: Boolean,
    onClick: () -> Unit,
    canRaise: Boolean,
    canLower: Boolean,
    onRaise: () -> Unit,
    onLower: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            // secondaryContainer = "opened for editing" per the selection-state doctrine.
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 8.dp),
    ) {
        PaintSwatch(layer.paint)
        Spacer(Modifier.width(10.dp))
        Text(
            if (layer.kind == LayerKind.FILL) "Fill" else "Stroke",
            style = mappoMiniTextStyle(),
            modifier = Modifier.weight(1f),
        )
        MappoIconButton(Icons.Default.KeyboardArrowUp, "Move layer up", onClick = onRaise, enabled = canRaise)
        Spacer(Modifier.width(4.dp))
        MappoIconButton(Icons.Default.KeyboardArrowDown, "Move layer down", onClick = onLower, enabled = canLower)
        Spacer(Modifier.width(4.dp))
        MappoIconButton(Icons.Default.Delete, "Delete layer", onClick = onDelete)
    }
}

/** Tiny preview of a layer's paint — solid chip or the gradient ramp in miniature. */
@Composable
private fun PaintSwatch(paint: LayerPaint) {
    // surfaceVariant baseplate so transparent paint regions read as such.
    val base = MaterialTheme.colorScheme.surfaceVariant
    Box(
        Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(base)
            .drawBehind {
                when (paint) {
                    is LayerPaint.Solid -> drawRect(Color(paint.argb))
                    is LayerPaint.Gradient -> drawRect(
                        Brush.horizontalGradient(colorStops = paint.resolvedStops().toTypedArray()),
                    )
                }
            },
    )
}

/** Full control set for the selected layer. Instant-commit through [onChange]. */
@Composable
private fun LayerControls(layer: AppearanceLayer, onChange: (AppearanceLayer) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 8.dp),
    ) {
        val isGradient = layer.paint is LayerPaint.Gradient
        OptionPillRow(
            label = "Paint",
            options = listOf(false to "Solid", true to "Gradient"),
            selected = isGradient,
            onSelect = { wantGradient ->
                if (wantGradient == isGradient) return@OptionPillRow
                onChange(
                    layer.copy(
                        paint = if (wantGradient) {
                            val argb = (layer.paint as LayerPaint.Solid).argb
                            // Seed: same color fading out — visibly a gradient immediately.
                            LayerPaint.Gradient(
                                stops = listOf(
                                    GradientStop(position = 0f, argb = argb, opacity = 1f),
                                    GradientStop(position = 1f, argb = argb, opacity = 0f),
                                ),
                            )
                        } else {
                            val g = layer.paint as LayerPaint.Gradient
                            LayerPaint.Solid(g.stops.firstOrNull()?.argb ?: Color.White.toArgb())
                        },
                    ),
                )
            },
        )
        when (val paint = layer.paint) {
            is LayerPaint.Solid -> {
                var picking by remember(layer.id) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Color",
                        style = mappoMiniTextStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    ColorPickerButton(color = Color(paint.argb), onClick = { picking = !picking }, size = 28.dp)
                }
                if (picking) {
                    // INLINE picker, not MappoColorPickerDialog: this drawer lives in a
                    // TYPE_APPLICATION_OVERLAY window, where dialog composables can't attach.
                    ColorPicker(
                        color = Color(paint.argb),
                        onChange = { c -> onChange(layer.copy(paint = LayerPaint.Solid(c.copy(alpha = 1f).toArgb()))) },
                        onClearOverride = null,
                        pickerKey = "layer-${layer.id}",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            is LayerPaint.Gradient -> GradientEditor(
                gradient = paint,
                onChange = { onChange(layer.copy(paint = it)) },
                editorKey = layer.id,
                // An across-stroke gradient's direction IS the stroke — no angle to set.
                showAngle = !(layer.kind == LayerKind.STROKE && layer.strokeGradientMode == StrokeGradientMode.ACROSS),
            )
        }
        MappoPercentSlider("Layer opacity", layer.opacity, onChange = { onChange(layer.copy(opacity = it)) })

        if (layer.kind == LayerKind.STROKE) {
            MappoSlider(
                label = "Width",
                value = layer.strokeWidthDp,
                onChange = { onChange(layer.copy(strokeWidthDp = it)) },
                valueRange = 0.5f..24f,
                step = 0.5f,
                unitLabel = "dp",
            )
            OptionPillRow(
                label = "Alignment",
                options = listOf(
                    StrokeAlign.INSIDE to "Inside",
                    StrokeAlign.CENTER to "Center",
                    StrokeAlign.OUTSIDE to "Outside",
                ),
                selected = layer.strokeAlign,
                onSelect = { onChange(layer.copy(strokeAlign = it)) },
            )
            OptionPillRow(
                label = "Style",
                options = listOf(
                    StrokeStyle.SOLID to "Solid",
                    StrokeStyle.DASHED to "Dashed",
                    StrokeStyle.DOTTED to "Dotted",
                ),
                selected = layer.strokeStyle,
                onSelect = { onChange(layer.copy(strokeStyle = it)) },
            )
            if (isGradient) {
                OptionPillRow(
                    label = "Gradient",
                    options = listOf(
                        StrokeGradientMode.LINEAR to "Linear",
                        StrokeGradientMode.ACROSS to "Across stroke",
                    ),
                    selected = layer.strokeGradientMode,
                    onSelect = { onChange(layer.copy(strokeGradientMode = it)) },
                )
                Text(
                    "Linear runs across the whole shape; across stroke wraps the edge outer to inner",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MappoSlider(
                label = "Offset X",
                value = layer.offsetXDp,
                onChange = { onChange(layer.copy(offsetXDp = it)) },
                valueRange = -24f..24f,
                step = 0.5f,
                unitLabel = "dp",
            )
            MappoSlider(
                label = "Offset Y",
                value = layer.offsetYDp,
                onChange = { onChange(layer.copy(offsetYDp = it)) },
                valueRange = -24f..24f,
                step = 0.5f,
                unitLabel = "dp",
            )
        }
    }
}

/** Settings row in the control family: mini label left, pill dropdown of options right. */
@Composable
private fun <T> OptionPillRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = mappoMiniTextStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        val labels = options.toMap()
        MappoPillDropdown(
            current = selected,
            options = options.map { it.first },
            optionLabel = { labels[it] ?: it.toString() },
            onPick = onSelect,
        )
    }
}

/** The stack a pre-layer element starts editing from: its light-appearance look, as layers. */
private fun legacyAppearance(element: OverlayElement, defaultFill: Color): ElementAppearance =
    ElementAppearance(
        corners = CornerRadii.uniform(
            when (element.shape) {
                OverlayElement.SHAPE_CIRCLE -> 1f
                OverlayElement.SHAPE_RECTANGLE -> 0f
                else -> ElementAppearance.DEFAULT_CORNER_RADIUS
            },
        ),
        layers = listOf(
            defaultFillLayer(1L, element.fillColorArgb?.let { Color(it) } ?: defaultFill),
        ),
    )

private fun <T> List<T>.swapped(a: Int, b: Int): List<T> =
    toMutableList().also { val tmp = it[a]; it[a] = it[b]; it[b] = tmp }

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
