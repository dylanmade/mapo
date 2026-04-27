package com.pcpad.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pcpad.data.defaults.DefaultLayouts
import com.pcpad.data.model.GridButton
import com.pcpad.data.model.GridLayout
import com.pcpad.data.model.findFirstEmptyCell
import com.pcpad.data.model.toGridLayout
import com.pcpad.data.model.toKeyLayout
import com.pcpad.data.model.wouldOverlap
import com.pcpad.data.repository.LayoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
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

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val displayLayout: StateFlow<GridLayout> = combine(
        _selectedIndex, _isEditMode, _editingLayout, _layouts
    ) { index, editMode, editLayout, layouts ->
        if (editMode && editLayout != null) editLayout else layouts[index]
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefaultLayouts.all[0])

    init {
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

    fun selectLayout(index: Int) { _selectedIndex.value = index }

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
        viewModelScope.launch { repository.saveLayout(layout.toKeyLayout()) }
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
        _errorMessage.value = message
        viewModelScope.launch {
            delay(3_000)
            _errorMessage.value = null
        }
    }
}
