package com.pcpad.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pcpad.data.model.KeyLayout
import com.pcpad.data.repository.LayoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: LayoutRepository
) : ViewModel() {

    val layouts: StateFlow<List<KeyLayout>> = repository.getLayouts()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun onKeyPress(keyCode: String) {
        // TODO: Forward key press to InputAccessibilityService for injection
    }
}
