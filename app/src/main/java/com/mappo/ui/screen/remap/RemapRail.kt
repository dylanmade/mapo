package com.mappo.ui.screen.remap

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

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
 * The Remap screen's left rail: a narrow `surfaceContainer` [Surface] holding a centered [Column]
 * of [WideNavigationRailItem]s (collapsed icon-over-label look) — the section items (Buttons /
 * D-Pad / Triggers / Joysticks / Gyro). Right pane = [detailPane].
 *
 * (The rail's former first item — the action set / layer scope button + fly-out — was replaced
 * 2026-07-10 by the top tab bar (`RemapTopBar`), which is now the set/layer selection surface.
 * The rail is pure section navigation.)
 *
 * We hand-roll the rail rather than use [androidx.compose.material3.WideNavigationRail] because
 * that component forces a fixed 96dp collapsed container and places its items at x=0 — forcing it
 * narrower knocked the items off-center. Instead a [Column] with centered alignment **wraps its
 * content**, so the rail comes out a touch narrower than 96dp with reliably centered items.
 * Tunables: [RailHorizontalPadding], [RailItemSpacing], [RailItemMinHeight]. Rail is one step up
 * from the `surface` detail pane, with a [VerticalDivider] right border. Gamepad: focusing a
 * section selects it; D-pad right → enters the detail pane.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RemapRail(
    sections: List<SectionedPaneItem>,
    selectedSectionId: String,
    onSectionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    detailPane: @Composable (selectedId: String, firstDetailFocusRequester: FocusRequester) -> Unit,
) {
    val detailRequester = remember(selectedSectionId) { FocusRequester() }

    Row(modifier = modifier.fillMaxSize()) {
        // M3 role: surfaceContainer — one step up from the surface detail pane. No forced width:
        // the rail wraps its (centered) item column, which comes out a touch narrower than the M3
        // default 96dp container. Forcing a width here is what knocked the items off-center.
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            // D-pad → from anywhere in the rail jumps into the detail pane's first row.
            modifier = Modifier
                .fillMaxHeight()
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
                // the per-item height the real rail enforced.
                sections.forEach { section ->
                    val interaction = remember(section.id) { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    // Focus == selection: arrowing onto a section previews it in the detail pane.
                    LaunchedEffect(focused) {
                        if (focused && section.enabled) onSectionSelected(section.id)
                    }
                    Box(
                        modifier = Modifier.heightIn(min = RailItemMinHeight),
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
}

/** Horizontal padding on each side of the rail item column (lever) — sets how snug the rail is. */
private val RailHorizontalPadding = 4.dp

/** Vertical spacing between rail items (lever). Matches the M3 collapsed rail's 4dp item spacing. */
private val RailItemSpacing = 4.dp

/** Per-item min height (lever) — restores the height the real rail enforced (M3 token = 64dp). */
private val RailItemMinHeight = 64.dp

/** Try-best focus request — swallows the "not laid out yet" race. */
private fun FocusRequester.tryRequestFocus() {
    runCatching { requestFocus() }
}
