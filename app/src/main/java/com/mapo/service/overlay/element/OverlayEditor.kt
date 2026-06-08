package com.mapo.service.overlay.element

import com.mapo.data.model.OverlayElement
import com.mapo.data.repository.ControllerConfigRepository
import com.mapo.data.repository.OverlayRepository
import com.mapo.data.repository.ProfileRepository
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
    private val overlayRepository: OverlayRepository,
    private val profileRepository: ProfileRepository,
    private val controllerConfigRepository: ControllerConfigRepository,
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
        _editingScope.value = newScope
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

    /** Add a button near screen center, stamped with the current scope. No-op without a scope. */
    fun addDefaultElement() {
        val profileId = activeProfileId.value ?: return
        val current = _editingScope.value ?: return
        scope.launch {
            val n = elements.value.size
            val nudge = (n % 5) * 0.04f
            overlayRepository.add(
                OverlayElement(
                    profileId = profileId,
                    actionSetId = (current as? OverlayScope.Set)?.actionSetId,
                    actionLayerId = (current as? OverlayScope.Layer)?.actionLayerId,
                    label = "",
                    x = (0.40f + nudge).coerceIn(0f, 0.84f),
                    y = (0.40f + nudge).coerceIn(0f, 0.88f),
                    width = DEFAULT_WIDTH,
                    height = DEFAULT_HEIGHT,
                    zIndex = n,
                ),
            )
        }
    }

    /** Persist a new normalized rect for [id]. Inputs are clamped to sane bounds. */
    fun moveResize(id: Long, x: Float, y: Float, width: Float, height: Float) {
        scope.launch {
            val current = overlayRepository.getById(id) ?: return@launch
            val w = width.coerceIn(MIN_SIZE, 1f)
            val h = height.coerceIn(MIN_SIZE, 1f)
            overlayRepository.update(
                current.copy(
                    x = x.coerceIn(0f, 1f - w),
                    y = y.coerceIn(0f, 1f - h),
                    width = w,
                    height = h,
                ),
            )
        }
    }

    /** Persist a full element edit (instant-commit from the config drawer). */
    fun update(element: OverlayElement) {
        scope.launch { overlayRepository.update(element) }
    }

    fun delete(id: Long) {
        scope.launch { overlayRepository.deleteById(id) }
    }

    companion object {
        const val DEFAULT_WIDTH = 0.16f
        const val DEFAULT_HEIGHT = 0.10f
        const val MIN_SIZE = 0.04f
    }
}
