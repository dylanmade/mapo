package com.mapo.ui.screen.remap

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.ui.compact.CompactDropdownMenuItem
import com.mapo.ui.component.NameableText
import kotlin.math.roundToInt

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
 * The Remap screen's left rail, built on the real M3-expressive [WideNavigationRail], held
 * permanently **collapsed** (the narrow icon + label-under configuration). First slot is the
 * action set / layer **scope button** (Layers icon + current scope name), then the section slots
 * (Buttons / D-Pad / Triggers / Joysticks / Gyro). The scope button
 * opens a fly-out (just past the rail's right edge) modeled on the Edit Overlay scope picker:
 * sets with layers indented, "Add layer" per set, "Add set" at the end; the current scope is
 * primary-tinted + checked (no pill, no kebab). Right pane = [detailPane].
 *
 * `windowInsets` is zeroed (the Scaffold already insets). Rail container is `surfaceContainer`,
 * one step up from the `surface` detail pane, with a [VerticalDivider] right border. Gamepad:
 * focusing a section selects it; D-pad → enters the detail pane.
 *
 * The scope fly-out is rendered **outside** the rail's content slot (at the root [Box], anchored to
 * the scope item's captured bounds): an open [DropdownMenu] placed inside the rail's slot becomes a
 * measured child of the rail's item layout and shoves the section items downward when it opens.
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
    onSelectActionSet: (Long) -> Unit,
    onSelectLayer: (Long?) -> Unit,
    onAddSet: () -> Unit,
    onAddLayer: (parentSetId: Long) -> Unit,
    modifier: Modifier = Modifier,
    detailPane: @Composable (selectedId: String, firstDetailFocusRequester: FocusRequester) -> Unit,
) {
    val detailRequester = remember(selectedSectionId) { FocusRequester() }

    // Scope fly-out state, lifted so the menu renders outside the rail content (see kdoc).
    var menuOpen by remember { mutableStateOf(false) }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var scopeBounds by remember { mutableStateOf<Rect?>(null) }
    val density = LocalDensity.current

    Box(modifier = modifier.fillMaxSize().onGloballyPositioned { rootCoords = it }) {
        Row(Modifier.fillMaxSize()) {
            // Default state = collapsed (narrow); no toggle, so it stays narrow.
            WideNavigationRail(
                // M3 role: surfaceContainer — one step up from the surface detail pane.
                colors = WideNavigationRailDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                // Scaffold already applies the status-bar inset; don't double it. Trim the top so the
                // item stack sits a touch higher (the M3 default top space is 44dp).
                windowInsets = WindowInsets(0, 0, 0, 0),
                contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp),
                // D-pad → from anywhere in the rail jumps into the detail pane's first row. A tight
                // width also overrides the rail's default collapsed container width (96dp) — the
                // layout honors a non-zero min-width constraint over its token.
                modifier = Modifier
                    .width(NarrowRailWidth)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                            detailRequester.tryRequestFocus(); true
                        } else false
                    },
            ) {
                // First slot: the scope button. It captures its own bounds (relative to the root Box)
                // so the detached fly-out below can anchor to it.
                WideNavigationRailItem(
                    selected = false,
                    onClick = { menuOpen = true },
                    icon = { Icon(Icons.Filled.Layers, contentDescription = null) },
                    label = { NameableText(scopeLabel, maxWidth = ScopeLabelMaxWidth) },
                    railExpanded = false,
                    modifier = Modifier
                        .testTag("rail-scope")
                        .onGloballyPositioned { c ->
                            rootCoords?.let { scopeBounds = it.localBoundingBoxOf(c) }
                        },
                )

                sections.forEachIndexed { index, section ->
                    val interaction = remember(section.id) { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    // Focus == selection: arrowing onto a section previews it in the detail pane.
                    LaunchedEffect(focused) {
                        if (focused && section.enabled) onSectionSelected(section.id)
                    }
                    WideNavigationRailItem(
                        selected = section.id == selectedSectionId,
                        onClick = { onSectionSelected(section.id) },
                        icon = { Icon(section.icon ?: Icons.Filled.Circle, contentDescription = null) },
                        label = { Text(section.label) },
                        railExpanded = false,
                        enabled = section.enabled,
                        interactionSource = interaction,
                        // A touch of extra breathing room above the first section, setting it (and the
                        // scope divider above) apart from the scope button.
                        modifier = Modifier
                            .testTag("section-rail-item:${section.id}")
                            .then(if (index == 0) Modifier.padding(top = FirstSectionTopGap) else Modifier),
                    )
                }
            }

            // Right border of the rail.
            VerticalDivider()

            // M3 role: surface — primary content plane (reserved for input assignments).
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                detailPane(selectedSectionId, detailRequester)
            }
        }

        // Detached scope fly-out: a zero-content anchor placed exactly over the scope item, with the
        // menu hanging off its right edge. Living at the root Box keeps it out of the rail's vertical
        // item layout, so opening it no longer pushes the section items down.
        scopeBounds?.let { bounds ->
            Box(
                Modifier
                    .absoluteOffset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                    .size(
                        width = with(density) { bounds.width.toDp() },
                        height = with(density) { bounds.height.toDp() },
                    ),
            ) {
                ScopeFlyout(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    anchor = bounds,
                    density = density,
                    config = config,
                    viewingSetId = viewingSetId,
                    viewingLayerId = viewingLayerId,
                    onSelectActionSet = onSelectActionSet,
                    onSelectLayer = onSelectLayer,
                    onAddSet = onAddSet,
                    onAddLayer = onAddLayer,
                )
            }

            // A short rule hung in the gap beneath the scope item — drawn here at the root Box so it
            // adds no layout height of its own (a divider inside the rail's slot would count as an
            // extra spaced item). Sets the scope button apart from the traditional section items.
            HorizontalDivider(
                modifier = Modifier
                    .absoluteOffset {
                        val x = bounds.left + (bounds.width - ScopeDividerWidthPx(density)) / 2f
                        val y = bounds.bottom + ScopeDividerGapPx(density)
                        IntOffset(x.roundToInt(), y.roundToInt())
                    }
                    .width(ScopeDividerWidth),
            )
        }
    }
}

