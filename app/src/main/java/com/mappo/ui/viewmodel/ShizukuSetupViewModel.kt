package com.mappo.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.mappo.service.shizuku.ShizukuConnection
import com.mappo.service.shizuku.ShizukuFacade
import com.mappo.service.shizuku.ShizukuState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Brick A view model for [com.mappo.ui.screen.ShizukuSetupScreen]. Forwards state
 * from [ShizukuConnection] and exposes the action callbacks the screen wires to
 * its primary / outlined buttons. Brick G adds richer actions (e.g. open
 * troubleshooting docs).
 */
@HiltViewModel
class ShizukuSetupViewModel @Inject constructor(
    private val connection: ShizukuConnection,
    private val facade: ShizukuFacade,
) : ViewModel() {

    val state: StateFlow<ShizukuState> = connection.state

    fun installShizuku() {
        facade.openShizukuInstall()
    }

    fun openShizukuApp() {
        facade.openShizukuApp()
    }

    fun requestPermission() {
        connection.requestPermission()
    }
}
