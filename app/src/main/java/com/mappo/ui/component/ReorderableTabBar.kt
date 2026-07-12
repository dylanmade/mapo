package com.mappo.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mappo.ui.MappoGesture
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * One tab in a [ReorderableTabBar]. [key] must be unique across the bar (callers mixing entity
 * types prefix them, e.g. `"set:3"` / `"layer:7"`). [fillColor] overrides the tab background;
 * [leadingIcon] renders a small glyph before the label (e.g. an outlined Layers icon marking a
 * layer tab); [dimmed] renders the label in the secondary style even when unselected-styling
 * would already apply (used to visually subordinate child tabs).
 */
data class TabBarItem(
    val key: String,
    val label: String,
    val fillColor: Color? = null,
    val leadingIcon: ImageVector? = null,
    val dimmed: Boolean = false,
)

/**
 * The shared tab bar behavior extracted from the original virtual-keyboard tab UI
 * (`KeyboardTabBar`) so the Remap Controls action-set/layer tabs get the same fully-fleshed
 * gesture model. Combined gesture detector per tab:
 * - quick release before long-press timer → tap (select)
 * - drag before long-press timer → not consumed, lets the LazyRow scroll
 * - long-press timer fires → haptic, open contextual menu IMMEDIATELY
 *   - drag begins (when [reorderEnabled]) → close menu, switch to reorder mode
 *   - release without drag → menu stays open until tap-outside
 *
 * Reorder mode does NOT mutate the in-memory tab list while dragging — the dragged tab follows
 * the finger via translation, and other tabs slide aside via animated displacement. The actual
 * reorder commit fires once on drag end via [onReorder].
 *
 * The long-press menu content is caller-supplied through [menuContent] (rendered inside a
 * [DropdownMenu] anchored to the pressed tab); menu open-state is hoisted ([contextMenuFor] +
 * [onLongPressMenu]/[onCloseMenu]) so callers may keep it in a ViewModel or local state.
 */
@Composable
fun ReorderableTabBar(
    tabs: ImmutableList<TabBarItem>,
    selectedKey: String?,
    contextMenuFor: String?,
    onSelect: (key: String) -> Unit,
    onLongPressMenu: (key: String) -> Unit,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    onCloseMenu: () -> Unit,
    modifier: Modifier = Modifier,
    reorderEnabled: Boolean = true,
    tabHeight: Dp = DefaultTabHeight,
    // Dense = sub-Material metrics (smaller type/icons/padding) for compressed hosts like the
    // Remap Controls top bar.
    dense: Boolean = false,
    menuContent: @Composable ColumnScope.(tab: TabBarItem) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val tabBounds = remember { mutableStateMapOf<String, Rect>() }

    var draggingKey by remember { mutableStateOf<String?>(null) }
    var draggingFromIndex by remember { mutableStateOf(-1) }
    var draggingTargetIndex by remember { mutableStateOf(-1) }
    var draggingPointerX by remember { mutableStateOf(0f) }
    // Captured ONCE at drag start to keep translation math stable. Re-reading tabBounds during
    // the drag would oscillate translationX whenever boundsInParent re-emits.
    var draggingNaturalCenter by remember { mutableStateOf(0f) }
    var draggingTabWidth by remember { mutableStateOf(0f) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { coroutineScope.launch { listState.animateScrollBy(-CHEVRON_SCROLL_PX) } },
            enabled = listState.canScrollBackward,
            modifier = Modifier.size(if (dense) 26.dp else 32.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Scroll tabs left",
                modifier = Modifier.size(if (dense) 16.dp else 18.dp),
            )
        }
        LazyRow(
            modifier = Modifier.weight(1f),
            state = listState,
            horizontalArrangement = Arrangement.Start,
        ) {
            itemsIndexed(tabs, key = { _, t -> t.key }) { index, tab ->
                val isDragging = draggingKey == tab.key
                // Always-fresh `index` for the gesture coroutine, which is launched ONCE per
                // pointerInput key set and would otherwise capture a stale index after reorders.
                val currentIndex by rememberUpdatedState(index)
                val targetDisplacement: Float = when {
                    draggingKey == null || isDragging -> 0f
                    draggingFromIndex < draggingTargetIndex &&
                        index in (draggingFromIndex + 1)..draggingTargetIndex -> -draggingTabWidth
                    draggingFromIndex > draggingTargetIndex &&
                        index in draggingTargetIndex until draggingFromIndex -> draggingTabWidth
                    else -> 0f
                }
                // Animatable is keyed on draggingKey so EACH drag gets a fresh instance starting
                // at 0 — kills residual-animation carryover from the previous drag's in-flight
                // tween-to-0.
                val displacement = remember(draggingKey, tab.key) { Animatable(0f) }
                LaunchedEffect(draggingKey, targetDisplacement) {
                    if (draggingKey != null) {
                        displacement.animateTo(targetDisplacement, tween(durationMillis = 200))
                    }
                }

                // OUTER box: natural layout slot. Hosts pointerInput + bounds capture. Crucially
                // NO graphicsLayer here — pointerInput's local coords are post-graphicsLayer, so
                // putting the transform on the same node creates a feedback loop where the tab
                // chases the finger but the finger appears stationary in tab-local coords.
                Box(
                    modifier = Modifier
                        .height(tabHeight)
                        .testTag("tab:" + tab.key)
                        .zIndex(if (isDragging) 1f else 0f)
                        .onGloballyPositioned { coords ->
                            tabBounds[tab.key] = coords.boundsInParent()
                        }
                        .pointerInput(tab.key, reorderEnabled) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)

                                // Select on touch-down — feels more responsive, and means a
                                // long-press menu action starts with the right tab's content
                                // already visible.
                                onSelect(tab.key)

                                val touchSlop = viewConfiguration.touchSlop
                                val reorderSlop = MappoGesture.reorderSlopPx(viewConfiguration)
                                val longPressMs = viewConfiguration.longPressTimeoutMillis
                                val downPos = down.position

                                var releasedOrMoved = false
                                val longPressed: Boolean = try {
                                    withTimeout(longPressMs) {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                                ?: continue
                                            if (!change.pressed) {
                                                change.consume()
                                                releasedOrMoved = true
                                                break
                                            }
                                            val moved = (change.position - downPos).getDistance()
                                            if (moved > touchSlop) {
                                                releasedOrMoved = true
                                                break
                                            }
                                        }
                                    }
                                    !releasedOrMoved
                                } catch (_: PointerEventTimeoutCancellationException) {
                                    true
                                }

                                if (!longPressed) return@awaitEachGesture

                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPressMenu(tab.key)

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
                                            draggingKey = null
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
                                    if (!dragStarted && reorderEnabled && totalMoved > reorderSlop) {
                                        dragStarted = true
                                        onCloseMenu()
                                        val r = tabBounds[tab.key]
                                        if (r != null) {
                                            draggingNaturalCenter = r.center.x
                                            draggingTabWidth = r.width
                                        }
                                        draggingKey = tab.key
                                        draggingFromIndex = currentIndex
                                        draggingTargetIndex = currentIndex
                                    }
                                    if (dragStarted) {
                                        val deltaSinceDown = change.position.x - downPos.x
                                        draggingPointerX = draggingNaturalCenter + deltaSinceDown
                                        draggingTargetIndex = computeTargetIndex(
                                            draggedCenter = draggingPointerX,
                                            from = currentIndex,
                                            tabs = tabs,
                                            bounds = tabBounds,
                                        )
                                    }
                                }
                            }
                        },
                ) {
                    // INNER box: visual transform only. Decoupled from gesture detection.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = when {
                                    isDragging -> draggingPointerX - draggingNaturalCenter
                                    draggingKey == null -> 0f
                                    else -> displacement.value
                                }
                            },
                    ) {
                        TabSurface(
                            label = tab.label,
                            selected = tab.key == selectedKey,
                            backgroundOverride = tab.fillColor,
                            leadingIcon = tab.leadingIcon,
                            dimmed = tab.dimmed,
                            dense = dense,
                        )
                    }

                    DropdownMenu(
                        expanded = contextMenuFor == tab.key,
                        onDismissRequest = onCloseMenu,
                    ) {
                        menuContent(tab)
                    }
                }
            }
        }
        IconButton(
            onClick = { coroutineScope.launch { listState.animateScrollBy(CHEVRON_SCROLL_PX) } },
            enabled = listState.canScrollForward,
            modifier = Modifier.size(if (dense) 26.dp else 32.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Scroll tabs right",
                modifier = Modifier.size(if (dense) 16.dp else 18.dp),
            )
        }
    }
}

