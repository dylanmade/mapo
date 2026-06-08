package com.mapo.ui.screen.keyboard

import kotlinx.coroutines.flow.StateFlow

/**
 * Variant of [KeyboardHost] to render. Only `Activity` remains — the full-feature in-app
 * keyboard (edit mode, drawer, tab context menus, all CRUD callbacks). The former `Overlay`
 * variant (run-only system-overlay keyboard) was removed when the legacy keyboard-overlay
 * path was gutted in favor of the rebuilt free-positioned button overlay.
 */
sealed interface KeyboardHostMode {

    /**
     * In-activity host: full editing surface. Callbacks fan out to `MainViewModel`
     * actions + `NavController` navigations from the activity context.
     */
    data class Activity(
        // ── Edit-mode state observed by the top bar + grid ───────────────────
        val isEditMode: StateFlow<Boolean>,
        val selectedButtonId: StateFlow<String?>,
        val tabContextMenuFor: StateFlow<Long?>,

        // ── Top-bar callbacks ────────────────────────────────────────────────
        val onOpenTabMenu: (Long) -> Unit,
        val onCloseTabMenu: () -> Unit,
        val onReorderTabs: (Int, Int) -> Unit,
        val onMenuEditButtons: (Long) -> Unit,
        val onToggleEditMode: () -> Unit,
        val onMenuConfigure: (Long) -> Unit,
        val onMenuDuplicate: (Long) -> Unit,
        val onMenuRemove: (Long) -> Unit,
        val onMenuSaveTemplate: (Long) -> Unit,
        val onOpenDrawer: () -> Unit,
        val onAddKeyboard: () -> Unit,

        // ── Grid edit callbacks ──────────────────────────────────────────────
        val onSelectButton: (String) -> Unit,
        val onMoveButton: (String, Int, Int) -> Unit,
        val onResizeButton: (String, Int, Int, Int, Int) -> Unit,
        val onConfigureButton: (String) -> Unit,
        val onDuplicateButton: (String) -> Unit,
        val onRemoveButton: (String) -> Unit,
        val onAddAtCell: (Int, Int) -> Unit,
        val onLongPressEmptyArea: () -> Unit,

        // ── Bottom-bar callbacks ─────────────────────────────────────────────
        val onQuit: () -> Unit,
    ) : KeyboardHostMode
}
