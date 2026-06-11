package com.mapo.ui.screen.remap

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mapo.data.model.steam.ControllerConfig

/**
 * One section in the Remap rail. [icon] drives the M3-expressive item glyph; null falls
 * back to a neutral dot. Disabled sections render dimmed, are skipped by gamepad focus
 * wraparound, and are inert to tap — for surfacing "coming soon" sections.
 */
data class SectionedPaneItem(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
)

private const val BACK_KEY = "__back__"
private const val LAYER_KEY = "__layer__"
private const val DISABLED_ALPHA = 0.38f
private val RailWidth = 240.dp

/**
 * The Remap screen's left control surface, re-imagined as a single M3-expressive navigation
 * rail (replacing the old `TopAppBar` + section list). Top to bottom:
 *
 *  - **Back / "Controls"** — tap (or D-pad A/→) navigates home.
 *  - **Scope entry** — the current action set / layer; tap opens a fly-out of all sets with
 *    their layers indented (modeled on the Edit Overlay scope picker), each row carrying a
 *    kebab → Rename / Duplicate / Delete, plus "Add layer" per set and "Add set".
 *  - **Sections** — Buttons / D-Pad / Triggers / Joysticks / Gyro; focusing one selects it
 *    (drives the detail pane), D-pad → enters the detail pane.
 *
 * Gamepad model (ported from the prior section rail): Up/Down wrap through the focusable
 * rows (skipping disabled sections); →/A/Enter activates the focused row; focus on a section
 * *is* its selection. The whole right pane is reserved for [detailPane].
 */
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
    val rowOrder = remember(sections) { listOf(BACK_KEY, LAYER_KEY) + sections.map { it.id } }
    val requesters = remember(rowOrder) { rowOrder.associateWith { FocusRequester() } }
    val detailRequester = remember(selectedSectionId) { FocusRequester() }
    // Ordered list of focusable rows for wraparound (disabled sections excluded).
    val focusOrder = remember(sections) {
        listOf(BACK_KEY, LAYER_KEY) + sections.filter { it.enabled }.map { it.id }
    }
    fun neighbor(fromKey: String, dir: Int): String? {
        val idx = focusOrder.indexOf(fromKey)
        if (idx < 0 || focusOrder.isEmpty()) return null
        val n = ((idx + dir) % focusOrder.size + focusOrder.size) % focusOrder.size
        return focusOrder[n]
    }
    fun focus(key: String?) = key?.let { requesters[it]?.tryRequestFocus() }

    // Land focus on the active section so the rail is gamepad-ready and the detail matches.
    LaunchedEffect(Unit) { requesters[selectedSectionId]?.tryRequestFocus() }

    Row(modifier = modifier.fillMaxSize()) {
        // M3 role: surfaceContainer — the nav-rail plane.
        Surface(
            modifier = Modifier.width(RailWidth).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Back / title row.
                RailRow(
                    selected = false,
                    enabled = true,
                    testTag = "rail-back",
                    focusRequester = requesters[BACK_KEY]!!,
                    onFocused = {},
                    onClick = onBack,
                    onActivateRight = onBack,
                    onUp = { focus(neighbor(BACK_KEY, -1)) },
                    onDown = { focus(neighbor(BACK_KEY, +1)) },
                ) { contentColor ->
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = contentColor)
                    Spacer(Modifier.width(16.dp))
                    Text("Controls", style = MaterialTheme.typography.titleLarge, color = contentColor, maxLines = 1)
                }

                // Scope (action set / layer) entry + its fly-out.
                ScopeRailEntry(
                    scopeLabel = scopeLabel,
                    focusRequester = requesters[LAYER_KEY]!!,
                    onUp = { focus(neighbor(LAYER_KEY, -1)) },
                    onDown = { focus(neighbor(LAYER_KEY, +1)) },
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

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))

                // Section rows.
                sections.forEach { section ->
                    RailRow(
                        selected = section.id == selectedSectionId,
                        enabled = section.enabled,
                        testTag = "section-rail-item:${section.id}",
                        focusRequester = requesters[section.id]!!,
                        onFocused = { onSectionSelected(section.id) },
                        onClick = { onSectionSelected(section.id) },
                        onActivateRight = {
                            onSectionSelected(section.id)
                            detailRequester.tryRequestFocus()
                        },
                        onUp = { focus(neighbor(section.id, -1)) },
                        onDown = { focus(neighbor(section.id, +1)) },
                    ) { contentColor ->
                        Icon(
                            section.icon ?: Icons.Filled.Circle,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            section.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = contentColor,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
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
 * Shared shell for a rail row: the expressive active-indicator background, focus + gamepad
 * key handling, and ripple, with the icon/label supplied by [content]. Keeping the
 * `focusRequester` + `clickable` on one modifier chain is what lets the cross-row
 * wraparound + cross-pane focus work (a stock `NavigationRailItem` hides its focus target
 * internally, which would break it).
 */
@Composable
private fun RailRow(
    selected: Boolean,
    enabled: Boolean,
    testTag: String?,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onActivateRight: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.(contentColor: Color) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue = if (selected && enabled) colors.secondaryContainer else Color.Transparent,
        label = "railRowContainer",
    )
    val contentColor = when {
        !enabled -> colors.onSurface.copy(alpha = DISABLED_ALPHA)
        selected -> colors.onSecondaryContainer
        else -> colors.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused && enabled) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight, Key.Enter, Key.ButtonA, Key.NumPadEnter -> { onActivateRight(); true }
                    Key.DirectionDown -> { onDown(); false }
                    Key.DirectionUp -> { onUp(); false }
                    else -> false
                }
            }
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = { content(contentColor) },
    )
}

/**
 * The scope (action set / layer) rail row plus its fly-out [DropdownMenu]. The menu lists
 * every action set (filled [Icons.Filled.Layers]) with its layers indented beneath
 * (outlined [Icons.Outlined.Layers]); the current scope is primary-tinted + checked. Each
 * set/layer row carries a kebab → Rename / Duplicate / Delete; "Add layer" trails each set,
 * "Add set" the whole list. Any action closes the fly-out.
 */
@Composable
private fun ScopeRailEntry(
    scopeLabel: String,
    focusRequester: FocusRequester,
    onUp: () -> Unit,
    onDown: () -> Unit,
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
        RailRow(
            selected = false,
            enabled = true,
            testTag = "rail-scope",
            focusRequester = focusRequester,
            onFocused = {},
            onClick = { menuOpen = true },
            onActivateRight = { menuOpen = true },
            onUp = onUp,
            onDown = onDown,
        ) { contentColor ->
            Icon(Icons.Filled.Layers, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Text(
                scopeLabel,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = contentColor)
        }
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

/** Try-best focus request — swallows the "not laid out yet" race on first composition. */
private fun FocusRequester.tryRequestFocus() {
    runCatching { requestFocus() }
}
