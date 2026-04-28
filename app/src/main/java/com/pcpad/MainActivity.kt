package com.pcpad

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import com.pcpad.ui.screen.MainScreen
import com.pcpad.ui.theme.PcPadTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent pcpad from stealing hardware-button focus from the game on the primary screen.
        // Touch events still work normally; only hardware keyboard/gamepad focus is withheld.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
                val dv = window.decorView
                dv.systemGestureExclusionRects = listOf(Rect(0, 0, dv.width, dv.height))
            }
        }

        // TODO: Secondary display detection for AYN Thor secondary screen.
        // Use DisplayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        // to enumerate presentation displays and launch a PresentationWindow on the
        // secondary screen when one is detected.
        // val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        // val presentationDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        // presentationDisplays.firstOrNull()?.let { display -> /* launch on secondary */ }

        setContent {
            PcPadTheme {
                MainScreen()
            }
        }
    }
}
