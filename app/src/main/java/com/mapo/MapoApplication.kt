package com.mapo

import android.app.Application
import com.mapo.service.autoswitch.ProfileAutoSwitcher
import com.mapo.service.overlay.OverlayCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MapoApplication : Application() {

    @Inject lateinit var autoSwitcher: ProfileAutoSwitcher
    @Inject lateinit var overlayCoordinator: OverlayCoordinator

    override fun onCreate() {
        super.onCreate()
        autoSwitcher.start()
        overlayCoordinator.start()
    }
}
