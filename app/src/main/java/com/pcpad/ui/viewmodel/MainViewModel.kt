package com.pcpad.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.pcpad.data.defaults.DefaultLayouts
import com.pcpad.data.model.LayoutDef
import com.pcpad.data.repository.LayoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: LayoutRepository
) : ViewModel() {

    val layouts: List<LayoutDef> = DefaultLayouts.all

    private val _selectedIndex = MutableStateFlow(0)
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

    fun selectLayout(index: Int) {
        _selectedIndex.value = index
    }

    fun onKeyPress(code: String) {
        // TODO: Forward to InputAccessibilityService for injection
    }
}
