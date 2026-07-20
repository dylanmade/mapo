package com.mappo.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.mappo.data.model.overlay.GradientStop
import com.mappo.data.model.overlay.LayerPaint
import com.mappo.data.model.overlay.MIDPOINT_MAX
import com.mappo.data.model.overlay.MIDPOINT_MIN
import com.mappo.data.model.overlay.color
import com.mappo.data.model.overlay.resolvedStops
import com.mappo.data.model.overlay.sampleResolvedStops
import com.mappo.ui.component.colorpicker.ColorPickerButton
import kotlin.math.abs
import kotlin.math.roundToInt

/** What's selected on the ramp: a stop, or the midpoint between stop [index] and index+1. */
private sealed interface GradientSelection {
    data class Stop(val index: Int) : GradientSelection
    data class Midpoint(val index: Int) : GradientSelection
}

/**
 * Illustrator-style gradient editor (deliberate M3 deviation — this is an authoring tool,
 * not a settings row): a live ramp, draggable round stop markers below it, draggable
 * diamond midpoint markers between adjacent stops, and controls for whichever marker is
 * selected. Tap an empty spot under the ramp to add a stop there.
 *
 * [gradient]'s stops are assumed (and kept) sorted by position. Instant-commit: every
 * manipulation calls [onChange].
 *
 * @param editorKey stable identity of what's being edited (e.g. the layer id) — resets
 *   selection when the editor is retargeted.
 * @param showAngle hide for across-stroke gradients, whose direction is the stroke itself.
 */
