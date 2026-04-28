package com.mapo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapo.data.defaults.DefaultLayouts
import com.mapo.data.model.DeviceButton
import com.mapo.data.model.GridButton
import com.mapo.data.model.GridLayout
import com.mapo.data.model.Profile
import com.mapo.data.model.RemapTarget
import com.mapo.data.model.findFirstEmptyCell
import com.mapo.data.model.toGridLayout
import com.mapo.data.model.toKeyLayout
import com.mapo.data.model.wouldOverlap
import com.mapo.data.repository.GamepadMappingRepository
import com.mapo.service.InputAccessibilityService
import com.mapo.data.repository.LayoutRepository
import com.mapo.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val layoutRepository: LayoutRepository,
    private val profileRepository: ProfileRepository,
    private val gampadMappingRepository: GamepadMappingRepository
) : ViewModel() {

    private val _layouts = MutableStateFlow(DefaultLayouts.all)
    val layouts: StateFlow<List<GridLayout>> = _layouts.asStateFlow()

    private val _selectedIndex = MutableStateFlow(0)
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

    private val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode.asStateFlow()

    private val _editingLayout = MutableStateFlow<GridLayout?>(null)
    val editingLayout: StateFlow<GridLayout?> = _editingLayout.asStateFlow()

    private val _selectedButtonId = MutableStateFlow<String?>(null)
    val selectedButtonId: StateFlow<String?> = _selectedButtonId.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private val _activeProfile = MutableStateFlow<Profile?>(null)
    val activeProfile: StateFlow<Profile?> = _activeProfile.asStateFlow()

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _remapEnabled = MutableStateFlow(false)
    val remapEnabled: StateFlow<Boolean> = _remapEnabled.asStateFlow()

    private val _showRemapControls = MutableStateFlow(false)
    val showRemapControls: StateFlow<Boolean> = _showRemapControls.asStateFlow()

    val activeProfileMappings: StateFlow<Map<String, RemapTarget>> =
        _activeProfile.filterNotNull()
            .flatMapLatest { gampadMappingRepository.getMappingsForProfile(it.id) }
            .map { list -> list.associate { it.gamepadButton to RemapTarget.decode(it.targetEncoded) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val displayLayout: StateFlow<GridLayout> = combine(
        _selectedIndex, _isEditMode, _editingLayout, _layouts
    ) { index, editMode, editLayout, layouts ->
        if (editMode && editLayout != null) editLayout else layouts[index]
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefaultLayouts.all[0])

    init {
        viewModelScope.launch {
            profileRepository.getAllProfiles().collect { _profiles.value = it }
        }
        viewModelScope.launch {
            profileRepository.getDefaultProfile().collect { profile ->
                if (_activeProfile.value == null && profile != null) {
                    _activeProfile.value = profile
                }
            }
        }
        viewModelScope.launch {
            _activeProfile.filterNotNull().flatMapLatest { profile ->
                layoutRepository.getLayoutsByProfile(profile.id)
            }.collect { roomLayouts ->
                val persisted = roomLayouts.map { it.toGridLayout() }
                val overrideMap = persisted.associateBy { it.name }
                val defaultNames = DefaultLayouts.all.map { it.name }.toSet()
                val customNew = persisted.filter { it.name !in defaultNames }
                _layouts.value = DefaultLayouts.all.map { overrideMap[it.name] ?: it } + customNew
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
        _activeProfile.value = profile
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
            if (_activeProfile.value?.id == profile.id && defaultProfile != null) {
                _activeProfile.value = defaultProfile
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
    fun saveRemapMappings(draft: Map<DeviceButton, RemapTarget>) {
        val profileId = _activeProfile.value?.id ?: return
        viewModelScope.launch { gampadMappingRepository.saveMappings(profileId, draft) }
        _showRemapControls.value = false
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    fun selectLayout(index: Int) { _selectedIndex.value = index }

    // ── Normal mode ───────────────────────────────────────────────────────────

    fun onKeyPress(code: String) {
        InputAccessibilityService.instance?.injectKey(code)
            ?: _toastMessage.tryEmit("Accessibility service not running")
    }

    // ── Edit mode lifecycle ───────────────────────────────────────────────────

    fun enterEditMode() {
        _editingLayout.value = _layouts.value[_selectedIndex.value].copy()
        _selectedButtonId.value = null
        _isEditMode.value = true
    }

    fun cancelEdits() {
        _isEditMode.value = false
        _editingLayout.value = null
        _selectedButtonId.value = null
    }

    fun saveEdits() {
        val layout = _editingLayout.value ?: return
        val profileId = _activeProfile.value?.id ?: return
        viewModelScope.launch { layoutRepository.saveLayout(layout.toKeyLayout(profileId)) }
        val updated = _layouts.value.toMutableList()
        val existingIdx = updated.indexOfFirst { it.name == layout.name }
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

    fun addButton(label: String, code: String) {
        val layout = _editingLayout.value ?: return
        val cell = layout.findFirstEmptyCell()
        if (cell == null) {
            emitError("No empty space available in this layout")
            return
        }
        val button = GridButton(label = label, code = code, col = cell.first, row = cell.second)
        _editingLayout.value = layout.copy(buttons = layout.buttons + button)
        _selectedButtonId.value = button.id
    }

    fun updateSelectedButton(label: String, code: String) {
        val id = _selectedButtonId.value ?: return
        _editingLayout.value = _editingLayout.value?.let { layout ->
            layout.copy(buttons = layout.buttons.map { btn ->
                if (btn.id == id) btn.copy(label = label, code = code) else btn
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

    // ── Error display ─────────────────────────────────────────────────────────

    private fun emitError(message: String) {
        _toastMessage.tryEmit(message)
    }
}
