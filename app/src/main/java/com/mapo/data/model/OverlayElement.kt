package com.mapo.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One free-positioned button in the rebuilt overlay (see `OVERLAY_REBUILD_PLAN.md`).
 *
 * This is the conventional mobile game-overlay model that replaces the legacy tabbed
 * `KeyLayout` grid: each element is placed anywhere on screen and renders as its own
 * `TYPE_APPLICATION_OVERLAY` window, so empty space is passthrough for free.
 *
 * Position + size are stored as **normalized fractions of the display** (`[0f, 1f]`),
 * so a layout authored on one device renders proportionally on another regardless of
 * resolution. The window manager multiplies these by live display metrics at attach time.
 *
 * **FC1 seam.** [tapTarget] is an encoded [RemapTarget] string (same encoding
 * `GridButton.onTap` uses). It is the *only* binding indirection on the element — when
 * overlay buttons migrate onto the physical-remap `Binding`/`Activator` pipeline, this
 * single field swaps to a binding reference and nothing else here changes.
 *
 * The active overlay is "every element for the active profile" today. That keeps
 * element ownership separable from *which* overlay is active, so the future
 * action-set-governed model can re-key without touching the element shape.
 */
@Entity(
    tableName = "overlay_elements",
    foreignKeys = [ForeignKey(
        entity = Profile::class,
        parentColumns = ["id"],
        childColumns = ["profileId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("profileId")],
)
data class OverlayElement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val label: String = "",

    // Normalized display fractions in [0f, 1f]. (x, y) is the top-left corner.
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,

    // Encoded RemapTarget (RemapTarget.encode/decode) per gesture. FC1 seam — see class doc.
    val tapTarget: String = RemapTarget.Unbound.encode(),
    val doubleTapTarget: String = RemapTarget.Unbound.encode(),
    val holdTarget: String = RemapTarget.Unbound.encode(),

    // ── Light appearance ──────────────────────────────────────────────────────
    // "rounded" | "circle" | "rectangle". See OverlayShape.
    val shape: String = SHAPE_ROUNDED,
    val opacity: Float = 1f,
    // null = theme default (secondaryContainer / onSecondaryContainer).
    val fillColorArgb: Int? = null,
    val contentColorArgb: Int? = null,

    // Paint order: higher draws on top. Mirrors the window z-order the manager assigns.
    val zIndex: Int = 0,
) {
    companion object {
        const val SHAPE_ROUNDED = "rounded"
        const val SHAPE_CIRCLE = "circle"
        const val SHAPE_RECTANGLE = "rectangle"
    }
}

/** The three command gestures an overlay button can emit. */
enum class OverlayGesture { TAP, DOUBLE_TAP, HOLD }

val OverlayElement.tapRemapTarget: RemapTarget get() = RemapTarget.decode(tapTarget)
val OverlayElement.doubleTapRemapTarget: RemapTarget get() = RemapTarget.decode(doubleTapTarget)
val OverlayElement.holdRemapTarget: RemapTarget get() = RemapTarget.decode(holdTarget)

/** Decoded command for [gesture]. */
fun OverlayElement.targetFor(gesture: OverlayGesture): RemapTarget = when (gesture) {
    OverlayGesture.TAP -> tapRemapTarget
    OverlayGesture.DOUBLE_TAP -> doubleTapRemapTarget
    OverlayGesture.HOLD -> holdRemapTarget
}

/** Copy with [gesture]'s command set to [target]. */
fun OverlayElement.withTarget(gesture: OverlayGesture, target: RemapTarget): OverlayElement = when (gesture) {
    OverlayGesture.TAP -> copy(tapTarget = target.encode())
    OverlayGesture.DOUBLE_TAP -> copy(doubleTapTarget = target.encode())
    OverlayGesture.HOLD -> copy(holdTarget = target.encode())
}
