package com.mappo.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mappo.data.model.steam.ActionLayer
import com.mappo.data.model.steam.ActionSet

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
 * **Scope (FC1).** Each element is owned by exactly one of:
 *  - an [ActionSet] ([actionSetId] set, [actionLayerId] null) — "set-owned", or
 *  - an [ActionLayer] ([actionLayerId] set, [actionSetId] null) — "layer-owned".
 * The editor edits one scope at a time (a dropdown picks it); run mode shows the active
 * action set's set-owned elements. Layer→set inheritance is a later brick — for now a
 * scope shows only its own elements. [profileId] is retained for the profile CASCADE and
 * "clear all for profile".
 */
@Entity(
    tableName = "overlay_elements",
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ActionSet::class,
            parentColumns = ["id"],
            childColumns = ["actionSetId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ActionLayer::class,
            parentColumns = ["id"],
            childColumns = ["actionLayerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId"), Index("actionSetId"), Index("actionLayerId")],
)
data class OverlayElement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    // Scope: exactly one is non-null (see class doc). Null/null = unscoped (legacy/no config).
    val actionSetId: Long? = null,
    val actionLayerId: Long? = null,
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

    // Encoded ElementAppearance (layered fills/strokes — see data/model/overlay/). Null =
    // element still renders via the light-appearance fields above; the two coexist because
    // pre-layer elements must keep their look until first edited in the layer editor.
    val appearanceJson: String? = null,

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
