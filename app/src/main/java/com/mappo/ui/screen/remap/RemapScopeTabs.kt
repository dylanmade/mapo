package com.mappo.ui.screen.remap

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mappo.data.model.steam.ControllerConfig
import com.mappo.ui.component.ReorderableTabBar
import com.mappo.ui.component.TabBarItem
import kotlinx.collections.immutable.toImmutableList

/**
 * Actions the scope tab bar can request on a set/layer tab (long-press menu picks). The screen
 * owns the actual dialogs; these just open them for the given id.
 */
internal class RemapScopeTabActions(
    val onRenameSet: (setId: Long) -> Unit,
    val onDuplicateSet: (setId: Long) -> Unit,
    val onDeleteSet: (setId: Long) -> Unit,
    val onAddLayer: (parentSetId: Long) -> Unit,
    val onRenameLayer: (layerId: Long) -> Unit,
    val onDuplicateLayer: (layerId: Long) -> Unit,
    val onDeleteLayer: (layerId: Long) -> Unit,
    val onAddSet: () -> Unit,
)

/**
 * The rebuilt Remap Controls top bar: back button + one tab per action set, each set's layers
 * following it as subordinate tabs (outlined Layers glyph), + a trailing add-set button. Tab
 * behavior (tap / long-press menu / chevron scroll) comes from the shared [ReorderableTabBar]
 * extracted from the virtual-keyboard tab UI. Drag-to-reorder is wired off until a
 * set-reordering repository op exists (the tab order IS the boot-set order, so this is the
 * planned home for that affordance).
 */
@Composable
internal fun RemapTopBar(
    config: ControllerConfig?,
    viewingSetId: Long?,
    viewingLayerId: Long?,
    onSelectActionSet: (Long) -> Unit,
    onSelectLayer: (Long?) -> Unit,
    onBack: () -> Unit,
    actions: RemapScopeTabActions,
    modifier: Modifier = Modifier,
) {
    // surfaceContainer — app-bar plane, one step up from the screen surface.
    Column(modifier = modifier) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                modifier = Modifier.fillMaxWidth().height(TopBarHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(20.dp),
                    )
                }
                // Tabs hug the bar's bottom edge so the selection underline meets the divider.
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    ScopeTabs(
                        config = config,
                        viewingSetId = viewingSetId,
                        viewingLayerId = viewingLayerId,
                        onSelectActionSet = onSelectActionSet,
                        onSelectLayer = onSelectLayer,
                        actions = actions,
                    )
                }
                IconButton(onClick = actions.onAddSet, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Add action set",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

private const val SET_PREFIX = "set:"
private const val LAYER_PREFIX = "layer:"

@Composable
private fun ScopeTabs(
    config: ControllerConfig?,
    viewingSetId: Long?,
    viewingLayerId: Long?,
    onSelectActionSet: (Long) -> Unit,
    onSelectLayer: (Long?) -> Unit,
    actions: RemapScopeTabActions,
) {
    val sets = config?.actionSets.orEmpty()
    val tabs = remember(config) {
        buildList {
            sets.forEach { setGraph ->
                add(TabBarItem(key = SET_PREFIX + setGraph.actionSet.id, label = setGraph.actionSet.title))
                setGraph.layers.forEach { layerGraph ->
                    add(
                        TabBarItem(
                            key = LAYER_PREFIX + layerGraph.layer.id,
                            label = layerGraph.layer.title,
                            leadingIcon = Icons.Outlined.Layers,
                            dimmed = true,
                        ),
                    )
                }
            }
        }.toImmutableList()
    }
    val selectedKey = when {
        viewingLayerId != null -> LAYER_PREFIX + viewingLayerId
        viewingSetId != null -> SET_PREFIX + viewingSetId
        else -> tabs.firstOrNull()?.key
    }
    // Menu state is local — transient chrome, no need to hoist to the ViewModel here.
    var menuFor by remember { mutableStateOf<String?>(null) }

    ReorderableTabBar(
        tabs = tabs,
        selectedKey = selectedKey,
        contextMenuFor = menuFor,
        onSelect = { key ->
            when {
                key.startsWith(SET_PREFIX) -> {
                    onSelectActionSet(key.removePrefix(SET_PREFIX).toLong())
                    onSelectLayer(null)
                }
                key.startsWith(LAYER_PREFIX) -> {
                    val layerId = key.removePrefix(LAYER_PREFIX).toLong()
                    val parentSet = sets.firstOrNull { s -> s.layers.any { it.layer.id == layerId } }
                    parentSet?.let { onSelectActionSet(it.actionSet.id) }
                    onSelectLayer(layerId)
                }
            }
        },
        onLongPressMenu = { menuFor = it },
        onReorder = { _, _ -> /* pending a set-reorder repository op */ },
        onCloseMenu = { menuFor = null },
        reorderEnabled = false,
        tabHeight = ScopeTabHeight,
        dense = true,
    ) { tab ->
        val close = { menuFor = null }
        if (tab.key.startsWith(SET_PREFIX)) {
            val setId = tab.key.removePrefix(SET_PREFIX).toLong()
            DropdownMenuItem(
                text = { Text("Rename set") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = { close(); actions.onRenameSet(setId) },
            )
            DropdownMenuItem(
                text = { Text("Duplicate set") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = { close(); actions.onDuplicateSet(setId) },
            )
            DropdownMenuItem(
                text = { Text("Add layer") },
                leadingIcon = { Icon(Icons.Outlined.Layers, contentDescription = null) },
                onClick = { close(); actions.onAddLayer(setId) },
            )
            DropdownMenuItem(
                text = { Text("Delete set", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { close(); actions.onDeleteSet(setId) },
            )
        } else {
            val layerId = tab.key.removePrefix(LAYER_PREFIX).toLong()
            DropdownMenuItem(
                text = { Text("Rename layer") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = { close(); actions.onRenameLayer(layerId) },
            )
            DropdownMenuItem(
                text = { Text("Duplicate layer") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = { close(); actions.onDuplicateLayer(layerId) },
            )
            DropdownMenuItem(
                text = { Text("Delete layer", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { close(); actions.onDeleteLayer(layerId) },
            )
        }
    }
}

private val TopBarHeight = 40.dp
private val ScopeTabHeight = 32.dp
