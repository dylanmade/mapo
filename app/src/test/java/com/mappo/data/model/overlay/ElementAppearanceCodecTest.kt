package com.mappo.data.model.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Round-trip contract for the appearanceJson codec (org.json needs the Android runtime). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ElementAppearanceCodecTest {

    private val gradientStroke = AppearanceLayer(
        id = 2L,
        kind = LayerKind.STROKE,
        paint = LayerPaint.Gradient(
            stops = listOf(
                GradientStop(position = 0.85f, argb = 0xFFFFFFFF.toInt(), opacity = 0f, midpoint = 0.3f),
                GradientStop(position = 1f, argb = 0xFFFFFFFF.toInt(), opacity = 0.6f),
            ),
            angleDeg = 270f,
        ),
        opacity = 0.8f,
        strokeWidthDp = 3.5f,
        strokeAlign = StrokeAlign.INSIDE,
        strokeStyle = StrokeStyle.DASHED,
        strokeGradientMode = StrokeGradientMode.ACROSS,
        offsetXDp = -2f,
        offsetYDp = 1.5f,
    )

    private val appearance = ElementAppearance(
        corners = CornerRadii(topLeft = 0.4f, topRight = 0f, bottomRight = 1f, bottomLeft = 0.25f),
        layers = listOf(
            AppearanceLayer(id = 1L, kind = LayerKind.FILL, paint = LayerPaint.Solid(0xFF2C73E5.toInt())),
            gradientStroke,
        ),
    )

    @Test
    fun roundTripPreservesEveryField() {
        assertEquals(appearance, decodeElementAppearance(appearance.encode()))
    }

    @Test
    fun legacySingleCornerRadiusDecodesAsUniform() {
        val decoded = decodeElementAppearance("""{"v":1,"cornerRadius":0.7,"layers":[]}""")
        assertEquals(CornerRadii.uniform(0.7f), decoded?.corners)
    }

    @Test
    fun nullBlankAndCorruptDecodeToNull() {
        assertNull(decodeElementAppearance(null))
        assertNull(decodeElementAppearance(""))
        assertNull(decodeElementAppearance("not json"))
    }

    @Test
    fun strokeFieldsOmittedForFills() {
        val json = ElementAppearance(
            layers = listOf(AppearanceLayer(id = 1L, kind = LayerKind.FILL, paint = LayerPaint.Solid(0))),
        ).encode()
        assertTrue("fill layers should not carry stroke fields", "width" !in json)
    }

    @Test
    fun midpointExpansionOnlyWhenOffCenter() {
        val linear = LayerPaint.Gradient(
            stops = listOf(GradientStop(0f, 0xFF000000.toInt()), GradientStop(1f, 0xFFFFFFFF.toInt())),
        )
        assertEquals(2, linear.resolvedStops().size)
        val eased = LayerPaint.Gradient(
            stops = listOf(
                GradientStop(0f, 0xFF000000.toInt(), midpoint = 0.2f),
                GradientStop(1f, 0xFFFFFFFF.toInt()),
            ),
        )
        assertTrue("off-center midpoint should expand into intermediate stops", eased.resolvedStops().size > 2)
    }
}
