package com.mappo.service.overlay.element

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import com.mappo.data.model.OverlayElement
import com.mappo.data.settings.OverlaySettings
import dagger.hilt.android.qualifiers.ApplicationContext
import com.mappo.data.repository.ControllerConfigRepository
import com.mappo.data.repository.OverlayRepository
import com.mappo.data.repository.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which action set / layer the overlay editor is currently editing. An [OverlayElement] is
 * owned by exactly one of these (see the entity doc).
 */
sealed interface OverlayScope {
    data class Set(val actionSetId: Long) : OverlayScope
    data class Layer(val actionLayerId: Long, val parentActionSetId: Long) : OverlayScope
}

/** A selectable scope for the editor dropdown (a set, or a layer nested under its set). */
data class ScopeOption(
    val scope: OverlayScope,
    val label: String,
    /** True for a layer row (rendered indented under its parent set). */
    val isLayer: Boolean,
)

/**
 * Shared edit operations on the rebuilt overlay's [OverlayElement]s (Brick C of
 * `OVERLAY_REBUILD_PLAN.md`). `@Singleton` so the live editor (`OverlayLiveEditController`,
 * which runs outside the activity and can't reach a `ViewModel`) writes through a single
 * source of truth that the run-mode [OverlayPresenter] also observes.
 *
 * Operations target the **current [editingScope]** — a single action set or layer of the
 * active profile, chosen via the editor's scope dropdown. [availableScopes] enumerates the
 * profile's sets + layers for that dropdown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class OverlayEditor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val overlayRepository: OverlayRepository,
    private val profileRepository: ProfileRepository,
    private val controllerConfigRepository: ControllerConfigRepository,
    private val overlaySettings: OverlaySettings,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val activeProfileId: StateFlow<Long?> = profileRepository.activeProfile
        .map { it?.id }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The active profile's action sets + their layers, flattened for the scope dropdown. */
    val availableScopes: StateFlow<List<ScopeOption>> = activeProfileId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else controllerConfigRepository.observeActiveConfig(id).map { config ->
                config?.actionSets.orEmpty().flatMap { setGraph ->
                    buildList {
                        add(
                            ScopeOption(
                                scope = OverlayScope.Set(setGraph.actionSet.id),
                                label = setGraph.actionSet.title,
                                isLayer = false,
                            ),
                        )
                        setGraph.layers.forEach { layerGraph ->
                            add(
                                ScopeOption(
                                    scope = OverlayScope.Layer(
                                        actionLayerId = layerGraph.layer.id,
                                        parentActionSetId = setGraph.actionSet.id,
                                    ),
                                    label = layerGraph.layer.title,
                                    isLayer = true,
                                ),
                            )
                        }
                    }
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _editingScope = MutableStateFlow<OverlayScope?>(null)
    /** The scope the editor is currently editing (defaults to the first set; see init). */
    val editingScope: StateFlow<OverlayScope?> = _editingScope.asStateFlow()

    init {
        // Default to the first available scope (the active/first set), and reset whenever the
        // current scope disappears (profile switch, or its set/layer was deleted).
        scope.launch {
            availableScopes.collect { options ->
                val current = _editingScope.value
                val stillValid = current != null && options.any { it.scope == current }
                if (!stillValid) _editingScope.value = options.firstOrNull()?.scope
            }
        }
    }

    fun setScope(newScope: OverlayScope) {
        if (_editingScope.value != newScope) clearHistory() // undo history is per editing scope
        _editingScope.value = newScope
    }

    // ── undo / redo (per editing scope; snapshots of the scope's full element list) ───────────────
    private val undoStack = ArrayDeque<List<OverlayElement>>()
    private val redoStack = ArrayDeque<List<OverlayElement>>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /** Capture the current scope's elements onto the undo stack — call BEFORE a mutating edit. */
    fun pushUndoSnapshot() {
        if (_editingScope.value == null) return
        undoStack.addLast(elements.value)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        syncUndoFlags()
    }

    fun undo() = restoreFrom(undoStack, redoStack)
    fun redo() = restoreFrom(redoStack, undoStack)

    private fun restoreFrom(from: ArrayDeque<List<OverlayElement>>, to: ArrayDeque<List<OverlayElement>>) {
        val current = _editingScope.value ?: return
        val snapshot = from.removeLastOrNull() ?: return
        to.addLast(elements.value) // current state becomes the inverse-direction entry
        syncUndoFlags()
        scope.launch {
            overlayRepository.replaceScopeElements(
                actionSetId = (current as? OverlayScope.Set)?.actionSetId,
                actionLayerId = (current as? OverlayScope.Layer)?.actionLayerId,
                elements = snapshot,
            )
        }
    }

    private fun clearHistory() {
        undoStack.clear(); redoStack.clear(); syncUndoFlags()
    }

    private fun syncUndoFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    /** Elements of the current [editingScope], live. Empty when no scope is selected. */
    val elements: StateFlow<List<OverlayElement>> = _editingScope
        .flatMapLatest { current ->
            when (current) {
                null -> flowOf(emptyList())
                is OverlayScope.Set -> overlayRepository.elementsBySet(current.actionSetId)
                is OverlayScope.Layer -> overlayRepository.elementsByLayer(current.actionLayerId)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Add a button near screen center, stamped with the current scope. No-op without a
     * scope. Defaults to a plain unbound circle (no label): width is a display fraction,
     * and height is derived from the display's aspect ratio so the button lands square
     * in pixels.
     */
    fun addDefaultElement() {
        val profileId = activeProfileId.value ?: return
        val current = _editingScope.value ?: return
        scope.launch {
            val n = elements.value.size
            val nudge = (n % 5) * 0.04f
            val size = displaySizePx()
            val height = if (size.y > 0) {
                (DEFAULT_DIAMETER * size.x.toFloat() / size.y.toFloat()).coerceIn(MIN_SIZE, 1f)
            } else DEFAULT_DIAMETER
            overlayRepository.add(
                clampGeometry(
                    OverlayElement(
                        profileId = profileId,
                        actionSetId = (current as? OverlayScope.Set)?.actionSetId,
                        actionLayerId = (current as? OverlayScope.Layer)?.actionLayerId,
                        label = "",
                        x = 0.40f + nudge,
                        y = 0.40f + nudge,
                        width = DEFAULT_DIAMETER,
                        height = height,
                        shape = OverlayElement.SHAPE_CIRCLE,
                        zIndex = n,
                    ),
                    editBounds(),
                ),
            )
        }
    }

    /** A normalized rect destined for one element, for [moveResizeAll]. */
    data class ElementRect(
        val id: Long,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    )

    /** Persist a new normalized rect for [id]. Inputs are clamped to sane bounds. */
    fun moveResize(id: Long, x: Float, y: Float, width: Float, height: Float) {
        moveResizeAll(listOf(ElementRect(id, x, y, width, height)))
    }

    /**
     * Persist new normalized rects for several elements in a **single** repository write, so the
     * [elements] flow re-emits exactly once with every new value. Committing a multi-button drag
     * this way (rather than one [moveResize] per element) avoids an intermediate emission where
     * some buttons carry their new position and others their old one — which the live editor would
     * otherwise render as a one-frame "flash" of the un-committed buttons back at their old spots.
     */
    fun moveResizeAll(rects: List<ElementRect>) {
        if (rects.isEmpty()) return
        scope.launch {
            val b = editBounds()
            val updated = rects.mapNotNull { r ->
                val current = overlayRepository.getById(r.id) ?: return@mapNotNull null
                clampGeometry(current.copy(x = r.x, y = r.y, width = r.width, height = r.height), b)
            }
            if (updated.isNotEmpty()) overlayRepository.update(updated)
        }
    }

    /** Persist a full element edit (instant-commit from the config drawer). */
    fun update(element: OverlayElement) {
        scope.launch { overlayRepository.update(clampGeometry(element, editBounds())) }
    }

    // ── Editable-area bounds ──────────────────────────────────────────────────────
    //
    // Normally the full display (normalized 0..1). With OverlaySettings.squareEditArea on
    // ("1:1 screen"), the centered square of side min(displayW, displayH) — the smallest
    // supported screen shape. This is the single DATA-LEVEL chokepoint: every geometry
    // write (drag/resize commits, nudges, config-drawer sliders, duplicates, adds) passes
    // through it, so nothing can persist outside the active bounds.

    private data class NormBounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
        val width: Float get() = maxX - minX
        val height: Float get() = maxY - minY
    }

    private fun editBounds(): NormBounds {
        if (!overlaySettings.squareEditArea.value) return FULL_BOUNDS
        val size = displaySizePx()
        if (size.x <= 0 || size.y <= 0) return FULL_BOUNDS
        val side = minOf(size.x, size.y).toFloat()
        val left = (size.x - side) / 2f / size.x
        val top = (size.y - side) / 2f / size.y
        return NormBounds(left, top, left + side / size.x, top + side / size.y)
    }

    private fun clampGeometry(el: OverlayElement, b: NormBounds): OverlayElement {
        val w = el.width.coerceIn(MIN_SIZE, b.width)
        val h = el.height.coerceIn(MIN_SIZE, b.height)
        return el.copy(
            x = el.x.coerceIn(b.minX, b.maxX - w),
            y = el.y.coerceIn(b.minY, b.maxY - h),
            width = w,
            height = h,
        )
    }

    private fun displaySizePx(): Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
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

    /**
     * Clone [sources] into the CURRENT scope, nudged by [PASTE_OFFSET] so the copies don't sit exactly
     * on top of their originals. New rows (id reset to 0 → auto-assigned). [onDone] receives the new ids
     * (e.g. so the caller can select the pastes); it may run on a background thread.
     */
    fun duplicate(sources: List<OverlayElement>, onDone: (List<Long>) -> Unit = {}) {
        val profileId = activeProfileId.value ?: return
        val current = _editingScope.value ?: return
        if (sources.isEmpty()) return
        scope.launch {
            val base = elements.value.size
            val b = editBounds()
            // ONE batched insert → a single flow emission → renderElements/raiseToolbar run ONCE
            // (inserting the clones one-by-one storms the toolbar recreation and can crash it).
            val clones = sources.mapIndexed { i, src ->
                clampGeometry(
                    src.copy(
                        id = 0,
                        profileId = profileId,
                        actionSetId = (current as? OverlayScope.Set)?.actionSetId,
                        actionLayerId = (current as? OverlayScope.Layer)?.actionLayerId,
                        x = src.x + PASTE_OFFSET,
                        y = src.y + PASTE_OFFSET,
                        zIndex = base + i,
                    ),
                    b,
                )
            }
            onDone(overlayRepository.addAll(clones))
        }
    }

    fun delete(id: Long) {
        scope.launch { overlayRepository.deleteById(id) }
    }

    /**
     * Create a new (default-seeded) action set in the active controller config and report its scope
     * (so the caller can switch the editor to it). The new set appears in [availableScopes] reactively.
     */
    fun addActionSet(onDone: (OverlayScope) -> Unit = {}) {
        val profileId = activeProfileId.value ?: return
        scope.launch {
            val config = controllerConfigRepository.getActiveConfigOnce(profileId) ?: return@launch
            val n = config.actionSets.size + 1
            val id = controllerConfigRepository.addActionSet(
                controllerProfileId = config.controllerProfile.id,
                name = "set_$n",
                title = "Action set $n",
            )
            onDone(OverlayScope.Set(id))
        }
    }

    /** Create a new empty layer under [actionSetId] and report its scope. */
    fun addLayer(actionSetId: Long, onDone: (OverlayScope) -> Unit = {}) {
        val profileId = activeProfileId.value ?: return
        scope.launch {
            val config = controllerConfigRepository.getActiveConfigOnce(profileId) ?: return@launch
            val set = config.actionSets.firstOrNull { it.actionSet.id == actionSetId } ?: return@launch
            val n = set.layers.size + 1
            val id = controllerConfigRepository.addLayer(actionSetId, name = "layer_$n", title = "Layer $n")
            onDone(OverlayScope.Layer(actionLayerId = id, parentActionSetId = actionSetId))
        }
    }

    companion object {
        private val FULL_BOUNDS = NormBounds(0f, 0f, 1f, 1f)

        // New buttons are circles: this display-width fraction is the diameter.
        const val DEFAULT_DIAMETER = 0.10f
        const val MIN_SIZE = 0.04f
        // Normalized offset applied to pasted/duplicated copies so they don't overlap the originals.
        const val PASTE_OFFSET = 0.03f
        // Depth of the per-scope undo history.
        const val MAX_UNDO = 50
    }
}
