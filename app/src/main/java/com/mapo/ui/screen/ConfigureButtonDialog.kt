package com.mapo.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.mapo.data.defaults.GridButtonDefaults
import com.mapo.data.model.ButtonRegion
import com.mapo.data.model.GridButton
import com.mapo.data.model.RegionPosition
import com.mapo.data.model.RemapTarget
import com.mapo.data.model.TrackpadGesture
import com.mapo.data.model.displayLabel
import com.mapo.data.model.isTrackpad
import com.mapo.data.model.onDoubleTapTarget
import com.mapo.data.model.onHoldTarget
import com.mapo.data.model.onTapTarget
import com.mapo.ui.component.ColorPicker
import com.mapo.ui.component.IconPickerDialog
import com.mapo.ui.component.MapoIcons
import com.mapo.ui.component.SizeDropdown

/**
 * Two-tab full-screen configuration screen for one [GridButton]. Behavior tab covers type,
 * gesture mappings, and trackpad sensitivity. Appearance tab covers fill/outline colors and
 * the nine drawable regions (CENTER + 8 edges). Reset buttons restore the per-type defaults
 * from [GridButtonDefaults].
 *
 * Edits commit immediately via [onUpdate] — there's no draft layer. The caller (MainScreen)
 * routes the update through the ViewModel's instant-commit `updateSelectedButton`. The
 * `autoDeriveLabel` wrap keeps `GridButton.label` in sync on every change so dialog text
 * elsewhere (Remove confirm, conflict labels) stays meaningful.
 *
 * Sub-dialogs (color picker, region edit, icon picker) stay as `AlertDialog`s because each is
 * a short-lived OK/Cancel pick-a-value sub-flow. The remap-target picker, on the other hand,
 * is its own Navigation destination — this screen invokes [onOpenPicker] to navigate there,
 * and observes [pickerResult] (provided by the caller from the destination's
 * `savedStateHandle`) to apply the choice back to whichever gesture was being edited.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigureButtonScreen(
    button: GridButton,
    pickerResult: RemapTarget?,
    onConsumePickerResult: () -> Unit,
    onUpdate: (GridButton) -> Unit,
    onOpenPicker: (title: String, current: RemapTarget) -> Unit,
    onBack: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    // Tracks which of the three trackpad gestures is being remapped while the picker
    // destination is active. Survives the navigation round-trip via NavBackStackEntry's
    // remembered state. `null` means "no edit in flight".
    var editingGesture by remember { mutableStateOf<TrackpadGesture?>(null) }
    var editingColor by remember { mutableStateOf<ColorRole?>(null) }
    var editingRegion by remember { mutableStateOf<RegionPosition?>(null) }

    val commit: (GridButton) -> Unit = { next -> onUpdate(autoDeriveLabel(next)) }

    // When the picker pops back with a result, apply it to whichever gesture is being edited
    // and clear the in-flight edit. Consume the saved-state entry so re-entering the screen
    // doesn't re-fire the same result.
    LaunchedEffect(pickerResult) {
        val target = pickerResult ?: return@LaunchedEffect
        val gesture = editingGesture
        if (gesture != null) {
            val updated = when (gesture) {
                TrackpadGesture.TAP        -> button.copy(onTap = target.encode())
                TrackpadGesture.DOUBLE_TAP -> button.copy(onDoubleTap = target.encode())
                TrackpadGesture.LONG_PRESS -> button.copy(onHold = target.encode())
            }
            commit(updated)
            editingGesture = null
        }
        onConsumePickerResult()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configure Button") },
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
                    text = { Text("Behavior") },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("Appearance") },
                )
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (tab) {
                    0 -> BehaviorTab(
                        draft = button,
                        onDraftChange = commit,
                        onEditGesture = { gesture ->
                            editingGesture = gesture
                            val current = when (gesture) {
                                TrackpadGesture.TAP        -> button.onTapTarget
                                TrackpadGesture.DOUBLE_TAP -> button.onDoubleTapTarget
                                TrackpadGesture.LONG_PRESS -> button.onHoldTarget
                            }
                            onOpenPicker(gesture.displayName, current)
                        },
                    )
                    else -> AppearanceTab(
                        draft = button,
                        onDraftChange = commit,
                        onEditColor = { editingColor = it },
                        onEditRegion = { editingRegion = it },
                    )
                }
            }
        }
    }

    editingColor?.let { role ->
        val current = role.read(button)
        ColorPickerInDialog(
            title = role.title,
            initial = current,
            onConfirm = { color ->
                commit(role.write(button, color))
                editingColor = null
            },
            onClear = if (role.canClear) {
                { commit(role.clear(button)); editingColor = null }
            } else null,
            onDismiss = { editingColor = null },
        )
    }

    editingRegion?.let { pos ->
        val current = button.regions[pos.name] ?: ButtonRegion(sizeSp = pos.defaultSizeSp())
        val placeholder = remember(button.onTap) { simpleLabel(button.onTapTarget) }
        RegionEditDialog(
            position = pos,
            initial = current,
            labelPlaceholder = placeholder,
            onConfirm = { updated ->
                val next = button.regions.toMutableMap()
                if (updated.isEmpty()) next.remove(pos.name) else next[pos.name] = updated
                commit(button.copy(regions = next))
                editingRegion = null
            },
            onClear = {
                val next = button.regions.toMutableMap()
                next.remove(pos.name)
                commit(button.copy(regions = next))
                editingRegion = null
            },
            onDismiss = { editingRegion = null },
        )
    }
}

// ── Behavior tab ──────────────────────────────────────────────────────────────

@Composable
private fun BehaviorTab(
    draft: GridButton,
    onDraftChange: (GridButton) -> Unit,
    onEditGesture: (TrackpadGesture) -> Unit,
) {
    TypeToggle(
        isTrackpad = draft.isTrackpad,
        onChange = { isTrackpad ->
            onDraftChange(draft.copy(type = if (isTrackpad) "trackpad" else "key"))
        },
    )

    GestureRow(
        label = TrackpadGesture.TAP.displayName,
        target = draft.onTapTarget,
        onClick = { onEditGesture(TrackpadGesture.TAP) },
    )
    GestureRow(
        label = TrackpadGesture.DOUBLE_TAP.displayName,
        target = draft.onDoubleTapTarget,
        onClick = { onEditGesture(TrackpadGesture.DOUBLE_TAP) },
    )
    GestureRow(
        label = TrackpadGesture.LONG_PRESS.displayName,
        target = draft.onHoldTarget,
        onClick = { onEditGesture(TrackpadGesture.LONG_PRESS) },
    )

    if (draft.isTrackpad) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Sensitivity",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(110.dp),
            )
            val sens = draft.sensitivity ?: GridButtonDefaults.TRACKPAD_SENSITIVITY
            Slider(
                value = sens,
                onValueChange = { onDraftChange(draft.copy(sensitivity = it)) },
                valueRange = 0.5f..4.0f,
                modifier = Modifier.weight(1f),
            )
            Text(
                "%.1f×".format(sens),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(40.dp),
            )
        }
    }

    Spacer(Modifier.height(4.dp))
    ResetButton(
        label = "Reset Behavior",
        onClick = {
            val preset = GridButtonDefaults.behaviorFor(draft.type)
            onDraftChange(preset.apply(draft))
        },
    )
}

// ── Appearance tab ────────────────────────────────────────────────────────────

@Composable
private fun AppearanceTab(
    draft: GridButton,
    onDraftChange: (GridButton) -> Unit,
    onEditColor: (ColorRole) -> Unit,
    onEditRegion: (RegionPosition) -> Unit,
) {
    ColorRow(
        label = "Button fill",
        argb = draft.fillColorArgb,
        onClick = { onEditColor(ColorRole.Fill) },
    )
    ColorRow(
        label = "Button outline",
        argb = draft.outlineColorArgb,
        onClick = { onEditColor(ColorRole.Outline) },
    )

    Spacer(Modifier.height(4.dp))
    Text(
        "Regions",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    RegionPosition.values().forEach { pos ->
        RegionRowItem(
            position = pos,
            region = draft.regions[pos.name],
            onTapPreview = simpleLabel(draft.onTapTarget),
            onClick = { onEditRegion(pos) },
        )
    }

    Spacer(Modifier.height(4.dp))
    ResetButton(
        label = "Reset Appearance",
        onClick = {
            val preset = GridButtonDefaults.appearanceFor(draft.type)
            onDraftChange(preset.apply(draft))
        },
    )
}

// ── Sub-rows ──────────────────────────────────────────────────────────────────

@Composable
private fun TypeToggle(isTrackpad: Boolean, onChange: (Boolean) -> Unit) {
    val options = listOf("Button" to false, "Trackpad" to true)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (label, trackpad) ->
            SegmentedButton(
                selected = isTrackpad == trackpad,
                onClick = { onChange(trackpad) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun GestureRow(label: String, target: RemapTarget, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(110.dp),
        )
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                target.displayLabel(),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ColorRow(label: String, argb: Int?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(110.dp),
        )
        ColorSwatch(argb = argb)
        Spacer(Modifier.weight(1f))
        Text(
            text = if (argb == null) "Theme default" else "#%08X".format(argb),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColorSwatch(argb: Int?) {
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

@Composable
private fun RegionRowItem(
    position: RegionPosition,
    region: ButtonRegion?,
    onTapPreview: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
    ) {
        RegionPositionIndicator(position)
        Text(
            position.displayName(),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(96.dp),
        )
        val labelPreview = region?.label ?: onTapPreview
        val iconVec = MapoIcons.resolve(region?.icon)
        if (iconVec != null) {
            Icon(iconVec, contentDescription = null, modifier = Modifier.size(14.dp))
        }
        Text(
            text = labelPreview.ifEmpty { "—" },
            style = MaterialTheme.typography.labelMedium,
            color = if (region == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (region == null) "default" else "${region.sizeSp.toInt()}sp",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Tiny 3×3 grid where one cell is highlighted to show which region this row edits.
 */
