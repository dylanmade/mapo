package com.mapo.data.model

import java.util.UUID

data class GridLayout(
    val id: Long = 0L,
    val name: String,
    val columns: Int,
    val rows: Int,
    val buttons: List<GridButton>,

    // Appearance — four parallel "color slots" (fill, outline, bevel, shadow), mirroring
    // [GridButton]'s appearance fields. The semantics are identical: *Enabled is the
    // master switch, *ColorArgb is the user's last manually-picked color (preserved
    // across switch toggles), and *IsAuto means the color is derived from the parent
    // in the hierarchy (M3 theme surface → fill → outline/bevel/shadow). See
    // [com.mapo.ui.util.resolveAutoLayoutColors] for the resolver.
    //
    // Default for new keyboards: only fill ON (auto), matching the pre-refactor visual
    // (a keyboard with the M3 surface as its background). Outline/bevel/shadow OFF so
    // they don't appear unless the user opts in.
    val fillEnabled: Boolean = true,
    val fillColorArgb: Int? = null,
    val fillIsAuto: Boolean = true,

    val outlineEnabled: Boolean = false,
    val outlineColorArgb: Int? = null,
    val outlineIsAuto: Boolean = true,

    val bevelEnabled: Boolean = false,
    val bevelColorArgb: Int? = null,
    val bevelIsAuto: Boolean = true,

    val shadowEnabled: Boolean = false,
    val shadowColorArgb: Int? = null,
    val shadowIsAuto: Boolean = true,

    // Default-button template — seeds newly-added buttons in this keyboard.
    // Existing buttons are NOT touched when these change; this is purely a stamp-out
    // template. Size is auto-clamped to fit within columns/rows on keyboard resize.
    // Color-slot defaults mirror [GridButton]'s field defaults (fill+bevel+shadow on,
    // outline off, all auto) so a freshly-created keyboard stamps out buttons that
    // look exactly like a pre-refactor default button.
    val defaultButtonColSpan: Int = 1,
    val defaultButtonRowSpan: Int = 1,

    val defaultButtonFillEnabled: Boolean = true,
    val defaultButtonFillColorArgb: Int? = null,
    val defaultButtonFillIsAuto: Boolean = true,

    val defaultButtonOutlineEnabled: Boolean = false,
    val defaultButtonOutlineColorArgb: Int? = null,
    val defaultButtonOutlineIsAuto: Boolean = true,

    val defaultButtonBevelEnabled: Boolean = true,
    val defaultButtonBevelColorArgb: Int? = null,
    val defaultButtonBevelIsAuto: Boolean = true,

    val defaultButtonShadowEnabled: Boolean = true,
    val defaultButtonShadowColorArgb: Int? = null,
    val defaultButtonShadowIsAuto: Boolean = true,

    val defaultButtonRegions: Map<String, ButtonRegion> = emptyMap(),
)

/**
 * Synthesizes a [GridButton] from this layout's default-button fields. The Buttons
 * tab in `ConfigureKeyboardScreen` edits this synthesized template via the same
 * composables that power `ConfigureButtonScreen`; commits go back through
 * [withDefaultButtonFrom]. `col`/`row` are placeholders — only the size + appearance
 * + regions fields are read.
 */
fun GridLayout.defaultButtonTemplate(): GridButton = GridButton(
    col = 0,
    row = 0,
    colSpan = defaultButtonColSpan,
    rowSpan = defaultButtonRowSpan,
    type = "key",
    fillEnabled = defaultButtonFillEnabled,
    fillColorArgb = defaultButtonFillColorArgb,
    fillIsAuto = defaultButtonFillIsAuto,
    outlineEnabled = defaultButtonOutlineEnabled,
    outlineColorArgb = defaultButtonOutlineColorArgb,
    outlineIsAuto = defaultButtonOutlineIsAuto,
    bevelEnabled = defaultButtonBevelEnabled,
    bevelColorArgb = defaultButtonBevelColorArgb,
    bevelIsAuto = defaultButtonBevelIsAuto,
    shadowEnabled = defaultButtonShadowEnabled,
    shadowColorArgb = defaultButtonShadowColorArgb,
    shadowIsAuto = defaultButtonShadowIsAuto,
    regions = defaultButtonRegions,
)

/** Inverse of [defaultButtonTemplate]: write a synthesized template back into the layout. */
fun GridLayout.withDefaultButtonFrom(template: GridButton): GridLayout = copy(
    defaultButtonColSpan = template.colSpan,
    defaultButtonRowSpan = template.rowSpan,
    defaultButtonFillEnabled = template.fillEnabled,
    defaultButtonFillColorArgb = template.fillColorArgb,
    defaultButtonFillIsAuto = template.fillIsAuto,
    defaultButtonOutlineEnabled = template.outlineEnabled,
    defaultButtonOutlineColorArgb = template.outlineColorArgb,
    defaultButtonOutlineIsAuto = template.outlineIsAuto,
    defaultButtonBevelEnabled = template.bevelEnabled,
    defaultButtonBevelColorArgb = template.bevelColorArgb,
    defaultButtonBevelIsAuto = template.bevelIsAuto,
    defaultButtonShadowEnabled = template.shadowEnabled,
    defaultButtonShadowColorArgb = template.shadowColorArgb,
    defaultButtonShadowIsAuto = template.shadowIsAuto,
    defaultButtonRegions = template.regions,
)

