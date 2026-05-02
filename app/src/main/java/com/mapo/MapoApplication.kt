package com.mapo

import android.app.Application
import com.mapo.service.autoswitch.ProfileAutoSwitcher
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MapoApplication : Application() {

    @Inject lateinit var autoSwitcher: ProfileAutoSwitcher

    override fun onCreate() {
        super.onCreate()
        autoSwitcher.start()
    }
}
