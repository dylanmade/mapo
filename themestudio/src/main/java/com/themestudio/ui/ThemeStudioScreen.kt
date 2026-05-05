package com.themestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.themestudio.core.ColorRoles
import com.themestudio.core.LocalThemeStudioController
import com.themestudio.core.LocalThemeStudioVariantOverride
import com.themestudio.core.ShapeRoles
import com.themestudio.core.TypographyRoles
import com.themestudio.preview.ColorRoleSwatches
import com.themestudio.preview.MaterialComponentGallery
import com.themestudio.preview.ShapeSpecimen
import com.themestudio.preview.TypographySpecimen

private enum class StudioTab(val label: String) {
    Colors("Colors"),
    Typography("Typography"),
    Shapes("Shapes"),
}

/**
 * Top-level editor screen. Hosts three tabs (Colors, Typography, Shapes),
 * each pairing a tappable specimen with a sticky preview gallery so changes
 * flow through to real components live.
 *
 * The screen wraps each tab's preview content in [theme] so the consumer's
 * theme function picks up [LocalThemeStudioVariantOverride] (and any color
 * overrides) — this is what lets the dev preview the *opposite* variant
 * from the device's current setting and see edits applied to every
 * component.
 *
 * Defaults wire up library-provided specimens + [MaterialComponentGallery];
 * consumers can swap in app-specific previews per tab if they want.
 *
 * Typical wiring:
 * ```
 * ThemeStudioScreen(
 *     onClose = { ... },
 *     theme = { content -> MapoTheme { content() } },
 * )
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeStudioScreen(
    onClose: () -> Unit,
    theme: @Composable (content: @Composable () -> Unit) -> Unit,
    colorsPreview: @Composable (onPickRole: (String) -> Unit) -> Unit = { onPick ->
        ColorRoleSwatches(onPickRole = onPick)
        MaterialComponentGallery()
    },
    typographyPreview: @Composable (onPickRole: (String) -> Unit) -> Unit = { onPick ->
        TypographySpecimen(onPickRole = onPick)
        MaterialComponentGallery()
    },
    shapesPreview: @Composable (onPickRole: (String) -> Unit) -> Unit = { onPick ->
        ShapeSpecimen(onPickRole = onPick)
        MaterialComponentGallery()
    },
) {
    val controller = LocalThemeStudioController.current
    val overrides = controller.overrides

    var editingDark by remember { mutableStateOf(false) }
    var tabIndex by remember { mutableIntStateOf(0) }
    var showExport by remember { mutableStateOf(false) }
    var pickerColorRole by remember { mutableStateOf<String?>(null) }
    var pickerTypoRole by remember { mutableStateOf<String?>(null) }
    var pickerShapeRole by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        TopBar(
            onClose = onClose,
            editingDark = editingDark,
            onVariantChange = { editingDark = it },
            onExport = { showExport = true },
            onReset = { controller.reset() },
        )
        HorizontalDivider()

        PrimaryTabRow(selectedTabIndex = tabIndex) {
            StudioTab.values().forEachIndexed { i, tab ->
                Tab(
                    selected = tabIndex == i,
                    onClick = { tabIndex = i },
                    text = { Text(tab.label, fontSize = 12.sp) },
                )
            }
        }

        // Preview pane wraps in the consumer's theme so overrides + variant
        // override propagate to children. Takes all remaining height.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            CompositionLocalProvider(LocalThemeStudioVariantOverride provides editingDark) {
                theme {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    ) {
                        when (StudioTab.values()[tabIndex]) {
                            StudioTab.Colors -> colorsPreview { name -> pickerColorRole = name }
                            StudioTab.Typography -> typographyPreview { name -> pickerTypoRole = name }
                            StudioTab.Shapes -> shapesPreview { name -> pickerShapeRole = name }
                        }
                    }
                }
            }
        }
    }

    // ── Color picker sheet ────────────────────────────────────────────────
    pickerColorRole?.let { roleName ->
        val role = remember(roleName) { ColorRoles.all.first { it.name == roleName } }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = { pickerColorRole = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                SheetHeader(title = role.name, subtitle = if (editingDark) "Editing: dark" else "Editing: light")
                val variant = if (editingDark) overrides.colors.dark else overrides.colors.light
                val effective = role.readOverride(variant) ?: role.read(MaterialTheme.colorScheme)
                val isOverridden = role.readOverride(variant) != null
                ColorPicker(
                    color = effective,
                    onChange = { c ->
                        if (editingDark) controller.setDarkRole(role, c)
                        else controller.setLightRole(role, c)
                    },
                    onClearOverride = if (isOverridden) {
                        {
                            if (editingDark) controller.setDarkRole(role, null)
                            else controller.setLightRole(role, null)
                        }
                    } else null,
                    pickerKey = "$roleName.${if (editingDark) "dark" else "light"}",
                )
            }
        }
    }

    // ── Typography picker sheet ───────────────────────────────────────────
    pickerTypoRole?.let { roleName ->
        val role = remember(roleName) { TypographyRoles.all.first { it.name == roleName } }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = { pickerTypoRole = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                SheetHeader(title = role.name, subtitle = "Typography")
                val baseStyle = role.read(MaterialTheme.typography)
                val current = role.readOverride(overrides.typography)
                TypographyPicker(
                    role = role,
                    baseStyle = baseStyle,
                    current = current,
                    onChange = { v -> controller.setTypographyRole(role, v) },
                    onClear = { controller.setTypographyRole(role, com.themestudio.core.TextStyleOverride()) },
                )
            }
        }
    }

    // ── Shape picker sheet ────────────────────────────────────────────────
    pickerShapeRole?.let { roleName ->
        val role = remember(roleName) { ShapeRoles.all.first { it.name == roleName } }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = { pickerShapeRole = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                SheetHeader(title = role.name, subtitle = "Shape (corner radius)")
                val current = role.readOverride(overrides.shapes)
                val baseRadius = defaultShapeRadius(roleName)
                ShapePicker(
                    baseRadius = baseRadius,
                    current = current,
                    onChange = { dp -> controller.setShapeRole(role, dp) },
                    onClear = { controller.setShapeRole(role, null) },
                )
            }
        }
    }

    if (showExport) {
        ExportDialog(overrides = overrides, onDismiss = { showExport = false })
    }
}

/** M3 default corner radii — used to seed the shape picker when no override is set. */
private fun defaultShapeRadius(name: String): androidx.compose.ui.unit.Dp = when (name) {
    "extraSmall" -> 4.dp
    "small" -> 8.dp
    "medium" -> 12.dp
    "large" -> 16.dp
    "extraLarge" -> 28.dp
    else -> 0.dp
}

@Composable
private fun SheetHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = subtitle,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TopBar(
    onClose: () -> Unit,
    editingDark: Boolean,
    onVariantChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        TextButton(onClick = onClose) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("Back", fontSize = 13.sp)
        }
        Spacer(Modifier.width(4.dp))
        Text(
            "Theme Studio",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = !editingDark,
                onClick = { onVariantChange(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Light", fontSize = 11.sp) }
            SegmentedButton(
                selected = editingDark,
                onClick = { onVariantChange(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Dark", fontSize = 11.sp) }
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onExport) { Text("Export", fontSize = 12.sp) }
        TextButton(onClick = onReset) { Text("Reset", fontSize = 12.sp) }
    }
}

