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
        regions = emptyMap(),
    )

    private val trackpadAppearance = AppearancePreset(
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

    /**
     * Per-type appearance preset. Color-slot defaults (fill on, outline off, bevel on,
     * shadow on, all auto) live on [GridButton] itself; "Reset Appearance" rebuilds the
     * button from a fresh [GridButton] so the slot defaults stay in one place.
     */
    data class AppearancePreset(
        val regions: Map<String, com.mapo.data.model.ButtonRegion>,
    ) {
        fun apply(button: GridButton): GridButton {
            val template = GridButton(col = button.col, row = button.row)
            return button.copy(
                fillEnabled = template.fillEnabled,
                fillColorArgb = template.fillColorArgb,
                fillIsAuto = template.fillIsAuto,
                outlineEnabled = template.outlineEnabled,
                outlineColorArgb = template.outlineColorArgb,
                outlineIsAuto = template.outlineIsAuto,
                bevelEnabled = template.bevelEnabled,
                bevelColorArgb = template.bevelColorArgb,
                bevelIsAuto = template.bevelIsAuto,
                shadowEnabled = template.shadowEnabled,
                shadowColorArgb = template.shadowColorArgb,
                shadowIsAuto = template.shadowIsAuto,
                animationEnabled = template.animationEnabled,
                animationColorArgb = template.animationColorArgb,
                animationIsAuto = template.animationIsAuto,
                animationMotionEnabled = template.animationMotionEnabled,
                regions = regions,
            )
        }
    }
}
