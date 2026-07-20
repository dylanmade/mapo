package com.mappo.ui.screen.overlay

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.mappo.data.model.overlay.AppearanceLayer
import com.mappo.data.model.overlay.CornerRadii
import com.mappo.data.model.overlay.ElementAppearance
import com.mappo.data.model.overlay.LayerKind
import com.mappo.data.model.overlay.LayerPaint
import com.mappo.data.model.overlay.StrokeAlign
import com.mappo.data.model.overlay.StrokeGradientMode
import com.mappo.data.model.overlay.StrokeStyle
import com.mappo.data.model.overlay.resolvedStops
import com.mappo.data.model.overlay.sampleResolvedStops
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws an [ElementAppearance] layer stack over this scope's full size. Pure draw-phase
 * work — every layer is a (per-corner) rounded-rect fill or stroke (across-stroke gradients
 * band into concentric sub-strokes), so cost stays flat while games run underneath. No
 * blur, no RenderEffect, no AGSL.
 *
 * OUTSIDE-aligned strokes and offsets draw past the layout bounds; inside the overlay they
 * clip at the element's own window edge (each element IS its window). That mirrors the
 * physical constraint the editor should eventually surface, not a rendering bug.
 */
fun DrawScope.drawElementAppearance(appearance: ElementAppearance) {
    val radii = radiiPx(appearance.corners)
    appearance.layers.forEach { layer ->
        val dx = layer.offsetXDp.dp.toPx()
        val dy = layer.offsetYDp.dp.toPx()
        if (dx == 0f && dy == 0f) drawLayer(layer, radii)
        else translate(dx, dy) { drawLayer(layer, radii) }
    }
}

/**
 * The shape's silhouette as a stroke — the editor's selection outline over a layered
 * element (the legacy path uses Surface's border instead).
 */
fun DrawScope.drawAppearanceOutline(appearance: ElementAppearance, color: Color, strokeWidth: Float) {
    val radii = radiiPx(appearance.corners)
    // Half-width inset keeps the outline fully inside the window (nothing to clip it).
    drawPath(
        shapePath(strokeWidth / 2f, radii),
        color = color,
        style = Stroke(width = strokeWidth),
    )
}

/** Per-corner radii in px: fraction of half the short side, like a single-radius shape. */
private fun DrawScope.radiiPx(corners: CornerRadii): CornerRadiiPx {
    val half = min(size.width, size.height) / 2f
    return CornerRadiiPx(
        topLeft = corners.topLeft.coerceIn(0f, 1f) * half,
        topRight = corners.topRight.coerceIn(0f, 1f) * half,
        bottomRight = corners.bottomRight.coerceIn(0f, 1f) * half,
        bottomLeft = corners.bottomLeft.coerceIn(0f, 1f) * half,
    )
}

private data class CornerRadiiPx(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float,
)

/**
 * The shape's outline path inset by [inset] on all sides (negative = outset), with each
 * corner kept concentric. A square corner stays square at every alignment (an outside
 * stroke on a right angle keeps the right angle, as in Illustrator).
 */
private fun DrawScope.shapePath(inset: Float, radii: CornerRadiiPx): Path {
    fun at(r: Float): CornerRadius =
        if (r <= 0f) CornerRadius.Zero else CornerRadius((r - inset).coerceAtLeast(0f))
    return Path().apply {
        addRoundRect(
            RoundRect(
                left = inset,
                top = inset,
                right = size.width - inset,
                bottom = size.height - inset,
                topLeftCornerRadius = at(radii.topLeft),
                topRightCornerRadius = at(radii.topRight),
                bottomRightCornerRadius = at(radii.bottomRight),
                bottomLeftCornerRadius = at(radii.bottomLeft),
            ),
        )
    }
}

private fun DrawScope.drawLayer(layer: AppearanceLayer, radii: CornerRadiiPx) = when (layer.kind) {
    LayerKind.FILL -> drawFill(layer, radii)
    LayerKind.STROKE -> drawStroke(layer, radii)
}

private fun DrawScope.drawFill(layer: AppearanceLayer, radii: CornerRadiiPx) {
    val path = shapePath(0f, radii)
    when (val paint = layer.paint) {
        is LayerPaint.Solid -> drawPath(path, color = Color(paint.argb), alpha = layer.opacity, style = Fill)
        is LayerPaint.Gradient -> drawPath(
            path,
            brush = linearBrushFor(paint, size),
            alpha = layer.opacity,
            style = Fill,
        )
    }
}

