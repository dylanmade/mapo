package com.mapo

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.themestudio.core.ThemeStudioProvider
import com.themestudio.persistence.SharedPrefsThemeOverridesStorage
import dagger.hilt.android.AndroidEntryPoint
import com.mapo.ui.screen.MainScreen
import com.mapo.ui.theme.MapoTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fully immersive, GameNative-style: the system bars are HIDDEN by default and Mapo
        // uses the entire screen (incl. the display cutout). A swipe from the edge reveals the
        // bars transiently, OVER the content, without shifting layout — so the Edit Overlay
        // coordinate space matches a real fullscreen game.
        //
        // Why immersive and not plain edge-to-edge: the window is translucent (so the home
        // drawer reveals the app behind), and a translucent activity is laid out within the
        // content frame — it's exempt from edge-to-edge enforcement and can't draw under the
        // bars no matter the flags. Hiding the bars sidesteps that: there are no bar insets,
        // so the content frame becomes the whole display. FLAG_LAYOUT_NO_LIMITS pins the frame
        // to the full display (incl. cutout); enableEdgeToEdge keeps the bars transparent for
        // the moments they transiently appear. See `hideSystemBars` + onWindowFocusChanged.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hideSystemBars()

        // The home IS a drawer, so the default "scale up from the launcher icon" open animation
        // looks wrong. Override it to slide the (mostly transparent) window in from the start
        // edge — visually, just the drawer panel sliding in from the side while the app behind
        // holds still — and to retract the same way on leave. API 34+ only; older devices keep
        // the platform default.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, R.anim.drawer_slide_in, R.anim.hold)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.hold, R.anim.drawer_slide_out)
        }

        setContent {
            val themeStorage = remember { SharedPrefsThemeOverridesStorage(applicationContext) }
            ThemeStudioProvider(storage = themeStorage) {
                MapoTheme {
                    MainScreen()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Immersive isn't sticky across focus changes (the transient swipe, returning from
        // another activity, dialogs), so re-hide whenever we regain focus.
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
    }
}