@Composable
fun GradientEditor(
    gradient: LayerPaint.Gradient,
    onChange: (LayerPaint.Gradient) -> Unit,
    editorKey: Any,
    modifier: Modifier = Modifier,
    showAngle: Boolean = true,
) {
    val current by rememberUpdatedState(gradient)
    val commit by rememberUpdatedState(onChange)
    var selection by remember(editorKey) { mutableStateOf<GradientSelection>(GradientSelection.Stop(0)) }
    var stripWidthPx by remember { mutableFloatStateOf(0f) }

    val stops = gradient.stops
    fun stopAt(i: Int): GradientStop? = stops.getOrNull(i)

    // px → ramp fraction against the live strip width.
    fun fractionOf(x: Float): Float = if (stripWidthPx <= 0f) 0f else (x / stripWidthPx).coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ── Ramp preview (tap to add a stop at that spot) ──
        val outlineVariant = MaterialTheme.colorScheme.outlineVariant
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(6.dp))
                // surfaceVariant baseplate so transparent regions of the ramp read as such.
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .drawBehind {
                    val resolved = current.resolvedStops()
                    drawRoundRect(
                        brush = Brush.horizontalGradient(colorStops = resolved.toTypedArray()),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                }
                .border(1.dp, outlineVariant, RoundedCornerShape(6.dp))
                .pointerInput(editorKey) {
                    detectTapGestures { pos ->
                        val g = current
                        val t = if (size.width <= 0) 0f else (pos.x / size.width).coerceIn(0f, 1f)
                        val sampled = sampleResolvedStops(g.resolvedStops(), t)
                        val added = GradientStop(
                            position = t,
                            argb = sampled.copy(alpha = 1f).toArgb(),
                            opacity = sampled.alpha,
                        )
                        val newStops = (g.stops + added).sortedBy { it.position }
                        selection = GradientSelection.Stop(newStops.indexOf(added))
                        commit(g.copy(stops = newStops))
                    }
                },
        )

        // ── Marker strip: stops (circles) + midpoints (diamonds) ──
        GradientMarkerStrip(
            gradient = gradient,
            selection = selection,
            onSelect = { selection = it },
            onDragStop = { index, x ->
                val g = current
                val s = g.stops.getOrNull(index) ?: return@GradientMarkerStrip
                val lo = (g.stops.getOrNull(index - 1)?.position ?: 0f) + STOP_MIN_GAP
                val hi = (g.stops.getOrNull(index + 1)?.position ?: 1f) - STOP_MIN_GAP
                if (lo > hi) return@GradientMarkerStrip
                commit(g.copy(stops = g.stops.toMutableList().also { it[index] = s.copy(position = fractionOf(x).coerceIn(lo, hi)) }))
            },
            onDragMidpoint = { index, x ->
                val g = current
                val s0 = g.stops.getOrNull(index) ?: return@GradientMarkerStrip
                val s1 = g.stops.getOrNull(index + 1) ?: return@GradientMarkerStrip
                val span = s1.position - s0.position
                if (span <= 1e-4f) return@GradientMarkerStrip
                val m = ((fractionOf(x) - s0.position) / span).coerceIn(MIDPOINT_MIN, MIDPOINT_MAX)
                commit(g.copy(stops = g.stops.toMutableList().also { it[index] = s0.copy(midpoint = m) }))
            },
            onWidth = { stripWidthPx = it },
        )
        Text(
            "Tap the ramp to add a stop; drag markers to move them",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Selected-marker controls ──
        when (val sel = selection) {
            is GradientSelection.Stop -> {
                val stop = stopAt(sel.index) ?: return@Column
                var picking by remember(editorKey, sel.index) { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ColorPickerButton(color = Color(stop.argb), onClick = { picking = !picking }, size = 28.dp)
                    Text(
                        "Stop at ${(stop.position * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val g = current
                            if (g.stops.size <= 2) return@TextButton
                            selection = GradientSelection.Stop((sel.index - 1).coerceAtLeast(0))
                            commit(g.copy(stops = g.stops.filterIndexed { i, _ -> i != sel.index }))
                        },
                        enabled = stops.size > 2,
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text("  Remove")
                    }
                }
                LabeledPercentSlider("Location", stop.position, onChange = { v ->
                    val g = current
                    val s = g.stops.getOrNull(sel.index) ?: return@LabeledPercentSlider
                    val lo = (g.stops.getOrNull(sel.index - 1)?.position ?: 0f) + STOP_MIN_GAP
                    val hi = (g.stops.getOrNull(sel.index + 1)?.position ?: 1f) - STOP_MIN_GAP
                    if (lo > hi) return@LabeledPercentSlider
                    commit(g.copy(stops = g.stops.toMutableList().also { it[sel.index] = s.copy(position = v.coerceIn(lo, hi)) }))
                })
                LabeledPercentSlider("Opacity", stop.opacity, onChange = { v ->
                    val g = current
                    val s = g.stops.getOrNull(sel.index) ?: return@LabeledPercentSlider
                    commit(g.copy(stops = g.stops.toMutableList().also { it[sel.index] = s.copy(opacity = v) }))
                })
                if (picking) {
                    // INLINE picker, not MappoColorPickerDialog: this editor also runs inside
                    // TYPE_APPLICATION_OVERLAY windows, where dialog composables can't attach.
                    ColorPicker(
                        color = Color(stop.argb).copy(alpha = 1f),
                        onChange = { c ->
                            val g = current
                            val s = g.stops.getOrNull(sel.index) ?: return@ColorPicker
                            commit(g.copy(stops = g.stops.toMutableList().also { it[sel.index] = s.copy(argb = c.copy(alpha = 1f).toArgb()) }))
                        },
                        onClearOverride = null,
                        pickerKey = "$editorKey-stop-${sel.index}",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            is GradientSelection.Midpoint -> {
                val s0 = stopAt(sel.index) ?: return@Column
                LabeledPercentSlider(
                    "Midpoint",
                    s0.midpoint,
                    range = MIDPOINT_MIN..MIDPOINT_MAX,
                    onChange = { v ->
                        val g = current
                        val s = g.stops.getOrNull(sel.index) ?: return@LabeledPercentSlider
                        commit(g.copy(stops = g.stops.toMutableList().also { it[sel.index] = s.copy(midpoint = v) }))
                    },
                )
            }
        }

        if (showAngle) {
            Column {
                Text(
                    "Angle  ${gradient.angleDeg.roundToInt()}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = gradient.angleDeg.coerceIn(0f, 360f),
                    onValueChange = { commit(current.copy(angleDeg = it)) },
                    valueRange = 0f..360f,
                )
            }
        }
    }
}

/**
 * The draggable marker row. Kept as one Canvas + one gesture surface so stop and midpoint
 * hit-testing share a single coordinate space.
 */
@Composable
private fun GradientMarkerStrip(
    gradient: LayerPaint.Gradient,
    selection: GradientSelection,
    onSelect: (GradientSelection) -> Unit,
    onDragStop: (index: Int, x: Float) -> Unit,
    onDragMidpoint: (index: Int, x: Float) -> Unit,
    onWidth: (Float) -> Unit,
) {
    val current by rememberUpdatedState(gradient)
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    var dragTarget by remember { mutableStateOf<GradientSelection?>(null) }

    fun markerXs(width: Float): Pair<List<Float>, List<Float>> {
        val stops = current.stops
        val stopXs = stops.map { it.position * width }
        val midXs = stops.dropLast(1).mapIndexed { i, s ->
            (s.position + s.midpoint * (stops[i + 1].position - s.position)) * width
        }
        return stopXs to midXs
    }

    fun hitTest(x: Float, width: Float, thresholdPx: Float): GradientSelection? {
        val (stopXs, midXs) = markerXs(width)
        val stopHit = stopXs.withIndex().minByOrNull { abs(it.value - x) }
        if (stopHit != null && abs(stopHit.value - x) <= thresholdPx) return GradientSelection.Stop(stopHit.index)
        val midHit = midXs.withIndex().minByOrNull { abs(it.value - x) }
        if (midHit != null && abs(midHit.value - x) <= thresholdPx) return GradientSelection.Midpoint(midHit.index)
        return null
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { onWidth(it.width.toFloat()) }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    hitTest(pos.x, size.width.toFloat(), MARKER_HIT_RADIUS.toPx())?.let(onSelect)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        dragTarget = hitTest(pos.x, size.width.toFloat(), MARKER_HIT_RADIUS.toPx())
                        dragTarget?.let(onSelect)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        when (val t = dragTarget) {
                            is GradientSelection.Stop -> onDragStop(t.index, change.position.x)
                            is GradientSelection.Midpoint -> onDragMidpoint(t.index, change.position.x)
                            null -> {}
                        }
                    },
                    onDragEnd = { dragTarget = null },
                    onDragCancel = { dragTarget = null },
                )
            },
    ) {
        val cy = size.height / 2f
        val (stopXs, midXs) = markerXs(size.width)
        // Midpoints first so a coincident stop marker draws on top.
        midXs.forEachIndexed { i, x ->
            val selected = selection == GradientSelection.Midpoint(i)
            val r = 5.dp.toPx()
            val diamond = Path().apply {
                moveTo(x, cy - r); lineTo(x + r, cy); lineTo(x, cy + r); lineTo(x - r, cy); close()
            }
            drawPath(diamond, color = if (selected) primary else onSurfaceVariant)
        }
        stopXs.forEachIndexed { i, x ->
            val selected = selection == GradientSelection.Stop(i)
            val r = 8.dp.toPx()
            drawCircle(color = current.stops[i].color.copy(alpha = 1f), radius = r, center = Offset(x, cy))
            drawCircle(
                color = if (selected) primary else outline,
                radius = r,
                center = Offset(x, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (selected) 3.dp.toPx() else 1.5.dp.toPx()),
            )
        }
    }
}

@Composable
private fun LabeledPercentSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    Column {
        Text(
            "$label  ${(value * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

private val MARKER_HIT_RADIUS = 14.dp
private const val STOP_MIN_GAP = 0.005f
