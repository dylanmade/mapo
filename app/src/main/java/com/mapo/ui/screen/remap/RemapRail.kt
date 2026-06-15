package com.mapo.ui.screen.remap

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * The Remap screen's left **section-nav** rail, built on the real M3-expressive
 * [WideNavigationRail]: section slots (Buttons / D-Pad / Triggers / Joysticks / Gyro). Pinned to
 * the **wide / expanded** configuration (icon + label beside), no collapse toggle. The action set
 * / layer scope picker lives in the app bar (see `RemapControlsScreen`), not here. Right pane =
 * [detailPane].
 *
 * `windowInsets` is zeroed (the Scaffold already applies the inset). Rail container is
 * `surfaceContainer`, one step up from the `surface` detail pane. Gamepad: focusing a section
 * selects it; D-pad → enters the detail pane.
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
    // Forced wide: pin the rail to its expanded (icon + label-beside) configuration, no toggle.
    val railState = rememberWideNavigationRailState(WideNavigationRailValue.Expanded)

    Row(modifier = modifier.fillMaxSize()) {
        WideNavigationRail(
            state = railState,
            // M3 role: surfaceContainer — one step up from the surface detail pane.
            colors = WideNavigationRailDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            // Scaffold already applies the status-bar inset; don't double it.
            windowInsets = WindowInsets(0, 0, 0, 0),
            contentPadding = PaddingValues(top = 8.dp, bottom = 4.dp),
            // D-pad → from anywhere in the rail jumps into the detail pane's first row.
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    detailRequester.tryRequestFocus(); true
                } else false
            },
        ) {
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
                    icon = { Icon(section.icon ?: Icons.Filled.Circle, contentDescription = null) },
                    label = { Text(section.label) },
                    railExpanded = true,
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

/** Try-best focus request — swallows the "not laid out yet" race. */
private fun FocusRequester.tryRequestFocus() {
    runCatching { requestFocus() }
}