/** Collapsed rail width — a touch narrower than the M3 default 96dp container. */
private val NarrowRailWidth = 84.dp

/** Max width for the scope (action set / layer) label — the rail container is only ~84dp wide. */
private val ScopeLabelMaxWidth = 76.dp

/** Extra space above the first section item, past the scope button + its divider. */
private val FirstSectionTopGap = 6.dp

/** Width + gap placement of the short rule separating the scope button from the section items. */
private val ScopeDividerWidth = 24.dp
private val ScopeDividerGap = 8.dp
private fun ScopeDividerWidthPx(density: androidx.compose.ui.unit.Density) =
    with(density) { ScopeDividerWidth.toPx() }
private fun ScopeDividerGapPx(density: androidx.compose.ui.unit.Density) =
    with(density) { ScopeDividerGap.toPx() }

/**
 * The scope (action set / layer) fly-out: a [DropdownMenu] anchored just past the rail's right edge,
 * aligned to the scope item's top (the caller positions a zero-content anchor box over the item).
 * Modeled on the Edit Overlay scope picker — every action set (filled [Icons.Filled.Layers]) with
 * its layers indented (outlined [Icons.Outlined.Layers]), "Add layer" trailing each set and "Add
 * set" the whole list; the current scope is primary-tinted + checked, no pill or kebab.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScopeFlyout(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchor: Rect,
    density: androidx.compose.ui.unit.Density,
    config: ControllerConfig?,
    viewingSetId: Long?,
    viewingLayerId: Long?,
    onSelectActionSet: (Long) -> Unit,
    onSelectLayer: (Long?) -> Unit,
    onAddSet: () -> Unit,
    onAddLayer: (parentSetId: Long) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        // Open just past the rail's right edge, aligned to the item's top.
        offset = with(density) { DpOffset(anchor.width.toDp(), -anchor.height.toDp()) },
    ) {
        val sets = config?.actionSets.orEmpty()
        sets.forEach { setGraph ->
            val setId = setGraph.actionSet.id
            ScopeMenuRow(
                label = setGraph.actionSet.title,
                icon = Icons.Filled.Layers,
                indent = false,
                selected = viewingLayerId == null && viewingSetId == setId,
                onClick = { onSelectActionSet(setId); onSelectLayer(null); onDismiss() },
            )
            setGraph.layers.forEach { layerGraph ->
                val layerId = layerGraph.layer.id
                ScopeMenuRow(
                    label = layerGraph.layer.title,
                    icon = Icons.Outlined.Layers,
                    indent = true,
                    selected = viewingLayerId == layerId,
                    onClick = { onSelectActionSet(setId); onSelectLayer(layerId); onDismiss() },
                )
            }
            CompactDropdownMenuItem(
                text = "Add layer",
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { onAddLayer(setId); onDismiss() },
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        CompactDropdownMenuItem(
            text = "Add set",
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
            onClick = { onAddSet(); onDismiss() },
        )
    }
}

/**
 * One set/layer row in the scope fly-out (Edit Overlay style): a [CompactDropdownMenuItem] whose
 * leading [icon] + a trailing check go primary when [selected]; no pill background, no kebab.
 */
@Composable
private fun ScopeMenuRow(
    label: String,
    icon: ImageVector,
    indent: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    CompactDropdownMenuItem(
        text = label,
        onClick = onClick,
        modifier = if (indent) Modifier.padding(start = 16.dp) else Modifier,
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = if (selected) colors.primary else colors.onSurfaceVariant)
        },
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.padding(start = 8.dp)) }
        } else null,
    )
}

/** Try-best focus request — swallows the "not laid out yet" race. */
private fun FocusRequester.tryRequestFocus() {
    runCatching { requestFocus() }
}
