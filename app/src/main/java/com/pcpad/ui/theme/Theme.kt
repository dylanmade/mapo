package com.pcpad.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentBlue,
    secondary = AccentBlueVariant,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = OnAccent,
    onBackground = KeyText,
    onSurface = KeyText
)

@Composable
fun PcPadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
