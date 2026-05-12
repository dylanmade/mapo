package com.mapo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapo.data.defaults.DefaultLayouts
import com.mapo.data.model.AppProfileBinding
import com.mapo.data.model.GridButton
import com.mapo.data.model.GridLayout
import com.mapo.data.model.LayoutSnapshot
import com.mapo.data.model.Profile
import com.mapo.data.model.RemapTarget
import com.mapo.data.model.TemplateRef
import com.mapo.data.model.TrackpadGesture
import com.mapo.data.model.buttonsExceeding
import com.mapo.data.model.withFreshButtonIds
import com.mapo.data.model.gestureTarget
import com.mapo.data.model.onDoubleTapTarget
import com.mapo.data.model.onHoldTarget
import com.mapo.data.model.onTapTarget
import com.mapo.data.model.findFirstEmptyArea
import com.mapo.data.model.findFirstEmptyCell
import com.mapo.data.model.parseOriginalSnapshot
import com.mapo.data.model.toGridLayout
import com.mapo.data.model.toJson
import com.mapo.data.model.toKeyLayout
import com.mapo.data.model.toSnapshot
import com.mapo.data.model.wouldOverlap
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.data.repository.AppProfileBindingRepository
import com.mapo.data.repository.ControllerConfigRepository
import com.mapo.data.repository.KeyboardTemplateRepository
import com.mapo.data.repository.LayoutRepository
import com.mapo.data.repository.ProfileRepository
import com.mapo.data.settings.AutoSwitchSettings
import com.mapo.di.IoDispatcher
import com.mapo.service.autoswitch.ProfileAutoSwitcher
import com.mapo.service.foreground.ForegroundAppFilter
import com.mapo.service.input.InputDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.flow.flow
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class TabUiEvent {
    data class ConfigureConflict(
        val layoutId: Long,
        val name: String,
        val cols: Int,
        val rows: Int,
        val bgColor: Int?,
        val offendingLabels: List<String>
    ) : TabUiEvent()

    data class TemplateNameConflict(
        val layoutId: Long,
        val templateName: String,
        val existing: TemplateRef
    ) : TabUiEvent()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val layoutRepository: LayoutRepository,
    private val profileRepository: ProfileRepository,
    private val controllerConfigRepository: ControllerConfigRepository,
    private val appProfileBindingRepository: AppProfileBindingRepository,
    private val autoSwitchSettings: AutoSwitchSettings,
    private val autoSwitcher: ProfileAutoSwitcher,
    private val foregroundAppFilter: ForegroundAppFilter,
    private val keyboardTemplateRepository: KeyboardTemplateRepository,
    private val inputDispatcher: InputDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    // Empty until the active profile's layouts emit from the DB. Avoids duplicate LazyRow keys
    // (every DefaultLayouts entry has id=0 since they're code-only).
    private val _layouts = MutableStateFlow<ImmutableList<GridLayout>>(persistentListOf())
    val layouts: StateFlow<ImmutableList<GridLayout>> = _layouts.asStateFlow()

    private val _originalNames = MutableStateFlow<Map<Long, String>>(emptyMap())
    val originalNames: StateFlow<Map<Long, String>> = _originalNames.asStateFlow()

    private val _selectedIndex = MutableStateFlow(0)
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

    // Single source of truth for "is some tab being edited?". Replaces the previous
    // (_isEditMode, _editingLayout) pair: there's no buffered draft anymore — every
    // edit op writes through to _layouts and the DB immediately. `null` = not editing.
    private val _editingLayoutId = MutableStateFlow<Long?>(null)
    val editingLayoutId: StateFlow<Long?> = _editingLayoutId.asStateFlow()
    val isEditMode: StateFlow<Boolean> = _editingLayoutId
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _selectedButtonId = MutableStateFlow<String?>(null)
    val selectedButtonId: StateFlow<String?> = _selectedButtonId.asStateFlow()

    private val _tabContextMenuFor = MutableStateFlow<Long?>(null)
    val tabContextMenuFor: StateFlow<Long?> = _tabContextMenuFor.asStateFlow()

    // Bursts during duplicate/reorder/template flows would otherwise drop with a 1-slot buffer.
    private val _toastMessage = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private val _tabUiEvents = MutableSharedFlow<TabUiEvent>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val tabUiEvents: SharedFlow<TabUiEvent> = _tabUiEvents.asSharedFlow()

    val activeProfile: StateFlow<Profile?> = profileRepository.activeProfile

    private val _profiles = MutableStateFlow<ImmutableList<Profile>>(persistentListOf())
    val profiles: StateFlow<ImmutableList<Profile>> = _profiles.asStateFlow()

    private val _remapEnabled = MutableStateFlow(false)
    val remapEnabled: StateFlow<Boolean> = _remapEnabled.asStateFlow()

    val autoSwitchEnabled: StateFlow<Boolean> = autoSwitchSettings.autoSwitchEnabled

    val autoCreateProfilesEnabled: StateFlow<Boolean> = autoSwitchSettings.autoCreateProfilesEnabled

    val ignoredPackages: StateFlow<Set<String>> = autoSwitchSettings.ignoredPackages

    val appProfileBindings: StateFlow<ImmutableList<AppProfileBinding>> =
        appProfileBindingRepository.getAll()
            .map { it.toImmutableList() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    // Cached app labels for packages referenced by bindings or the blocklist. Resolved
    // off the main thread (PackageManager calls aren't free) and looked up by Compose
    // via `appLabels[pkg] ?: pkg` — never call PackageManager from composition.
    private val _appLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val appLabels: StateFlow<Map<String, String>> = _appLabels.asStateFlow()

    val autoSwitchEvents: SharedFlow<ProfileAutoSwitcher.UiEvent> = autoSwitcher.events

    /**
     * The materialized binding graph for the active profile's active controller.
     * Auto-seeds a default config on first observation if none exists.
     * `RemapControlsScreen` reads this and writes back via [setControllerBinding].
     *
     * Runtime input dispatch is NOT wired to this graph yet — brick 1.4 removed the
     * legacy gamepad-mapping pipeline, so `InputDispatcher.currentMappings` stays empty
     * until Phase 2 (runtime evaluator) reads bindings out of this graph directly.
     */
    val activeControllerConfig: StateFlow<ControllerConfig?> =
        activeProfile.filterNotNull()
            .flatMapLatest { controllerConfigRepository.observeActiveConfig(it.id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val templates: StateFlow<ImmutableList<TemplateRef>> = keyboardTemplateRepository.allTemplates
        .map { it.toImmutableList() }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            keyboardTemplateRepository.builtIns.toImmutableList()
        )

    val displayLayout: StateFlow<GridLayout> = combine(
        _selectedIndex, _layouts
    ) { index, layouts ->
        layouts.getOrNull(index) ?: layouts.firstOrNull() ?: DefaultLayouts.all[0]
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefaultLayouts.all[0])

    init {
        viewModelScope.launch {
            profileRepository.getAllProfiles().collect { _profiles.value = it.toImmutableList() }
        }
        viewModelScope.launch {
            activeProfile.filterNotNull().flatMapLatest { profile ->
                flow {
                    layoutRepository.seedDefaultsIfEmpty(profile.id)
                    emitAll(layoutRepository.getLayoutsByProfile(profile.id))
                }
            }.collect { roomLayouts ->
                _layouts.value = roomLayouts.map { it.toGridLayout() }.toImmutableList()
                _originalNames.value = roomLayouts.mapNotNull { row ->
                    row.parseOriginalSnapshot()?.let { snap -> row.id to snap.name }
                }.toMap()
                if (_selectedIndex.value >= roomLayouts.size) {
                    _selectedIndex.value = (roomLayouts.size - 1).coerceAtLeast(0)
                }
            }
        }
        viewModelScope.launch {
            combine(appProfileBindings, ignoredPackages) { bindings, ignored ->
                bindings.mapTo(mutableSetOf()) { it.packageName }.apply { addAll(ignored) }
            }.collect { packages ->
                val current = _appLabels.value
                val missing = packages - current.keys
                if (missing.isEmpty()) return@collect
                val resolved = withContext(ioDispatcher) {
                    missing.associateWith { foregroundAppFilter.appLabel(it) }
                }
                _appLabels.value = current + resolved
            }
        }
    }

    // ── Profile ───────────────────────────────────────────────────────────────

    fun selectProfile(profile: Profile) {
        profileRepository.setActiveProfile(profile)
        _selectedIndex.value = 0
    }

    fun addProfile(name: String) {
        viewModelScope.launch { profileRepository.addProfile(name) }
    }

    fun duplicateProfile(source: Profile) {
        viewModelScope.launch {
            profileRepository.duplicateProfile(source, "Copy of ${source.name}")
        }
    }

    fun deleteProfile(profile: Profile) {
        val defaultProfile = _profiles.value.firstOrNull { it.isDefault }
        viewModelScope.launch {
            profileRepository.deleteProfile(profile)
            if (activeProfile.value?.id == profile.id && defaultProfile != null) {
                profileRepository.setActiveProfile(defaultProfile)
                _selectedIndex.value = 0
            }
        }
    }

    fun toggleRemap() {
        val enabled = !_remapEnabled.value
        _remapEnabled.value = enabled
        inputDispatcher.setRemapEnabled(enabled)
    }

    /**
     * Tell the accessibility service to swallow `KEYCODE_BACK` while the keyboard view
     * (Main destination + drawer closed) is showing — that view has FLAG_NOT_FOCUSABLE set
     * for gamepad routing, so back has no focusable target and would ANR the input dispatcher.
     */
    fun setConsumeSystemBack(consume: Boolean) {
        inputDispatcher.setConsumeSystemBack(consume)
    }

    // ── Auto-switch ───────────────────────────────────────────────────────────

    fun setAutoSwitchEnabled(enabled: Boolean) {
        autoSwitchSettings.setAutoSwitchEnabled(enabled)
    }

    fun setAutoCreateProfilesEnabled(enabled: Boolean) {
        autoSwitchSettings.setAutoCreateProfilesEnabled(enabled)
    }

    /** Re-fire auto-switch against the cached foreground package; called on activity resume. */
    fun reevaluateAutoSwitch() {
        autoSwitcher.reevaluate()
    }

    fun acceptCreateProfilePrompt(pkg: String, appLabel: String) {
        viewModelScope.launch { autoSwitcher.createProfileAndBind(pkg, appLabel) }
    }

    fun ignorePackageForever(pkg: String) {
        autoSwitcher.ignorePackage(pkg)
    }

    fun unignorePackage(pkg: String) {
        autoSwitchSettings.removeIgnoredPackage(pkg)
    }

    fun deleteBinding(packageName: String, subId: String = "") {
        viewModelScope.launch { appProfileBindingRepository.unbind(packageName, subId) }
    }

    /**
     * Replaces the single binding on [activatorId] with [output]. Active-profile guard
     * means picker round-trips that fire after the profile is gone become no-ops
     * rather than throwing.
     */
    fun setControllerBinding(activatorId: Long, output: BindingOutput) {
        if (activeProfile.value == null) return
        viewModelScope.launch { controllerConfigRepository.setBinding(activatorId, output) }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    fun selectLayout(index: Int) {
        // Visiting any tab exits edit mode for the previously-edited tab. Per design,
        // only one tab can be in edit mode at a time and tab navigation is always free.
        _selectedIndex.value = index
        if (_editingLayoutId.value != null && _layouts.value.getOrNull(index)?.id != _editingLayoutId.value) {
            _editingLayoutId.value = null
            _selectedButtonId.value = null
        }
    }

    // ── Normal mode ───────────────────────────────────────────────────────────

    fun onButtonTap(button: GridButton) = dispatchButtonTarget(button.onTapTarget)
    fun onButtonDoubleTap(button: GridButton) = dispatchButtonTarget(button.onDoubleTapTarget)
    fun onButtonHold(button: GridButton) = dispatchButtonTarget(button.onHoldTarget)

    private fun dispatchButtonTarget(target: RemapTarget) {
        if (target is RemapTarget.Unbound) return
        if (!inputDispatcher.isReady) {
            _toastMessage.tryEmit("Accessibility service not running")
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
        android.util.Log.d("MapoInput", "onTrackpadGesture button=${button.id} gesture=$gesture target=$target")
        if (!inputDispatcher.isReady) {
            _toastMessage.tryEmit("Accessibility service not running")
            return
        }
        inputDispatcher.dispatchTargetAsClick(target)
    }

    fun onDragStart() {
        if (inputDispatcher.isReady) {
            inputDispatcher.startMouseDrag()
        } else {
            _toastMessage.tryEmit("Accessibility service not running")
        }
    }

    fun onMouseMove(dx: Float, dy: Float) {
        inputDispatcher.injectMouseMove(dx, dy)
    }

    fun onDragEnd() {
        inputDispatcher.endMouseDrag()
    }

    // ── Tab context menu ──────────────────────────────────────────────────────

    fun openTabMenu(layoutId: Long) {
        // Tab gestures are unrestricted in edit mode — long-press still opens the menu.
        _tabContextMenuFor.value = layoutId
    }

    fun closeTabMenu() {
        _tabContextMenuFor.value = null
    }

    // ── Edit mode lifecycle ───────────────────────────────────────────────────

    fun enterEditMode(layoutId: Long) {
        val targetIdx = _layouts.value.indexOfFirst { it.id == layoutId }
        if (targetIdx < 0) return
        _selectedIndex.value = targetIdx
        _selectedButtonId.value = null
        _editingLayoutId.value = layoutId
        _tabContextMenuFor.value = null
    }

    fun exitEditMode() {
        _editingLayoutId.value = null
        _selectedButtonId.value = null
    }

    // ── Button selection ──────────────────────────────────────────────────────

    fun selectButton(id: String) {
        _selectedButtonId.value = if (_selectedButtonId.value == id) null else id
    }

    /** Force-select [id] (no toggle). Used by the long-press menu's "Configure" action. */
    fun selectButtonOnly(id: String) {
        _selectedButtonId.value = id
    }

    // ── Button CRUD ───────────────────────────────────────────────────────────
    //
    // All button mutations write through to the DB via persistLayoutFields. Edits are
    // permanent the moment they're made — there is no draft/Save/Cancel layer. Edit
    // mode (`_editingLayoutId`) is purely a UI-affordance flag (drag handles, +icons,
    // long-press menus) and does NOT gate writes; otherwise instant-commit paths like
    // ConfigureButtonScreen would silently no-op when edit mode happened to be off.

    /**
     * Apply [transform] to whichever layout currently owns [buttonId]. Used by per-
     * button operations (update/delete/duplicate/move/resize) where the relevant
     * layout is unambiguously the one containing the targeted button.
     */
    private inline fun mutateLayoutContaining(
        buttonId: String,
        transform: (GridLayout) -> GridLayout?,
    ) {
        val profileId = activeProfile.value?.id ?: return
        val current = _layouts.value.find { l -> l.buttons.any { it.id == buttonId } } ?: return
        val updated = transform(current) ?: return
        persistLayoutFields(updated, profileId)
    }

    /**
     * Apply [transform] to the currently-displayed layout. Used by add-style operations
     * where the only meaningful target is the visible tab — these are only triggered
     * from the visible keyboard's edit-mode UI in the first place.
     *
     * Resolves the displayed layout from `_layouts` + `_selectedIndex` directly rather
     * than reading [displayLayout].value — that StateFlow uses WhileSubscribed and
     * returns its initial fallback when there are no active collectors (e.g. in unit
     * tests), which would silently route writes to the default layout instead of the
     * one the user is editing.
     */
    private inline fun mutateDisplayedLayout(transform: (GridLayout) -> GridLayout?) {
        val profileId = activeProfile.value?.id ?: return
        val layouts = _layouts.value
        val current = layouts.getOrNull(_selectedIndex.value) ?: layouts.firstOrNull() ?: return
        val updated = transform(current) ?: return
        persistLayoutFields(updated, profileId)
    }

    /**
     * Add [spec] to the displayed layout at the first available cell. The spec's
     * `id`, `col`, and `row` are overwritten with a fresh UUID and the chosen cell.
     */
    fun addButton(spec: GridButton) {
        mutateDisplayedLayout { layout ->
            val cell = layout.findFirstEmptyCell()
            if (cell == null) {
                emitError("No empty space available in this layout")
                return@mutateDisplayedLayout null
            }
            val placed = spec.copy(
                id = java.util.UUID.randomUUID().toString(),
                col = cell.first,
                row = cell.second,
            )
            _selectedButtonId.value = placed.id
            layout.copy(buttons = layout.buttons + placed)
        }
    }

    /** Replace the currently-selected button with [updated]. The button's id must match. */
    fun updateSelectedButton(updated: GridButton) {
        val id = _selectedButtonId.value ?: return
        mutateLayoutContaining(id) { layout ->
            layout.copy(buttons = layout.buttons.map { btn ->
                if (btn.id == id) updated.copy(id = id) else btn
            })
        }
    }

    fun deleteSelectedButton() {
        val id = _selectedButtonId.value ?: return
        mutateLayoutContaining(id) { layout ->
            _selectedButtonId.value = null
            layout.copy(buttons = layout.buttons.filter { it.id != id })
        }
    }

    fun deleteButton(id: String) {
        mutateLayoutContaining(id) { layout ->
            if (_selectedButtonId.value == id) _selectedButtonId.value = null
            layout.copy(buttons = layout.buttons.filter { it.id != id })
        }
    }

    fun duplicateButton(id: String) {
        mutateLayoutContaining(id) { layout ->
            val source = layout.buttons.find { it.id == id }
                ?: return@mutateLayoutContaining null
            // Try the source's original size first; only downscale if no empty area
            // that big exists. Falling all the way to 1×1 (always last attempt) is
            // better than refusing the duplicate when only smaller gaps remain.
            val originalFit = layout.findFirstEmptyArea(source.colSpan, source.rowSpan)
            val (col, row, cs, rs) = if (originalFit != null) {
                Placement(originalFit.first, originalFit.second, source.colSpan, source.rowSpan)
            } else {
                val small = layout.findFirstEmptyCell()
                if (small == null) {
                    emitError("No empty space available in this layout")
                    return@mutateLayoutContaining null
                }
                Placement(small.first, small.second, 1, 1)
            }
            val copy = source.copy(
                id = java.util.UUID.randomUUID().toString(),
                col = col,
                row = row,
                colSpan = cs,
                rowSpan = rs
            )
            _selectedButtonId.value = copy.id
            layout.copy(buttons = layout.buttons + copy)
        }
    }

    private data class Placement(val col: Int, val row: Int, val colSpan: Int, val rowSpan: Int)

    fun addButtonAt(col: Int, row: Int, spec: GridButton) {
        mutateDisplayedLayout { layout ->
            if (col !in 0 until layout.columns || row !in 0 until layout.rows) {
                return@mutateDisplayedLayout null
            }
            if (layout.wouldOverlap("__new__", col, row, 1, 1)) {
                emitError("Cell already occupied")
                return@mutateDisplayedLayout null
            }
            val placed = spec.copy(
                id = java.util.UUID.randomUUID().toString(),
                col = col,
                row = row,
            )
            _selectedButtonId.value = placed.id
            layout.copy(buttons = layout.buttons + placed)
        }
    }

    // ── Drag to move ──────────────────────────────────────────────────────────

    fun moveButton(buttonId: String, newCol: Int, newRow: Int) {
        mutateLayoutContaining(buttonId) { layout ->
            val button = layout.buttons.find { it.id == buttonId }
                ?: return@mutateLayoutContaining null
            val col = newCol.coerceIn(0, layout.columns - button.colSpan)
            val row = newRow.coerceIn(0, layout.rows - button.rowSpan)
            if (layout.wouldOverlap(buttonId, col, row, button.colSpan, button.rowSpan)) {
                return@mutateLayoutContaining null
            }
            layout.copy(
                buttons = layout.buttons.map {
                    if (it.id == buttonId) it.copy(col = col, row = row) else it
                }
            )
        }
    }

    // ── Resize ────────────────────────────────────────────────────────────────

    /** Resize from the bottom-right corner: origin (col,row) stays put. */
    fun resizeButton(buttonId: String, newColSpan: Int, newRowSpan: Int) {
        mutateLayoutContaining(buttonId) { layout ->
            val button = layout.buttons.find { it.id == buttonId }
                ?: return@mutateLayoutContaining null
            resizeMutation(layout, button, button.col, button.row, newColSpan, newRowSpan)
        }
    }

    /** Resize from any corner: origin (col,row) may shift in addition to the spans
     *  changing. Used by the four-corner resize handles. */
    fun resizeButton(buttonId: String, newCol: Int, newRow: Int, newColSpan: Int, newRowSpan: Int) {
        mutateLayoutContaining(buttonId) { layout ->
            val button = layout.buttons.find { it.id == buttonId }
                ?: return@mutateLayoutContaining null
            resizeMutation(layout, button, newCol, newRow, newColSpan, newRowSpan)
        }
    }

    private fun resizeMutation(
        layout: GridLayout,
        button: GridButton,
        newCol: Int,
        newRow: Int,
        newColSpan: Int,
        newRowSpan: Int,
    ): GridLayout? {
        val col = newCol.coerceIn(0, layout.columns - 1)
        val row = newRow.coerceIn(0, layout.rows - 1)
        val colSpan = newColSpan.coerceIn(1, layout.columns - col)
        val rowSpan = newRowSpan.coerceIn(1, layout.rows - row)
        if (layout.wouldOverlap(button.id, col, row, colSpan, rowSpan)) {
            emitError("Cannot resize: overlaps another button")
            return null
        }
        return layout.copy(
            buttons = layout.buttons.map {
                if (it.id == button.id) {
                    it.copy(col = col, row = row, colSpan = colSpan, rowSpan = rowSpan)
                } else it
            }
        )
    }

    // ── Tab actions ───────────────────────────────────────────────────────────

    fun reorderTabs(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val current = _layouts.value
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val mutable = current.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        val reordered = mutable.toImmutableList()
        _layouts.value = reordered

        // Keep selection on the moved tab if it was selected; otherwise re-resolve by id.
        val previouslySelectedId = current.getOrNull(_selectedIndex.value)?.id
        if (previouslySelectedId != null) {
            val newIdx = reordered.indexOfFirst { it.id == previouslySelectedId }
            if (newIdx >= 0) _selectedIndex.value = newIdx
        }

        val profileId = activeProfile.value?.id ?: return
        val idToPosition = reordered.mapIndexed { idx, layout -> layout.id to idx }.toMap()
        viewModelScope.launch { layoutRepository.reorder(profileId, idToPosition) }
    }

    fun configureKeyboard(layoutId: Long, name: String, columns: Int, rows: Int, bgColor: Int?) {
        val profileId = activeProfile.value?.id ?: return
        val layout = _layouts.value.find { it.id == layoutId } ?: return
        val offending = layout.buttonsExceeding(columns, rows)
        if (offending.isNotEmpty()) {
            viewModelScope.launch {
                _tabUiEvents.emit(
                    TabUiEvent.ConfigureConflict(
                        layoutId = layoutId,
                        name = name,
                        cols = columns,
                        rows = rows,
                        bgColor = bgColor,
                        offendingLabels = offending.map { it.label.ifBlank { "(unnamed)" } }
                    )
                )
            }
            return
        }
        val updated = layout.copy(
            name = name,
            columns = columns,
            rows = rows,
            backgroundColorArgb = bgColor
        )
        persistLayoutFields(updated, profileId)
    }

    fun applyConfigureWithAutoResize(
        layoutId: Long,
        name: String,
        columns: Int,
        rows: Int,
        bgColor: Int?
    ) {
        val profileId = activeProfile.value?.id ?: return
        val layout = _layouts.value.find { it.id == layoutId } ?: return
        val resized = autoFitButtons(layout.buttons, columns, rows)
        val dropped = layout.buttons.size - resized.size
        val updated = layout.copy(
            name = name,
            columns = columns,
            rows = rows,
            backgroundColorArgb = bgColor,
            buttons = resized
        )
        persistLayoutFields(updated, profileId)
        if (dropped > 0) {
            emitToast("$dropped ${if (dropped == 1) "button" else "buttons"} removed")
        }
    }

    internal fun autoFitButtons(buttons: List<GridButton>, cols: Int, rows: Int): List<GridButton> {
        if (cols < 1 || rows < 1) return emptyList()
        val placed = mutableListOf<GridButton>()
        for (b in buttons) {
            val cs = b.colSpan.coerceIn(1, cols)
            val rs = b.rowSpan.coerceIn(1, rows)
            val newCol = b.col.coerceIn(0, cols - cs)
            val newRow = b.row.coerceIn(0, rows - rs)
            val collides = placed.any { p ->
                val bEndC = newCol + cs
                val bEndR = newRow + rs
                val pEndC = p.col + p.colSpan
                val pEndR = p.row + p.rowSpan
                !(bEndC <= p.col || pEndC <= newCol || bEndR <= p.row || pEndR <= newRow)
            }
            if (collides) continue
            placed.add(b.copy(col = newCol, row = newRow, colSpan = cs, rowSpan = rs))
        }
        return placed
    }

    fun resetKeyboard(layoutId: Long) {
        val profileId = activeProfile.value?.id ?: return
        val current = _layouts.value.find { it.id == layoutId } ?: return
        val previousName = current.name
        viewModelScope.launch {
            val row = layoutRepository.getById(layoutId) ?: return@launch
            val snapshot: LayoutSnapshot = row.parseOriginalSnapshot() ?: run {
                emitToast("No original config to revert to")
                return@launch
            }
            val reverted = snapshot.toGridLayout(layoutId)
            // Optimistic update.
            val current = _layouts.value
            val idx = current.indexOfFirst { it.id == layoutId }
            if (idx >= 0) {
                _layouts.value = current.toPersistentList().set(idx, reverted)
            }
            layoutRepository.saveLayout(
                reverted.toKeyLayout(
                    profileId = profileId,
                    position = row.position,
                    originalSnapshotJson = row.originalSnapshotJson
                )
            )
            emitToast("\"$previousName\" reset to \"${snapshot.name}\"")
        }
    }

    fun duplicateKeyboard(layoutId: Long) {
        val profileId = activeProfile.value?.id ?: return
        val sourceIdx = _layouts.value.indexOfFirst { it.id == layoutId }
        if (sourceIdx < 0) return
        val source = _layouts.value[sourceIdx]
        val newName = nextCopyName(source.name, _layouts.value.map { it.name }.toSet())
        // Fresh UUIDs so the copy's buttons don't collide with the source's — both the
        // live draft and the reset-to-original snapshot must reference the new ids.
        val draftLayout = source.copy(name = newName).withFreshButtonIds()
        val newSnapshotJson = draftLayout.toSnapshot().toJson()

        viewModelScope.launch {
            val current = layoutRepository.getLayoutsByProfileOnce(profileId)
            val newPosition = current.firstOrNull { it.id == layoutId }?.let { it.position + 1 }
                ?: current.size

            // Shift positions of siblings at or beyond newPosition up by one.
            val shifts = current
                .filter { it.position >= newPosition }
                .associate { it.id to it.position + 1 }
            if (shifts.isNotEmpty()) layoutRepository.reorder(profileId, shifts)

            val draft = draftLayout.toKeyLayout(
                profileId = profileId,
                position = newPosition,
                originalSnapshotJson = newSnapshotJson
            ).copy(id = 0L)
            layoutRepository.saveLayout(draft)

            // Resolve the newly inserted row's id and select it.
            val refreshed = layoutRepository.getLayoutsByProfileOnce(profileId)
            val newIdx = refreshed.indexOfFirst { it.position == newPosition && it.name == newName }
            if (newIdx >= 0) _selectedIndex.value = newIdx
            emitToast("\"$newName\" copied")
        }
    }

    internal fun nextCopyName(base: String, existing: Set<String>): String {
        val first = "$base Copy"
        if (first !in existing) return first
        var i = 2
        while ("$base Copy $i" in existing) i++
        return "$base Copy $i"
    }

    fun removeKeyboard(layoutId: Long) {
        val profile = activeProfile.value ?: return
        val current = _layouts.value
        val idx = current.indexOfFirst { it.id == layoutId }
        if (idx < 0) return
        val name = current[idx].name

        // Optimistic UI.
        val newList = current.toPersistentList().removeAt(idx)
        _layouts.value = newList
        if (_selectedIndex.value >= newList.size) {
            _selectedIndex.value = (newList.size - 1).coerceAtLeast(0)
        }

        viewModelScope.launch {
            layoutRepository.deleteById(layoutId)
            val refreshed = layoutRepository.getLayoutsByProfileOnce(profile.id)
            val compacted = refreshed
                .mapIndexed { i, row -> row.id to i }
                .filter { (id, pos) -> refreshed.first { it.id == id }.position != pos }
                .toMap()
            if (compacted.isNotEmpty()) layoutRepository.reorder(profile.id, compacted)
            emitToast("\"$name\" removed from \"${profile.name}\" profile")
        }
    }

    fun saveAsNewTemplate(layoutId: Long, templateName: String) {
        val layout = _layouts.value.find { it.id == layoutId } ?: return
        val keyboardName = layout.name
        viewModelScope.launch {
            val existing = keyboardTemplateRepository.findByName(templateName)
            if (existing != null) {
                _tabUiEvents.emit(
                    TabUiEvent.TemplateNameConflict(
                        layoutId = layoutId,
                        templateName = templateName,
                        existing = existing
                    )
                )
                return@launch
            }
            keyboardTemplateRepository.insertNew(layout, templateName)
            emitToast("\"$keyboardName\" keyboard template saved")
        }
    }

    fun updateExistingTemplate(layoutId: Long, ref: TemplateRef.User) {
        val layout = _layouts.value.find { it.id == layoutId } ?: return
        val keyboardName = layout.name
        viewModelScope.launch {
            keyboardTemplateRepository.updateExisting(ref.id, layout)
            emitToast("\"$keyboardName\" keyboard template updated")
        }
    }

    fun addBlankKeyboard() {
        val profileId = activeProfile.value?.id ?: return
        val name = nextNumberedName("New Keyboard", _layouts.value.map { it.name }.toSet())
        val draft = GridLayout(name = name, columns = 6, rows = 4, buttons = emptyList())
        appendNewLayout(draft, profileId)
    }

    fun addKeyboardFromTemplate(template: TemplateRef) {
        val profileId = activeProfile.value?.id ?: return
        val existing = _layouts.value.map { it.name }.toSet()
        val name = if (template.name in existing)
            nextNumberedName(template.name, existing)
        else template.name
        // Built-in templates share GridButton instances across every instantiation, and
        // user-template JSON carries the ids the template was saved with — either way,
        // we need fresh UUIDs so this keyboard's buttons are distinguishable from any
        // other keyboard derived from the same template.
        val draft = GridLayout(
            name = name,
            columns = template.columns,
            rows = template.rows,
            buttons = template.buttons,
            backgroundColorArgb = template.backgroundColorArgb
        ).withFreshButtonIds()
        appendNewLayout(draft, profileId)
    }

    fun addKeyboardFromProfile(sourceLayoutId: Long) {
        val profileId = activeProfile.value?.id ?: return
        viewModelScope.launch {
            val sourceRow = layoutRepository.getById(sourceLayoutId) ?: return@launch
            val sourceGrid = sourceRow.toGridLayout()
            val existing = _layouts.value.map { it.name }.toSet()
            val name = if (sourceGrid.name in existing)
                nextNumberedName(sourceGrid.name, existing)
            else sourceGrid.name
            // Fresh UUIDs so this keyboard is editable independently of the source
            // (whose buttons may also exist in the active profile via a prior copy).
            val draft = sourceGrid.copy(name = name, id = 0L).withFreshButtonIds()
            appendNewLayoutSuspending(draft, profileId)
        }
    }

    suspend fun layoutsForProfile(profileId: Long): List<GridLayout> =
        layoutRepository.getLayoutsByProfileOnce(profileId).map { it.toGridLayout() }

    internal fun nextNumberedName(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        var i = 2
        while ("$base $i" in existing) i++
        return "$base $i"
    }

    private fun appendNewLayout(draft: GridLayout, profileId: Long) {
        viewModelScope.launch { appendNewLayoutSuspending(draft, profileId) }
    }

    private suspend fun appendNewLayoutSuspending(draft: GridLayout, profileId: Long) {
        val current = layoutRepository.getLayoutsByProfileOnce(profileId)
        val newPosition = current.size
        val snapshotJson = draft.toSnapshot().toJson()
        val newRow = draft.copy(id = 0L).toKeyLayout(profileId, newPosition, snapshotJson)
        layoutRepository.saveLayout(newRow)
        val refreshed = layoutRepository.getLayoutsByProfileOnce(profileId)
        val newIdx = refreshed.indexOfFirst { it.position == newPosition && it.name == draft.name }
        if (newIdx >= 0) _selectedIndex.value = newIdx
        // Committing to add a new keyboard ends the edit context. Cancel/dismiss paths
        // never reach this funnel, so they correctly leave edit mode untouched.
        exitEditMode()
        emitToast("\"${draft.name}\" added")
    }

    fun emitToast(message: String) {
        _toastMessage.tryEmit(message)
    }

    private fun persistLayoutFields(updated: GridLayout, profileId: Long) {
        // Optimistic in-memory update.
        val current = _layouts.value
        val idx = current.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            _layouts.value = current.toPersistentList().set(idx, updated)
        }
        viewModelScope.launch {
            val existing = layoutRepository.getById(updated.id)
            layoutRepository.saveLayout(
                updated.toKeyLayout(
                    profileId = profileId,
                    position = existing?.position ?: idx.coerceAtLeast(0),
                    originalSnapshotJson = existing?.originalSnapshotJson
                )
            )
        }
    }

    // ── Error display ─────────────────────────────────────────────────────────

    private fun emitError(message: String) {
        _toastMessage.tryEmit(message)
    }
}
