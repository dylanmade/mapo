package com.mappo.data.model.overlay

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Layered skeuomorphic appearance for an overlay element (and, eventually, any Mappo
 * "shape" — the handheld frame is planned to become a saved template of this model).
 *
 * The model is deliberately Illustrator-shaped rather than M3-shaped — it exists to author
 * faux-physical looks (bevels, wrapped edge highlights, plastic shells) that M3 components
 * can't express. A shape is an ordered stack of fill and stroke layers over one rounded-rect
 * geometry; each layer carries its own paint (solid or multi-stop gradient) and opacity.
 *
 * Two gradient-stroke types (both proven out in the user's Illustrator reference SVG):
 *  - [StrokeGradientMode.LINEAR] — the gradient is laid across the whole shape in shape
 *    space and shows *within* the stroke band. Stops clustered near one end of the ramp
 *    produce single-edge directional highlights/shadows.
 *  - [StrokeGradientMode.ACROSS] — the gradient runs across the stroke's width (outer edge
 *    → inner edge), curving uniformly around corners: the "wraps around a physical edge
 *    front-to-back" look. Illustrator can only rasterize this; we band it into concentric
 *    sub-strokes at draw time.
 */
data class ElementAppearance(
    /** Per-corner rounding — see [CornerRadii]. */
    val corners: CornerRadii = CornerRadii.uniform(DEFAULT_CORNER_RADIUS),
    /** Paint layers, bottom → top (index 0 draws first). */
    val layers: List<AppearanceLayer> = emptyList(),
) {
    companion object {
        const val DEFAULT_CORNER_RADIUS = 0.35f
    }
}

/**
 * Per-corner rounding, each a fraction of half the shape's short side: 0 = square,
 * 1 = fully round (all four at 1 on a square shape = circle).
 */
data class CornerRadii(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float,
) {
    val isUniform: Boolean
        get() = topLeft == topRight && topRight == bottomRight && bottomRight == bottomLeft

    /** Representative single value for a master slider — the average of the four. */
    val average: Float get() = (topLeft + topRight + bottomRight + bottomLeft) / 4f

    companion object {
        fun uniform(f: Float): CornerRadii = CornerRadii(f, f, f, f)
    }
}

enum class LayerKind { FILL, STROKE }

/** Where the stroke band sits relative to the shape edge. */
enum class StrokeAlign { INSIDE, CENTER, OUTSIDE }

enum class StrokeStyle { SOLID, DASHED, DOTTED }

/** How a gradient paint is applied to a stroke — see the class doc. Ignored on fills. */
enum class StrokeGradientMode { LINEAR, ACROSS }

data class AppearanceLayer(
    /** Stable id within this element's stack, for editor selection/reorder. */
    val id: Long,
    val kind: LayerKind,
    val paint: LayerPaint,
    val opacity: Float = 1f,
    // ── Stroke-only fields (ignored when kind == FILL) ────────────────────────
    val strokeWidthDp: Float = 2f,
    val strokeAlign: StrokeAlign = StrokeAlign.CENTER,
    val strokeStyle: StrokeStyle = StrokeStyle.SOLID,
    val strokeGradientMode: StrokeGradientMode = StrokeGradientMode.LINEAR,
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 0f,
)

sealed interface LayerPaint {
    data class Solid(val argb: Int) : LayerPaint

    data class Gradient(
        /** Kept sorted by position by every editor operation. */
        val stops: List<GradientStop>,
        /** Illustrator convention: 0° = left→right, 90° = bottom→top. Unused in ACROSS mode. */
        val angleDeg: Float = 90f,
    ) : LayerPaint
}

data class GradientStop(
    /** Location on the ramp, 0..1. */
    val position: Float,
    /** Stop color (RGB; any encoded alpha is superseded by [opacity]). */
    val argb: Int,
    val opacity: Float = 1f,
    /**
     * Illustrator midpoint slider toward the NEXT stop: the fraction of the span where the
     * blend reaches 50%. 0.5 = linear. Ignored on the last stop.
     */
    val midpoint: Float = 0.5f,
)

/** The stop's effective color, with its opacity applied. */
val GradientStop.color: Color get() = Color(argb).copy(alpha = opacity.coerceIn(0f, 1f))

/**
 * The gradient flattened to plain (position, color) pairs with midpoint easing expanded into
 * intermediate stops (the same trick Illustrator's SVG export uses) — ready for a
 * `Brush.linearGradient` or for direct sampling. Always returns at least two stops.
 */
