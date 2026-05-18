package com.mapo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.themestudio.core.ThemeStudioProvider
import com.themestudio.persistence.SharedPrefsThemeOverridesStorage
import dagger.hilt.android.AndroidEntryPoint
import com.mapo.ui.screen.MainScreen
import com.mapo.ui.theme.MapoTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Window-flag toggling lives entirely in `ApplyMainScreenWindowBehavior` (see
        // MainScreen.kt). The single-screen refactor removed the unconditional
        // `FLAG_NOT_FOCUSABLE` bootstrap that used to live here: with the run-mode
        // keyboard now in an overlay rather than this activity's content, the only
        // remaining use of that flag is the Thor secondary-device path (activity-mode
        // keyboard on bottom screen while a game runs on top). That path is handled
        // per-destination by the Compose-side toggle.
        //
        // No enableEdgeToEdge() on purpose: we want the OS to size the window below the
        // status bar where one exists (phone, Thor primary screen) and to leave the window
        // alone where one doesn't (Thor bottom bezel screen). enableEdgeToEdge + reactive
        // statusBarsPadding had an intermittent first-frame stale-inset bug that shifted
        // content down on the bezel screen. Letting decorFitsSystemWindows stay at its
        // default (true) sidesteps the inset race entirely — no per-screen padding needed.

        setContent {
            val themeStorage = remember { SharedPrefsThemeOverridesStorage(applicationContext) }
            ThemeStudioProvider(storage = themeStorage) {
                MapoTheme {
                    MainScreen()
                }
            }
        }
    }
}
