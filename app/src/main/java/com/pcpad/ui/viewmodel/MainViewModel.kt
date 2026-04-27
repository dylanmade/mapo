package com.pcpad.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pcpad.data.defaults.DefaultLayouts
import com.pcpad.data.model.GridButton
import com.pcpad.data.model.GridLayout
import com.pcpad.data.model.toGridLayout
import com.pcpad.data.model.toKeyLayout
import com.pcpad.data.repository.LayoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: LayoutRepository
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

    // What the grid should display: editing copy in edit mode, live layout otherwise
    val displayLayout: StateFlow<GridLayout> = combine(
        _selectedIndex, _isEditMode, _editingLayout, _layouts
    ) { index, editMode, editLayout, layouts ->
        if (editMode && editLayout != null) editLayout else layouts[index]
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefaultLayouts.all[0])

    init {
        // Merge Room-persisted layouts with defaults on startup
        viewModelScope.launch {
            repository.getLayouts().collect { roomLayouts ->
                val persisted = roomLayouts.map { it.toGridLayout() }
                val overrideMap = persisted.associateBy { it.name }
                val defaultNames = DefaultLayouts.all.map { it.name }.toSet()
                val customNew = persisted.filter { it.name !in defaultNames }
                _layouts.value = DefaultLayouts.all.map { overrideMap[it.name] ?: it } + customNew
            }
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    fun selectLayout(index: Int) {
        _selectedIndex.value = index
    }

    // ── Normal mode ───────────────────────────────────────────────────────────

    fun onKeyPress(code: String) {
        // TODO: forward to InputAccessibilityService
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
        viewModelScope.launch {
            repository.saveLayout(layout.toKeyLayout())
        }
        // Optimistically update in-memory list
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

    fun clearSelection() {
        _selectedButtonId.value = null
    }

    // ── Button CRUD ───────────────────────────────────────────────────────────

    fun addButton(button: GridButton) {
        _editingLayout.value = _editingLayout.value?.let {
            it.copy(buttons = it.buttons + button)
        }
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
        _editingLayout.value = _editingLayout.value?.let { layout ->
            layout.copy(buttons = layout.buttons.map { btn ->
                if (btn.id == buttonId)
                    btn.copy(
                        col = newCol.coerceIn(0, layout.columns - btn.colSpan),
                        row = newRow.coerceIn(0, layout.rows - btn.rowSpan)
                    )
                else btn
            })
        }
    }
}
