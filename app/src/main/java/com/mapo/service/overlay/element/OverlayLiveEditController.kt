package com.mapo.service.overlay.element

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlignHorizontalCenter
import androidx.compose.material.icons.filled.AlignHorizontalLeft
import androidx.compose.material.icons.filled.AlignHorizontalRight
import androidx.compose.material.icons.filled.AlignVerticalBottom
import androidx.compose.material.icons.filled.AlignVerticalCenter
import androidx.compose.material.icons.filled.AlignVerticalTop
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.HorizontalDistribute
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalDistribute
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mapo.data.model.OverlayElement
import com.mapo.data.settings.OverlaySettings
import com.mapo.service.input.InputDispatcher
import com.mapo.service.overlay.OverlayLifecycleOwner
import com.mapo.ui.overlay.OverlayEditActivity
import com.mapo.ui.screen.overlay.OverlayElementConfigContent
import com.mapo.ui.screen.overlay.OverlayElementLabel
import com.mapo.ui.screen.overlay.overlayContentColor
import com.mapo.ui.screen.overlay.overlayFillColor
import com.mapo.ui.screen.overlay.overlayShape
import com.mapo.ui.theme.MapoTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Candidate **C2** for the overlay editor (Brick C of `OVERLAY_REBUILD_PLAN.md`): a live
 * on-overlay editor. Edit mode mounts the real button windows over whatever's
 * foregrounded and lets the user drag them around directly — true WYSIWYG. Writes to the
 * shared [OverlayEditor].
 *
 * Pieces (all `TYPE_APPLICATION_OVERLAY`):
 *  - a full-screen dim **scrim** (signals edit mode + absorbs stray touches so the game
 *    underneath isn't disturbed; tap to deselect),
 *  - one **editable element window** per button — dragged via a raw-coordinate
 *    `OnTouchListener` (the stable chat-head technique; Compose pointer gestures fight a
 *    window that moves under the finger),
 *  - a **toolbar** window (Add / resize / configure / delete / done), and
 *  - a **focusable config** window (the only focusable surface — its label field needs
 *    IME) shown on demand.
 *
 * Trade-off vs the in-app canvas (C1): WYSIWYG over the real game, at the cost of window
 * + focus juggling. Resize is via toolbar zoom in/out here (corner-resize on a small
 * moving window is fiddly) — a prototype simplification.
 */
@Singleton
class OverlayLiveEditController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val overlayEditor: OverlayEditor,
    private val runPresenter: OverlayPresenter,
    private val overlaySettings: OverlaySettings,
    private val inputDispatcher: InputDispatcher,
) {

    /**
     * Frozen game backdrop captured at [requestEdit] time, read by `OverlayEditActivity`.
     * Null when capture is unavailable (API < 30) or failed → the activity uses a plain
     * backdrop. Held here (not passed via Intent) because bitmaps are too large for extras.
     */
    var backdropBitmap: Bitmap? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing
    // Multi-select: tapping buttons accumulates them; dragging any member moves the whole set.
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    // Flipped when the user picks Copy / Copy style. UI-only for now — the real copy of button
    // data + style waits on the virtual-button model rework; this just makes Paste's enabled
    // state demonstrable.
    private val clipboardHasContent = MutableStateFlow(false)

    private val windowManager get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val snapThresholdPx =
        (OverlaySettings.SNAP_THRESHOLD_DP * context.resources.displayMetrics.density).roundToInt()

    private val elementWindows = mutableMapOf<Long, ElementWindow>()
    // Resize chrome for the current selection: 4 corner-handle windows (+ a dashed bounding-box
    // outline window when >1 button is selected). Rebuilt on selection change, repositioned as the
    // selection moves/resizes. Null when nothing is selected.
    private var selectionChrome: SelectionChrome? = null
    private var selectionJob: Job? = null
    private var scrim: Pair<View, OverlayLifecycleOwner>? = null
    private var toolbar: Pair<View, OverlayLifecycleOwner>? = null
    private var toolbarParams: WindowManager.LayoutParams? = null
    private var configWindow: Pair<View, OverlayLifecycleOwner>? = null
    private var confirmWindow: Pair<View, OverlayLifecycleOwner>? = null
    // The editor menu's ROOT is always-visible — it IS the toolbar window (a vertical panel with a
    // drag handle top + bottom). Its submenus open as cascading fly-out windows, one per open
    // level, tracked in [menuStack] (depth 1 = a submenu of a root row, 2 = a submenu of that, …).
    // Outside taps dismiss submenus via each fly-out window's FLAG_WATCH_OUTSIDE_TOUCH (no scrim, so
    // the tap also lands on whatever's underneath — another menu button, a virtual button, etc.).
    private val menuStack = mutableListOf<MenuWindow>()
    // Editor-local toggles surfaced under Options. "Show grid" has no renderer yet (gap); held
    // here as an inert flag (like clipboardHasContent) so the switch animates. Grid size likewise
    // displays the snap grid's division count but isn't editable yet.
    private val showGrid = MutableStateFlow(false)
    private val gridDivisions = MutableStateFlow(OverlaySettings.GRID_DIVISIONS)
    // Align submenu's reference (Selection vs Canvas) — gates which align/space buttons are enabled.
    private val alignTo = MutableStateFlow(AlignTarget.Selection)
    // Core menu orientation: false = vertical panel, true = horizontal icon-only bar ("Rotate menu").
    private val menuHorizontal = MutableStateFlow(false)
    // Center the panel only on its very first sizing; afterwards (incl. rotate) keep its position.
    private var centerToolbarPending = true
    // Captured just before a rotate so the new orientation preserves the old bottom-left corner.
    private var pendingBottomLeft: Point? = null
    // Per-window tap router for the (interop-dragged) core menu — routes a tap to the row/icon under
    // it, since the raw-coord drag filter consumes all touches and children can't use `clickable`.
    private val toolbarRouter = MenuTapRouter()

    fun canShow(): Boolean = Settings.canDrawOverlays(context)
    fun isEditing(): Boolean = _editing.value

    /**
     * Public entry point for editing. Captures a backdrop screenshot (the game, when
     * triggered over it via the QS tile), then launches the foreground [OverlayEditActivity]
     * — which enters lock-task to block home/recents and calls [start]. From the in-app
     * drawer Mapo is foreground, so the backdrop is whatever Mapo was showing (or null).
     */
    fun requestEdit() {
        if (_editing.value) return
        if (!canShow()) {
            Log.w(TAG, "requestEdit() skipped: overlay permission not granted")
            return
        }
        val launch: (Bitmap?) -> Unit = { bmp ->
            backdropBitmap = bmp
            val intent = Intent(context, OverlayEditActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure { Log.e(TAG, "launch OverlayEditActivity failed", it) }
        }
        // Only capture a backdrop when a real (non-Mapo) app is foreground — otherwise the
        // shot would just be Mapo's own UI, so we use a plain canvas instead.
        // (queryPrimaryDisplayForegroundPackage returns null when Mapo is foreground.)
        if (inputDispatcher.queryPrimaryDisplayForegroundPackage() != null) {
            inputDispatcher.captureScreenshot(launch)
        } else {
            launch(null)
        }
    }

    fun start() {
        runOnMain {
            if (_editing.value) return@runOnMain
            if (!canShow()) {
                Log.w(TAG, "start() skipped: overlay permission not granted")
                return@runOnMain
            }
            // Avoid stacking with the run-mode overlay's windows.
            runPresenter.hide()
            _editing.value = true
            selectedIds.value = emptySet()
            addScrim()
            addToolbar()
            collectJob = scope.launch {
                overlayEditor.elements.collect { renderElements(it) }
            }
            // Rebuild the resize chrome whenever the selection changes.
            selectionJob = scope.launch {
                selectedIds.collect { onSelectionChanged() }
            }
            Log.i(TAG, "live edit started")
        }
    }

    fun stop() {
        runOnMain {
            collectJob?.cancel()
            collectJob = null
            selectionJob?.cancel()
            selectionJob = null
            removeChrome()
            dismissConfig()
            dismissExitConfirm()
            dismissSubmenus()
            elementWindows.keys.toList().forEach { detachElement(it) }
            scrim?.let { detach(it) }; scrim = null
            toolbar?.let { detach(it) }; toolbar = null
            toolbarParams = null
            _editing.value = false
            backdropBitmap = null
            Log.i(TAG, "live edit stopped")
        }
    }

    fun toggle() {
        if (isEditing()) stop() else start()
    }

    // ── element windows ─────────────────────────────────────────────────────────

    private fun renderElements(elements: List<OverlayElement>) {
        val size = displaySizePx()
        val desired = elements.map { it.id }.toSet()
        elementWindows.keys.filter { it !in desired }.forEach { detachElement(it) }
        var attachedNew = false
        elements.forEach { element ->
            val existing = elementWindows[element.id]
            if (existing == null) {
                attachElement(element, size)
                attachedNew = true
            } else {
                existing.state.value = element
                val p = existing.params
                val nx = (element.x * size.x).roundToInt()
                val ny = (element.y * size.y).roundToInt()
                val nw = (element.width * size.x).roundToInt().coerceAtLeast(1)
                val nh = (element.height * size.y).roundToInt().coerceAtLeast(1)
                if (p.x != nx || p.y != ny || p.width != nw || p.height != nh) {
                    p.x = nx; p.y = ny; p.width = nw; p.height = nh
                    runCatching { windowManager.updateViewLayout(existing.view, p) }
                }
            }
        }
        // A newly attached button window stacks above everything added earlier. Re-raise the
        // resize chrome (so its handles stay grabbable) and the editor menus (so the core menu +
        // any open submenus always draw above the buttons). Otherwise just reposition the chrome.
        if (attachedNew && selectionChrome != null) onSelectionChanged() else updateSelectionChrome()
        if (attachedNew) raiseEditorMenusAboveButtons()
    }

    /**
     * Re-add (raise) the core menu window and any open submenu windows so they sit above button
     * windows that were just attached. WindowManager has no z-order field for app-overlay windows —
     * a window is raised only by removing and re-adding it (which keeps its content; the ComposeView
     * re-composes on re-attach). Re-added in z-order: toolbar → submenus.
     */
    private fun raiseEditorMenusAboveButtons() {
        // The always-visible core menu is raised blink-free (add-new-then-remove-old). Submenus are
        // transient and usually closed during a button add, so the simpler raise is fine.
        raiseToolbar()
        menuStack.toList().forEach { mw -> raiseWindow(mw.view, mw.params) }
    }

    /** Bring [view]'s overlay window to the top by re-adding it (keeps [params], hence its position). */
    private fun raiseWindow(view: View, params: WindowManager.LayoutParams) {
        runCatching {
            windowManager.removeViewImmediate(view)
            windowManager.addView(view, params)
        }.onFailure { Log.w(TAG, "raiseWindow failed", it) }
    }

    private fun attachElement(element: OverlayElement, size: Point) {
        val owner = OverlayLifecycleOwner()
        val state: MutableState<OverlayElement> = mutableStateOf(element)
        // Per-element touch handler. pointerInteropFilter (not a view OnTouchListener, which
        // never fires on a ComposeView) hands us the raw MotionEvent, so we drag with
        // absolute screen coords (rawX/rawY) anchored to the window position captured at
        // touch-down. That's immune to the "window moves under the finger" lag that made
        // window-relative deltas oscillate/jitter.
        var startRawX = 0f
        var startRawY = 0f
        var dragging = false
        // Window positions of every element that moves with this drag, captured at touch-down.
        // It's the whole selection when this button is part of it, else just this button.
        var groupStart: Map<Long, Point> = emptyMap()
        val onTouch: (MotionEvent) -> Boolean = { ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    dragging = false
                    val sel = selectedIds.value
                    val ids = if (element.id in sel) sel else setOf(element.id)
                    groupStart = ids.mapNotNull { gid ->
                        elementWindows[gid]?.let { gid to Point(it.params.x, it.params.y) }
                    }.toMap()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                    if (dragging) moveGroup(element.id, groupStart, dx, dy)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) handleElementTap(element.id)
                    else onElementsDragEnd(groupStart.keys)
                    true
                }
                else -> false
            }
        }

        val view = ComposeView(context).apply {
            attachOwner(owner)
            setContent {
                MapoTheme {
                    val sel by selectedIds.collectAsStateWithLifecycle()
                    EditableElement(
                        element = state.value,
                        selected = state.value.id in sel,
                        onTouch = onTouch,
                    )
                }
            }
        }
        owner.resumeTo()
        val params = layoutParams(
            width = (element.width * size.x).roundToInt().coerceAtLeast(1),
            height = (element.height * size.y).roundToInt().coerceAtLeast(1),
            focusable = false,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (element.x * size.x).roundToInt()
            y = (element.y * size.y).roundToInt()
        }
        runCatching { windowManager.addView(view, params) }
            .onFailure { Log.e(TAG, "addView(element ${element.id}) failed", it); return }
        elementWindows[element.id] = ElementWindow(view, owner, params, state)
    }

    /**
     * Drag a set of element windows together by [dx]/[dy] from their captured [groupStart]
     * positions. Only the [primaryId] (the one under the finger) snaps; the resolved snap delta
     * is applied to every member so the group keeps its relative layout. Members of the moving
     * group are excluded as snap targets so they don't snap to each other.
     */
    private fun moveGroup(primaryId: Long, groupStart: Map<Long, Point>, dx: Float, dy: Float) {
        val size = displaySizePx()
        val primary = elementWindows[primaryId] ?: return
        val ps = groupStart[primaryId] ?: return
        var px = (ps.x + dx).roundToInt().coerceIn(0, size.x - primary.params.width)
        var py = (ps.y + dy).roundToInt().coerceIn(0, size.y - primary.params.height)
        if (overlaySettings.snapEnabled.value) {
            val snapped = snapPosition(px, py, primary.params.width, primary.params.height, size, groupStart.keys)
            px = snapped.x; py = snapped.y
        }
        val appliedDx = px - ps.x
        val appliedDy = py - ps.y
        groupStart.forEach { (gid, start) ->
            val w = elementWindows[gid] ?: return@forEach
            w.params.x = (start.x + appliedDx).coerceIn(0, size.x - w.params.width)
            w.params.y = (start.y + appliedDy).coerceIn(0, size.y - w.params.height)
            runCatching { windowManager.updateViewLayout(w.view, w.params) }
        }
        // Keep the resize chrome glued to the selection as it moves.
        updateSelectionChrome()
    }

    /**
     * Tap on a button (no drag): build a multi-selection by accumulation. An unselected button
     * is added; a button that's the *sole* selection opens its config; tapping one of several
     * selected buttons removes it. (Tapping empty space clears everything — see [addScrim].)
     */
    private fun handleElementTap(id: Long) {
        val current = selectedIds.value
        when {
            id !in current -> selectedIds.value = current + id
            current.size == 1 -> showConfig(id)
            else -> selectedIds.value = current - id
        }
    }

    /** Delete every selected button and clear the selection. */
    private fun deleteSelected() {
        val ids = selectedIds.value
        ids.forEach { overlayEditor.delete(it) }
        selectedIds.value = emptySet()
    }

    /**
     * Commit the post-drag positions of [ids] in one batch write. Going through a single
     * [OverlayEditor.moveResizeAll] (rather than a [OverlayEditor.moveResize] per id) means the
     * elements flow re-emits once with every new position, so [renderElements] never sees a
     * partially-committed list and never resets an un-committed window back to its old spot —
     * which is what produced the one-frame flash on a multi-button drag.
     */
    private fun onElementsDragEnd(ids: Set<Long>) {
        val size = displaySizePx()
        val rects = ids.mapNotNull { id ->
            val w = elementWindows[id] ?: return@mapNotNull null
            OverlayEditor.ElementRect(
                id = id,
                x = w.params.x.toFloat() / size.x,
                y = w.params.y.toFloat() / size.y,
                width = w.params.width.toFloat() / size.x,
                height = w.params.height.toFloat() / size.y,
            )
        }
        overlayEditor.moveResizeAll(rects)
    }

    private fun detachElement(id: Long) {
        val w = elementWindows.remove(id) ?: return
        detach(w.view to w.owner)
    }

    // ── resize chrome (corner handles + group transform box) ─────────────────────

    /**
     * Rebuild the resize chrome for the current selection. One button → four corner handles that
     * rest on the button's corners and resize it directly. Several buttons → the same four handles
     * on the **union** bounding box plus a dashed outline of that box; dragging a corner scales the
     * whole group about the opposite (fixed) corner, Photoshop-style. Empty selection → no chrome.
     */
    private fun onSelectionChanged() {
        removeChrome()
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        buildChrome(multi = ids.size > 1)
        updateSelectionChrome()
    }

    private fun removeChrome() {
        val chrome = selectionChrome ?: return
        chrome.handles.values.forEach { detach(it.view to it.owner) }
        chrome.box?.let { detach(it.view to it.owner) }
        selectionChrome = null
    }

    private fun buildChrome(multi: Boolean) {
        // Add the (passthrough) outline first so the handle windows stack above it.
        val box = if (multi) addSelectionBoxWindow() else null
        val handles = Corner.values().associateWith { addHandleWindow(it) }
        selectionChrome = SelectionChrome(handles, box)
    }

    /** The selection's union bounding box in screen px, from the live window positions. */
    private fun selectionBoxPx(): android.graphics.Rect? {
        val rects = selectedIds.value.mapNotNull { id ->
            elementWindows[id]?.params?.let { p ->
                android.graphics.Rect(p.x, p.y, p.x + p.width, p.y + p.height)
            }
        }
        if (rects.isEmpty()) return null
        return android.graphics.Rect(
            rects.minOf { it.left }, rects.minOf { it.top },
            rects.maxOf { it.right }, rects.maxOf { it.bottom },
        )
    }

    /** Reposition the chrome windows onto the current selection bounding box. No-op if no chrome. */
    private fun updateSelectionChrome() {
        val chrome = selectionChrome ?: return
        val box = selectionBoxPx() ?: return
        val half = (HANDLE_TOUCH_DP * context.resources.displayMetrics.density / 2f).roundToInt()
        chrome.handles.forEach { (corner, h) ->
            val (cx, cy) = corner.cornerOf(box)
            h.params.x = cx - half
            h.params.y = cy - half
            runCatching { windowManager.updateViewLayout(h.view, h.params) }
        }
        chrome.box?.let { b ->
            b.params.x = box.left; b.params.y = box.top
            b.params.width = box.width().coerceAtLeast(1); b.params.height = box.height().coerceAtLeast(1)
            runCatching { windowManager.updateViewLayout(b.view, b.params) }
        }
    }

    private fun addHandleWindow(corner: Corner): HandleWindow {
        val owner = OverlayLifecycleOwner()
        val onTouch = makeHandleTouch(corner)
        val view = ComposeView(context).apply {
            attachOwner(owner)
            setContent { MapoTheme { HandleDot(onTouch) } }
        }
        owner.resumeTo()
        val sizePx = (HANDLE_TOUCH_DP * context.resources.displayMetrics.density).roundToInt()
        val params = layoutParams(width = sizePx, height = sizePx, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        runCatching { windowManager.addView(view, params) }
            .onFailure { Log.e(TAG, "addView(handle $corner) failed", it) }
        return HandleWindow(corner, view, owner, params)
    }

    private fun addSelectionBoxWindow(): BoxWindow {
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(context).apply {
            attachOwner(owner)
            setContent { MapoTheme { SelectionBoxOutline() } }
        }
        owner.resumeTo()
        // Passthrough (NOT_TOUCHABLE): the dashed outline must never steal a touch from a button.
        val params = layoutParams(width = 1, height = 1, focusable = false, touchable = false).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        runCatching { windowManager.addView(view, params) }
            .onFailure { Log.e(TAG, "addView(selection box) failed", it) }
        return BoxWindow(view, owner, params)
    }

    /**
     * Touch handler for a corner handle. Like the button drag, it works in raw screen coords
     * anchored at touch-down — essential here because the handle window itself moves under the
     * finger as the box resizes. Captures the box + each selected button's rect at DOWN, then on
     * MOVE re-derives the box from the dragged corner and scales every button about the fixed
     * (opposite) corner. Commits the whole group in one batch on UP (no per-window flash).
     */
    private fun makeHandleTouch(corner: Corner): (MotionEvent) -> Boolean {
        var startRawX = 0f
        var startRawY = 0f
        var startBox = android.graphics.Rect()
        var startRects: Map<Long, android.graphics.Rect> = emptyMap()
        var dragging = false
        return { ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX; startRawY = ev.rawY
                    dragging = false
                    startBox = selectionBoxPx() ?: android.graphics.Rect()
                    startRects = selectedIds.value.mapNotNull { id ->
                        elementWindows[id]?.params?.let { p ->
                            id to android.graphics.Rect(p.x, p.y, p.x + p.width, p.y + p.height)
                        }
                    }.toMap()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                    if (dragging && !startBox.isEmpty && startRects.isNotEmpty()) {
                        resizeSelection(corner, startBox, startRects, dx.roundToInt(), dy.roundToInt())
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) onElementsDragEnd(startRects.keys)
                    true
                }
                else -> false
            }
        }
    }

    /** Apply a corner drag: derive the new box, then scale every selected button about the fixed corner. */
    private fun resizeSelection(
        corner: Corner,
        startBox: android.graphics.Rect,
        startRects: Map<Long, android.graphics.Rect>,
        dx: Int,
        dy: Int,
    ) {
        if (startBox.width() <= 0 || startBox.height() <= 0) return
        val size = displaySizePx()
        val minWpx = (OverlayEditor.MIN_SIZE * size.x).roundToInt().coerceAtLeast(1)
        val minHpx = (OverlayEditor.MIN_SIZE * size.y).roundToInt().coerceAtLeast(1)
        // Smallest box that keeps every button at/above MIN_SIZE after scaling.
        val minBoxW = (startBox.width() * startRects.values.maxOf { minWpx.toFloat() / it.width() })
            .roundToInt().coerceIn(1, startBox.width())
        val minBoxH = (startBox.height() * startRects.values.maxOf { minHpx.toFloat() / it.height() })
            .roundToInt().coerceIn(1, startBox.height())

        val newBox = resizedBox(startBox, corner, dx, dy, minBoxW, minBoxH, size)
        val sx = newBox.width().toFloat() / startBox.width()
        val sy = newBox.height().toFloat() / startBox.height()
        val (anchorX, anchorY) = corner.fixedCornerOf(startBox)

        startRects.forEach { (id, er) ->
            val w = elementWindows[id] ?: return@forEach
            w.params.x = (anchorX + (er.left - anchorX) * sx).roundToInt()
            w.params.y = (anchorY + (er.top - anchorY) * sy).roundToInt()
            w.params.width = (er.width() * sx).roundToInt().coerceAtLeast(1)
            w.params.height = (er.height() * sy).roundToInt().coerceAtLeast(1)
            runCatching { windowManager.updateViewLayout(w.view, w.params) }
        }
        updateSelectionChrome()
    }

    /** New box rect after dragging [corner] by [dx]/[dy], with min-size + screen clamping. */
    private fun resizedBox(
        start: android.graphics.Rect,
        corner: Corner,
        dx: Int,
        dy: Int,
        minW: Int,
        minH: Int,
        size: Point,
    ): android.graphics.Rect {
        var left = start.left; var top = start.top; var right = start.right; var bottom = start.bottom
        val leftMoved = corner == Corner.TopLeft || corner == Corner.BottomLeft
        val topMoved = corner == Corner.TopLeft || corner == Corner.TopRight
        if (leftMoved) left = (start.left + dx).coerceIn(0, right - minW)
        else right = (start.right + dx).coerceIn(left + minW, size.x)
        if (topMoved) top = (start.top + dy).coerceIn(0, bottom - minH)
        else bottom = (start.bottom + dy).coerceIn(top + minH, size.y)
        return android.graphics.Rect(left, top, right, bottom)
    }

    // ── scrim + toolbar + config ────────────────────────────────────────────────

    private fun addScrim() {
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(context).apply {
            attachOwner(owner)
            setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            // Tap empty space to deselect all; touches are consumed so they
                            // don't reach the app underneath while editing.
                            detectTapGestures(onTap = { selectedIds.value = emptySet() })
                        },
                )
            }
        }
        owner.resumeTo()
        val params = layoutParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            focusable = false,
        )
        view.setBackgroundColor(SCRIM_COLOR)
        runCatching { windowManager.addView(view, params) }
            .onFailure { Log.e(TAG, "addView(scrim) failed", it); return }
        scrim = view to owner
        // Note: we intentionally do NOT exclude the system back gesture here anymore. Back
        // (gesture or button) should route through the activity's back dispatcher to the
        // "Exit overlay editing?" confirm — an escape hatch, with a guard against accidents.
    }

    /**
     * Build a core-menu ComposeView with its own raw-coord touch handler. The WHOLE menu is
     * grab-and-draggable like the buttons: raw coords anchored at touch-down (immune to the window
     * moving under the finger); past the touch slop it drags, and a tap that never moves is routed
     * to the row/icon under it via [toolbarRouter] (the filter consumes all touches, so children
     * can't use `clickable`).
     */
    private fun createToolbarView(): Pair<View, OverlayLifecycleOwner> {
        val owner = OverlayLifecycleOwner()
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val onTouch: (MotionEvent) -> Boolean = { ev ->
            val p = toolbarParams
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX; startRawY = ev.rawY
                    startX = p?.x ?: 0; startY = p?.y ?: 0
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (p != null) {
                        val dx = ev.rawX - startRawX
                        val dy = ev.rawY - startRawY
                        if (!dragging && hypot(dx, dy) > touchSlop) {
                            dragging = true
                            dismissSubmenus() // starting a drag closes submenus (anchored to the toolbar)
                        }
                        if (dragging) {
                            val size = displaySizePx()
                            val v = toolbar?.first
                            val w = v?.width ?: 0
                            val h = v?.height ?: 0
                            p.x = (startX + dx).roundToInt().coerceIn(minOf(0, size.x - w), maxOf(0, size.x - w))
                            p.y = (startY + dy).roundToInt().coerceIn(minOf(0, size.y - h), maxOf(0, size.y - h))
                            runCatching { windowManager.updateViewLayout(v, p) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) toolbarRouter.route(ev.x, ev.y)
                    true
                }
                else -> true
            }
        }
        val view = ComposeView(context).apply {
            attachOwner(owner)
            setContent { MapoTheme { ToolbarContent(onTouch = onTouch) } }
        }
        owner.resumeTo()
        return view to owner
    }

    /** Wire up the size-to-content + position passes for a (freshly added) toolbar view. */
    private fun wireToolbarResize(view: View) {
        view.post { resizeToolbarToContent() }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resizeToolbarToContent() }
    }

    private fun addToolbar() {
        val (view, owner) = createToolbarView()
        // WRAP_CONTENT here, but the window size is then driven EXPLICITLY by resizeToolbarToContent
        // (measures UNSPECIFIED) so the menu can flex as wide as its content needs, even past the
        // screen edge (NO_LIMITS) — a plain WRAP_CONTENT overlay window caps at the display width.
        val params = layoutParams(
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            focusable = false,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (12 * context.resources.displayMetrics.density).roundToInt()
        }
        runCatching { windowManager.addView(view, params) }
            .onFailure { Log.e(TAG, "addView(toolbar) failed", it); return }
        toolbar = view to owner
        toolbarParams = params
        wireToolbarResize(view)
    }

    /**
     * Raise the core menu above newly-added button windows WITHOUT the one-frame blink of a plain
     * remove+re-add: build a fresh toolbar view, add it on top (the old one stays visible beneath
     * until the new one has rendered the identical content at the same spot), then remove the old.
     */
    private fun raiseToolbar() {
        val old = toolbar ?: return
        val oldParams = toolbarParams ?: return
        val (newView, newOwner) = createToolbarView()
        val newParams = WindowManager.LayoutParams().apply { copyFrom(oldParams) }
        runCatching { windowManager.addView(newView, newParams) }
            .onFailure { Log.e(TAG, "raiseToolbar addView failed", it); return }
        toolbar = newView to newOwner
        toolbarParams = newParams
        wireToolbarResize(newView)
        // Remove the OLD view only AFTER the new one has rendered a frame (double-post → past at
        // least one layout/draw), so the old stays visible underneath until then — no gap, no blink.
        newView.post { newView.post { detach(old) } }
    }

    /**
     * Size the toolbar window to its content's *natural* size, then position it. We measure with an
     * UNSPECIFIED spec (so the window can flex as wide as the content needs, even past the screen —
     * WRAP_CONTENT overlay windows instead cap at the display width and clip the overflow) and set
     * the size EXPLICITLY. [View.forceLayout] before measuring bypasses the spec-keyed measure cache
     * so a rotate re-measures the NEW orientation instead of returning the stale size.
     *
     * Position: first sizing → default (left edge, vertically centered); on a rotate → preserve the
     * bottom-left corner ([pendingBottomLeft]) so it converts roughly in place (and the horizontal
     * bar lands at the vertical menu's bottom-left). Finally clamp on-screen.
     */
    private fun resizeToolbarToContent() {
        val view = toolbar?.first ?: return
        val params = toolbarParams ?: return
        view.forceLayout()
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(unspec, unspec)
        val w = view.measuredWidth
        val h = view.measuredHeight
        if (w <= 0 || h <= 0) return
        val size = displaySizePx()
        var nx = params.x
        var ny = params.y
        val bl = pendingBottomLeft
        when {
            bl != null -> {
                pendingBottomLeft = null
                // New top-left so the bottom-left corner stays put across the rotation.
                nx = bl.x
                ny = bl.y - h
            }
            centerToolbarPending -> {
                centerToolbarPending = false
                // Default: docked to the left, vertically centered. (The window carries the shadow
                // margin, so x = 0 leaves the visible menu a hair in from the screen's left edge.)
                nx = 0
                ny = (size.y - h) / 2
            }
        }
        // Keep on-screen. Allow negative offsets only when the menu genuinely exceeds the screen.
        nx = nx.coerceIn(minOf(0, size.x - w), maxOf(0, size.x - w))
        ny = ny.coerceIn(minOf(0, size.y - h), maxOf(0, size.y - h))
        if (params.width == w && params.height == h && params.x == nx && params.y == ny) return
        params.width = w; params.height = h; params.x = nx; params.y = ny
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * Per-button config as a conventional **M3 modal navigation drawer** ([ConfigDrawer]):
     * a full-screen, focusable window (focusable so the label field's IME works) holding a
     * scrim + a [ModalDrawerSheet] docked to the edge *opposite* the button (so the button
     * stays partly visible behind the scrim). The slide + scrim fade are animated inside
     * Compose, and it dismisses like a real modal drawer — tap the scrim or swipe the sheet
     * back. Edits commit live (WYSIWYG) — the button's window re-renders as the repo emits.
     */
    private fun showConfig(elementId: Long) {
        dismissConfig()
        val element = overlayEditor.elements.value.firstOrNull { it.id == elementId } ?: return
        val owner = OverlayLifecycleOwner()
        // Dock to the side opposite the button (button center on the left half → drawer right).
        val drawerOnStart = (element.x + element.width / 2f) >= 0.5f
        val view = ComposeView(context).apply {
            attachOwner(owner)
            setContent {
                MapoTheme {
                    ConfigDrawer(
                        element = element,
                        elementId = elementId,
                        drawerOnStart = drawerOnStart,
                        onClosed = { removeConfigWindow() },
                    )
                }
            }
        }
        owner.resumeTo()
        // Full-screen + FOCUSABLE: the scrim fills the screen and absorbs outside taps (which
        // dismiss the drawer); focusable so the label TextField can raise the IME. No
        // windowAnimations — the slide + scrim fade are driven in Compose (see ConfigDrawer).
        val params = layoutParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            focusable = true,
        )
        runCatching { windowManager.addView(view, params) }
            .onFailure { Log.e(TAG, "addView(config) failed", it); return }
        configWindow = view to owner
    }

    /**
     * The config drawer content: an M3 [ModalDrawerSheet] over a scrim, both driven by a
     * single [Animatable] "open fraction" (0 = closed/off-screen, 1 = fully open) — the same
     * offset model M3's own `ModalNavigationDrawer` uses, so the sheet slides and the scrim
     * fades in lockstep. Dismiss matches a real modal drawer: tap the scrim, or swipe the
     * sheet back toward its docked edge. [onClosed] removes the host window once the close
     * animation has played.
     */
    @Composable
    private fun ConfigDrawer(
        element: OverlayElement,
        elementId: Long,
        drawerOnStart: Boolean,
        onClosed: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        val openFraction = remember { Animatable(0f) }
        var widthPx by remember { mutableStateOf(0f) }
        var closing by remember { mutableStateOf(false) }
        // Direction the sheet sits off-screen when closed: a start-docked drawer exits left.
        val dir = if (drawerOnStart) -1f else 1f

        // Enter only once measured — we need the width to translate the sheet fully off-screen
        // before sliding it in.
        LaunchedEffect(widthPx) {
            if (widthPx > 0f && !closing) openFraction.animateTo(1f, tween(DRAWER_ANIM_MS))
        }
        fun requestClose() {
            if (closing) return
            closing = true
            scope.launch {
                openFraction.animateTo(0f, tween(DRAWER_ANIM_MS))
                onClosed()
            }
        }

        Box(Modifier.fillMaxSize()) {
            // Scrim — fades with the open fraction; tap outside the sheet to dismiss.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = openFraction.value }
                    .background(DrawerDefaults.scrimColor)
                    .pointerInput(Unit) { detectTapGestures { requestClose() } },
            )
            // The sheet, docked to its edge, sliding + swipe-to-dismiss. DrawerDefaults.shape
            // rounds the trailing (end) corners for a start-docked sheet; mirror it for an
            // end-docked one so the rounded corners face inward, not the screen edge.
            val sheetShape = if (drawerOnStart) {
                DrawerDefaults.shape
            } else {
                RoundedCornerShape(topStart = DRAWER_CORNER_DP.dp, bottomStart = DRAWER_CORNER_DP.dp)
            }
            ModalDrawerSheet(
                drawerShape = sheetShape,
                // surfaceContainerHigh — canonical Mapo drawer container (matches the home drawer).
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .align(if (drawerOnStart) Alignment.CenterStart else Alignment.CenterEnd)
                    .onSizeChanged { widthPx = it.width.toFloat() }
                    .graphicsLayer {
                        // Hide until measured so the first (width-unknown) frame doesn't flash open.
                        alpha = if (widthPx == 0f) 0f else 1f
                        translationX = (1f - openFraction.value) * widthPx * dir
                    }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            if (widthPx <= 0f) return@rememberDraggableState
                            val next = (openFraction.value - delta * dir / widthPx).coerceIn(0f, 1f)
                            scope.launch { openFraction.snapTo(next) }
                        },
                        onDragStopped = {
                            // Settle through the SAME scope as the per-delta snapTo launches so
                            // it's enqueued after the final one (FIFO on the UI dispatcher) and
                            // wins the Animatable's single-mutation slot — otherwise a late snapTo
                            // could cancel the settle and freeze the sheet mid-swipe.
                            scope.launch {
                                // Past halfway back → finish closing; otherwise settle open.
                                if (openFraction.value < 0.5f) {
                                    openFraction.animateTo(0f, tween(DRAWER_ANIM_MS))
                                    if (!closing) { closing = true; onClosed() }
                                } else {
                                    openFraction.animateTo(1f, tween(DRAWER_ANIM_MS))
                                }
                            }
                        },
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 20.dp),
                ) {
                    OverlayElementConfigContent(
                        element = element,
                        onChange = { overlayEditor.update(it) },
                        onDelete = {
                            overlayEditor.delete(elementId)
                            selectedIds.value = selectedIds.value - elementId
                            requestClose()
                        },
                        onDone = { requestClose() },
                    )
                }
            }
        }
    }

    /** Tear down the config window immediately (used by [stop]; the animated close path in
     *  [ConfigDrawer] calls this only after its slide-out has finished). */
    private fun removeConfigWindow() {
        configWindow?.let { detach(it) }
        configWindow = null
    }

    private fun dismissConfig() = removeConfigWindow()

    /**
     * Back-button entry point (called from `OverlayEditActivity`'s back dispatcher): toggle the
     * "Exit overlay editing?" confirm. Drawn as a top overlay window because an activity dialog
     * would sit *beneath* the edit-mode overlay windows.
     */
    fun handleBack() = runOnMain {
        if (confirmWindow != null) dismissExitConfirm() else showExitConfirm(reForegroundOnCancel = false)
    }

    /**
     * Home entry point (from `OverlayEditActivity.onUserLeaveHint`). Home can't be intercepted, so
     * the activity has already gone to the launcher by the time this fires; we raise the same
     * confirm over it. Cancel re-foregrounds the editor (resumes the session); Exit tears it down.
     */
    fun onHomePressed() = runOnMain {
        if (_editing.value && confirmWindow == null) showExitConfirm(reForegroundOnCancel = true)
    }

    /**
     * "Exit overlay editing?" confirm, drawn as a top overlay window (an activity dialog would sit
     * *beneath* the edit windows). The window is full-screen for the modal scrim; the card itself
     * is constrained to a standard dialog width.
     */
    private fun showExitConfirm(reForegroundOnCancel: Boolean) {
        if (confirmWindow != null) return
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(context).apply {
            attachOwner(owner)
            setContent {
                MapoTheme {
                    val onCancel = {
                        dismissExitConfirm()
                        if (reForegroundOnCancel) reForegroundEditActivity()
                    }
                    val onExit = { dismissExitConfirm(); stop() }
                    // Gamepad / key support (the window is focusable): dpad moves between the
                    // buttons via Compose focus; A activates the focused one; B or Back cancels.
                    // DPAD-center / Enter are handled by the buttons themselves.
                    val cancelFocus = remember { FocusRequester() }
                    var focusedAction by remember { mutableStateOf<(() -> Unit)?>(null) }
                    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }

                    // Hand-built to the M3 AlertDialog spec (it can't be a real `AlertDialog`:
                    // that needs an activity window token, and this renders in a system-overlay
                    // ComposeView above the editor's overlay windows). All color/type/shape come
                    // from theme tokens. Scrim = `scrim` token at the standard 0.6 dialog dim.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.Back, Key.ButtonB -> { onCancel(); true }
                                    Key.ButtonA -> { (focusedAction ?: onCancel).invoke(); true }
                                    else -> false
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            // M3 dialog: extraLarge shape, surfaceContainerHigh, Level3 (6.dp)
                            // tonal elevation, clamped to the [280, 560] dp dialog width range.
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 6.dp,
                            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp),
                        ) {
                            Column(modifier = Modifier.padding(24.dp)) {
                                Text(
                                    text = "Exit overlay editing?",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Your buttons are saved as you edit them.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 16.dp),
                                )
                                // Action buttons: text buttons, end-aligned. Sized to content
                                // (no fillMaxWidth) so the card wraps to the [280, 560] range
                                // instead of stretching to the full-screen window width.
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        .padding(top = 24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TextButton(
                                        onClick = onCancel,
                                        modifier = Modifier
                                            .focusRequester(cancelFocus)
                                            .onFocusChanged { if (it.isFocused) focusedAction = onCancel },
                                    ) { Text("Cancel") }
                                    TextButton(
                                        onClick = onExit,
                                        modifier = Modifier
                                            .onFocusChanged { if (it.isFocused) focusedAction = onExit },
                                    ) { Text("Exit") }
                                }
                            }
                        }
                    }
                }
            }
        }
        owner.resumeTo()
        // Full-screen + FOCUSABLE: full-screen absorbs touches (the editor below is frozen), and
        // focusable so the dialog takes key focus — gamepad dpad/A/B + Back work inside it (see the
        // onPreviewKeyEvent above; Back is handled there as Cancel). The non-focusable editor
        // windows still route Back to the activity dispatcher when the dialog ISN'T up.
        val params = layoutParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            focusable = true,
        ).apply {
            // Standard dialog spawn/dismiss motion (fade + scale). The enter plays on addView;
            // the exit plays only if the window is removed with removeView (see detachAnimated).
            windowAnimations = android.R.style.Animation_Dialog
        }
        runCatching { windowManager.addView(view, params) }
            .onFailure { Log.e(TAG, "addView(confirm) failed", it); return }
        confirmWindow = view to owner
    }

    /** Bring the (Home-backgrounded) edit activity back to the front so editing resumes. */
    private fun reForegroundEditActivity() {
        val intent = Intent(context, OverlayEditActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.e(TAG, "reForeground edit activity failed", it) }
    }

    private fun dismissExitConfirm() {
        confirmWindow?.let { detachAnimated(it) }
        confirmWindow = null
    }

    // ── editor menu (always-visible root panel + cascading fly-out submenus) ──────
    //
    // The ROOT menu IS the toolbar window: an always-visible vertical panel with a drag handle on
    // top + bottom. A root row with a submenu shows a trailing right arrow and opens a cascading
    // fly-out window beside it; deeper rows cascade further. Fly-outs are clamped fully on-screen
    // (worst case overlapping their parent). [menuStack] holds the open fly-out levels (depth 1 =
    // a submenu of a root row); the root panel is depth 0 and is never in the stack.

    /** A trailing affordance on a menu row. (A submenu's right-arrow is implied by [MenuEntry.Item.submenu].) */
    private sealed interface MenuTrailing {
        data object None : MenuTrailing
        data class Check(val checked: Boolean, val onToggle: (Boolean) -> Unit) : MenuTrailing
        data class Value(val text: String) : MenuTrailing
    }

    /** One menu entry: a divider, or an item (leaf action, submenu opener, or a split select+submenu row). */
    private sealed interface MenuEntry {
        data object Divider : MenuEntry
        data class Item(
            val label: String,
            val enabled: Boolean = true,
            val selected: Boolean = false,
            // Selected rows also show a trailing check (in addition to the theme-color text).
            val indent: Boolean = false,
            val leadingIcon: ImageVector? = null,
            val trailing: MenuTrailing = MenuTrailing.None,
            // When true a leaf action closes only THIS submenu level (keeps its parent open) — used
            // by in-menu pickers like Align-to. Default leaves close every open submenu.
            val closeToParentOnly: Boolean = false,
            // Non-null → a cascading fly-out submenu (shows a right arrow). @Composable + lazy so a
            // submenu's contents (e.g. live switch state) recompose when built.
            val submenu: (@Composable () -> List<MenuEntry>)? = null,
            // Leaf action. With a [submenu] also present, the row is SPLIT: body = onClick, arrow = open.
            val onClick: (() -> Unit)? = null,
        ) : MenuEntry
    }

    private class MenuWindow(
        val view: View,
        val owner: OverlayLifecycleOwner,
        val params: WindowManager.LayoutParams,
        val depth: Int,
        // Label of the row that opened this fly-out, so re-tapping that row toggles it closed.
        val sourceKey: String,
    )

    /**
     * Routes a tap (window-local x/y) to the row/icon under it for the interop-dragged core menu.
     * Rows register their current bounds + action keyed by label (unique per window) as they lay out.
     */
    private class MenuTapRouter {
        private class Target(val bounds: android.graphics.Rect, val onTap: () -> Unit)
        private val targets = LinkedHashMap<String, Target>()

        fun put(key: String, bounds: android.graphics.Rect, onTap: () -> Unit) {
            targets[key] = Target(bounds, onTap)
        }

        fun route(x: Float, y: Float): Boolean {
            val xi = x.roundToInt()
            val yi = y.roundToInt()
            targets.values.forEach { t -> if (t.bounds.contains(xi, yi)) { t.onTap(); return true } }
            return false
        }
    }

    /**
     * A host that surfaces ACTION_OUTSIDE (delivered to the window root via FLAG_WATCH_OUTSIDE_TOUCH).
     * ComposeView is final, so we wrap it in this FrameLayout and override dispatchTouchEvent —
     * reliable where a plain View.OnTouchListener isn't (Compose consumes inside touches first, but
     * ACTION_OUTSIDE has no Compose hit target so it reaches here).
     */
    private class OutsideAwareHost(
        context: Context,
        private val onOutside: (View, MotionEvent) -> Unit,
    ) : android.widget.FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onOutside(this, ev)
                return false
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    /** Geometry (x, width, y) of the parent at [parentDepth]: depth 0 = the always-visible toolbar. */
    private fun parentMenuGeometry(parentDepth: Int): Triple<Int, Int, Int>? {
        if (parentDepth == 0) {
            val v = toolbar?.first ?: return null
            val p = toolbarParams ?: return null
            return Triple(p.x, v.width, p.y)
        }
        val mw = menuStack.getOrNull(parentDepth - 1) ?: return null
        return Triple(mw.params.x, mw.view.width, mw.params.y)
    }

    /** Re-tapping the row that opened the current fly-out at this depth closes it; else (re)open. */
    private fun toggleSubmenu(
        parentDepth: Int,
        rowTop: Int,
        sourceKey: String,
        builder: @Composable () -> List<MenuEntry>,
    ) = runOnMain {
        // A fly-out opened from a row at [parentDepth] lives at stack index parentDepth.
        val openHere = menuStack.getOrNull(parentDepth)
        if (openHere != null && openHere.sourceKey == sourceKey) truncateMenusTo(parentDepth)
        else openSubmenu(parentDepth, rowTop, sourceKey, builder)
    }

    /**
     * Open a submenu of the row at [rowTopInParent] (px from the parent window's top) inside the
     * level at [parentDepth]. Closes any deeper levels first, then anchors the child to the parent's
     * right edge at the row's height — clamped on-screen by [pushMenuLevel].
     */
    private fun openSubmenu(
        parentDepth: Int,
        rowTopInParent: Int,
        sourceKey: String,
        builder: @Composable () -> List<MenuEntry>,
    ) = runOnMain {
        truncateMenusTo(parentDepth)
        val (px, pw, py) = parentMenuGeometry(parentDepth) ?: return@runOnMain
        pushMenuLevel(
            depth = parentDepth + 1,
            sourceKey = sourceKey,
            anchorLeft = px + pw,
            anchorTop = py + rowTopInParent,
            builder = builder,
        )
    }

    /**
     * Open/close (toggle) a submenu of a ROOT item, anchored by its bounds in the toolbar window.
     * Orientation-aware (see [rootSubmenuAnchor]): vertical menu → fly-out to the right; horizontal
     * menu → fly-out below or above depending on which screen half the menu sits in.
     */
    private fun toggleRootSubmenu(
        sourceKey: String,
        itemBounds: android.graphics.Rect,
        builder: @Composable () -> List<MenuEntry>,
    ) = runOnMain {
        val openHere = menuStack.getOrNull(0)
        if (openHere != null && openHere.sourceKey == sourceKey) {
            truncateMenusTo(0)
            return@runOnMain
        }
        truncateMenusTo(0)
        val tp = toolbarParams ?: return@runOnMain
        val tv = toolbar?.first ?: return@runOnMain
        val (left, top, growUp) = rootSubmenuAnchor(tp, tv.width, tv.height, itemBounds)
        pushMenuLevel(depth = 1, sourceKey = sourceKey, anchorLeft = left, anchorTop = top, growUp = growUp, builder = builder)
    }

    /**
     * (anchorLeft, anchorTop, growUp) for a root item's fly-out. The toolbar window carries a
     * transparent [MENU_SHADOW_MARGIN] inset for its shadow, so the VISIBLE menu edges are inset by
     * [margin] from the window edges — we anchor to the visible edge so the fly-out sits flush
     * against the menu, not [margin] away from it.
     */
    private fun rootSubmenuAnchor(
        tp: WindowManager.LayoutParams,
        toolbarW: Int,
        toolbarH: Int,
        itemBounds: android.graphics.Rect,
    ): Triple<Int, Int, Boolean> {
        val margin = (MENU_SHADOW_MARGIN * context.resources.displayMetrics.density).roundToInt()
        if (!menuHorizontal.value) {
            // Vertical menu: fly-out to the right of the visible menu, aligned to the row's top.
            return Triple(tp.x + toolbarW - margin, tp.y + itemBounds.top, false)
        }
        // Horizontal menu: fly-out below (menu in top half) or above (menu in bottom half), aligned
        // to the icon's left edge. Anchor to the visible bottom/top (inset by the shadow margin).
        val left = tp.x + itemBounds.left
        val inTopHalf = (tp.y + toolbarH / 2) < (displaySizePx().y / 2)
        return if (inTopHalf) Triple(left, tp.y + toolbarH - margin, false)
        else Triple(left, tp.y + margin, true)
    }

    /**
     * Handle an outside tap reported (via FLAG_WATCH_OUTSIDE_TOUCH) by an open fly-out: close
     * everything DEEPER than the level the tap landed inside (or all of them if it landed outside
     * every fly-out). The tap itself still reaches whatever window is underneath, so it isn't eaten.
     */
    private fun handleMenuOutsideTap(rawX: Int, rawY: Int) = runOnMain {
        var keep = 0
        menuStack.forEachIndexed { i, mw ->
            val l = mw.params.x; val t = mw.params.y
            if (rawX in l until (l + mw.view.width) && rawY in t until (t + mw.view.height)) keep = i + 1
        }
        truncateMenusTo(keep)
    }

    /**
     * Add a fly-out window at ([anchorLeft], [anchorTop]), then clamp it fully on-screen once
     * measured (a ComposeView only composes after it's attached, so we measure post-add and nudge).
     */
    private fun pushMenuLevel(
        depth: Int,
        sourceKey: String,
        anchorLeft: Int,
        anchorTop: Int,
        growUp: Boolean = false,
        builder: @Composable () -> List<MenuEntry>,
    ) {
        val owner = OverlayLifecycleOwner()
        val composeView = ComposeView(context).apply {
            defaultFocusHighlightEnabled = false
            setContent { MapoTheme { CascadeMenuLevel(depth, builder) } }
        }
        // Host catches ACTION_OUTSIDE; only the TOP-most open fly-out acts on it (others get it too).
        // ViewTree owners live on the host so the child ComposeView resolves them up the tree.
        val view = OutsideAwareHost(context) { self, ev ->
            if (menuStack.lastOrNull()?.view === self) handleMenuOutsideTap(ev.rawX.roundToInt(), ev.rawY.roundToInt())
        }.apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            addView(composeView)
        }
        owner.resumeTo()
        val params = layoutParams(
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            focusable = false,
            watchOutside = true,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchorLeft
            y = anchorTop
            // Start INVISIBLE: a ComposeView only composes after attach, so we don't know the size
            // until a frame later. Showing it at the un-measured anchor (and worse, growing UP from
            // it) made the fly-out flash in at the wrong spot then jump. We position it while hidden
            // and reveal it once placed (the content fades in via CascadeMenuLevel).
            alpha = 0f
        }
        runCatching { windowManager.addView(view, params) }
            .onFailure { Log.e(TAG, "addView(menu level $depth) failed", it); return }
        menuStack.add(MenuWindow(view, owner, params, depth, sourceKey))
        view.post {
            val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            view.measure(unspec, unspec)
            val size = displaySizePx()
            // growUp: [anchorTop] is the menu's TOP edge and the fly-out opens upward, so its BOTTOM
            // sits at anchorTop (used by the horizontal menu when it's in the screen's bottom half).
            val baseY = if (growUp) anchorTop - view.measuredHeight else anchorTop
            params.x = anchorLeft.coerceIn(0, maxOf(0, size.x - view.measuredWidth))
            params.y = baseY.coerceIn(0, maxOf(0, size.y - view.measuredHeight))
            params.alpha = 1f
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    /** Close fly-out levels until only [keep] remain (keep = 0 closes them all; the root panel stays). */
    private fun truncateMenusTo(keep: Int) {
        while (menuStack.size > keep) {
            val mw = menuStack.removeAt(menuStack.size - 1)
            detachAnimated(mw.view to mw.owner)
        }
    }

    private fun dismissSubmenus() = truncateMenusTo(0)

    @Composable
    private fun CascadeMenuLevel(depth: Int, builder: @Composable () -> List<MenuEntry>) {
        // Content fade-in (the window is positioned-while-hidden then revealed; no window animation).
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(110)) }
        // surfaceContainerHighest — menu container (canonical M3 menu surface). No shadow margin here
        // (submenus anchor flush to the core menu; their own margin would re-open the gap).
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
            modifier = Modifier.graphicsLayer { alpha = appear.value },
        ) {
            MenuList(depth = depth, builder = builder, modifier = Modifier.heightIn(max = MENU_MAX_HEIGHT_DP.dp))
        }
    }

    /** The column of menu rows shared by the root panel (toolbar) and every fly-out window. */
    @Composable
    private fun MenuList(
        depth: Int,
        builder: @Composable () -> List<MenuEntry>,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier = modifier
                .width(MENU_WIDTH_DP.dp)
                .verticalScroll(rememberScrollState())
                // Same top/bottom breathing room the vertical core menu has.
                .padding(vertical = 6.dp),
        ) {
            builder().forEach { entry ->
                when (entry) {
                    is MenuEntry.Divider -> HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    is MenuEntry.Item -> MenuRow(entry, depth)
                }
            }
        }
    }

    @Composable
    private fun MenuRow(item: MenuEntry.Item, depth: Int) {
        var rowTop by remember { mutableStateOf(0) }
        val hasSub = item.submenu != null
        val trailing = item.trailing
        // Check row toggles in place; leaf action runs + closes submenus (or just this one if
        // closeToParentOnly); a pure submenu row toggles its fly-out. (A split row — onClick AND
        // submenu — selects on the body and exposes the arrow as its own tap target below.)
        val leafClose: () -> Unit =
            if (item.closeToParentOnly) ({ truncateMenusTo(depth - 1) }) else ({ dismissSubmenus() })
        val rowClick: (() -> Unit)? = when {
            !item.enabled -> null
            trailing is MenuTrailing.Check -> ({ trailing.onToggle(!trailing.checked) })
            item.onClick != null -> ({ item.onClick!!.invoke(); leafClose() })
            hasSub -> ({ toggleSubmenu(depth, rowTop, item.label, item.submenu!!) })
            else -> null
        }
        val contentColor = when {
            // 0.38 = M3 disabled-content opacity (no dedicated scheme token).
            !item.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            item.selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { rowTop = it.positionInWindow().y.roundToInt() }
                .then(if (rowClick != null) Modifier.clickable(onClick = rowClick) else Modifier)
                .padding(start = if (item.indent) 28.dp else 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item.leadingIcon?.let {
                Icon(it, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            }
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                color = contentColor,
            )
            // Selected rows get a check (on top of the theme-color label).
            if (item.selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            when (trailing) {
                is MenuTrailing.Check ->
                    // onCheckedChange = null so the ROW owns the toggle (whole row is tappable).
                    Checkbox(checked = trailing.checked, onCheckedChange = null, enabled = item.enabled)
                is MenuTrailing.Value ->
                    Text(
                        trailing.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                MenuTrailing.None -> {}
            }
            if (hasSub) {
                val arrow = Icons.AutoMirrored.Filled.ArrowRight
                if (item.onClick != null) {
                    // Split row: arrow is its own tap target so the body can keep its select action.
                    Icon(
                        arrow,
                        contentDescription = "Open submenu",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(enabled = item.enabled) { toggleSubmenu(depth, rowTop, item.label, item.submenu!!) }
                            .padding(2.dp)
                            .size(18.dp),
                    )
                } else {
                    Icon(
                        arrow,
                        contentDescription = null,
                        tint = if (item.enabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    // ── menu content builders ────────────────────────────────────────────────────

    /** Log-only placeholder for menu actions whose real behavior isn't built yet (see gap list). */
    private fun menuGap(name: String) {
        Log.i(TAG, "editor menu action not implemented yet: $name")
    }

    @Composable
    private fun RootMenuEntries(): List<MenuEntry> {
        val sel by selectedIds.collectAsStateWithLifecycle()
        val scopes by overlayEditor.availableScopes.collectAsStateWithLifecycle()
        val currentScope by overlayEditor.editingScope.collectAsStateWithLifecycle()
        val hasClipboard by clipboardHasContent.collectAsStateWithLifecycle()
        val hasSel = sel.isNotEmpty()
        val singleSel = sel.size == 1
        val currentLabel = scopes.firstOrNull { it.scope == currentScope }?.label ?: "No action set"

        return buildList {
            add(MenuEntry.Item(label = currentLabel, leadingIcon = Icons.Default.Layers, submenu = { ScopeSetEntries() }))
            add(MenuEntry.Divider)
            add(
                MenuEntry.Item(
                    "Add",
                    leadingIcon = Icons.Default.Add,
                    submenu = {
                        listOf(
                            MenuEntry.Item("Add new", leadingIcon = Icons.Default.Add, onClick = { overlayEditor.addDefaultElement() }),
                            MenuEntry.Item("Add template", leadingIcon = Icons.Default.Widgets, onClick = { menuGap("Add template") }),
                        )
                    },
                ),
            )
            add(
                MenuEntry.Item(
                    "Edit",
                    leadingIcon = Icons.Default.Edit,
                    enabled = singleSel,
                    onClick = { sel.singleOrNull()?.let { showConfig(it) } },
                ),
            )
            add(MenuEntry.Item("Copy", leadingIcon = Icons.Default.ContentCopy, enabled = hasSel, onClick = { onCopy(styleOnly = false) }))
            add(
                MenuEntry.Item(
                    "Paste",
                    leadingIcon = Icons.Default.ContentPaste,
                    enabled = hasClipboard,
                    submenu = {
                        listOf(
                            MenuEntry.Item("Paste new", leadingIcon = Icons.Default.ContentPaste, onClick = { onPaste(asOverride = false) }),
                            MenuEntry.Item("Paste style", leadingIcon = Icons.Default.Style, enabled = hasSel, onClick = { onPaste(asOverride = true) }),
                        )
                    },
                ),
            )
            add(MenuEntry.Item("Align", leadingIcon = Icons.Default.AlignHorizontalLeft, submenu = { AlignEntries() }))
            add(
                MenuEntry.Item(
                    "Templatize",
                    leadingIcon = Icons.Default.Widgets,
                    submenu = {
                        listOf(
                            MenuEntry.Item("Whole overlay", leadingIcon = Icons.Default.SelectAll, onClick = { menuGap("Templatize whole overlay") }),
                            MenuEntry.Item("Selection", leadingIcon = Icons.Default.HighlightAlt, enabled = hasSel, onClick = { menuGap("Templatize selection") }),
                        )
                    },
                ),
            )
            add(MenuEntry.Item("Delete", leadingIcon = Icons.Default.Delete, enabled = hasSel, onClick = { deleteSelected() }))
            add(MenuEntry.Divider)
            add(MenuEntry.Item("Undo", leadingIcon = Icons.AutoMirrored.Filled.Undo, onClick = { menuGap("Undo") }))
            add(MenuEntry.Item("Redo", leadingIcon = Icons.AutoMirrored.Filled.Redo, onClick = { menuGap("Redo") }))
            add(MenuEntry.Divider)
            add(MenuEntry.Item("Options", leadingIcon = Icons.Default.Tune, submenu = { OptionsEntries() }))
            add(MenuEntry.Item("Exit", leadingIcon = Icons.AutoMirrored.Filled.ExitToApp, onClick = { stop() }))
        }
    }

    /**
     * Action-set picker, flat + indented: each action set, then its layers as the next rows
     * (indented, leading a subdirectory arrow), then an indented "Add layer". "Add set" at the end.
     * The current scope (set or layer) is theme-colored AND gets a trailing check.
     */
    @Composable
    private fun ScopeSetEntries(): List<MenuEntry> {
        val scopes by overlayEditor.availableScopes.collectAsStateWithLifecycle()
        val current by overlayEditor.editingScope.collectAsStateWithLifecycle()
        val sets = scopes.filter { !it.isLayer }
        return buildList {
            sets.forEach { setOpt ->
                val setId = (setOpt.scope as? OverlayScope.Set)?.actionSetId
                add(
                    MenuEntry.Item(
                        label = setOpt.label,
                        leadingIcon = Icons.Default.Layers,
                        selected = setOpt.scope == current,
                        onClick = { overlayEditor.setScope(setOpt.scope) },
                    ),
                )
                scopes.filter { it.isLayer && (it.scope as? OverlayScope.Layer)?.parentActionSetId == setId }
                    .forEach { layerOpt ->
                        add(
                            MenuEntry.Item(
                                label = layerOpt.label,
                                indent = true,
                                leadingIcon = Icons.Outlined.Layers,
                                selected = layerOpt.scope == current,
                                onClick = { overlayEditor.setScope(layerOpt.scope) },
                            ),
                        )
                    }
                add(
                    MenuEntry.Item(
                        "Add layer",
                        indent = true,
                        leadingIcon = Icons.Default.Add,
                        onClick = { menuGap("Add layer (set ${setOpt.label})") },
                    ),
                )
            }
            add(MenuEntry.Item("Add set", leadingIcon = Icons.Default.Add, onClick = { menuGap("Add set") }))
        }
    }

    /** What Align/Space operate relative to. */
    private enum class AlignTarget(val label: String) { Selection("Selection"), Canvas("Canvas") }

    /**
     * Align / space submenu. The "Align to" row picks the reference (Selection vs Canvas); the
     * align/space buttons enable based on it: Canvas needs ≥1 selected, Selection needs ≥2. All
     * align/space actions are placeholders for now (see gap list).
     */
    @Composable
    private fun AlignEntries(): List<MenuEntry> {
        val target by alignTo.collectAsStateWithLifecycle()
        val sel by selectedIds.collectAsStateWithLifecycle()
        val canAlign = when (target) {
            AlignTarget.Canvas -> sel.isNotEmpty()
            AlignTarget.Selection -> sel.size >= 2
        }
        fun align(name: String, icon: ImageVector) =
            MenuEntry.Item(name, leadingIcon = icon, enabled = canAlign, onClick = { menuGap("$name (to ${target.label})") })
        return buildList {
            add(
                MenuEntry.Item(
                    // Current value is intentionally NOT shown here — it would crowd out "Align to".
                    "Align to",
                    leadingIcon = Icons.Default.FilterCenterFocus,
                    submenu = {
                        AlignTarget.entries.map { t ->
                            MenuEntry.Item(
                                t.label,
                                selected = t == target,
                                closeToParentOnly = true,
                                onClick = { alignTo.value = t },
                            )
                        }
                    },
                ),
            )
            add(MenuEntry.Divider)
            add(align("Align left", Icons.Default.AlignHorizontalLeft))
            add(align("Align vertically", Icons.Default.AlignHorizontalCenter))
            add(align("Align right", Icons.Default.AlignHorizontalRight))
            add(align("Space vertically", Icons.Default.VerticalDistribute))
            add(MenuEntry.Divider)
            add(align("Align top", Icons.Default.AlignVerticalTop))
            add(align("Align horizontally", Icons.Default.AlignVerticalCenter))
            add(align("Align bottom", Icons.Default.AlignVerticalBottom))
            add(align("Space horizontally", Icons.Default.HorizontalDistribute))
        }
    }

    @Composable
    private fun OptionsEntries(): List<MenuEntry> {
        val snap by overlaySettings.snapEnabled.collectAsStateWithLifecycle()
        val grid by showGrid.collectAsStateWithLifecycle()
        val divisions by gridDivisions.collectAsStateWithLifecycle()
        return listOf(
            MenuEntry.Item("Snapping", leadingIcon = Icons.Default.GridOn, trailing = MenuTrailing.Check(snap) { overlaySettings.setSnapEnabled(it) }),
            MenuEntry.Item("Show grid", leadingIcon = Icons.Default.Grid4x4, trailing = MenuTrailing.Check(grid) { showGrid.value = it }),
            MenuEntry.Item("Grid size", leadingIcon = Icons.Default.Straighten, trailing = MenuTrailing.Value(divisions.toString())),
            MenuEntry.Divider,
            MenuEntry.Item("Show controller", leadingIcon = Icons.Default.SportsEsports, onClick = { menuGap("Show controller") }),
            MenuEntry.Item("Rotate menu", leadingIcon = Icons.Default.ScreenRotation, onClick = { rotateMenu() }),
        )
    }

    // Real copy/paste of button data + style is deferred to the virtual-button model rework; for
    // now these only log and flip the "clipboard" flag so Paste's enabled state is demonstrable.
    private fun onCopy(styleOnly: Boolean) {
        Log.i(TAG, "copy ${if (styleOnly) "style" else "full"} of ${selectedIds.value.size} button(s) — stub")
        clipboardHasContent.value = true
    }

    private fun onPaste(asOverride: Boolean) {
        Log.i(TAG, "paste ${if (asOverride) "as override" else "as new"} — stub")
    }

    /** Flip the core menu between the vertical panel and the horizontal icon bar, preserving the
     *  bottom-left corner (so vertical→horizontal drops the bar to the vertical menu's bottom-left). */
    private fun rotateMenu() = runOnMain {
        dismissSubmenus()
        toolbar?.first?.let { v -> toolbarParams?.let { p -> pendingBottomLeft = Point(p.x, p.y + v.height) } }
        menuHorizontal.value = !menuHorizontal.value
    }

    /**
     * The always-visible core menu (the "toolbar" window). Grab-and-draggable anywhere (the whole
     * window owns a raw-coord touch filter via [onTouch]; a tap that doesn't move is routed by
     * [toolbarRouter] to the row/icon under it). Renders as a vertical panel, or a horizontal
     * icon-only bar when [menuHorizontal] is set ("Rotate menu").
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun ToolbarContent(onTouch: (MotionEvent) -> Boolean) {
        val horizontal by menuHorizontal.collectAsStateWithLifecycle()
        // Re-size the window when the orientation flips. The window keeps its old explicit size while
        // the new content composes (the layout-change listener won't fire — the clipped view keeps
        // its old bounds), so trigger the resize here; forceLayout in resizeToolbarToContent makes
        // the re-measure fresh (no stale cached size).
        LaunchedEffect(horizontal) { resizeToolbarToContent() }
        // Pill ends when horizontal; rounded when vertical. Drop shadow needs room, so the window's
        // content is inset by MENU_SHADOW_MARGIN (the visible Surface is that much in from the window
        // edge) and the shadow is drawn into that margin.
        val shape = if (horizontal) CircleShape else MaterialTheme.shapes.large
        val shadowCorner = if (horizontal) 100.dp else 16.dp
        Box(
            // Interop filter OUTERMOST so its ev.x/ev.y share the window origin with the rows'
            // positionInWindow() used for tap routing; the shadow-margin padding is inside it.
            modifier = Modifier
                .pointerInteropFilter(onTouchEvent = onTouch)
                .padding(MENU_SHADOW_MARGIN.dp),
        ) {
            // Shadow on a wrapper Box (sized to the Surface) so it can't be clipped by the Surface's
            // shape; surfaceContainerHigh — floating panel (elevated container).
            Box(Modifier.menuDropShadow(shadowCorner)) {
            Surface(
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                // Rows re-register their current bounds (keyed by label) as they lay out; same labels
                // across vertical/horizontal so entries overwrite rather than going stale.
                if (horizontal) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RootMenuEntries().forEach { entry ->
                            when (entry) {
                                is MenuEntry.Divider ->
                                    VerticalDivider(Modifier.height(26.dp).padding(horizontal = 2.dp))
                                is MenuEntry.Item -> RoutedIcon(entry)
                            }
                        }
                    }
                } else {
                    // No scroll: the raw-coord drag filter consumes touches (so a scroll couldn't work
                    // anyway), and the core menu is meant to fit all its rows. The window sizes to it;
                    // if it ever exceeds the screen the user drags it (the drag allows sliding off).
                    Column(modifier = Modifier.width(MENU_WIDTH_DP.dp).padding(vertical = 6.dp)) {
                        RootMenuEntries().forEach { entry ->
                            when (entry) {
                                is MenuEntry.Divider -> HorizontalDivider(Modifier.padding(vertical = 2.dp))
                                is MenuEntry.Item -> RoutedRow(entry)
                            }
                        }
                    }
                }
            }
            }
        }
    }

    /**
     * A soft, blurred drop shadow for the menu panel, drawn with [android.graphics.BlurMaskFilter]
     * rather than `Modifier.shadow` — Android elevation shadows shift direction with the window's
     * screen position, which looks wrong on a draggable overlay (mirrors `MainScreen.softDropShadow`).
     * Draws into the surrounding [MENU_SHADOW_MARGIN] inset.
     */
    private fun Modifier.menuDropShadow(cornerRadius: Dp): Modifier = drawBehind {
        val blurPx = 8.dp.toPx()
        val offsetYPx = 2.dp.toPx()
        val cornerPx = cornerRadius.toPx()
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(72, 0, 0, 0)
            // NORMAL (not OUTER) so the shadow hugs the edge — the inner half is covered by the
            // opaque Surface drawn on top, leaving just the soft outer halo with no detached gap.
            maskFilter = android.graphics.BlurMaskFilter(blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
            isAntiAlias = true
        }
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRoundRect(
                0f, offsetYPx, size.width, size.height + offsetYPx, cornerPx, cornerPx, paint,
            )
        }
    }

    /** Tap action for a ROOT item with the latest [bounds] (window-local): open its submenu, or act. */
    private fun rootItemTap(item: MenuEntry.Item, bounds: android.graphics.Rect) {
        if (!item.enabled) return
        val sub = item.submenu
        if (sub != null) toggleRootSubmenu(item.label, bounds, sub)
        else { item.onClick?.invoke(); dismissSubmenus() }
    }

    /** A routed root row (vertical menu): like a menu row, but registers into [toolbarRouter] (no clickable). */
    @Composable
    private fun RoutedRow(item: MenuEntry.Item) {
        val contentColor =
            if (item.enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { c ->
                    val p = c.positionInWindow()
                    val b = android.graphics.Rect(
                        p.x.roundToInt(), p.y.roundToInt(),
                        p.x.roundToInt() + c.size.width, p.y.roundToInt() + c.size.height,
                    )
                    toolbarRouter.put(item.label, b) { rootItemTap(item, b) }
                }
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item.leadingIcon?.let {
                Icon(it, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            }
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                color = contentColor,
            )
            if (item.submenu != null) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowRight,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    /** A routed root icon (horizontal menu): icon only, registers into [toolbarRouter]. */
    @Composable
    private fun RoutedIcon(item: MenuEntry.Item) {
        val tint =
            if (item.enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        Box(
            modifier = Modifier
                .size(40.dp)
                .onGloballyPositioned { c ->
                    val p = c.positionInWindow()
                    val b = android.graphics.Rect(
                        p.x.roundToInt(), p.y.roundToInt(),
                        p.x.roundToInt() + c.size.width, p.y.roundToInt() + c.size.height,
                    )
                    toolbarRouter.put(item.label, b) { rootItemTap(item, b) }
                },
            contentAlignment = Alignment.Center,
        ) {
            item.leadingIcon?.let {
                Icon(it, contentDescription = item.label, tint = tint, modifier = Modifier.size(22.dp))
            }
        }
    }

    /**
     * Editable button. Select + drag are driven by **`pointerInteropFilter`**, which hands
     * us the raw `MotionEvent` (incl. screen `rawX/rawY`) inside Compose — a plain view
     * `OnTouchListener` does NOT fire on a ComposeView. Raw coords keep dragging jitter-free
     * (independent of the window's lagging on-screen position). All gesture logic lives in
     * [onTouch] (built per element in `attachElement`).
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun EditableElement(
        element: OverlayElement,
        selected: Boolean,
        onTouch: (MotionEvent) -> Boolean,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInteropFilter(onTouchEvent = onTouch),
        ) {
            // WYSIWYG — same shape / fill / content / opacity as the run-mode button, with a
            // selection outline added.
            Surface(
                shape = overlayShape(element),
                color = overlayFillColor(element),
                contentColor = overlayContentColor(element),
                tonalElevation = 3.dp,
                border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier.fillMaxSize().alpha(element.opacity),
            ) {
                OverlayElementLabel(element)
            }
        }
    }

    /** A single corner resize handle: a small primary dot centered in a larger touch area. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun HandleDot(onTouch: (MotionEvent) -> Boolean) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInteropFilter(onTouchEvent = onTouch),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(HANDLE_DOT_DP.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.onPrimary, CircleShape),
            )
        }
    }

    /** The dashed bounding-box outline shown around a multi-button selection. */
    @Composable
    private fun SelectionBoxOutline() {
        val color = MaterialTheme.colorScheme.primary
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        color = color,
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(8.dp.toPx(), 6.dp.toPx()), 0f,
                            ),
                        ),
                    )
                },
        )
    }

    // ── window plumbing ─────────────────────────────────────────────────────────

    private fun ComposeView.attachOwner(owner: OverlayLifecycleOwner) {
        setViewTreeLifecycleOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        defaultFocusHighlightEnabled = false
    }

    private fun OverlayLifecycleOwner.resumeTo() {
        handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        handleLifecycleEvent(Lifecycle.Event.ON_START)
        handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    private fun detach(pair: Pair<View, OverlayLifecycleOwner>) {
        runCatching { windowManager.removeViewImmediate(pair.first) }
            .onFailure { Log.w(TAG, "removeView failed (already gone?)", it) }
        pair.second.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        pair.second.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        pair.second.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    /**
     * Like [detach] but plays the window's exit animation: `removeView` (not
     * `removeViewImmediate`) lets the WM animate the surface out, and the lifecycle owner is torn
     * down only after the animation so the content stays drawn through it.
     */
    private fun detachAnimated(pair: Pair<View, OverlayLifecycleOwner>) {
        runCatching { windowManager.removeView(pair.first) }
            .onFailure { Log.w(TAG, "removeView failed (already gone?)", it) }
        mainHandler.postDelayed({
            pair.second.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            pair.second.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            pair.second.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }, EXIT_ANIM_TEARDOWN_MS)
    }

    private fun layoutParams(
        width: Int,
        height: Int,
        focusable: Boolean,
        touchable: Boolean = true,
        watchOutside: Boolean = false,
    ): WindowManager.LayoutParams {
        // FLAG_HARDWARE_ACCELERATED is required for WindowManager-added windows: unlike
        // Activity windows it is NOT inherited from the manifest, and without it the
        // ComposeView renders in software, where gradients band and antialiased rounded
        // shapes (slider tracks/thumbs, swatches) come out jagged and "pixely".
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        // NOT_TOUCHABLE: the window never intercepts touches — they pass straight through to
        // whatever is below (used for the selection bounding-box outline, so dragging a button
        // inside the box still reaches the button's own window).
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        // WATCH_OUTSIDE_TOUCH: get an ACTION_OUTSIDE for taps outside the window WHILE the tap is
        // still delivered to the window behind (NOT_TOUCH_MODAL) — so a submenu can dismiss on an
        // outside tap without eating it. The tap also lands on whatever is underneath.
        if (watchOutside) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        )
    }

    // ── snapping ────────────────────────────────────────────────────────────────

    /**
     * Snap the dragged element's top-left to the nearest grid line or sibling edge/center
     * within [snapThresholdPx], so groups line up cleanly. Per axis it considers: the grid,
     * sibling near/far edges (including adjacent stacking), and sibling centers. [exclude] holds
     * ids that must not be snap targets (the dragged element + the rest of a moving group).
     */
    private fun snapPosition(x: Int, y: Int, w: Int, h: Int, size: Point, exclude: Set<Long>): Point {
        val others = overlayEditor.elements.value.filter { it.id !in exclude }
        val gridX = size.x.toFloat() / OverlaySettings.GRID_DIVISIONS
        val gridY = size.y.toFloat() / OverlaySettings.GRID_DIVISIONS

        val xCandidates = mutableListOf(((x / gridX).roundToInt() * gridX).roundToInt())
        val yCandidates = mutableListOf(((y / gridY).roundToInt() * gridY).roundToInt())
        others.forEach { o ->
            val ol = (o.x * size.x).roundToInt()
            val or = ((o.x + o.width) * size.x).roundToInt()
            val ocx = ((o.x + o.width / 2f) * size.x).roundToInt()
            xCandidates += listOf(ol, or, ol - w, or - w, ocx - w / 2)
            val ot = (o.y * size.y).roundToInt()
            val ob = ((o.y + o.height) * size.y).roundToInt()
            val ocy = ((o.y + o.height / 2f) * size.y).roundToInt()
            yCandidates += listOf(ot, ob, ot - h, ob - h, ocy - h / 2)
        }
        val sx = snapValue(x, xCandidates).coerceIn(0, size.x - w)
        val sy = snapValue(y, yCandidates).coerceIn(0, size.y - h)
        return Point(sx, sy)
    }

    /** Nearest candidate within [snapThresholdPx], else [value] unchanged. */
    private fun snapValue(value: Int, candidates: List<Int>): Int {
        var best = value
        var bestDist = snapThresholdPx + 1
        candidates.forEach { c ->
            val d = abs(value - c)
            if (d <= snapThresholdPx && d < bestDist) {
                best = c
                bestDist = d
            }
        }
        return best
    }

    private fun displaySizePx(): Point {
        val wm = windowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private data class ElementWindow(
        val view: View,
        val owner: OverlayLifecycleOwner,
        val params: WindowManager.LayoutParams,
        val state: MutableState<OverlayElement>,
    )

    /** The four resize corners. */
    private enum class Corner { TopLeft, TopRight, BottomLeft, BottomRight }

    /** The corner of [r] this handle sits on (where to draw it). */
    private fun Corner.cornerOf(r: android.graphics.Rect): Pair<Int, Int> = when (this) {
        Corner.TopLeft -> r.left to r.top
        Corner.TopRight -> r.right to r.top
        Corner.BottomLeft -> r.left to r.bottom
        Corner.BottomRight -> r.right to r.bottom
    }

    /** The *opposite* corner of [r] — the fixed anchor a drag from this corner scales about. */
    private fun Corner.fixedCornerOf(r: android.graphics.Rect): Pair<Int, Int> = when (this) {
        Corner.TopLeft -> r.right to r.bottom
        Corner.TopRight -> r.left to r.bottom
        Corner.BottomLeft -> r.right to r.top
        Corner.BottomRight -> r.left to r.top
    }

    private class HandleWindow(
        val corner: Corner,
        val view: View,
        val owner: OverlayLifecycleOwner,
        val params: WindowManager.LayoutParams,
    )

    private class BoxWindow(
        val view: View,
        val owner: OverlayLifecycleOwner,
        val params: WindowManager.LayoutParams,
    )

    private class SelectionChrome(
        val handles: Map<Corner, HandleWindow>,
        val box: BoxWindow?,
    )

    companion object {
        private const val TAG = "OverlayLiveEdit"
        private const val SCRIM_COLOR = 0x66000000.toInt() // ~40% black
        // Config drawer slide/fade duration, and its leading-corner radius (mirrors
        // DrawerDefaults.shape's 16dp trailing corners for an end-docked sheet).
        private const val DRAWER_ANIM_MS = 300
        private const val DRAWER_CORNER_DP = 16
        // Slightly longer than the platform dialog exit animation so the content stays drawn
        // until the surface has animated out.
        private const val EXIT_ANIM_TEARDOWN_MS = 300L
        // Resize handle: the touchable window is larger than the visible dot for an easy grab.
        private const val HANDLE_TOUCH_DP = 32
        private const val HANDLE_DOT_DP = 14
        // Editor menu sizing: narrow fixed width, capped height (scrolls past it).
        private const val MENU_WIDTH_DP = 156
        private const val MENU_MAX_HEIGHT_DP = 360
        // Transparent inset around the menu Surface inside its window, giving the drop shadow room.
        private const val MENU_SHADOW_MARGIN = 10
    }
}
