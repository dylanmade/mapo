package com.mappo.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A dashed "phantom" outline that holds the home position of an element that has temporarily
 * moved elsewhere — drag previews, morph-in-place editors, drop targets. Zero content: callers
 * size it to the departed element's measured bounds so the surrounding layout doesn't shift,
 * and animate the element back to it on release/close.
 *
 * Generalized from the remap screen's group-box placeholder (retired 2026-07-12: the expanded
 * editor grew to cover the whole band, so the dashes were never visible there). Currently
 * unused — kept as a shared component for the next surface that needs the pattern.
 */
fun Modifier.dashedPlaceholderOutline(
    color: Color,
    cornerRadius: Dp,
    strokeWidth: Dp = 1.dp,
    dashLength: Dp = 3.dp,
    gapLength: Dp = 2.5.dp,
): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(cornerRadius.toPx()),
        style = Stroke(
            width = strokeWidth.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(dashLength.toPx(), gapLength.toPx()),
            ),
        ),
    )
}

/** Convenience wrapper: a fixed-size empty box wearing [dashedPlaceholderOutline]. */
@Composable
fun DashedPlaceholderBox(
    width: Dp,
    height: Dp,
    color: Color,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 1.dp,
) {
    Box(
        modifier
            .size(width = width, height = height)
            .dashedPlaceholderOutline(color, cornerRadius, strokeWidth),
    )
}
