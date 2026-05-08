package com.mapo

import android.os.Bundle
import android.view.WindowManager
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
        // Initial window state: not focusable (so unmapped gamepad inputs go to the game on
        // the primary screen) and gesture-suppressed across the whole window. Both are
        // toggled per-destination by ApplyMainScreenWindowBehavior in MainScreen — on the
        // keyboard view they stay set, on secondary screens they're cleared so back works.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        // No enableEdgeToEdge() on purpose: we want the OS to size the window below the
        // status bar where one exists (phone, Thor primary screen) and to leave the window
        // alone where one doesn't (Thor bottom bezel screen). enableEdgeToEdge + reactive
        // statusBarsPadding had an intermittent first-frame stale-inset bug that shifted
        // content down on the bezel screen. Letting decorFitsSystemWindows stay at its
        // default (true) sidesteps the inset race entirely — no per-screen padding needed.

        // TODO: Secondary display detection for AYN Thor secondary screen.
        // Use DisplayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        // to enumerate presentation displays and launch a PresentationWindow on the
        // secondary screen when one is detected.
        // val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        // val presentationDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        // presentationDisplays.firstOrNull()?.let { display -> /* launch on secondary */ }

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
