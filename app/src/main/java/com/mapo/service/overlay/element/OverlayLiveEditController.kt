package com.mapo.service.overlay.element

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mapo.data.model.OverlayElement
import com.mapo.data.model.displayLabel
import com.mapo.data.model.tapRemapTarget
import com.mapo.data.settings.OverlaySettings
import com.mapo.service.overlay.OverlayLifecycleOwner
import com.mapo.ui.screen.overlay.OverlayElementConfigContent
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
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing
    private val selectedId = MutableStateFlow<Long?>(null)

    private val windowManager get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val snapThresholdPx =
        (OverlaySettings.SNAP_THRESHOLD_DP * context.resources.displayMetrics.density).roundToInt()

    private val elementWindows = mutableMapOf<Long, ElementWindow>()
    private var scrim: Pair<View, OverlayLifecycleOwner>? = null
    private var toolbar: Pair<View, OverlayLifecycleOwner>? = null
    private var configWindow: Pair<View, OverlayLifecycleOwner>? = null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)
    fun isEditing(): Boolean = _editing.value

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
        val view = ComposeView(context).apply {
            attachOwner(owner)
            setContent {
                MapoTheme {
                    val sel by selectedId.collectAsStateWithLifecycle()
                    EditableElement(
                        element = state.value,
                        selected = sel == state.value.id,
                        // Tap selects; tapping an already-selected button opens its command
                        // config (so assigning an input is reachable without the toolbar pencil).
                        onTap = {
                            if (selectedId.value == element.id) showConfig(element.id)
                            else selectedId.value = element.id
                        },
                        onDrag = { dx, dy -> onElementDrag(element.id, dx, dy) },
                        onDragEnd = { onElementDragEnd(element.id) },
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
     * Move an element's window by a Compose-gesture delta. [dx]/[dy] are
     * `pointer.position − grabPoint` (current-window-relative), so `params + delta` tracks
     * the finger's absolute screen position even though the window moves under it each
     * event — no raw-coordinate listener needed (and a `setOnTouchListener` on a ComposeView
     * never fires anyway; the Compose child eats the touch — see memory).
     */
    private fun onElementDrag(id: Long, dx: Float, dy: Float) {
        val w = elementWindows[id] ?: return
        val size = displaySizePx()
        var nx = (w.params.x + dx).roundToInt().coerceIn(0, size.x - w.params.width)
        var ny = (w.params.y + dy).roundToInt().coerceIn(0, size.y - w.params.height)
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

    private fun showConfig(elementId: Long) {
        dismissConfig()
        val element = overlayEditor.elements.value.firstOrNull { it.id == elementId } ?: return
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(context).apply {
            attachOwner(owner)
            setContent {
                MapoTheme {
                    // surfaceContainerHigh — elevated transient config panel.
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Box(Modifier.padding(vertical = 16.dp)) {
                            OverlayElementConfigContent(
                                initialLabel = element.label,
                                initialTarget = element.tapRemapTarget,
                                onSave = { label, target ->
                                    overlayEditor.setBinding(elementId, label, target)
                                },
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
        // Focusable — the label TextField needs IME. The only focusable surface in edit mode.
        val params = layoutParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            focusable = true,
        ).apply { gravity = Gravity.CENTER }
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
     * Editable button. Select + drag are driven by **Compose `pointerInput`** (not a view
     * `OnTouchListener`, which never fires on a ComposeView). The drag reports
     * `position − grab` deltas; [onDrag] applies them to the window. A move past touch-slop
     * is a drag; otherwise it's a tap.
     */
    @Composable
    private fun EditableElement(
        element: OverlayElement,
        selected: Boolean,
        onTap: () -> Unit,
        onDrag: (dx: Float, dy: Float) -> Unit,
        onDragEnd: () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(element.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val grabX = down.position.x
                        val grabY = down.position.y
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dx = change.position.x - grabX
                            val dy = change.position.y - grabY
                            if (!dragging && hypot(dx, dy) > viewConfiguration.touchSlop) dragging = true
                            if (dragging) {
                                onDrag(dx, dy)
                                change.consume()
                            }
                        }
                        if (dragging) onDragEnd() else onTap()
                    }
                },
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                tonalElevation = 3.dp,
                border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(4.dp)) {
                    Text(
                        text = element.label.ifBlank { element.tapRemapTarget.displayLabel() },
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
    }
}
