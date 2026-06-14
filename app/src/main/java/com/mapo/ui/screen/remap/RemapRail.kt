package com.mapo.ui.screen.remap

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.ui.compact.CompactDropdownMenuItem

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
 * [WideNavigationRail], held **permanently collapsed** so it reads as a simple vertical
 * toolbar: the scope (action set / layer) entry occupies the first slot, a thin divider sits
 * at the bottom of that slot, and the section items (Buttons / D-Pad / Triggers / Joysticks /
 * Gyro) fill the equally-spaced slots beneath it. The whole right pane is [detailPane].
 *
 * The scope entry lives in the **content** (not the header) so the rail clamps it to the
 * collapsed item width — otherwise it balloons to the loose max width (which also threw the
 * fly-out far off to the right). `windowInsets` is zeroed because the enclosing Scaffold
 * already applies the status-bar inset. Rail container is `surfaceContainer`, one step up from
 * the `surface` detail pane. Gamepad: focusing a section selects it; D-pad → enters detail.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RemapRail(
    sections: List<SectionedPaneItem>,
    selectedSectionId: String,
    onSectionSelected: (String) -> Unit,
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
    val detailRequester = remember(selectedSectionId) { FocusRequester() }

    Row(modifier = modifier.fillMaxSize()) {
        WideNavigationRail(
            // M3 role: surfaceContainer — one step up from the surface detail pane.
            colors = WideNavigationRailDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            // Scaffold already applies the status-bar inset; don't double it.
            windowInsets = WindowInsets(0, 0, 0, 0),
            contentPadding = PaddingValues(top = 14.dp, bottom = 4.dp),
            // D-pad → from anywhere in the rail jumps into the detail pane's first row.
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    detailRequester.tryRequestFocus(); true
                } else false
            },
        ) {
            // First slot: the scope entry, with its own bottom divider.
            ScopeRailItem(
                scopeLabel = scopeLabel,
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

            // Section slots.
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
                    railExpanded = false,
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
 * The scope (action set / layer) entry: a [WideNavigationRailItem] with a trailing ▸ (the same
 * [Icons.AutoMirrored.Filled.ArrowRight] the Edit Overlay submenu rows use) and a thin divider
 * pinned to the bottom of its slot (so it separates scope from sections without consuming an
 * extra 64dp rail slot). Tapping it opens a fly-out [DropdownMenu] just past the rail's right
 * edge — offset computed from the item's measured bounds. The menu lists every action set
 * (filled [Icons.Filled.Layers]) with layers indented (outlined [Icons.Outlined.Layers]); the
 * current scope is pill-highlighted; each row's horizontal kebab opens Rename / Duplicate /
 * Delete; "Add layer" trails each set, "Add set" the whole list. Rows are [CompactDropdownMenuItem].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScopeRailItem(
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
) {
    var menuOpen by remember { mutableStateOf(false) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    Box(modifier = Modifier.onGloballyPositioned { anchorSize = it.size }) {
        WideNavigationRailItem(
            selected = false,
            onClick = { menuOpen = true },
            icon = { Icon(Icons.Filled.Layers, contentDescription = null) },
            label = { Text(scopeLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            railExpanded = false,
            modifier = Modifier
                .padding(top = 2.dp)
                .testTag("rail-scope"),
        )
        // Submenu affordance, matching the Edit Overlay rows; sits just by the icon/label.
        // Icon(
        //     Icons.AutoMirrored.Filled.ArrowRight,
        //     contentDescription = null,
        //     tint = MaterialTheme.colorScheme.onSurfaceVariant,
        //     modifier = Modifier
        //         .align(Alignment.CenterEnd)
        //         .offset(y = (-8).dp)
        //         .padding(end = 6.dp)
        //         .size(18.dp),
        // )
        // Thin divider at the bottom of the scope's slot — like the Edit Overlay menu divider.
        HorizontalDivider(Modifier.align(Alignment.BottomCenter))
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            // Computed from real bounds: nudge right past the rail's edge, up to the item's top.
            offset = with(density) {
                DpOffset(anchorSize.width.toDp(), -anchorSize.height.toDp())
            },
        ) {
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
                CompactDropdownMenuItem(
                    text = "Add layer",
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = { onAddLayer(setId); menuOpen = false },
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            CompactDropdownMenuItem(
                text = "Add set",
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { onAddSet(); menuOpen = false },
            )
        }
    }
}

/**
 * One set/layer row inside the scope fly-out: a [CompactDropdownMenuItem] (denser than the
 * stock 48dp item), selected via a pill background rather than a check, managed via a trailing
 * horizontal kebab (sized down from the 48dp default) that opens a compact sub-menu.
 */
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
    val rowModifier = (if (indent) Modifier.padding(start = 16.dp) else Modifier)
        .then(
            if (selected) {
                Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.secondaryContainer)
            } else Modifier,
        )
    CompactDropdownMenuItem(
        text = label,
        onClick = onSelect,
        modifier = rowModifier,
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = {
            Box {
                // Tighter than the 48dp default so the kebab hugs the row's trailing edge.
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    IconButton(onClick = { kebabOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.MoreHoriz, contentDescription = "Manage \"$label\"")
                    }
                }
                DropdownMenu(expanded = kebabOpen, onDismissRequest = { kebabOpen = false }) {
                    CompactDropdownMenuItem(text = "Rename", onClick = { kebabOpen = false; onRename() })
                    CompactDropdownMenuItem(text = "Duplicate", onClick = { kebabOpen = false; onDuplicate() })
                    CompactDropdownMenuItem(
                        text = "Delete",
                        enabled = canDelete,
                        onClick = { kebabOpen = false; onDelete() },
                    )
                }
            }
        },
    )
}

/** Try-best focus request — swallows the "not laid out yet" race. */
private fun FocusRequester.tryRequestFocus() {
    runCatching { requestFocus() }
}
