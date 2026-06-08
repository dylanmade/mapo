package com.mapo.service.overlay.element

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
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
    private val selectedId = MutableStateFlow<Long?>(null)

    private val windowManager get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val snapThresholdPx =
        (OverlaySettings.SNAP_THRESHOLD_DP * context.resources.displayMetrics.density).roundToInt()

    private val elementWindows = mutableMapOf<Long, ElementWindow>()
    private var scrim: Pair<View, OverlayLifecycleOwner>? = null
    private var toolbar: Pair<View, OverlayLifecycleOwner>? = null
    private var configWindow: Pair<View, OverlayLifecycleOwner>? = null

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
            selectedId.value = null
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
            elementWindows.keys.toList().forEach { detachElement(it) }
            scrim?.let { detach(it) }; scrim = null
            toolbar?.let { detach(it) }; toolbar = null
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
        var startWinX = 0
        var startWinY = 0
        var dragging = false
        val onTouch: (MotionEvent) -> Boolean = { ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val w = elementWindows[element.id]
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    startWinX = w?.params?.x ?: 0
                    startWinY = w?.params?.y ?: 0
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                    if (dragging) {
                        moveElementTo(
                            element.id,
                            (startWinX + dx).roundToInt(),
                            (startWinY + dy).roundToInt(),
                        )
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        // Tap selects; tap an already-selected button to open its config.
                        if (selectedId.value == element.id) showConfig(element.id)
                        else selectedId.value = element.id
                    } else {
                        onElementDragEnd(element.id)
                    }
                    true
                }
                else -> false
            }
        }

        val view = ComposeView(context).apply {
            attachOwner(owner)
            setContent {
                MapoTheme {
                    val sel by selectedId.collectAsStateWithLifecycle()
                    EditableElement(
                        element = state.value,
                        selected = sel == state.value.id,
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

    /** Move an element's window to an absolute screen position (clamped, then snapped). */
    private fun moveElementTo(id: Long, targetX: Int, targetY: Int) {
        val w = elementWindows[id] ?: return
        val size = displaySizePx()
        var nx = targetX.coerceIn(0, size.x - w.params.width)
        var ny = targetY.coerceIn(0, size.y - w.params.height)
        if (overlaySettings.snapEnabled.value) {
            val snapped = snapPosition(id, nx, ny, w.params.width, w.params.height, size)
            nx = snapped.x; ny = snapped.y
        }
        w.params.x = nx; w.params.y = ny
        runCatching { windowManager.updateViewLayout(w.view, w.params) }
    }

    private fun onElementDragEnd(id: Long) {
        val w = elementWindows[id] ?: return
        val size = displaySizePx()
        overlayEditor.moveResize(
            id = id,
            x = w.params.x.toFloat() / size.x,
            y = w.params.y.toFloat() / size.y,
            width = w.params.width.toFloat() / size.x,
            height = w.params.height.toFloat() / size.y,
        )
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
                            // Tap empty space to deselect; touches are consumed so they
                            // don't reach the app underneath while editing.
                            detectTapGestures(onTap = { selectedId.value = null })
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
        // Suppress the system back / edge gestures across the whole edit surface so dragging
        // a button near an edge doesn't trigger them. (The bottom home/recents gesture is
        // reserved by the platform and can't be excluded by a non-launcher app.) API 29+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val size = displaySizePx()
            view.post { view.systemGestureExclusionRects = listOf(Rect(0, 0, size.x, size.y)) }
        }
    }

    private fun addToolbar() {
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(context).apply {
            attachOwner(owner)
            setContent { MapoTheme { ToolbarContent() } }
        }
        owner.resumeTo()
        // Full-width window so the centered toolbar pill can never be clipped off the
        // screen edge (which made the rightmost / Done button hard to hit).
        val params = layoutParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            focusable = false,
        ).apply {
            gravity = Gravity.TOP
        }
        runCatching { windowManager.addView(view, params) }
            .onFailure { Log.e(TAG, "addView(toolbar) failed", it); return }
        toolbar = view to owner
    }

    /**
     * Config as a full-height **side drawer** docked to the edge *opposite* the button, so
     * the button stays visible while editing. A side panel scrolls freely for long settings
     * lists (a bottom sheet would dismiss on scroll). Edits commit live (WYSIWYG) — the
     * button's window re-renders as the repo emits.
     */
    private fun showConfig(elementId: Long) {
        dismissConfig()
        val element = overlayEditor.elements.value.firstOrNull { it.id == elementId } ?: return
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(context).apply {
            attachOwner(owner)
            setContent {
                MapoTheme {
                    // surfaceContainerHigh — elevated side panel; scrolls for long settings.
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 20.dp),
                        ) {
                            OverlayElementConfigContent(
                                element = element,
                                onChange = { overlayEditor.update(it) },
                                onDelete = {
                                    overlayEditor.delete(elementId)
                                    selectedId.value = null
                                    dismissConfig()
                                },
                                onDone = { dismissConfig() },
                            )
                        }
                    }
                }
            }
        }
        owner.resumeTo()
        // Dock to the side opposite the button (button center on the left half → drawer right).
        val buttonOnLeftHalf = (element.x + element.width / 2f) < 0.5f
        val drawerEdge = if (buttonOnLeftHalf) Gravity.END else Gravity.START
        val widthPx = (CONFIG_DRAWER_WIDTH_DP * context.resources.displayMetrics.density).roundToInt()
        // Focusable — the label TextField needs IME. The only focusable surface in edit mode.
        val params = layoutParams(
            width = widthPx,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            focusable = true,
        ).apply { gravity = Gravity.TOP or drawerEdge }
        runCatching { windowManager.addView(view, params) }
            .onFailure { Log.e(TAG, "addView(config) failed", it); return }
        configWindow = view to owner
    }

    private fun dismissConfig() {
        configWindow?.let { detach(it) }
        configWindow = null
    }

    @Composable
    private fun ToolbarContent() {
        val sel by selectedId.collectAsStateWithLifecycle()
        val snap by overlaySettings.snapEnabled.collectAsStateWithLifecycle()
        // Center the pill within the full-width window with a little top inset (clears the
        // status bar / notch). surfaceContainerHigh — floating toolbar (elevated container).
        // This is the overlay's own settings surface in edit mode (Brick D): snap lives here.
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                IconButton(onClick = { sel?.let { scaleSelected(it, 1.15f) } }, enabled = sel != null) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Bigger")
                }
                IconButton(onClick = { sel?.let { scaleSelected(it, 1f / 1.15f) } }, enabled = sel != null) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "Smaller")
                }
                IconButton(onClick = { sel?.let { showConfig(it) } }, enabled = sel != null) {
                    Icon(Icons.Default.Edit, contentDescription = "Configure")
                }
                IconButton(
                    onClick = { sel?.let { overlayEditor.delete(it); selectedId.value = null } },
                    enabled = sel != null,
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
                FilledIconButton(onClick = { stop() }) {
                    Icon(Icons.Default.Check, contentDescription = "Done")
                }
            }
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

    private fun scaleSelected(id: Long, factor: Float) {
        val element = overlayEditor.elements.value.firstOrNull { it.id == id } ?: return
        overlayEditor.moveResize(
            id = id,
            x = element.x,
            y = element.y,
            width = element.width * factor,
            height = element.height * factor,
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

    private fun layoutParams(width: Int, height: Int, focusable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
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
     * sibling near/far edges (including adjacent stacking), and sibling centers.
     */
    private fun snapPosition(draggedId: Long, x: Int, y: Int, w: Int, h: Int, size: Point): Point {
        val others = overlayEditor.elements.value.filter { it.id != draggedId }
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
        private const val CONFIG_DRAWER_WIDTH_DP = 340
    }
}
