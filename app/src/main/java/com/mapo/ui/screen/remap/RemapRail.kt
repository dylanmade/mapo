package com.mapo.ui.screen.remap

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
 * The Remap screen's left rail: a narrow `surfaceContainer` [Surface] holding a centered [Column] of
 * [WideNavigationRailItem]s (collapsed icon-over-label look). First item is the action set / layer
 * **scope button** (Layers icon + current scope name), then the section items (Buttons / D-Pad /
 * Triggers / Joysticks / Gyro). The scope button opens a fly-out (just past the rail's right edge)
 * modeled on the Edit Overlay scope picker: sets with layers indented, "Add layer" per set, "Add
 * set" at the end; the current scope is primary-tinted + checked (no pill, no kebab). Right pane =
 * [detailPane].
 *
 * We hand-roll the rail rather than use [androidx.compose.material3.WideNavigationRail] because that
 * component forces a fixed 96dp collapsed container and places its items at x=0 — forcing it narrower
 * knocked the items off-center. Instead a [Column] with centered alignment **wraps its content**, so
 * the rail comes out a touch narrower than 96dp with reliably centered items. Tunables:
 * [RailHorizontalPadding], [RailItemSpacing], [RailItemMinHeight]. Rail is one step up from the
 * `surface` detail pane, with a [VerticalDivider] right border. Gamepad: focusing a section selects
 * it; D-pad right → enters the detail pane.
 *
 * The scope fly-out + the short separator rule are rendered **outside** the rail (at the root [Box],
 * positioned off the scope item's captured bounds) so neither perturbs the rail's vertical layout.
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
    var railBounds by remember { mutableStateOf<Rect?>(null) }
    val density = LocalDensity.current

    Box(modifier = modifier.fillMaxSize().onGloballyPositioned { rootCoords = it }) {
        Row(Modifier.fillMaxSize()) {
            // M3 role: surfaceContainer — one step up from the surface detail pane. No forced width:
            // the rail wraps its (centered) item column, which comes out a touch narrower than the M3
            // default 96dp container. Forcing a width here is what knocked the items off-center.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                // D-pad → from anywhere in the rail jumps into the detail pane's first row.
                modifier = Modifier
                    .fillMaxHeight()
                    .onGloballyPositioned { c -> rootCoords?.let { railBounds = it.localBoundingBoxOf(c) } }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                            detailRequester.tryRequestFocus(); true
                        } else false
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(top = 4.dp, bottom = 4.dp, start = RailHorizontalPadding, end = RailHorizontalPadding)
                        .selectableGroup(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(RailItemSpacing),
                ) {
                    // Items are bare [WideNavigationRailItem]s sized to their content and centered by
                    // the column. Each sits in a fixed-min-height [Box] (RailItemMinHeight) to restore
                    // the per-item height the real rail enforced. Both that and RailHorizontalPadding /
                    // RailItemSpacing are levers below.

                    // First item: the scope button. The wrapping Box captures the row bounds (relative
                    // to the root Box) so the detached fly-out + separator can anchor off it.
                    Box(
                        modifier = Modifier
                            .heightIn(min = RailItemMinHeight)
                            .onGloballyPositioned { c ->
                                rootCoords?.let { scopeBounds = it.localBoundingBoxOf(c) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        WideNavigationRailItem(
                            selected = false,
                            onClick = { menuOpen = true },
                            icon = { Icon(Icons.Filled.Layers, contentDescription = null) },
                            label = { NameableText(scopeLabel, maxWidth = ScopeLabelMaxWidth) },
                            railExpanded = false,
                            modifier = Modifier.testTag("rail-scope"),
                        )
                    }

                    sections.forEachIndexed { index, section ->
                        val interaction = remember(section.id) { MutableInteractionSource() }
                        val focused by interaction.collectIsFocusedAsState()
                        // Focus == selection: arrowing onto a section previews it in the detail pane.
                        LaunchedEffect(focused) {
                            if (focused && section.enabled) onSectionSelected(section.id)
                        }
                        Box(
                            modifier = Modifier
                                .heightIn(min = RailItemMinHeight)
                                // Extra breathing room above the first section, setting it (and the
                                // scope divider above) apart from the scope button.
                                .then(if (index == 0) Modifier.padding(top = FirstSectionTopGap) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            WideNavigationRailItem(
                                selected = section.id == selectedSectionId,
                                onClick = { onSectionSelected(section.id) },
                                icon = { Icon(section.icon ?: Icons.Filled.Circle, contentDescription = null) },
                                label = { Text(section.label) },
                                railExpanded = false,
                                enabled = section.enabled,
                                interactionSource = interaction,
                                modifier = Modifier.testTag("section-rail-item:${section.id}"),
                            )
                        }
                    }
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

        // Detached scope fly-out: a zero-content anchor spanning the rail width at the scope item's
        // row, with the menu hanging off the rail's right edge. Living at the root Box keeps it out
        // of the rail's vertical item layout, so opening it no longer pushes the section items down.
        // Anchored off the rail's captured bounds (the rail wraps its content, so its width varies).
        val bounds = scopeBounds
        val rail = railBounds
        if (bounds != null && rail != null) {
            Box(
                Modifier
                    .absoluteOffset { IntOffset(rail.left.roundToInt(), bounds.top.roundToInt()) }
                    .size(
                        width = with(density) { rail.width.toDp() },
                        height = with(density) { bounds.height.toDp() },
                    ),
            ) {
                ScopeFlyout(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    // anchor.width drives the menu's right offset; height aligns it to the row top.
                    anchor = Rect(0f, 0f, rail.width, bounds.height),
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
            // adds no layout height of its own. Centered on the rail. Sets the scope button apart
            // from the traditional section items.
            HorizontalDivider(
                modifier = Modifier
                    .absoluteOffset {
                        val x = rail.left + (rail.width - ScopeDividerWidthPx(density)) / 2f
                        val y = bounds.bottom + ScopeDividerGapPx(density)
                        IntOffset(x.roundToInt(), y.roundToInt())
                    }
                    .width(ScopeDividerWidth),
            )
        }
    }
}

/** Horizontal padding on each side of the rail item column (lever) — sets how snug the rail is. */
private val RailHorizontalPadding = 4.dp

/** Vertical spacing between rail items (lever). Matches the M3 collapsed rail's 4dp item spacing. */
private val RailItemSpacing = 4.dp

/** Per-item min height (lever) — restores the height the real rail enforced (M3 token = 64dp). */
private val RailItemMinHeight = 64.dp

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
