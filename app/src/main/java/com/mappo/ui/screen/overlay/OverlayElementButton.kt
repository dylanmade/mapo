package com.mappo.ui.screen.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mappo.data.model.OverlayElement
import com.mappo.data.model.RemapTarget
import com.mappo.data.model.displayLabel
import com.mappo.data.model.overlay.ElementAppearance
import com.mappo.data.model.overlay.decodeElementAppearance
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
    // Unbound is not a real command for an overlay button — never advertise it (the
    // physical-remap "(Device default)" vocabulary doesn't apply to user-made buttons).
    val text = element.label.ifBlank {
        element.tapRemapTarget.takeUnless { it is RemapTarget.Unbound }?.displayLabel().orEmpty()
    }
    if (text.isEmpty()) return
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The button's visual, shared verbatim by run mode and the live editor's edit replica so
 * WYSIWYG can't drift. Elements with a layered [ElementAppearance] draw through
 * [drawElementAppearance]; pre-layer elements keep the light-appearance Surface path.
 *
 * @param selectionColor when non-null, the editor's selection outline is drawn along the
 *   shape's silhouette.
 */
@Composable
fun OverlayElementVisual(
    element: OverlayElement,
    modifier: Modifier = Modifier,
    selectionColor: Color? = null,
) {
    val appearance = remember(element.appearanceJson) { decodeElementAppearance(element.appearanceJson) }
    if (appearance != null) {
        // Layered appearance is user-authored paint, not an M3 surface — the label's
        // content color still routes through the element's text-color override.
        Box(
            modifier = modifier
                .fillMaxSize()
                .alpha(element.opacity)
                .drawBehind {
                    drawElementAppearance(appearance)
                    if (selectionColor != null) {
                        drawAppearanceOutline(appearance, selectionColor, 2.dp.toPx())
                    }
                },
        ) {
            CompositionLocalProvider(LocalContentColor provides overlayContentColor(element)) {
                OverlayElementLabel(element)
            }
        }
    } else {
        // secondaryContainer (or the element's fill override) — interactive overlay control.
        Surface(
            shape = overlayShape(element),
            color = overlayFillColor(element),
            contentColor = overlayContentColor(element),
            tonalElevation = 3.dp,
            border = selectionColor?.let { BorderStroke(2.dp, it) },
            modifier = modifier.fillMaxSize().alpha(element.opacity),
        ) {
            OverlayElementLabel(element)
        }
    }
}

/**
 * Run-mode button for one free-positioned overlay element. Fills its window (each element
 * renders in a window sized exactly to its bounds) and emits the element's per-gesture
 * commands.
 */
@Composable
fun OverlayElementButton(
    element: OverlayElement,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(element.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap() },
                    onLongPress = { onHold() },
                )
            },
    ) {
        OverlayElementVisual(element)
    }
}
