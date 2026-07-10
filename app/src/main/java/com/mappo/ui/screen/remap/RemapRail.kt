package com.mappo.ui.screen.remap

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
 * The advanced editor's left rail: a slim icon-only `surfaceContainer` strip of section items
 * (Buttons / D-Pad / Triggers / Joysticks / Gyro). Hand-rolled compact items (2026-07-10 —
 * `WideNavigationRailItem` + labels burned ~90dp of width; inside the advanced-editor overlay
 * that's wasted space, so items are 40dp icon tiles with `selectable` Tab semantics and the
 * label as the content description). Right pane = [detailPane].
 *
 * Gamepad: focusing a section selects it; D-pad right → enters the detail pane.
 */
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
        // M3 role: surfaceContainer — one step up from the surface detail pane.
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
                    .padding(RailPadding)
                    .selectableGroup(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(RailItemSpacing),
            ) {
                sections.forEach { section ->
                    val interaction = remember(section.id) { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    // Focus == selection: arrowing onto a section previews it in the detail pane.
                    LaunchedEffect(focused) {
                        if (focused && section.enabled) onSectionSelected(section.id)
                    }
                    val selected = section.id == selectedSectionId
                    Box(
                        modifier = Modifier
                            .size(RailItemSize)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent,
                            )
                            .selectable(
                                selected = selected,
                                enabled = section.enabled,
                                role = Role.Tab,
                                interactionSource = interaction,
                                indication = ripple(),
                            ) { onSectionSelected(section.id) }
                            .testTag("section-rail-item:${section.id}")
                            .alpha(if (section.enabled) 1f else 0.38f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            section.icon ?: Icons.Filled.Circle,
                            // The label survives as the accessibility name — no visual label.
                            contentDescription = section.label,
                            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
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

/** Edge padding around the rail's item column. */
private val RailPadding = 4.dp

/** Vertical spacing between rail items. */
private val RailItemSpacing = 4.dp

/** Icon-tile edge — the rail's whole width is this + 2×[RailPadding]. */
private val RailItemSize = 40.dp

/** Try-best focus request — swallows the "not laid out yet" race. */
private fun FocusRequester.tryRequestFocus() {
    runCatching { requestFocus() }
}
