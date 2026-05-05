package com.themestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.themestudio.core.ColorOverrides
import com.themestudio.core.ColorRole
import com.themestudio.core.ColorRoles
import com.themestudio.core.LocalThemeStudioController
import com.themestudio.core.LocalThemeStudioVariantOverride

/**
 * Top-level editor screen. Renders:
 *  - top bar with back / variant toggle / export / reset
 *  - sticky preview canvas (consumer-provided)
 *  - scrollable role list
 *  - modal bottom sheet for editing a tapped role
 *
 * Tap any role in the role list, or tap/long-press a swatch in the preview
 * (when the consumer wires its [com.themestudio.preview.ColorRoleSwatches]
 * with `onPickRole`), to open the picker sheet. Live updates flow through
 * the controller as the user drags sliders or types hex.
 *
 * The screen MUST be hosted inside the consumer's theme function. Wrap the
 * call site like:
 * ```
 * MapoTheme {
 *     ThemeStudioScreen(onClose = { ... }) {
 *         // Wrap previewContent in your theme too — that's what lets the
 *         // editor preview the *opposite* variant from the device's
 *         // current setting.
 *         MapoTheme {
 *             ColorRoleSwatches(onPickRole = ...)
 *             MaterialComponentGallery()
 *         }
 *     }
 * }
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeStudioScreen(
    onClose: () -> Unit,
    previewContent: @Composable (onPickRole: (String) -> Unit) -> Unit,
) {
    val controller = LocalThemeStudioController.current
    val overrides = controller.overrides

    var editingDark by remember { mutableStateOf(false) }
    var pickerRole by remember { mutableStateOf<String?>(null) }
    var showExport by remember { mutableStateOf(false) }

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

        // Preview pane — consumer supplies the content; we set the variant
        // override CL so the consumer's theme function (re-invoked inside the
        // preview block) resolves to the variant being edited.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 320.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            CompositionLocalProvider(LocalThemeStudioVariantOverride provides editingDark) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    previewContent { role -> pickerRole = role }
                }
            }
        }
        HorizontalDivider()

        Text(
            "Tap any role to edit · long-press swatches above",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        HorizontalDivider()

        RoleList(
            variant = if (editingDark) overrides.dark else overrides.light,
            onPickRole = { name -> pickerRole = name },
            modifier = Modifier.weight(1f),
        )
    }

    pickerRole?.let { roleName ->
        val role = remember(roleName) { ColorRoles.all.first { it.name == roleName } }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = { pickerRole = null },
        ) {
            // Resolve the effective color via the same logic the role list
            // uses so what you see in the sheet matches the "before" state.
            val variant = if (editingDark) overrides.dark else overrides.light
            val effective = role.readOverride(variant) ?: role.read(MaterialTheme.colorScheme)
            val isOverridden = role.readOverride(variant) != null
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    text = role.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    text = if (editingDark) "Editing: dark variant" else "Editing: light variant",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
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
                )
            }
        }
    }

    if (showExport) {
        ExportDialog(overrides = overrides, onDismiss = { showExport = false })
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

@Composable
private fun RoleList(
    variant: ColorOverrides,
    onPickRole: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(ColorRoles.all, key = { it.name }) { role ->
            val effective: Color = role.readOverride(variant) ?: role.read(scheme)
            val isOverridden = role.readOverride(variant) != null
            RoleRow(
                name = role.name,
                effectiveColor = effective,
                isOverridden = isOverridden,
                onClick = { onPickRole(role.name) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
