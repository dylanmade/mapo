package com.mapo.data.model

data class GridLayout(
    val id: Long = 0L,
    val name: String,
    val columns: Int,
    val rows: Int,
    val buttons: List<GridButton>,
    val backgroundColorArgb: Int? = null
)

// Returns (col, row) of the first unoccupied 1×1 cell, or null if the grid is full.
fun GridLayout.findFirstEmptyCell(): Pair<Int, Int>? {
    val occupied = buildSet {
        for (btn in buttons) {
            for (r in btn.row until btn.row + btn.rowSpan) {
                for (c in btn.col until btn.col + btn.colSpan) {
                    add(c to r)
                }
            }
        }
    }
    for (r in 0 until rows) {
        for (c in 0 until columns) {
            if ((c to r) !in occupied) return c to r
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
