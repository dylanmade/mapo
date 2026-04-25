package com.pcpad

import android.hardware.display.DisplayManager
import android.os.Bundle
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
        enableEdgeToEdge()

        // TODO: Secondary display detection for AYN Thor secondary screen.
        // Use DisplayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        // to enumerate presentation displays and launch a PresentationWindow on the
        // secondary screen when one is detected.
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val presentationDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        // presentationDisplays.firstOrNull()?.let { display -> /* launch on secondary */ }

        setContent {
            PcPadTheme {
                MainScreen()
            }
        }
    }
}
