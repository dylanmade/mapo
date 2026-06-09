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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
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
    private var scrim: Pair<View, OverlayLifecycleOwner>? = null
    private var toolbar: Pair<View, OverlayLifecycleOwner>? = null
    private var toolbarParams: WindowManager.LayoutParams? = null
    private var configWindow: Pair<View, OverlayLifecycleOwner>? = null
    private var confirmWindow: Pair<View, OverlayLifecycleOwner>? = null
    // Single shared dropdown-menu window (scope picker / copy / paste — only one shows at a time);
    // kept out of the toolbar window so opening it never resizes the draggable toolbar.
    private var menuWindow: Pair<View, OverlayLifecycleOwner>? = null
    // Drives the toolbar's scope-dropdown arrow specifically (true only while the scope menu is up).
    private val scopeMenuOpen = MutableStateFlow(false)

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
            Log.i(TAG, "live edit started")
        }
    }

    fun stop() {
        runOnMain {
            collectJob?.cancel()
            collectJob = null
            dismissConfig()
            dismissExitConfirm()
            dismissMenu()
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
        elements.forEach { element ->
            val existing = elementWindows[element.id]
            if (existing == null) {
                attachElement(element, size)
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

    private fun addToolbar() {
        val owner = OverlayLifecycleOwner()
        // Drag handler for the toolbar's grip — same raw-coord technique as the buttons
        // (anchored to the window position at touch-down so the moving window doesn't fight
        // the finger). Lets the user shove the toolbar aside to edit what's beneath it.
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        val onHandleTouch: (MotionEvent) -> Boolean = { ev ->
            val p = toolbarParams
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX; startRawY = ev.rawY
                    startX = p?.x ?: 0; startY = p?.y ?: 0
                    // Close any open dropdown (its own window, anchored to the toolbar) so it
                    // doesn't float detached while the toolbar moves.
                    dismissMenu()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (p != null) {
                        val size = displaySizePx()
                        val v = toolbar?.first
                        val w = v?.width ?: 0
                        val h = v?.height ?: 0
                        // When the toolbar is wider/taller than the screen, allow NEGATIVE offsets
                        // so it can be slid off the edge to reveal the part that's off-screen.
                        p.x = (startX + (ev.rawX - startRawX)).roundToInt()
                            .coerceIn(minOf(0, size.x - w), maxOf(0, size.x - w))
                        p.y = (startY + (ev.rawY - startRawY)).roundToInt()
                            .coerceIn(minOf(0, size.y - h), maxOf(0, size.y - h))
                        runCatching { windowManager.updateViewLayout(v, p) }
                    }
                    true
                }
                else -> true
            }
        }
        val view = ComposeView(context).apply {
            attachOwner(owner)
            setContent { MapoTheme { ToolbarContent(onHandleTouch = onHandleTouch) } }
        }
        owner.resumeTo()
        // Compact, free-positioned, draggable window anchored top-left. Its width is set
        // explicitly to the toolbar's measured CONTENT width (see resizeToolbarToContent) rather
        // than WRAP_CONTENT — a WRAP_CONTENT overlay window is measured AT_MOST the display width,
        // which caps the row at the screen and hides the overflow. Explicit width + NO_LIMITS lets
        // the toolbar flex as wide as its buttons need, even past the screen edge.
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
        // Size the window to the content's true width once laid out, and re-size whenever the
        // content's intrinsic width changes (e.g. a longer action-set label).
        view.post { resizeToolbarToContent(center = true) }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resizeToolbarToContent(center = false) }
    }

    /**
     * Set the toolbar window's width to the toolbar content's *natural* width. We measure the view
     * with an UNSPECIFIED spec to get the width it WANTS — bypassing whatever constraint the window
     * imposes on the WRAP_CONTENT ComposeView — then apply it explicitly so the window can grow as
     * wide as the row needs, even beyond the screen (FLAG_LAYOUT_NO_LIMITS). [center] re-centers on
     * the first pass.
     */
    private fun resizeToolbarToContent(center: Boolean) {
        val view = toolbar?.first ?: return
        val params = toolbarParams ?: return
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
        val w = view.measuredWidth
        val h = view.measuredHeight
        if (w <= 0) return
        // Guard against a relayout loop: only push an update when the size actually changed.
        if (params.width == w && params.height == h && !center) return
        Log.i(TAG, "toolbar sized to content: ${w}x$h (display=${displaySizePx().x})")
        params.width = w
        params.height = h
        if (center) {
            val size = displaySizePx()
            params.x = ((size.x - w) / 2).coerceAtLeast(0)
        }
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

    // ── toolbar dropdown menus (one shared window) ───────────────────────────────

    /** A single dropdown-menu row: label + enabled/selected/indent styling + action. */
    private data class MenuItem(
        val label: String,
        val enabled: Boolean = true,
        val selected: Boolean = false,
        val indent: Boolean = false,
        val onClick: () -> Unit,
    )

    /**
     * Show a dropdown menu in its **own** window docked just under the toolbar. Kept out of the
     * toolbar window so opening it never resizes the toolbar — a resize animated the dragged
     * toolbar back to its pre-drag spot. Only one menu shows at a time (scope / copy / paste).
     */
    private fun showMenu(items: List<MenuItem>) {
        dismissMenu()
        val toolbarView = toolbar?.first ?: return
        val tp = toolbarParams ?: return
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(context).apply {
            attachOwner(owner)
            setContent { MapoTheme { MenuContent(items) } }
        }
        owner.resumeTo()
        val params = layoutParams(
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            focusable = false,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = tp.x
            y = tp.y + toolbarView.height
            // Conventional dropdown motion (scale-from-top-start + fade). Enter plays on addView;
            // exit plays because dismiss removes via removeView (detachAnimated).
            windowAnimations = com.mapo.R.style.Animation_Mapo_Dropdown
        }
        runCatching { windowManager.addView(view, params) }
            .onFailure { Log.e(TAG, "addView(menu) failed", it); return }
        menuWindow = view to owner
    }

    @Composable
    private fun MenuContent(items: List<MenuItem>) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 180.dp, max = 300.dp)
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = item.enabled) {
                                item.onClick()
                                dismissMenu()
                            }
                            .padding(
                                start = if (item.indent) 32.dp else 16.dp,
                                end = 24.dp,
                                top = 12.dp,
                                bottom = 12.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = when {
                                // 0.38 = M3 disabled-content opacity (no dedicated scheme token).
                                !item.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                item.selected -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }

    private fun dismissMenu() {
        menuWindow?.let { detachAnimated(it) }
        menuWindow = null
        scopeMenuOpen.value = false
    }

    // ── scope picker ─────────────────────────────────────────────────────────────

    private fun toggleScopeMenu() = runOnMain {
        if (scopeMenuOpen.value) dismissMenu() else showScopeMenu()
    }

    /** The action-set/layer picker. Layers are indented under their set; current is highlighted. */
    private fun showScopeMenu() {
        val current = overlayEditor.editingScope.value
        val items = overlayEditor.availableScopes.value.map { option ->
            MenuItem(
                label = option.label,
                selected = option.scope == current,
                indent = option.isLayer,
                onClick = { overlayEditor.setScope(option.scope) },
            )
        }
        showMenu(items)
        scopeMenuOpen.value = true // set AFTER showMenu (its dismissMenu resets the flag first)
    }

    // ── copy / paste menus (UI-only stubs; see clipboardHasContent) ──────────────

    private fun showCopyMenu() = runOnMain {
        showMenu(
            listOf(
                MenuItem("Copy") { onCopy(styleOnly = false) },
                MenuItem("Copy style") { onCopy(styleOnly = true) },
            ),
        )
    }

    private fun showPasteMenu() = runOnMain {
        val hasSelection = selectedIds.value.isNotEmpty()
        showMenu(
            listOf(
                MenuItem("Paste as new") { onPaste(asOverride = false) },
                MenuItem("Paste as override", enabled = hasSelection) { onPaste(asOverride = true) },
            ),
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

    @Composable
    private fun ToolbarContent(onHandleTouch: (MotionEvent) -> Boolean) {
        val sel by selectedIds.collectAsStateWithLifecycle()
        val snap by overlaySettings.snapEnabled.collectAsStateWithLifecycle()
        val scopes by overlayEditor.availableScopes.collectAsStateWithLifecycle()
        val currentScope by overlayEditor.editingScope.collectAsStateWithLifecycle()
        val menuOpen by scopeMenuOpen.collectAsStateWithLifecycle()
        val hasClipboard by clipboardHasContent.collectAsStateWithLifecycle()
        val currentLabel = scopes.firstOrNull { it.scope == currentScope }?.label ?: "No action set"
        val hasSelection = sel.isNotEmpty()

        // surfaceContainerHigh — floating toolbar (elevated container). Also the overlay's own
        // settings surface in edit mode (Brick D): snap + the action-set/layer scope live here.
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            // One row that sizes to its content — flexbox-style, NO max width and NO scroll. The
            // window is resized to this row's measured width (resizeToolbarToContent), so the
            // toolbar grows as wide as its buttons need (off-screen if necessary; drag to reveal).
            // A drag handle flanks each end.
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                DragHandle(onHandleTouch)
                TextButton(
                    onClick = { if (scopes.isNotEmpty()) toggleScopeMenu() },
                    enabled = scopes.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(
                        currentLabel,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    Icon(
                        if (menuOpen) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = "Choose action set or layer",
                    )
                }
                IconButton(onClick = { overlayEditor.addDefaultElement() }) {
                    Icon(Icons.Default.Add, contentDescription = "Add button")
                }
                FilledIconToggleButton(
                    checked = snap,
                    onCheckedChange = { overlaySettings.setSnapEnabled(it) },
                ) {
                    Icon(
                        if (snap) Icons.Default.GridOn else Icons.Default.GridOff,
                        contentDescription = if (snap) "Snapping on" else "Snapping off",
                    )
                }
                // Copy: enabled with a selection → menu of Copy / Copy style.
                IconButton(onClick = { showCopyMenu() }, enabled = hasSelection) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                }
                // Paste: enabled when the clipboard has content → menu of Paste as new / override.
                IconButton(onClick = { showPasteMenu() }, enabled = hasClipboard) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                }
                // Configure only makes sense for a single button.
                IconButton(onClick = { sel.singleOrNull()?.let { showConfig(it) } }, enabled = sel.size == 1) {
                    Icon(Icons.Default.Edit, contentDescription = "Configure")
                }
                IconButton(onClick = { deleteSelected() }, enabled = hasSelection) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
                FilledIconButton(onClick = { stop() }) {
                    Icon(Icons.Default.Check, contentDescription = "Done")
                }
                DragHandle(onHandleTouch)
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun DragHandle(onHandleTouch: (MotionEvent) -> Boolean) {
        Icon(
            Icons.Default.DragIndicator,
            contentDescription = "Move toolbar",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .pointerInteropFilter(onTouchEvent = onHandleTouch),
        )
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

    private fun layoutParams(width: Int, height: Int, focusable: Boolean): WindowManager.LayoutParams {
        // FLAG_HARDWARE_ACCELERATED is required for WindowManager-added windows: unlike
        // Activity windows it is NOT inherited from the manifest, and without it the
        // ComposeView renders in software, where gradients band and antialiased rounded
        // shapes (slider tracks/thumbs, swatches) come out jagged and "pixely".
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
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
    }
}