@Composable
private fun TabSurface(
    label: String,
    selected: Boolean,
    backgroundOverride: Color?,
    leadingIcon: ImageVector?,
    dimmed: Boolean,
    dense: Boolean,
) {
    val container = backgroundOverride
        ?: if (selected) MaterialTheme.colorScheme.secondaryContainer
        else Color.Transparent
    val labelColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    val indicatorColor = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(container, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .drawBehind {
                if (selected) {
                    val strokePx = (if (dense) 2.dp else 3.dp).toPx()
                    drawRect(
                        color = indicatorColor,
                        topLeft = Offset(0f, size.height - strokePx),
                        size = Size(size.width, strokePx),
                    )
                }
            }
            .padding(horizontal = if (dense) 10.dp else 14.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                leadingIcon,
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier.size(if (dense) 12.dp else 14.dp),
            )
            Spacer(Modifier.width(if (dense) 5.dp else 6.dp))
        }
        // Tab labels are user-typed names (keyboards, action sets, layers) — cap + ellipsize.
        val base = if (dense) {
            MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 14.sp)
        } else {
            MaterialTheme.typography.labelMedium
        }
        NameableText(
            text = label,
            style = if (selected && !dimmed) base else base.copy(fontWeight = FontWeight.Normal),
            color = labelColor,
        )
    }
}

/**
 * Target index = how many non-dragged tabs sit (by natural center) to the left of the dragged
 * tab's current center. The user has to drag past a tab's midpoint for it to "pass" — exactly
 * the convention Material drag-reorder lists use. Deterministic, no boundary oscillation, and
 * indifferent to per-tab width variation.
 */
private fun computeTargetIndex(
    draggedCenter: Float,
    from: Int,
    tabs: List<TabBarItem>,
    bounds: Map<String, Rect>,
): Int {
    var target = 0
    tabs.forEachIndexed { i, t ->
        if (i == from) return@forEachIndexed
        val center = bounds[t.key]?.center?.x ?: return@forEachIndexed
        if (draggedCenter > center) target++
    }
    return target
}

private val DefaultTabHeight = 40.dp
private const val CHEVRON_SCROLL_PX = 240f
