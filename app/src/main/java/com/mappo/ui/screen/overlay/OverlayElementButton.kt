package com.mappo.ui.screen.overlay

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mappo.data.model.OverlayElement
import com.mappo.data.model.displayLabel
import com.mappo.data.model.tapRemapTarget

/** Shape for an [OverlayElement] per its `shape` field. */
@Composable
fun overlayShape(element: OverlayElement): Shape = when (element.shape) {
    OverlayElement.SHAPE_CIRCLE -> CircleShape
    OverlayElement.SHAPE_RECTANGLE -> RectangleShape
    else -> MaterialTheme.shapes.medium
}

/** Fill color — the element's override, else the theme default. */
@Composable
fun overlayFillColor(element: OverlayElement): Color =
    element.fillColorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.secondaryContainer

/** Content (label) color — the element's override, else the theme default. */
@Composable
fun overlayContentColor(element: OverlayElement): Color =
    element.contentColorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.onSecondaryContainer

/** Centered label, with a sensible fallback to the tap command when unlabeled. */
@Composable
fun OverlayElementLabel(element: OverlayElement) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(4.dp)) {
        Text(
            text = element.label.ifBlank { element.tapRemapTarget.displayLabel() },
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Run-mode visual for one free-positioned overlay button. Fills its window (each element
 * renders in a window sized exactly to its bounds). Emits the element's per-gesture
 * commands; appearance (shape / opacity / colors) comes from the element's light-appearance
 * fields.
 */
@Composable
fun OverlayElementButton(
    element: OverlayElement,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // secondaryContainer (or the element's fill override) — interactive overlay control.
    Surface(
        shape = overlayShape(element),
        color = overlayFillColor(element),
        contentColor = overlayContentColor(element),
        tonalElevation = 3.dp,
        modifier = modifier
            .fillMaxSize()
            .alpha(element.opacity)
            .pointerInput(element.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap() },
                    onLongPress = { onHold() },
                )
            },
    ) {
        OverlayElementLabel(element)
    }
}
