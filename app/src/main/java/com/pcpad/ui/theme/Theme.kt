package com.pcpad.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary         = TabAccent,    // tab indicator + selected tab text
    background      = DarkBackground,
    surface         = KeyFill,      // button container fill
    onSurface       = KeyLabel,     // button text
    outline         = KeyOutline,   // button border
    onBackground    = KeyLabel,
    onPrimary       = OnAccent,
)

@Composable
fun PcPadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