@Composable
private fun RegionPositionIndicator(position: RegionPosition) {
    val cellSize = 6.dp
    val highlighted = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outlineVariant
    Column(
        verticalArrangement = Arrangement.spacedBy(1.dp),
        modifier = Modifier
            .size(24.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
            .padding(1.dp),
    ) {
        listOf(
            listOf(RegionPosition.TOP_LEFT, RegionPosition.TOP_CENTER, RegionPosition.TOP_RIGHT),
            listOf(RegionPosition.CENTER_LEFT, RegionPosition.CENTER, RegionPosition.CENTER_RIGHT),
            listOf(RegionPosition.BOTTOM_LEFT, RegionPosition.BOTTOM_CENTER, RegionPosition.BOTTOM_RIGHT),
        ).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                row.forEach { p ->
                    Box(
                        modifier = Modifier
                            .size(cellSize)
                            .background(if (p == position) highlighted else dim, RoundedCornerShape(1.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResetButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

// ── Sub-dialogs ───────────────────────────────────────────────────────────────

@Composable
private fun ColorPickerInDialog(
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

@Composable
private fun RegionEditDialog(
    position: RegionPosition,
    initial: ButtonRegion,
    labelPlaceholder: String,
    onConfirm: (ButtonRegion) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var region by remember(position) { mutableStateOf(initial) }
    var iconPickerOpen by remember { mutableStateOf(false) }
    var labelColorOpen by remember { mutableStateOf(false) }
    var iconColorOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Region: ${position.displayName()}") },
        text = {
            Column(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 460.dp)
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { iconPickerOpen = true }
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        "Icon",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(96.dp),
                    )
                    val vec = MapoIcons.resolve(region.icon)
                    if (vec != null) {
                        Icon(vec, contentDescription = null, modifier = Modifier.size(20.dp))
                    } else {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Text("—", style = MaterialTheme.typography.labelMedium) }
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        region.icon ?: "None",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = region.label ?: "",
                    onValueChange = { v -> region = region.copy(label = v.ifEmpty { null }) },
                    label = { Text("Label") },
                    placeholder = { Text("default: $labelPlaceholder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { labelColorOpen = true }
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        "Label color",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(96.dp),
                    )
                    ColorSwatch(argb = region.labelColorArgb)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (region.labelColorArgb == null) "Theme default" else "#%08X".format(region.labelColorArgb!!),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { iconColorOpen = true }
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        "Icon color",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(96.dp),
                    )
                    ColorSwatch(argb = region.iconColorArgb)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (region.iconColorArgb == null) "Theme default" else "#%08X".format(region.iconColorArgb!!),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SizeDropdown(
                    value = region.sizeSp,
                    onChange = { v -> region = region.copy(sizeSp = v) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(region) }) { Text("OK") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

    if (iconPickerOpen) {
        IconPickerDialog(
            current = region.icon,
            onSelect = { name ->
                region = region.copy(icon = name)
                iconPickerOpen = false
            },
            onDismiss = { iconPickerOpen = false },
        )
    }

    if (labelColorOpen) {
        ColorPickerInDialog(
            title = "Label color",
            initial = region.labelColorArgb?.let { Color(it) } ?: Color.White,
            onConfirm = { c ->
                region = region.copy(labelColorArgb = c.toArgb())
                labelColorOpen = false
            },
            onClear = { region = region.copy(labelColorArgb = null); labelColorOpen = false },
            onDismiss = { labelColorOpen = false },
        )
    }

    if (iconColorOpen) {
        ColorPickerInDialog(
            title = "Icon color",
            initial = region.iconColorArgb?.let { Color(it) } ?: Color.White,
            onConfirm = { c ->
                region = region.copy(iconColorArgb = c.toArgb())
                iconColorOpen = false
            },
            onClear = { region = region.copy(iconColorArgb = null); iconColorOpen = false },
            onDismiss = { iconColorOpen = false },
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private enum class ColorRole(
    val title: String,
    val canClear: Boolean,
) {
    Fill("Button fill", true),
    Outline("Button outline", true);

    fun read(b: GridButton): Color = when (this) {
        Fill    -> b.fillColorArgb?.let { Color(it) } ?: Color.White
        Outline -> b.outlineColorArgb?.let { Color(it) } ?: Color.White
    }

    fun write(b: GridButton, c: Color): GridButton = when (this) {
        Fill    -> b.copy(fillColorArgb = c.toArgb())
        Outline -> b.copy(outlineColorArgb = c.toArgb())
    }

    fun clear(b: GridButton): GridButton = when (this) {
        Fill    -> b.copy(fillColorArgb = null)
        Outline -> b.copy(outlineColorArgb = null)
    }
}

private fun RegionPosition.displayName(): String = when (this) {
    RegionPosition.CENTER        -> "Center"
    RegionPosition.TOP_LEFT      -> "Top left"
    RegionPosition.TOP_CENTER    -> "Top center"
    RegionPosition.TOP_RIGHT     -> "Top right"
    RegionPosition.CENTER_LEFT   -> "Center left"
    RegionPosition.CENTER_RIGHT  -> "Center right"
    RegionPosition.BOTTOM_LEFT   -> "Bottom left"
    RegionPosition.BOTTOM_CENTER -> "Bottom center"
    RegionPosition.BOTTOM_RIGHT  -> "Bottom right"
}

internal fun RegionPosition.defaultSizeSp(): Float =
    if (this == RegionPosition.CENTER) 14f else 9f

private fun ButtonRegion.isEmpty(): Boolean =
    icon == null && label == null && labelColorArgb == null && iconColorArgb == null

internal fun simpleLabel(target: RemapTarget): String = when (target) {
    is RemapTarget.Unbound  -> ""
    is RemapTarget.Gamepad  -> target.button
    is RemapTarget.Keyboard -> target.code
    is RemapTarget.Mouse    -> target.code
}

/**
 * Keep [GridButton.label] in sync with the visible center label so dialog text
 * (Remove confirm, conflict labels) stays meaningful even though the field isn't
 * directly editable in this dialog.
 */
private fun autoDeriveLabel(b: GridButton): GridButton {
    val centerLabel = b.regions[RegionPosition.CENTER.name]?.label?.takeIf { it.isNotBlank() }
    val derived = centerLabel ?: simpleLabel(b.onTapTarget)
    return b.copy(label = derived)
}
