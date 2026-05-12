package com.mapo.ui.component.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * One item in the master rail of [SectionedListDetailPane].
 *
 * Disabled items render at reduced alpha and are skipped by gamepad focus
 * navigation but still hit-testable (tap is a no-op). This lets a screen
 * surface "coming soon" sections without breaking D-pad wraparound.
 */
data class SectionedPaneItem(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
)

/**
 * A two-pane "master rail + detail" layout, modeled after Steam Input's binding
 * configurator. Default split is 30/70.
 *
 * Navigation model:
 *  - On first composition, focus lands on the first enabled section.
 *  - Section focus is the selection — focusing a section updates the detail pane.
 *    Tap (touch / mouse / Enter) and D-pad Up/Down both move focus + selection.
 *  - D-pad Up/Down wraps around (skipping disabled sections).
 *  - D-pad Right, A button, or Enter on a section moves focus into the detail pane
 *    via [firstDetailFocusRequester] — the caller attaches it to the first row of
 *    their detail content.
 *  - D-pad Left from the detail pane returns focus to the active section via
 *    Compose's default geometric focus traversal.
 *
 * Material 3: the rail uses `surfaceContainer` as background and `surfaceContainerHighest`
 * for the selected item, matching M3's selected-row treatment without leaning on
 * NavigationRail (which is sized for app-level navigation, not in-screen sectioning).
 */
@Composable
fun SectionedListDetailPane(
    sections: List<SectionedPaneItem>,
    selectedSectionId: String,
    onSectionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    leftPaneWeight: Float = 0.3f,
    rightPaneWeight: Float = 0.7f,
    detailPane: @Composable (selectedId: String, firstDetailFocusRequester: FocusRequester) -> Unit,
) {
    // Per-section focus requesters keyed by id so wraparound + cross-pane focus
    // can target specific items. Detail focus is keyed on selectedSectionId so a
    // fresh requester is generated each time the detail content swaps.
    val sectionRequesters = remember(sections.map { it.id }) {
        sections.associate { it.id to FocusRequester() }
    }
    val detailRequester = remember(selectedSectionId) { FocusRequester() }
    val firstEnabledId = remember(sections) { sections.firstOrNull { it.enabled }?.id }

    LaunchedEffect(firstEnabledId) {
        firstEnabledId?.let { sectionRequesters[it]?.tryRequestFocus() }
    }

    Row(modifier = modifier.fillMaxSize()) {
        // M3 role: surfaceContainer — vertical rail under the same plane as a NavigationRail.
        Surface(
            modifier = Modifier
                .weight(leftPaneWeight)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                sections.forEachIndexed { index, section ->
                    SectionRailItem(
                        section = section,
                        selected = section.id == selectedSectionId,
                        focusRequester = sectionRequesters[section.id]!!,
                        onSelect = { if (section.enabled) onSectionSelected(section.id) },
                        onEnterDetail = { detailRequester.tryRequestFocus() },
                        onWraparoundUp = {
                            sectionWraparound(sections, index, -1)?.let { id ->
                                sectionRequesters[id]?.tryRequestFocus()
                            }
                        },
                        onWraparoundDown = {
                            sectionWraparound(sections, index, +1)?.let { id ->
                                sectionRequesters[id]?.tryRequestFocus()
                            }
                        },
                    )
                }
            }
        }
        // M3 role: surface — primary content plane.
        Surface(
            modifier = Modifier
                .weight(rightPaneWeight)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            detailPane(selectedSectionId, detailRequester)
        }
    }
}

@Composable
private fun SectionRailItem(
    section: SectionedPaneItem,
    selected: Boolean,
    focusRequester: FocusRequester,
    onSelect: () -> Unit,
    onEnterDetail: () -> Unit,
    onWraparoundUp: () -> Unit,
    onWraparoundDown: () -> Unit,
) {
    val containerColor = if (selected && section.enabled) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else Color.Transparent
    val contentColor = when {
        !section.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("section-rail-item:${section.id}")
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(containerColor, RoundedCornerShape(12.dp))
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.isFocused && section.enabled) onSelect()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight, Key.Enter, Key.ButtonA, Key.NumPadEnter -> {
                        onEnterDetail()
                        true
                    }
                    Key.DirectionDown -> { onWraparoundDown(); false }
                    Key.DirectionUp -> { onWraparoundUp(); false }
                    else -> false
                }
            }
            .clickable(
                enabled = section.enabled,
                role = Role.Tab,
                onClick = onSelect,
            )
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = section.label,
            color = contentColor,
            style = MaterialTheme.typography.bodyLarge,
            modifier = if (!section.enabled) Modifier.alpha(DISABLED_ALPHA) else Modifier,
        )
    }
}

/** Returns the wraparound neighbor's id at [direction] (+1 down, -1 up), skipping disabled. */
private fun sectionWraparound(
    sections: List<SectionedPaneItem>,
    fromIndex: Int,
    direction: Int,
): String? {
    if (sections.isEmpty()) return null
    val size = sections.size
    var i = fromIndex
    repeat(size) {
        i = ((i + direction) % size + size) % size
        if (sections[i].enabled) return sections[i].id
    }
    return null
}

/** Try-best focus request — swallows the "not laid out yet" race on first composition. */
private fun FocusRequester.tryRequestFocus() {
    runCatching { requestFocus() }
}

internal const val DISABLED_ALPHA = 0.38f