/**
 * Returns a copy of [base] (a fresh `GridButton(col, row, type)` from the caller)
 * seeded with this layout's default-button template — size, color slots, and regions.
 * Used by add-button paths so newly-placed buttons inherit the keyboard's Buttons-tab
 * defaults.
 */
fun GridLayout.seedNewButton(base: GridButton): GridButton = base.copy(
    colSpan = defaultButtonColSpan,
    rowSpan = defaultButtonRowSpan,
    fillEnabled = defaultButtonFillEnabled,
    fillColorArgb = defaultButtonFillColorArgb,
    fillIsAuto = defaultButtonFillIsAuto,
    outlineEnabled = defaultButtonOutlineEnabled,
    outlineColorArgb = defaultButtonOutlineColorArgb,
    outlineIsAuto = defaultButtonOutlineIsAuto,
    bevelEnabled = defaultButtonBevelEnabled,
    bevelColorArgb = defaultButtonBevelColorArgb,
    bevelIsAuto = defaultButtonBevelIsAuto,
    shadowEnabled = defaultButtonShadowEnabled,
    shadowColorArgb = defaultButtonShadowColorArgb,
    shadowIsAuto = defaultButtonShadowIsAuto,
    regions = defaultButtonRegions,
)

/**
 * Returns a copy of this layout with a fresh UUID on every button. Required whenever a
 * GridLayout is cloned to seed a new persisted keyboard (duplicate tab, instantiate from
 * a template, copy from another profile). Without this, the copy's buttons retain the
 * source's ids; [MainViewModel.mutateLayoutContaining] matches buttons by id across the
 * active profile's layouts, so edits land on whichever layout it finds first — usually
 * the original, making the copy appear "uneditable" until the source is deleted.
 */
fun GridLayout.withFreshButtonIds(): GridLayout =
    copy(buttons = buttons.map { it.copy(id = UUID.randomUUID().toString()) })

// Returns (col, row) of the first unoccupied 1×1 cell, or null if the grid is full.
fun GridLayout.findFirstEmptyCell(): Pair<Int, Int>? = findFirstEmptyArea(1, 1)

/**
 * Returns (col, row) of the first row-major position where a [colSpan] × [rowSpan]
 * block fits without overlapping existing buttons or the grid edges. Returns null
 * if no such position exists. Used by duplicate-at-original-size: callers try the
 * source button's full size first, then fall back to a smaller area.
 */
fun GridLayout.findFirstEmptyArea(colSpan: Int, rowSpan: Int): Pair<Int, Int>? {
    if (colSpan < 1 || rowSpan < 1) return null
    if (colSpan > columns || rowSpan > rows) return null
    val occupied = buildSet {
        for (btn in buttons) {
            for (r in btn.row until btn.row + btn.rowSpan) {
                for (c in btn.col until btn.col + btn.colSpan) {
                    add(c to r)
                }
            }
        }
    }
    for (r in 0..(rows - rowSpan)) {
        for (c in 0..(columns - colSpan)) {
            var fits = true
            outer@ for (rr in r until r + rowSpan) {
                for (cc in c until c + colSpan) {
                    if ((cc to rr) in occupied) {
                        fits = false
                        break@outer
                    }
                }
            }
            if (fits) return c to r
        }
    }
    return null
}

// Returns true if placing a button with the given bounds would overlap any other button.
fun GridLayout.wouldOverlap(
    excludeId: String,
    col: Int, row: Int,
    colSpan: Int, rowSpan: Int
): Boolean {
    val proposed = buildSet {
        for (r in row until row + rowSpan) {
            for (c in col until col + colSpan) {
                add(c to r)
            }
        }
    }
    return buttons.any { btn ->
        if (btn.id == excludeId) return@any false
        val btnCells = buildSet {
            for (r in btn.row until btn.row + btn.rowSpan) {
                for (c in btn.col until btn.col + btn.colSpan) {
                    add(c to r)
                }
            }
        }
        proposed.intersect(btnCells).isNotEmpty()
    }
}

// Buttons whose extent (col + colSpan, row + rowSpan) does not fit within the given grid size.
fun GridLayout.buttonsExceeding(cols: Int, rows: Int): List<GridButton> =
    buttons.filter { it.col + it.colSpan > cols || it.row + it.rowSpan > rows }

fun GridLayout.fitsWithin(cols: Int, rows: Int): Boolean =
    buttonsExceeding(cols, rows).isEmpty()
