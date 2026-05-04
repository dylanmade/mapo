package com.mapo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapo.data.defaults.DefaultLayouts
import com.mapo.data.model.AppProfileBinding
import com.mapo.data.model.DeviceButton
import com.mapo.data.model.GridButton
import com.mapo.data.model.GridLayout
import com.mapo.data.model.LayoutSnapshot
import com.mapo.data.model.Profile
import com.mapo.data.model.RemapTarget
import com.mapo.data.model.TemplateRef
import com.mapo.data.model.TrackpadGesture
import com.mapo.data.model.buttonsExceeding
import com.mapo.data.model.gestureTarget
import com.mapo.data.model.findFirstEmptyCell
import com.mapo.data.model.parseOriginalSnapshot
import com.mapo.data.model.toGridLayout
import com.mapo.data.model.toJson
import com.mapo.data.model.toKeyLayout
import com.mapo.data.model.toSnapshot
import com.mapo.data.model.wouldOverlap
import com.mapo.data.repository.AppProfileBindingRepository
import com.mapo.data.repository.GamepadMappingRepository
import com.mapo.data.repository.KeyboardTemplateRepository
import com.mapo.data.repository.LayoutRepository
import com.mapo.data.repository.ProfileRepository
import com.mapo.data.settings.AutoSwitchSettings
import com.mapo.service.InputAccessibilityService
import com.mapo.service.autoswitch.ProfileAutoSwitcher
import com.mapo.service.foreground.ForegroundAppFilter
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val gampadMappingRepository: GamepadMappingRepository,
    private val appProfileBindingRepository: AppProfileBindingRepository,
    private val autoSwitchSettings: AutoSwitchSettings,
    private val autoSwitcher: ProfileAutoSwitcher,
    private val foregroundAppFilter: ForegroundAppFilter,
    private val keyboardTemplateRepository: KeyboardTemplateRepository
) : ViewModel() {

    // Empty until the active profile's layouts emit from the DB. Avoids duplicate LazyRow keys
    // (every DefaultLayouts entry has id=0 since they're code-only).
    private val _layouts = MutableStateFlow<List<GridLayout>>(emptyList())
    val layouts: StateFlow<List<GridLayout>> = _layouts.asStateFlow()

    private val _originalNames = MutableStateFlow<Map<Long, String>>(emptyMap())
    val originalNames: StateFlow<Map<Long, String>> = _originalNames.asStateFlow()

    private val _selectedIndex = MutableStateFlow(0)
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

    private val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode.asStateFlow()

    private val _editingLayout = MutableStateFlow<GridLayout?>(null)
    val editingLayout: StateFlow<GridLayout?> = _editingLayout.asStateFlow()

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

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _remapEnabled = MutableStateFlow(false)
    val remapEnabled: StateFlow<Boolean> = _remapEnabled.asStateFlow()

    private val _showRemapControls = MutableStateFlow(false)
    val showRemapControls: StateFlow<Boolean> = _showRemapControls.asStateFlow()

    val autoSwitchEnabled: StateFlow<Boolean> = autoSwitchSettings.autoSwitchEnabled

    val ignoredPackages: StateFlow<Set<String>> = autoSwitchSettings.ignoredPackages

    val appProfileBindings: StateFlow<List<AppProfileBinding>> =
        appProfileBindingRepository.getAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val autoSwitchEvents: SharedFlow<ProfileAutoSwitcher.UiEvent> = autoSwitcher.events

    val activeProfileMappings: StateFlow<Map<String, RemapTarget>> =
        activeProfile.filterNotNull()
            .flatMapLatest { gampadMappingRepository.getMappingsForProfile(it.id) }
            .map { list -> list.associate { it.gamepadButton to RemapTarget.decode(it.targetEncoded) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val templates: StateFlow<List<TemplateRef>> = keyboardTemplateRepository.allTemplates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), keyboardTemplateRepository.builtIns)

    val displayLayout: StateFlow<GridLayout> = combine(
        _selectedIndex, _isEditMode, _editingLayout, _layouts
    ) { index, editMode, editLayout, layouts ->
        if (editMode && editLayout != null) editLayout
        else layouts.getOrNull(index) ?: layouts.firstOrNull() ?: DefaultLayouts.all[0]
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefaultLayouts.all[0])

    init {
        viewModelScope.launch {
            profileRepository.getAllProfiles().collect { _profiles.value = it }
        }
        viewModelScope.launch {
            activeProfile.filterNotNull().flatMapLatest { profile ->
                flow {
                    layoutRepository.seedDefaultsIfEmpty(profile.id)
                    emitAll(layoutRepository.getLayoutsByProfile(profile.id))
                }
            }.collect { roomLayouts ->
                _layouts.value = roomLayouts.map { it.toGridLayout() }
                _originalNames.value = roomLayouts.mapNotNull { row ->
                    row.parseOriginalSnapshot()?.let { snap -> row.id to snap.name }
                }.toMap()
                if (_selectedIndex.value >= roomLayouts.size) {
                    _selectedIndex.value = (roomLayouts.size - 1).coerceAtLeast(0)
                }
            }
        }
        viewModelScope.launch {
            activeProfileMappings.collect { rawMappings ->
                val mapped = rawMappings
                    .mapNotNull { (key, value) ->
                        runCatching { DeviceButton.valueOf(key) }.getOrNull()?.let { it to value }
                    }
                    .toMap()
                InputAccessibilityService.currentMappings = mapped
                android.util.Log.d("MainViewModel", "Pushed ${mapped.size} remap entries to service: $mapped")
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
        InputAccessibilityService.remapEnabled = enabled
    }

    fun openRemapControls() { _showRemapControls.value = true }
    fun closeRemapControls() { _showRemapControls.value = false }

    // ── Auto-switch ───────────────────────────────────────────────────────────

    fun setAutoSwitchEnabled(enabled: Boolean) {
        autoSwitchSettings.setAutoSwitchEnabled(enabled)
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

    fun appLabelFor(pkg: String): String = foregroundAppFilter.appLabel(pkg)

    fun saveRemapMappings(draft: Map<DeviceButton, RemapTarget>) {
        val profileId = activeProfile.value?.id ?: return
        viewModelScope.launch { gampadMappingRepository.saveMappings(profileId, draft) }
        _showRemapControls.value = false
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    fun selectLayout(index: Int) { _selectedIndex.value = index }

    // ── Normal mode ───────────────────────────────────────────────────────────

    fun onKeyPress(code: String) {
        val svc = InputAccessibilityService.instance
            ?: run { _toastMessage.tryEmit("Accessibility service not running"); return }
        when (code) {
            "MOUSE_LEFT", "MOUSE_MIDDLE", "MOUSE_RIGHT", "MOUSE_BACK", "MOUSE_FORWARD",
            "SCROLL_UP", "SCROLL_DOWN" -> svc.dispatchTargetAsClick(RemapTarget.Mouse(code))
            else -> svc.injectKey(code)
        }
    }

    fun onTrackpadGesture(button: GridButton, gesture: TrackpadGesture) {
        val target = button.gestureTarget(gesture)
        android.util.Log.d("MapoInput", "onTrackpadGesture button=${button.id} gesture=$gesture target=$target")
        val svc = InputAccessibilityService.instance
            ?: run { _toastMessage.tryEmit("Accessibility service not running"); return }
        svc.dispatchTargetAsClick(target)
    }

    fun onDragStart() {
        InputAccessibilityService.instance?.startMouseDrag()
            ?: _toastMessage.tryEmit("Accessibility service not running")
    }

    fun onMouseMove(dx: Float, dy: Float) {
        InputAccessibilityService.instance?.injectMouseMove(dx, dy)
    }

    fun onDragEnd() {
        InputAccessibilityService.instance?.endMouseDrag()
    }

    // ── Tab context menu ──────────────────────────────────────────────────────

    fun openTabMenu(layoutId: Long) {
        if (_isEditMode.value) return
        _tabContextMenuFor.value = layoutId
    }

    fun closeTabMenu() {
        _tabContextMenuFor.value = null
    }

    // ── Edit mode lifecycle ───────────────────────────────────────────────────

    fun enterEditMode(layoutId: Long? = null) {
        // Allow entering edit mode for an explicit tab id (when invoked from the long-press menu)
        // or default to the currently selected tab (when invoked from the legacy Edit button).
        val targetIdx = layoutId?.let { id ->
            _layouts.value.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        } ?: _selectedIndex.value
        val target = _layouts.value.getOrNull(targetIdx) ?: return
        _selectedIndex.value = targetIdx
        _editingLayout.value = target.copy()
        _selectedButtonId.value = null
        _isEditMode.value = true
        _tabContextMenuFor.value = null
    }

    fun cancelEdits() {
        _isEditMode.value = false
        _editingLayout.value = null
        _selectedButtonId.value = null
    }

    fun saveEdits() {
        val layout = _editingLayout.value ?: return
        val profileId = activeProfile.value?.id ?: return
        viewModelScope.launch {
            val existing = layoutRepository.getById(layout.id)
            layoutRepository.saveLayout(
                layout.toKeyLayout(
                    profileId = profileId,
                    position = existing?.position ?: 0,
                    originalSnapshotJson = existing?.originalSnapshotJson
                )
            )
        }
        // Optimistic UI: update the in-memory list by id (not name — Configure-rename breaks name match).
        val updated = _layouts.value.toMutableList()
        val existingIdx = updated.indexOfFirst { it.id == layout.id }
        if (existingIdx >= 0) updated[existingIdx] = layout else updated.add(layout)
        _layouts.value = updated
        _isEditMode.value = false
        _editingLayout.value = null
        _selectedButtonId.value = null
    }

    // ── Button selection ──────────────────────────────────────────────────────

    fun selectButton(id: String) {
        _selectedButtonId.value = if (_selectedButtonId.value == id) null else id
    }

    // ── Button CRUD ───────────────────────────────────────────────────────────

    fun addButton(
        label: String, code: String,
        topText: String = "", topAlign: String = "CENTER",
        bottomText: String = "", bottomAlign: String = "CENTER",
        type: String = "key",
        sensitivity: Float? = null,
        gestureMappings: Map<String, String>? = null
    ) {
        val layout = _editingLayout.value ?: return
        val cell = layout.findFirstEmptyCell()
        if (cell == null) {
            emitError("No empty space available in this layout")
            return
        }
        val button = GridButton(
            label = label, code = code,
            col = cell.first, row = cell.second,
            topText = topText.ifEmpty { null }, topAlign = topAlign,
            bottomText = bottomText.ifEmpty { null }, bottomAlign = bottomAlign,
            type = type,
            sensitivity = sensitivity,
            gestureMappings = gestureMappings
        )
        _editingLayout.value = layout.copy(buttons = layout.buttons + button)
        _selectedButtonId.value = button.id
    }

    fun updateSelectedButton(
        label: String, code: String,
        topText: String, topAlign: String,
        bottomText: String, bottomAlign: String,
        type: String = "key",
        sensitivity: Float? = null,
        gestureMappings: Map<String, String>? = null
    ) {
        val id = _selectedButtonId.value ?: return
        _editingLayout.value = _editingLayout.value?.let { layout ->
            layout.copy(buttons = layout.buttons.map { btn ->
                if (btn.id == id) btn.copy(
                    label = label, code = code,
                    topText = topText.ifEmpty { null }, topAlign = topAlign,
                    bottomText = bottomText.ifEmpty { null }, bottomAlign = bottomAlign,
                    type = type,
                    sensitivity = sensitivity,
                    gestureMappings = gestureMappings
                ) else btn
            })
        }
    }

    fun deleteSelectedButton() {
        val id = _selectedButtonId.value ?: return
        _editingLayout.value = _editingLayout.value?.let { layout ->
            layout.copy(buttons = layout.buttons.filter { it.id != id })
        }
        _selectedButtonId.value = null
    }

    // ── Drag to move ──────────────────────────────────────────────────────────

    fun moveButton(buttonId: String, newCol: Int, newRow: Int) {
        val layout = _editingLayout.value ?: return
        val button = layout.buttons.find { it.id == buttonId } ?: return
        val col = newCol.coerceIn(0, layout.columns - button.colSpan)
        val row = newRow.coerceIn(0, layout.rows - button.rowSpan)
        if (layout.wouldOverlap(buttonId, col, row, button.colSpan, button.rowSpan)) return
        _editingLayout.value = layout.copy(
            buttons = layout.buttons.map { if (it.id == buttonId) it.copy(col = col, row = row) else it }
        )
    }

    // ── Resize ────────────────────────────────────────────────────────────────

    fun resizeButton(buttonId: String, newColSpan: Int, newRowSpan: Int) {
        val layout = _editingLayout.value ?: return
        val button = layout.buttons.find { it.id == buttonId } ?: return
        val colSpan = newColSpan.coerceIn(1, layout.columns - button.col)
        val rowSpan = newRowSpan.coerceIn(1, layout.rows - button.row)
        if (layout.wouldOverlap(buttonId, button.col, button.row, colSpan, rowSpan)) {
            emitError("Cannot resize: overlaps another button")
            return
        }
        _editingLayout.value = layout.copy(
            buttons = layout.buttons.map { if (it.id == buttonId) it.copy(colSpan = colSpan, rowSpan = rowSpan) else it }
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
        _layouts.value = mutable

        // Keep selection on the moved tab if it was selected; otherwise re-resolve by id.
        val previouslySelectedId = current.getOrNull(_selectedIndex.value)?.id
        if (previouslySelectedId != null) {
            val newIdx = mutable.indexOfFirst { it.id == previouslySelectedId }
            if (newIdx >= 0) _selectedIndex.value = newIdx
        }

        val profileId = activeProfile.value?.id ?: return
        val idToPosition = mutable.mapIndexed { idx, layout -> layout.id to idx }.toMap()
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

    private fun autoFitButtons(buttons: List<GridButton>, cols: Int, rows: Int): List<GridButton> {
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
            val list = _layouts.value.toMutableList()
            val idx = list.indexOfFirst { it.id == layoutId }
            if (idx >= 0) {
                list[idx] = reverted
                _layouts.value = list
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
        val newSnapshotJson = source.copy(name = newName).toSnapshot().toJson()

        viewModelScope.launch {
            val current = layoutRepository.getLayoutsByProfileOnce(profileId)
            val newPosition = current.firstOrNull { it.id == layoutId }?.let { it.position + 1 }
                ?: current.size

            // Shift positions of siblings at or beyond newPosition up by one.
            val shifts = current
                .filter { it.position >= newPosition }
                .associate { it.id to it.position + 1 }
            if (shifts.isNotEmpty()) layoutRepository.reorder(profileId, shifts)

            val draft = source.copy(name = newName).toKeyLayout(
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

    private fun nextCopyName(base: String, existing: Set<String>): String {
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
        val newList = current.toMutableList().apply { removeAt(idx) }
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

    fun emitToast(message: String) {
        _toastMessage.tryEmit(message)
    }

    private fun persistLayoutFields(updated: GridLayout, profileId: Long) {
        // Optimistic in-memory update.
        val list = _layouts.value.toMutableList()
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            list[idx] = updated
            _layouts.value = list
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
