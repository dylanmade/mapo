package com.pcpad.data.defaults

import com.pcpad.data.model.GridButton
import com.pcpad.data.model.GridLayout
import com.pcpad.data.model.KeyDef
import com.pcpad.data.model.LayoutDef
import kotlin.math.max
import kotlin.math.roundToInt

object DefaultLayouts {

    // ── Conversion helper ─────────────────────────────────────────────────────
    // Converts weight-based row layout to an absolute grid.
    // Each row's keys are scaled to fill [gridCols] columns proportionally.
    // Column positions are accumulated as floats to avoid rounding drift.
    private fun LayoutDef.toGridLayout(gridCols: Int): GridLayout {
        val buttons = mutableListOf<GridButton>()
        rows.forEachIndexed { rowIndex, row ->
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            var colF = 0f
            row.forEach { key ->
                val spanF = key.weight / totalWeight * gridCols
                if (key.code.isNotEmpty()) {
                    buttons.add(
                        GridButton(
                            label = key.label,
                            code = key.code,
                            col = colF.roundToInt(),
                            row = rowIndex,
                            colSpan = max(1, (colF + spanF).roundToInt() - colF.roundToInt())
                        )
                    )
                }
                colF += spanF
            }
        }
        return GridLayout(name = name, columns = gridCols, rows = rows.size, buttons = buttons)
    }

    // ── Row-based source definitions ──────────────────────────────────────────
    private fun k(label: String, code: String, weight: Float = 1f) = KeyDef(label, code, weight)
    private fun gap(weight: Float = 1f) = KeyDef("", "", weight)

    private val keysMainDef = LayoutDef(
        name = "Keys Main",
        rows = listOf(
            listOf(
                k("Esc", "ESCAPE"), k("F1", "F1"), k("F2", "F2"), k("F3", "F3"), k("F4", "F4"),
                k("F5", "F5"), k("F6", "F6"), k("F7", "F7"), k("F8", "F8"),
                k("F9", "F9"), k("F10", "F10"), k("F11", "F11"), k("F12", "F12")
            ),
            listOf(
                k("`", "GRAVE"), k("1", "1"), k("2", "2"), k("3", "3"), k("4", "4"),
                k("5", "5"), k("6", "6"), k("7", "7"), k("8", "8"), k("9", "9"),
                k("0", "0"), k("-", "MINUS"), k("=", "EQUALS"), k("⌫", "BACKSPACE", 2f)
            ),
            listOf(
                k("Tab", "TAB", 1.5f), k("Q", "Q"), k("W", "W"), k("E", "E"), k("R", "R"),
                k("T", "T"), k("Y", "Y"), k("U", "U"), k("I", "I"), k("O", "O"), k("P", "P"),
                k("[", "LEFT_BRACKET"), k("]", "RIGHT_BRACKET"), k("\\", "BACKSLASH", 1.5f)
            ),
            listOf(
                k("Caps", "CAPS_LOCK", 1.75f), k("A", "A"), k("S", "S"), k("D", "D"),
                k("F", "F"), k("G", "G"), k("H", "H"), k("J", "J"), k("K", "K"),
                k("L", "L"), k(";", "SEMICOLON"), k("'", "APOSTROPHE"), k("↵", "ENTER", 2.25f)
            ),
            listOf(
                k("Shift", "SHIFT_LEFT", 2.25f), k("Z", "Z"), k("X", "X"), k("C", "C"),
                k("V", "V"), k("B", "B"), k("N", "N"), k("M", "M"),
                k(",", "COMMA"), k(".", "PERIOD"), k("/", "SLASH"), k("Shift", "SHIFT_RIGHT", 2.75f)
            ),
            listOf(
                k("Ctrl", "CTRL_LEFT", 1.25f), k("Win", "META_LEFT", 1.25f),
                k("Alt", "ALT_LEFT", 1.25f), k("Space", "SPACE", 7.5f),
                k("Alt", "ALT_RIGHT", 1.25f), k("Menu", "MENU", 1.25f),
                k("Ctrl", "CTRL_RIGHT", 1.25f)
            )
        )
    )

    private val keysAltDef = LayoutDef(
        name = "Keys Alt",
        rows = listOf(
            listOf(k("PrtSc", "SYSRQ"), k("ScrLk", "SCROLL_LOCK"), k("Pause", "BREAK")),
            listOf(k("Ins", "INSERT"), k("Home", "MOVE_HOME"), k("PgUp", "PAGE_UP")),
            listOf(k("Del", "FORWARD_DEL"), k("End", "MOVE_END"), k("PgDn", "PAGE_DOWN")),
            listOf(gap(), k("↑", "DPAD_UP"), gap()),
            listOf(k("←", "DPAD_LEFT"), k("↓", "DPAD_DOWN"), k("→", "DPAD_RIGHT"))
        )
    )

    private val mouseDef = LayoutDef(
        name = "Mouse",
        rows = listOf(
            listOf(k("L Click", "MOUSE_LEFT"), k("M Click", "MOUSE_MIDDLE"), k("R Click", "MOUSE_RIGHT")),
            listOf(k("Scroll ↑", "SCROLL_UP", 1.5f), k("Scroll ↓", "SCROLL_DOWN", 1.5f)),
            listOf(k("Back", "MOUSE_BACK"), k("Forward", "MOUSE_FORWARD"))
        )
    )

    // ── Public GridLayout instances ───────────────────────────────────────────
    val keysMain: GridLayout = keysMainDef.toGridLayout(gridCols = 60)
    val keysAlt: GridLayout  = keysAltDef.toGridLayout(gridCols = 6)
    val mouse: GridLayout    = mouseDef.toGridLayout(gridCols = 6)

    val all: List<GridLayout> = listOf(keysMain, keysAlt, mouse)
}