private fun DrawScope.drawStroke(layer: AppearanceLayer, radii: CornerRadiiPx) {
    val width = layer.strokeWidthDp.dp.toPx().coerceAtLeast(0.5f)
    val inset = when (layer.strokeAlign) {
        StrokeAlign.INSIDE -> width / 2f
        StrokeAlign.CENTER -> 0f
        StrokeAlign.OUTSIDE -> -width / 2f
    }
    val paint = layer.paint
    if (paint is LayerPaint.Gradient && layer.strokeGradientMode == StrokeGradientMode.ACROSS) {
        drawAcrossStroke(layer, paint, radii, width, inset)
        return
    }
    if (strokeCollapsed(inset)) return
    val path = shapePath(inset, radii)
    val stroke = Stroke(width = width, cap = capFor(layer.strokeStyle), pathEffect = dashFor(layer.strokeStyle, width))
    when (paint) {
        is LayerPaint.Solid -> drawPath(path, color = Color(paint.argb), alpha = layer.opacity, style = stroke)
        is LayerPaint.Gradient -> drawPath(
            path,
            brush = linearBrushFor(paint, size),
            alpha = layer.opacity,
            style = stroke,
        )
    }
}

/**
 * The across-the-width gradient: [bands] concentric sub-strokes, each a solid sample of the
 * ramp (t = 0 at the stroke's OUTER edge, 1 at its inner edge). Band width targets ~1dp with
 * a hair of overlap so adjacent bands don't seam.
 */
private fun DrawScope.drawAcrossStroke(
    layer: AppearanceLayer,
    paint: LayerPaint.Gradient,
    radii: CornerRadiiPx,
    width: Float,
    inset: Float,
) {
    val stops = paint.resolvedStops()
    val bands = ceil(width / 1.dp.toPx()).toInt().coerceIn(2, MAX_ACROSS_BANDS)
    val bandWidth = width / bands
    val overlap = 0.6f
    for (k in 0 until bands) {
        val bandInset = inset - width / 2f + (k + 0.5f) * bandWidth
        if (strokeCollapsed(bandInset)) continue
        drawPath(
            shapePath(bandInset, radii),
            color = sampleResolvedStops(stops, (k + 0.5f) / bands),
            alpha = layer.opacity,
            style = Stroke(
                width = bandWidth + overlap,
                cap = capFor(layer.strokeStyle),
                pathEffect = dashFor(layer.strokeStyle, width),
            ),
        )
    }
}

/** True once an inset has consumed the whole shape (nothing left to stroke). */
private fun DrawScope.strokeCollapsed(inset: Float): Boolean =
    size.width - 2f * inset <= 0f || size.height - 2f * inset <= 0f

private fun capFor(style: StrokeStyle): StrokeCap =
    if (style == StrokeStyle.DOTTED) StrokeCap.Round else StrokeCap.Butt

private fun dashFor(style: StrokeStyle, width: Float): PathEffect? = when (style) {
    StrokeStyle.SOLID -> null
    StrokeStyle.DASHED -> PathEffect.dashPathEffect(floatArrayOf(width * 3f, width * 2f))
    // Near-zero dash + round cap renders a dot of the stroke's own diameter.
    StrokeStyle.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(0.01f, width * 2f))
}

/**
 * A shape-spanning linear gradient at the paint's angle. Illustrator convention: 0° flows
 * left→right, 90° bottom→top. Endpoints project the rect onto the gradient axis so position
 * 0/1 always land exactly on the shape's silhouette regardless of angle.
 */
fun linearBrushFor(paint: LayerPaint.Gradient, size: Size): Brush {
    val stops = paint.resolvedStops()
    val rad = Math.toRadians(paint.angleDeg.toDouble())
    val dirX = cos(rad).toFloat()
    val dirY = -sin(rad).toFloat()
    val halfLen = (abs(dirX) * size.width + abs(dirY) * size.height) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    return Brush.linearGradient(
        colorStops = stops.toTypedArray(),
        start = center - Offset(dirX * halfLen, dirY * halfLen),
        end = center + Offset(dirX * halfLen, dirY * halfLen),
    )
}

private const val MAX_ACROSS_BANDS = 32
