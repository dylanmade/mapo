package com.mapo.ui.screen.keyboard

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mapo.data.model.GridLayout
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Combined gesture detector for tabs:
 * - quick release before long-press timer → tap (select)
 * - drag before long-press timer → not consumed, lets LazyRow scroll
 * - long-press timer fires → haptic, open contextual menu IMMEDIATELY
 *   - drag begins → close menu, switch to reorder mode (displacement-based)
 *   - release without drag → menu stays open until tap-outside
 *
 * Reorder mode does NOT mutate the in-memory tab list while dragging — the dragged tab
 * follows the finger via translation, and other tabs slide aside via animated displacement.
 * The actual reorder commit fires once on drag end.
 */
@Composable
fun KeyboardTabBar(
    layouts: List<GridLayout>,
    selectedIndex: Int,
    isEditMode: Boolean,
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
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val tabBounds = remember { mutableStateMapOf<Long, Rect>() }

    var draggingId by remember { mutableStateOf<Long?>(null) }
    var draggingFromIndex by remember { mutableStateOf(-1) }
    var draggingTargetIndex by remember { mutableStateOf(-1) }
    var draggingPointerX by remember { mutableStateOf(0f) }
    // Captured ONCE at drag start to keep translation math stable. Re-reading tabBounds during
    // the drag would oscillate translationX whenever boundsInParent re-emits.
    var draggingNaturalCenter by remember { mutableStateOf(0f) }
    var draggingTabWidth by remember { mutableStateOf(0f) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isEditMode) {
            IconButton(
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollBy(-CHEVRON_SCROLL_PX)
                    }
                },
                enabled = listState.canScrollBackward,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Scroll tabs left",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        LazyRow(
            modifier = Modifier.weight(1f),
            state = listState,
            horizontalArrangement = Arrangement.Start
        ) {
            itemsIndexed(layouts, key = { _, l -> l.id }) { index, layout ->
                val isDragging = draggingId == layout.id
                // Always-fresh `index` for the gesture coroutine, which is launched ONCE per
                // pointerInput key set and would otherwise capture a stale index after reorders.
                val currentIndex by rememberUpdatedState(index)
                val targetDisplacement: Float = when {
                    draggingId == null || isDragging -> 0f
                    draggingFromIndex < draggingTargetIndex &&
                            index in (draggingFromIndex + 1)..draggingTargetIndex -> -draggingTabWidth
                    draggingFromIndex > draggingTargetIndex &&
                            index in draggingTargetIndex until draggingFromIndex -> draggingTabWidth
                    else -> 0f
                }
                val displacementAnim = remember(layout.id) { Animatable(0f) }
                LaunchedEffect(targetDisplacement, draggingId) {
                    if (draggingId == null) {
                        displacementAnim.snapTo(0f)
                    } else if (displacementAnim.value != targetDisplacement) {
                        displacementAnim.animateTo(targetDisplacement)
                    }
                }

                // OUTER box: natural layout slot. Hosts pointerInput + bounds capture. Crucially
                // NO graphicsLayer here — pointerInput's local coords are post-graphicsLayer, so
                // putting the transform on the same node creates a feedback loop where the tab
                // chases the finger but the finger appears stationary in tab-local coords.
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .widthIn(min = 80.dp)
                        .zIndex(if (isDragging) 1f else 0f)
                        .onGloballyPositioned { coords ->
                            tabBounds[layout.id] = coords.boundsInParent()
                        }
                        .pointerInput(layout.id, isEditMode) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (isEditMode) return@awaitEachGesture

                                val touchSlop = viewConfiguration.touchSlop
                                val longPressMs = viewConfiguration.longPressTimeoutMillis
                                val downPos = down.position

                                val longPressed: Boolean = try {
                                    withTimeout(longPressMs) {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                                ?: continue
                                            if (!change.pressed) {
                                                change.consume()
                                                onSelectIndex(currentIndex)
                                                return@withTimeout false
                                            }
                                            val moved = (change.position - downPos).getDistance()
                                            if (moved > touchSlop) {
                                                return@withTimeout false
                                            }
                                        }
                                        @Suppress("UNREACHABLE_CODE") false
                                    }
                                } catch (_: PointerEventTimeoutCancellationException) {
                                    true
                                }

                                if (!longPressed) return@awaitEachGesture

                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPressMenu(layout.id)

                                var dragStarted = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: continue
                                    change.consume()
                                    if (!change.pressed) {
                                        if (dragStarted) {
                                            val from = draggingFromIndex
                                            val to = draggingTargetIndex
                                            draggingId = null
                                            draggingFromIndex = -1
                                            draggingTargetIndex = -1
                                            draggingPointerX = 0f
                                            draggingNaturalCenter = 0f
                                            draggingTabWidth = 0f
                                            if (from >= 0 && to >= 0 && from != to) {
                                                onReorder(from, to)
                                            }
                                        }
                                        break
                                    }
                                    val totalMoved = (change.position - downPos).getDistance()
                                    if (!dragStarted && totalMoved > touchSlop) {
                                        dragStarted = true
                                        onCloseMenu()
                                        val r = tabBounds[layout.id]
                                        if (r != null) {
                                            draggingNaturalCenter = r.center.x
                                            draggingTabWidth = r.width
                                        }
                                        draggingId = layout.id
                                        draggingFromIndex = currentIndex
                                        draggingTargetIndex = currentIndex
                                    }
                                    if (dragStarted) {
                                        val deltaSinceDown = change.position.x - downPos.x
                                        draggingPointerX = draggingNaturalCenter + deltaSinceDown
                                        val newTarget = computeTargetIndex(
                                            pointerX = draggingPointerX,
                                            layouts = layouts,
                                            bounds = tabBounds,
                                            fallback = draggingTargetIndex.coerceAtLeast(currentIndex)
                                        )
                                        if (newTarget >= 0) draggingTargetIndex = newTarget
                                    }
                                }
                            }
                        }
                ) {
                    // INNER box: visual transform only. Decoupled from gesture detection.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = if (isDragging) {
                                    draggingPointerX - draggingNaturalCenter
                                } else displacementAnim.value
                            }
                    ) {
                        TabSurface(
                            label = layout.name,
                            selected = index == selectedIndex,
                            backgroundOverride = layout.backgroundColorArgb?.let { Color(it) }
                        )
                    }

                    DropdownMenu(
                        expanded = tabContextMenuFor == layout.id,
                        onDismissRequest = onCloseMenu
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit buttons") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                onCloseMenu()
                                onMenuEditButtons(layout.id)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Configure keyboard") },
                            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                            onClick = {
                                onCloseMenu()
                                onMenuConfigure(layout.id)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate keyboard") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = {
                                onCloseMenu()
                                onMenuDuplicate(layout.id)
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
                                onMenuRemove(layout.id)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Save as template") },
                            leadingIcon = { Icon(Icons.Default.Bookmark, contentDescription = null) },
                            onClick = {
                                onCloseMenu()
                                onMenuSaveTemplate(layout.id)
                            }
                        )
                    }
                }
            }
        }
        if (!isEditMode) {
            IconButton(
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollBy(CHEVRON_SCROLL_PX)
                    }
                },
                enabled = listState.canScrollForward,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Scroll tabs right",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun TabSurface(
    label: String,
    selected: Boolean,
    backgroundOverride: Color?
) {
    val container = backgroundOverride
        ?: if (selected) MaterialTheme.colorScheme.secondaryContainer
           else Color.Transparent
    val labelColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                     else MaterialTheme.colorScheme.onSurfaceVariant
    val indicatorColor = MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(container)
            .drawBehind {
                if (selected) {
                    val strokePx = 3.dp.toPx()
                    drawRect(
                        color = indicatorColor,
                        topLeft = Offset(0f, size.height - strokePx),
                        size = Size(size.width, strokePx)
                    )
                }
            }
            .padding(horizontal = 12.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = labelColor
        )
    }
}

private fun computeTargetIndex(
    pointerX: Float,
    layouts: List<GridLayout>,
    bounds: Map<Long, Rect>,
    fallback: Int
): Int {
    layouts.forEachIndexed { i, l ->
        val r = bounds[l.id] ?: return@forEachIndexed
        if (pointerX in r.left..r.right) return i
    }
    val firstBounds = layouts.firstOrNull()?.let { bounds[it.id] }
    val lastBounds = layouts.lastOrNull()?.let { bounds[it.id] }
    return when {
        firstBounds != null && pointerX < firstBounds.left -> 0
        lastBounds != null && pointerX > lastBounds.right -> layouts.lastIndex
        else -> fallback
    }
}

private const val CHEVRON_SCROLL_PX = 240f