fun LayerPaint.Gradient.resolvedStops(): List<Pair<Float, Color>> {
    val sorted = stops.sortedBy { it.position }
    if (sorted.isEmpty()) return listOf(0f to Color.Transparent, 1f to Color.Transparent)
    if (sorted.size == 1) return listOf(0f to sorted[0].color, 1f to sorted[0].color)
    val out = ArrayList<Pair<Float, Color>>(sorted.size * (MIDPOINT_SAMPLES.size + 1))
    sorted.forEachIndexed { i, stop ->
        out += stop.position to stop.color
        if (i == sorted.lastIndex) return@forEachIndexed
        val next = sorted[i + 1]
        val span = next.position - stop.position
        val m = stop.midpoint.coerceIn(MIDPOINT_MIN, MIDPOINT_MAX)
        // A midpoint at 0.5 is a plain linear blend — the two endpoint stops already express it.
        if (span <= 1e-4f || abs(m - 0.5f) < 0.01f) return@forEachIndexed
        val gamma = ln(0.5f) / ln(m)
        MIDPOINT_SAMPLES.forEach { f ->
            out += (stop.position + span * f) to lerp(stop.color, next.color, f.pow(gamma))
        }
    }
    return out
}

/** Piecewise-linear sample of already-[resolvedStops]-expanded stops at [t] in 0..1. */
fun sampleResolvedStops(stops: List<Pair<Float, Color>>, t: Float): Color {
    if (stops.isEmpty()) return Color.Transparent
    if (t <= stops.first().first) return stops.first().second
    if (t >= stops.last().first) return stops.last().second
    for (i in 0 until stops.lastIndex) {
        val (p0, c0) = stops[i]
        val (p1, c1) = stops[i + 1]
        if (t in p0..p1) {
            val f = if (p1 - p0 <= 1e-6f) 1f else (t - p0) / (p1 - p0)
            return lerp(c0, c1, f)
        }
    }
    return stops.last().second
}

const val MIDPOINT_MIN = 0.05f
const val MIDPOINT_MAX = 0.95f
private val MIDPOINT_SAMPLES = floatArrayOf(0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f)

// ── JSON codec ────────────────────────────────────────────────────────────────
//
// Hand-rolled org.json (the project doesn't pull in kotlinx-serialization — see MappoRoute's
// route-string rationale). Encoded form is stored in OverlayElement.appearanceJson and is the
// future template format for sharing/saving shapes, so field names are part of the contract.

fun ElementAppearance.encode(): String = JSONObject().apply {
    put("v", 1)
    put(
        "corners",
        JSONArray().apply {
            put(corners.topLeft.toDouble())
            put(corners.topRight.toDouble())
            put(corners.bottomRight.toDouble())
            put(corners.bottomLeft.toDouble())
        },
    )
    put(
        "layers",
        JSONArray().also { arr ->
            layers.forEach { layer ->
                arr.put(
                    JSONObject().apply {
                        put("id", layer.id)
                        put("kind", layer.kind.name.lowercase())
                        put("opacity", layer.opacity.toDouble())
                        put("paint", layer.paint.toJson())
                        if (layer.kind == LayerKind.STROKE) {
                            put("width", layer.strokeWidthDp.toDouble())
                            put("align", layer.strokeAlign.name.lowercase())
                            put("style", layer.strokeStyle.name.lowercase())
                            put("gradientMode", layer.strokeGradientMode.name.lowercase())
                            put("dx", layer.offsetXDp.toDouble())
                            put("dy", layer.offsetYDp.toDouble())
                        }
                    },
                )
            }
        },
    )
}.toString()

private fun LayerPaint.toJson(): JSONObject = when (this) {
    is LayerPaint.Solid -> JSONObject().apply {
        put("type", "solid")
        put("argb", argb)
    }
    is LayerPaint.Gradient -> JSONObject().apply {
        put("type", "gradient")
        put("angle", angleDeg.toDouble())
        put(
            "stops",
            JSONArray().also { arr ->
                stops.forEach { s ->
                    arr.put(
                        JSONObject().apply {
                            put("pos", s.position.toDouble())
                            put("argb", s.argb)
                            put("opacity", s.opacity.toDouble())
                            put("mid", s.midpoint.toDouble())
                        },
                    )
                }
            },
        )
    }
}

