package com.mapo.data.model

import java.util.UUID

/**
 * One of the nine drawable cells inside a button. CENTER is the dominant cell;
 * the eight surrounding positions are smaller by default.
 */
enum class RegionPosition {
    CENTER,
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    CENTER_LEFT, CENTER_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,
}

/**
 * Per-cell content for a button. A region with all-null content draws nothing.
 *
 * - [icon]: Material Symbols icon name (matches `Icons.Filled.<Name>`); null = no icon.
 * - [label]: text content. null falls back to the button's onTap target string at render
 *   time, so a fresh button shows e.g. "ENTER" without the user typing it.
 * - [iconColorArgb] / [labelColorArgb]: null inherits the button's content color.
 * - [sizeSp]: text size for the label and the box for the icon.
 */
data class ButtonRegion(
    val icon: String? = null,
    val label: String? = null,
    val iconColorArgb: Int? = null,
    val labelColorArgb: Int? = null,
    val sizeSp: Float = 9f,
)

data class GridButton(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val col: Int,
    val row: Int,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    // null / "key" = normal button; "trackpad" = mouse trackpad
    val type: String? = null,

    // Behavior — common to both types. Stored as encoded RemapTarget strings
    // (see RemapTarget.encode/decode) so Gson handles the sealed class safely.
    val onTap: String = RemapTarget.Unbound.encode(),
    val onDoubleTap: String = RemapTarget.Unbound.encode(),
    val onHold: String = RemapTarget.Unbound.encode(),

    // Behavior — trackpad-specific. null = use the global default sensitivity.
    val sensitivity: Float? = null,

    // Appearance — null on any field means "use the theme default for this button type".
    val fillColorArgb: Int? = null,
    val outlineColorArgb: Int? = null,
    // Keyed by RegionPosition.name so Gson serializes cleanly without an enum-key adapter.
    val regions: Map<String, ButtonRegion> = emptyMap(),
)

val GridButton.isTrackpad get() = type == "trackpad"

val GridButton.onTapTarget: RemapTarget get() = RemapTarget.decode(onTap)
val GridButton.onDoubleTapTarget: RemapTarget get() = RemapTarget.decode(onDoubleTap)
val GridButton.onHoldTarget: RemapTarget get() = RemapTarget.decode(onHold)

fun GridButton.region(pos: RegionPosition): ButtonRegion? = regions[pos.name]
