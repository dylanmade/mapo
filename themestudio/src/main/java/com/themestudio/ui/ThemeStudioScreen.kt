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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
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
 *  - top bar with back/light-dark toggle/export/reset
 *  - sticky preview canvas (consumer-provided)
 *  - scrollable role list with inline color pickers
 *
 * The screen MUST be hosted *inside* the consumer's theme function (so the
 * consumer's theme picks up the override changes). Wrap the call site like:
 * ```
 * MapoTheme {
 *     ThemeStudioScreen(onClose = { ... }) { /* preview content */ }
 * }
 * ```
 *
 * The screen overrides [LocalThemeStudioVariantOverride] internally to force
 * the preview canvas (and its surrounding theme) to match the variant being
 * edited, so light/dark can be tweaked independent of the device setting.
 */
@Composable
fun ThemeStudioScreen(
    onClose: () -> Unit,
    previewContent: @Composable () -> Unit,
) {
    val controller = LocalThemeStudioController.current
    val overrides = controller.overrides

    var editingDark by remember { mutableStateOf(false) }
    var expandedRole by remember { mutableStateOf<String?>(null) }
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
            onReset = {
                controller.reset()
                expandedRole = null
            },
        )
        HorizontalDivider()

        // The preview canvas is wrapped so its surrounding theme resolves to
        // the variant being edited — this is what lets the dev preview dark
        // mode while their device is in light mode.
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
                    previewContent()
                }
            }
        }
        HorizontalDivider()

        RoleList(
            variant = if (editingDark) overrides.dark else overrides.light,
            editingDark = editingDark,
            expandedRole = expandedRole,
            onToggleExpand = { name ->
                expandedRole = if (expandedRole == name) null else name
            },
        )
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
    editingDark: Boolean,
    expandedRole: String?,
    onToggleExpand: (String) -> Unit,
) {
    val controller = LocalThemeStudioController.current
    val scheme = MaterialTheme.colorScheme

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(ColorRoles.all, key = { it.name }) { role ->
            val effective: Color = role.readOverride(variant) ?: role.read(scheme)
            val isOverridden = role.readOverride(variant) != null
            val isExpanded = expandedRole == role.name
            RoleRow(
                name = role.name,
                effectiveColor = effective,
                isOverridden = isOverridden,
                expanded = isExpanded,
                onToggleExpand = { onToggleExpand(role.name) },
                pickerContent = {
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
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