/** Decode, or null for null/blank/corrupt input (corrupt JSON falls back to legacy rendering). */
fun decodeElementAppearance(json: String?): ElementAppearance? {
    if (json.isNullOrBlank()) return null
    return try {
        val root = JSONObject(json)
        val layersArr = root.optJSONArray("layers") ?: JSONArray()
        val cornersArr = root.optJSONArray("corners")
        val corners = if (cornersArr != null && cornersArr.length() == 4) {
            CornerRadii(
                topLeft = cornersArr.getDouble(0).toFloat().coerceIn(0f, 1f),
                topRight = cornersArr.getDouble(1).toFloat().coerceIn(0f, 1f),
                bottomRight = cornersArr.getDouble(2).toFloat().coerceIn(0f, 1f),
                bottomLeft = cornersArr.getDouble(3).toFloat().coerceIn(0f, 1f),
            )
        } else {
            // Pre-per-corner encoding: a single uniform "cornerRadius".
            CornerRadii.uniform(
                root.optDouble("cornerRadius", ElementAppearance.DEFAULT_CORNER_RADIUS.toDouble())
                    .toFloat().coerceIn(0f, 1f),
            )
        }
        ElementAppearance(
            corners = corners,
            layers = (0 until layersArr.length()).map { i ->
                val obj = layersArr.getJSONObject(i)
                AppearanceLayer(
                    id = obj.getLong("id"),
                    kind = enumOrDefault(obj.optString("kind"), LayerKind.FILL),
                    paint = paintFromJson(obj.getJSONObject("paint")),
                    opacity = obj.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
                    strokeWidthDp = obj.optDouble("width", 2.0).toFloat(),
                    strokeAlign = enumOrDefault(obj.optString("align"), StrokeAlign.CENTER),
                    strokeStyle = enumOrDefault(obj.optString("style"), StrokeStyle.SOLID),
                    strokeGradientMode = enumOrDefault(obj.optString("gradientMode"), StrokeGradientMode.LINEAR),
                    offsetXDp = obj.optDouble("dx", 0.0).toFloat(),
                    offsetYDp = obj.optDouble("dy", 0.0).toFloat(),
                )
            },
        )
    } catch (_: Exception) {
        null
    }
}

private fun paintFromJson(obj: JSONObject): LayerPaint = when (obj.optString("type")) {
    "gradient" -> {
        val stopsArr = obj.optJSONArray("stops") ?: JSONArray()
        LayerPaint.Gradient(
            stops = (0 until stopsArr.length()).map { i ->
                val s = stopsArr.getJSONObject(i)
                GradientStop(
                    position = s.optDouble("pos", 0.0).toFloat().coerceIn(0f, 1f),
                    argb = s.getInt("argb"),
                    opacity = s.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
                    midpoint = s.optDouble("mid", 0.5).toFloat().coerceIn(MIDPOINT_MIN, MIDPOINT_MAX),
                )
            }.sortedBy { it.position },
            angleDeg = obj.optDouble("angle", 90.0).toFloat(),
        )
    }
    else -> LayerPaint.Solid(obj.getInt("argb"))
}

private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: default

// ── Stack helpers (shared by editor + defaults) ───────────────────────────────

/** Next free layer id in [layers]. */
fun nextLayerId(layers: List<AppearanceLayer>): Long = (layers.maxOfOrNull { it.id } ?: 0L) + 1L

/** A fresh solid fill layer. */
fun defaultFillLayer(id: Long, color: Color): AppearanceLayer =
    AppearanceLayer(id = id, kind = LayerKind.FILL, paint = LayerPaint.Solid(color.toArgb()))

/**
 * A fresh stroke layer. Defaults to a centered 2dp white-to-transparent linear gradient —
 * the single most common authoring move here is a directional edge highlight, so the new
 * layer arrives one color-tweak away from one instead of as an opaque outline.
 */
fun defaultStrokeLayer(id: Long, color: Color): AppearanceLayer =
    AppearanceLayer(
        id = id,
        kind = LayerKind.STROKE,
        paint = LayerPaint.Gradient(
            stops = listOf(
                GradientStop(position = 0.85f, argb = color.toArgb(), opacity = 0f),
                GradientStop(position = 1f, argb = color.toArgb(), opacity = 0.6f),
            ),
        ),
    )
