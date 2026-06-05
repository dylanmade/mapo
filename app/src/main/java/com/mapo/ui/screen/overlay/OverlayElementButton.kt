package com.mapo.ui.screen.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mapo.data.model.OverlayElement
import com.mapo.data.model.displayLabel
import com.mapo.data.model.tapRemapTarget

/**
 * Visual for one free-positioned overlay button. Fills its window (each element renders
 * in a window sized exactly to its bounds), so sizing is governed by the window, not the
 * composable. MVP styling — the richer per-button appearance (shapes / bevels / shadows)
 * is cribbed from the legacy grid renderer in a later brick.
 */
@Composable
fun OverlayElementButton(
    element: OverlayElement,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // secondaryContainer — interactive overlay control sitting over arbitrary app content;
    // a filled tonal surface reads clearly against most game backgrounds. tonalElevation
    // only (M3 standards).
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().padding(4.dp),
        ) {
            Text(
                text = element.label.ifBlank { element.tapRemapTarget.displayLabel() },
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
