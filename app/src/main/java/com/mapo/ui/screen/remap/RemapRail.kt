package com.mapo.ui.screen.remap

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mapo.data.model.steam.ControllerConfig
import kotlinx.coroutines.launch

/**
 * One section in the Remap rail. [icon] drives the nav-item glyph; null falls back to a
 * neutral dot. Disabled sections render dimmed and are inert to tap.
 */
data class SectionedPaneItem(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
)

/**
 * The Remap screen's left control surface, built on the real M3-expressive
 * [WideNavigationRail] (collapsed-by-default-able, with built-in width + label transitions).
 * The homespun "left 30% / right content" pane it replaced is gone.
 *
 * Layout: a collapse/expand button in the rail header, then the back / "Controls" entry, the
 * scope (action set / layer) entry whose fly-out lists all sets with layers indented
 * (kebab → Rename/Duplicate/Delete per row, plus Add layer/Add set), then the section items
 * (Buttons / D-Pad / Triggers / Joysticks / Gyro). The whole right pane is [detailPane].
 *
 * Starts **expanded** (labels visible) since this is the screen's primary surface; the header
 * button toggles to the collapsed icon-only rail. Gamepad: focusing a section selects it (via
 * its [MutableInteractionSource]); D-pad → from the rail jumps into the detail pane.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RemapRail(
    sections: List<SectionedPaneItem>,
    selectedSectionId: String,
    onSectionSelected: (String) -> Unit,
    onBack: () -> Unit,
    scopeLabel: String,
    config: ControllerConfig?,
    viewingSetId: Long?,
    viewingLayerId: Long?,
    canDeleteSet: Boolean,
    onSelectActionSet: (Long) -> Unit,
    onSelectLayer: (Long?) -> Unit,
    onAddSet: () -> Unit,
    onAddLayer: (parentSetId: Long) -> Unit,
    onRenameSet: (setId: Long) -> Unit,
    onDuplicateSet: (setId: Long) -> Unit,
    onDeleteSet: (setId: Long) -> Unit,
    onRenameLayer: (layerId: Long) -> Unit,
    onDuplicateLayer: (layerId: Long) -> Unit,
    onDeleteLayer: (layerId: Long) -> Unit,
    modifier: Modifier = Modifier,
    detailPane: @Composable (selectedId: String, firstDetailFocusRequester: FocusRequester) -> Unit,
) {
    val railState = rememberWideNavigationRailState(WideNavigationRailValue.Expanded)
    val scope = rememberCoroutineScope()
    val expanded = railState.targetValue == WideNavigationRailValue.Expanded
    val detailRequester = remember(selectedSectionId) { FocusRequester() }

    Row(modifier = modifier.fillMaxSize()) {
        WideNavigationRail(
            state = railState,
            // D-pad → from anywhere in the rail jumps into the detail pane's first row.
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    detailRequester.tryRequestFocus(); true
                } else false
            },
            header = {
                IconButton(onClick = { scope.launch { railState.toggle() } }) {
                    Icon(
                        imageVector = if (expanded) Icons.AutoMirrored.Filled.MenuOpen else Icons.Filled.Menu,
                        contentDescription = if (expanded) "Collapse rail" else "Expand rail",
                    )
                }
            },
        ) {
            // Back / "Controls" — navigates home.
            WideNavigationRailItem(
                selected = false,
                onClick = onBack,
                icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") },
                label = { Text("Controls") },
                railExpanded = expanded,
                modifier = Modifier.testTag("rail-back"),
            )

            // Scope (action set / layer) entry + its fly-out.
            ScopeRailItem(
                scopeLabel = scopeLabel,
                railExpanded = expanded,
                config = config,
                viewingSetId = viewingSetId,
                viewingLayerId = viewingLayerId,
                canDeleteSet = canDeleteSet,
                onSelectActionSet = onSelectActionSet,
                onSelectLayer = onSelectLayer,
                onAddSet = onAddSet,
                onAddLayer = onAddLayer,
                onRenameSet = onRenameSet,
                onDuplicateSet = onDuplicateSet,
                onDeleteSet = onDeleteSet,
                onRenameLayer = onRenameLayer,
                onDuplicateLayer = onDuplicateLayer,
                onDeleteLayer = onDeleteLayer,
            )

            // Section items.
            sections.forEach { section ->
                val interaction = remember(section.id) { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                // Focus == selection: arrowing onto a section previews it in the detail pane.
                LaunchedEffect(focused) {
                    if (focused && section.enabled) onSectionSelected(section.id)
                }
                WideNavigationRailItem(
                    selected = section.id == selectedSectionId,
                    onClick = { onSectionSelected(section.id) },
                    icon = {
                        Icon(section.icon ?: Icons.Filled.Circle, contentDescription = null)
                    },
                    label = { Text(section.label) },
                    railExpanded = expanded,
                    enabled = section.enabled,
                    interactionSource = interaction,
                    modifier = Modifier.testTag("section-rail-item:${section.id}"),
                )
            }
        }

        // M3 role: surface — primary content plane (reserved for input assignments).
        Surface(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            detailPane(selectedSectionId, detailRequester)
        }
    }
}

/**
 * The scope (action set / layer) entry: a [WideNavigationRailItem] anchoring a fly-out
 * [DropdownMenu] of every action set (filled [Icons.Filled.Layers]) with its layers indented
 * (outlined [Icons.Outlined.Layers]); the current scope is primary-tinted + checked. Each
 * set/layer row carries a kebab → Rename / Duplicate / Delete; "Add layer" trails each set,
 * "Add set" the whole list. Any action closes the fly-out.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScopeRailItem(
    scopeLabel: String,
    railExpanded: Boolean,
    config: ControllerConfig?,
    viewingSetId: Long?,
    viewingLayerId: Long?,
    canDeleteSet: Boolean,
    onSelectActionSet: (Long) -> Unit,
    onSelectLayer: (Long?) -> Unit,
    onAddSet: () -> Unit,
    onAddLayer: (parentSetId: Long) -> Unit,
    onRenameSet: (setId: Long) -> Unit,
    onDuplicateSet: (setId: Long) -> Unit,
    onDeleteSet: (setId: Long) -> Unit,
    onRenameLayer: (layerId: Long) -> Unit,
    onDuplicateLayer: (layerId: Long) -> Unit,
    onDeleteLayer: (layerId: Long) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        WideNavigationRailItem(
            selected = false,
            onClick = { menuOpen = true },
            icon = { Icon(Icons.Filled.Layers, contentDescription = null) },
            label = {
                Text(scopeLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            railExpanded = railExpanded,
            modifier = Modifier.testTag("rail-scope"),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val sets = config?.actionSets.orEmpty()
            sets.forEach { setGraph ->
                val setId = setGraph.actionSet.id
                ScopeMenuRow(
                    label = setGraph.actionSet.title,
                    icon = Icons.Filled.Layers,
                    indent = false,
                    selected = viewingLayerId == null && viewingSetId == setId,
                    canDelete = canDeleteSet,
                    onSelect = {
                        onSelectActionSet(setId)
                        onSelectLayer(null)
                        menuOpen = false
                    },
                    onRename = { onRenameSet(setId); menuOpen = false },
                    onDuplicate = { onDuplicateSet(setId); menuOpen = false },
                    onDelete = { onDeleteSet(setId); menuOpen = false },
                )
                setGraph.layers.forEach { layerGraph ->
                    val layerId = layerGraph.layer.id
                    ScopeMenuRow(
                        label = layerGraph.layer.title,
                        icon = Icons.Outlined.Layers,
                        indent = true,
                        selected = viewingLayerId == layerId,
                        canDelete = true,
                        onSelect = {
                            onSelectActionSet(setId)
                            onSelectLayer(layerId)
                            menuOpen = false
                        },
                        onRename = { onRenameLayer(layerId); menuOpen = false },
                        onDuplicate = { onDuplicateLayer(layerId); menuOpen = false },
                        onDelete = { onDeleteLayer(layerId); menuOpen = false },
                    )
                }
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add layer") },
                    onClick = { onAddLayer(setId); menuOpen = false },
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add set") },
                onClick = { onAddSet(); menuOpen = false },
            )
        }
    }
}

/** One set/layer row inside the scope fly-out: select on body tap, manage via the kebab. */
@Composable
private fun ScopeMenuRow(
    label: String,
    icon: ImageVector,
    indent: Boolean,
    selected: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var kebabOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    DropdownMenuItem(
        modifier = if (indent) Modifier.padding(start = 16.dp) else Modifier,
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = if (selected) colors.primary else colors.onSurfaceVariant)
        },
        text = {
            Text(label, color = if (selected) colors.primary else Color.Unspecified, maxLines = 1)
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Box {
                    IconButton(onClick = { kebabOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Manage \"$label\"")
                    }
                    DropdownMenu(expanded = kebabOpen, onDismissRequest = { kebabOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { kebabOpen = false; onRename() })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { kebabOpen = false; onDuplicate() })
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            enabled = canDelete,
                            onClick = { kebabOpen = false; onDelete() },
                        )
                    }
                }
            }
        },
        onClick = onSelect,
    )
}

/** Try-best focus request — swallows the "not laid out yet" race. */
private fun FocusRequester.tryRequestFocus() {
    runCatching { requestFocus() }
}
