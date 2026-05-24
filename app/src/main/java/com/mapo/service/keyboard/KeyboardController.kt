package com.mapo.service.keyboard

import com.mapo.data.model.GridButton
import com.mapo.data.model.GridLayout
import com.mapo.data.model.RemapTarget
import com.mapo.data.model.TrackpadGesture
import com.mapo.data.model.gestureTarget
import com.mapo.data.model.onDoubleTapTarget
import com.mapo.data.model.onHoldTarget
import com.mapo.data.model.onTapTarget
import com.mapo.data.model.toGridLayout
import com.mapo.data.repository.LayoutRepository
import com.mapo.data.repository.ProfileRepository
import com.mapo.di.IoDispatcher
import com.mapo.service.input.InputDispatcher
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide owner of the virtual keyboard's runtime state and dispatch surface.
 * `@Singleton` (Hilt) so the in-activity keyboard host and the system-overlay
 * keyboard host both read from and write through the same instance — answering
 * R3 from the single-screen refactor plan (ViewModel instancing across activity
 * + overlay would otherwise produce divergent state).
 *
 * **What this owns (source of truth):**
 *  - `layouts` — the active profile's layouts in order. Auto-loaded from
 *    `LayoutRepository` whenever the active profile changes.
 *  - `selectedIndex` — which tab is selected.
 *  - `displayLayout` (FC1 seam — `StateFlow<GridLayout?>`) — the rendered grid.
 *    Today's resolver is `(profile, selectedIndex) → GridLayout`. Tomorrow's
 *    `(profile, activeActionSet, activeActionLayer) → GridLayout` is a local swap.
 *  - `tabs` (FC1 seam — `List<KeyboardTab>`) — opaque tab descriptors, not
 *    `List<Layout>`. Same reasoning as `displayLayout`.
 *  - `remapEnabled` — global remap-toggle flag, mirrored into [InputDispatcher].
 *  - `activeProfileId` — convenience projection of the profile-repo's active flow.
 *
 * **What this does NOT own (stays in [com.mapo.ui.viewmodel.MainViewModel]):**
 *  - Edit-mode UI state (`selectedButtonId`, `editingLayoutId`, tab context menus,
 *    dialog states, viewing action set / layer pointers) — all activity-local.
 *  - Layout CRUD logic (`addButton`, `deleteButton`, reorder, rename, duplicate,
 *    persistLayoutFields, …). Those still live in `MainViewModel` because they're
 *    edit-mode operations triggered from the activity surface; they call the
 *    [replaceLayouts] / [replaceLayoutById] mutators here to update the source of
 *    truth optimistically before the DB roundtrip.
 *
 * **Brick 2 status (behavior-preserving extraction).** No behavior change vs.
 * pre-extraction MainViewModel — the run-mode dispatch and state-load logic was
 * moved verbatim. Activity-side StateFlow surface is preserved by MainViewModel
 * re-exposing this controller's flows under the same names it used before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class KeyboardController @Inject constructor(
    private val inputDispatcher: InputDispatcher,
    private val layoutRepository: LayoutRepository,
    private val profileRepository: ProfileRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    // Singleton lives the lifetime of the process. SupervisorJob + Main so failures
    // in one collector don't cascade, and StateFlow updates land on the main thread
    // where Compose can pick them up without re-dispatch.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ── State (source of truth) ───────────────────────────────────────────────

    private val _layouts = MutableStateFlow<ImmutableList<GridLayout>>(persistentListOf())
    val layouts: StateFlow<ImmutableList<GridLayout>> = _layouts.asStateFlow()

    private val _selectedIndex = MutableStateFlow(0)
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

    private val _remapEnabled = MutableStateFlow(true)
    val remapEnabled: StateFlow<Boolean> = _remapEnabled.asStateFlow()

    /**
     * FC1 seam: the rendered grid is a nullable opaque [GridLayout]. Consumers that
     * need a non-null surface (like the activity-side host today) apply their own
     * fallback. Returns null when no layouts have loaded yet (e.g. very first cold
     * start before the repo emits) — that's the only legitimate null state.
     */
    val displayLayout: StateFlow<GridLayout?> = combine(_selectedIndex, _layouts) { idx, layouts ->
        layouts.getOrNull(idx) ?: layouts.firstOrNull()
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * FC1 seam: tabs as [KeyboardTab] descriptors, never [GridLayout] rows. When
     * the eventual action-set–governed model lands, the producer of this flow
     * swaps from `_layouts.map { … }` to `(activeSet, layers).map { … }` and the
     * consumer side doesn't notice.
     */
    val tabs: StateFlow<ImmutableList<KeyboardTab>> = _layouts
        .map { layouts -> layouts.map { KeyboardTab(it.id, it.name) }.toImmutableList() }
        .stateIn(scope, SharingStarted.Eagerly, persistentListOf())

    val activeProfileId: StateFlow<Long?> = profileRepository.activeProfile
        .map { it?.id }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Run-mode dispatch errors that the activity surfaces as toasts ("Accessibility
     * service not running"). MainViewModel collects this and relays into its
     * existing `toastMessage` SharedFlow so MainScreen's existing toast collector
     * keeps working unchanged.
     */
    private val _errorMessages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errorMessages: SharedFlow<String> = _errorMessages.asSharedFlow()

    init {
        // Layouts-from-repo collector. Mirrors what MainViewModel did pre-Brick-2.
        // Single subscription per process (we're @Singleton) — both activity and
        // overlay read the same StateFlow.
        scope.launch {
            profileRepository.activeProfile
                .filterNotNull()
                .flatMapLatest { profile ->
                    flow {
                        layoutRepository.seedDefaultsIfEmpty(profile.id)
                        emitAll(layoutRepository.getLayoutsByProfile(profile.id))
                    }
                }
                .collect { roomLayouts ->
                    _layouts.value = roomLayouts.map { it.toGridLayout() }.toImmutableList()
                    if (_selectedIndex.value >= roomLayouts.size) {
                        _selectedIndex.value = (roomLayouts.size - 1).coerceAtLeast(0)
                    }
                }
        }
    }

    // ── State mutators (called by MainViewModel CRUD paths) ───────────────────

    fun setSelectedIndex(index: Int) {
        _selectedIndex.value = index
    }

    /**
     * Replace the entire layouts list. Used by reorder/remove/batch operations as
     * an optimistic UI update — the next DB emit through the init collector
     * reconciles the in-memory state with persistence.
     */
    fun replaceLayouts(newLayouts: ImmutableList<GridLayout>) {
        _layouts.value = newLayouts
    }

    /**
     * Replace a single layout by id (no-op if not found). Used by per-button CRUD
     * where exactly one layout changed and a full-list rewrite would be wasteful.
     */
    fun replaceLayoutById(updated: GridLayout) {
        val current = _layouts.value
        val idx = current.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            _layouts.value = current.toPersistentList().set(idx, updated)
        }
    }

    fun toggleRemap() {
        val enabled = !_remapEnabled.value
        _remapEnabled.value = enabled
        inputDispatcher.setRemapEnabled(enabled)
    }

    // ── Run-mode dispatch ─────────────────────────────────────────────────────

    fun onButtonTap(button: GridButton) = dispatchButtonTarget(button.onTapTarget)
    fun onButtonDoubleTap(button: GridButton) = dispatchButtonTarget(button.onDoubleTapTarget)
    fun onButtonHold(button: GridButton) = dispatchButtonTarget(button.onHoldTarget)

    private fun dispatchButtonTarget(target: RemapTarget) {
        if (target is RemapTarget.Unbound) return
        if (!inputDispatcher.isReady) {
            _errorMessages.tryEmit(ERR_SERVICE_NOT_RUNNING)
            return
        }
        when (target) {
            is RemapTarget.Unbound -> Unit  // already returned above; here for exhaustiveness
            is RemapTarget.Keyboard -> inputDispatcher.injectKey(target.code)
            is RemapTarget.Mouse, is RemapTarget.Gamepad ->
                inputDispatcher.dispatchTargetAsClick(target)
        }
    }

    fun onTrackpadGesture(button: GridButton, gesture: TrackpadGesture) {
        val target = button.gestureTarget(gesture)
        android.util.Log.d(TAG, "onTrackpadGesture button=${button.id} gesture=$gesture target=$target")
        if (!inputDispatcher.isReady) {
            _errorMessages.tryEmit(ERR_SERVICE_NOT_RUNNING)
            return
        }
        inputDispatcher.dispatchTargetAsClick(target)
    }

    fun onDragStart() {
        if (inputDispatcher.isReady) {
            inputDispatcher.startMouseDrag()
        } else {
            _errorMessages.tryEmit(ERR_SERVICE_NOT_RUNNING)
        }
    }

    fun onMouseMove(dx: Float, dy: Float) {
        inputDispatcher.injectMouseMove(dx, dy)
    }

    fun onDragEnd() {
        inputDispatcher.endMouseDrag()
    }

    companion object {
        private const val TAG = "MapoInput"
        private const val ERR_SERVICE_NOT_RUNNING = "Accessibility service not running"
    }
}
