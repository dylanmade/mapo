package com.mappo.ui.screen.keyboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mappo.data.model.GridLayout
import com.mappo.ui.component.ReorderableTabBar
import com.mappo.ui.component.TabBarItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * The virtual-keyboard tab row. The tap / long-press-menu / drag-to-reorder gesture model lives
 * in the shared [ReorderableTabBar] (extracted from here so the Remap Controls action-set tabs
 * reuse it); this wrapper maps [GridLayout]s onto [TabBarItem]s and supplies the
 * keyboard-specific long-press menu.
 */
@Composable
fun KeyboardTabBar(
    layouts: ImmutableList<GridLayout>,
    selectedIndex: Int,
    tabContextMenuFor: Long?,
    onSelectIndex: (Int) -> Unit,
    onLongPressMenu: (Long) -> Unit,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    onCloseMenu: () -> Unit,
    onMenuEditButtons: (Long) -> Unit,
    onMenuConfigure: (Long) -> Unit,
    onMenuDuplicate: (Long) -> Unit,
    onMenuRemove: (Long) -> Unit,
    onMenuSaveTemplate: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = remember(layouts) {
        layouts.map { layout ->
            TabBarItem(
                key = layout.id.toString(),
                label = layout.name,
                fillColor = layout.fillColorArgb?.let { Color(it) },
            )
        }.toImmutableList()
    }
    ReorderableTabBar(
        tabs = tabs,
        selectedKey = layouts.getOrNull(selectedIndex)?.id?.toString(),
        contextMenuFor = tabContextMenuFor?.toString(),
        onSelect = { key -> onSelectIndex(layouts.indexOfFirst { it.id.toString() == key }) },
        onLongPressMenu = { key -> onLongPressMenu(key.toLong()) },
        onReorder = onReorder,
        onCloseMenu = onCloseMenu,
        modifier = modifier,
    ) { tab ->
        val id = tab.key.toLong()
        DropdownMenuItem(
            text = { Text("Edit buttons") },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            onClick = {
                onCloseMenu()
                onMenuEditButtons(id)
            }
        )
        DropdownMenuItem(
            text = { Text("Configure keyboard") },
            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
            onClick = {
                onCloseMenu()
                onMenuConfigure(id)
            }
        )
        DropdownMenuItem(
            text = { Text("Duplicate keyboard") },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
            onClick = {
                onCloseMenu()
                onMenuDuplicate(id)
            }
        )
        DropdownMenuItem(
            text = {
                Text(
                    "Remove keyboard",
                    color = MaterialTheme.colorScheme.error
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            onClick = {
                onCloseMenu()
                onMenuRemove(id)
            }
        )
        DropdownMenuItem(
            text = { Text("Save as template") },
            leadingIcon = { Icon(Icons.Default.Bookmark, contentDescription = null) },
            onClick = {
                onCloseMenu()
                onMenuSaveTemplate(id)
            }
        )
    }
}
