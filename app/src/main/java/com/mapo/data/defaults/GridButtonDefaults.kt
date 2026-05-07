package com.mapo.data.defaults

import com.mapo.data.model.ButtonRegion
import com.mapo.data.model.GridButton
import com.mapo.data.model.RegionPosition
import com.mapo.data.model.RemapTarget

/**
 * Default behavior + appearance per button type. The Configure Button dialog's
 * "Reset Behavior" and "Reset Appearance" buttons read from here.
 *
 * The two types currently have identical appearance; they're broken out so future
 * divergence (e.g. trackpad picks up its own default fill/outline) can land without
 * a structural rewrite.
 */
object GridButtonDefaults {

    const val TRACKPAD_SENSITIVITY = 1.5f

    private val buttonBehavior = BehaviorPreset(
        onTap = RemapTarget.Unbound.encode(),
        onDoubleTap = RemapTarget.Unbound.encode(),
        onHold = RemapTarget.Unbound.encode(),
        sensitivity = null,
    )

    private val trackpadBehavior = BehaviorPreset(
        onTap = RemapTarget.Mouse("MOUSE_LEFT").encode(),
        onDoubleTap = RemapTarget.Unbound.encode(),
        onHold = RemapTarget.Mouse("MOUSE_RIGHT").encode(),
        sensitivity = TRACKPAD_SENSITIVITY,
    )

    private val buttonAppearance = AppearancePreset(
        fillColorArgb = null,
        outlineColorArgb = null,
        regions = emptyMap(),
    )

    private val trackpadAppearance = AppearancePreset(
        fillColorArgb = null,
        outlineColorArgb = null,
        regions = mapOf(
            RegionPosition.CENTER.name to ButtonRegion(
                icon = "Mouse",
                label = "Trackpad",
                sizeSp = 14f,
            ),
        ),
    )

    fun behaviorFor(type: String?): BehaviorPreset =
        if (type == "trackpad") trackpadBehavior else buttonBehavior

    fun appearanceFor(type: String?): AppearancePreset =
        if (type == "trackpad") trackpadAppearance else buttonAppearance

    data class BehaviorPreset(
        val onTap: String,
        val onDoubleTap: String,
        val onHold: String,
        val sensitivity: Float?,
    ) {
        fun apply(button: GridButton): GridButton = button.copy(
            onTap = onTap,
            onDoubleTap = onDoubleTap,
            onHold = onHold,
            sensitivity = sensitivity,
        )
    }

    data class AppearancePreset(
        val fillColorArgb: Int?,
        val outlineColorArgb: Int?,
        val regions: Map<String, com.mapo.data.model.ButtonRegion>,
    ) {
        fun apply(button: GridButton): GridButton = button.copy(
            fillColorArgb = fillColorArgb,
            outlineColorArgb = outlineColorArgb,
            regions = regions,
        )
    }
}
