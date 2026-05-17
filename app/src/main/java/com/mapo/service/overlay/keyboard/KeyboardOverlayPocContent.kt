package com.mapo.service.overlay.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Brick 1 placeholder content for the keyboard overlay POC — a 3×4 grid of
 * tappable boxes whose only behavior is calling [onTap] with the slot index
 * (which the caller logs). Verifies the R1 risk: a large `FLAG_NOT_TOUCH_MODAL`
 * overlay receives in-bounds taps via Compose while letting out-of-bounds taps
 * pass through to the foreground app underneath.
 *
 * Replaced in Brick 4 by `KeyboardHost(mode = Overlay, ...)`.
 */
@Composable
fun KeyboardOverlayPocContent(onTap: (Int) -> Unit) {
    // surfaceContainerHigh — overlay surface (matches OverlayToast / OverlayCreatePrompt precedent)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            for (row in 0 until 3) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    for (col in 0 until 4) {
                        val slot = row * 4 + col
                        PocKey(
                            label = "K$slot",
                            onTap = { onTap(slot) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PocKey(label: String, onTap: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
