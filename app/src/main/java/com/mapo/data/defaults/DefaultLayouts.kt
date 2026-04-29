package com.mapo.data.defaults

import com.mapo.data.model.GridButton
import com.mapo.data.model.GridLayout
import com.mapo.data.model.KeyDef
import com.mapo.data.model.LayoutDef
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
                            colSpan = max(1, (colF + spanF).roundToInt() - colF.roundToInt()),
                            topText = key.topText.ifEmpty { null }
                        )
                    )
                }
                colF += spanF
            }
        }
        return GridLayout(name = name, columns = gridCols, rows = rows.size, buttons = buttons)
    }

    // ── Row-based source definitions ──────────────────────────────────────────
    private fun k(label: String, code: String, weight: Float = 1f, topText: String = "") = KeyDef(label, code, weight, topText)
    private fun gap(weight: Float = 1f) = KeyDef("", "", weight)

    // Key width tiers: standard=1.0, slightly wider=1.5, wider=2.0, space=4.0
    private val keysMainDef = LayoutDef(
        name = "Keys Main",
        rows = listOf(
            listOf(
                k("Esc", "ESCAPE"), k("F1", "F1"), k("F2", "F2"), k("F3", "F3"), k("F4", "F4"),
                k("F5", "F5"), k("F6", "F6"), k("F7", "F7"), k("F8", "F8"),
                k("F9", "F9"), k("F10", "F10"), k("F11", "F11"), k("F12", "F12")
            ),
            listOf(
                k("`",  "GRAVE",         topText = "~"),
                k("1",  "1",             topText = "!"),
                k("2",  "2",             topText = "@"),
                k("3",  "3",             topText = "#"),
                k("4",  "4",             topText = "$"),
                k("5",  "5",             topText = "%"),
                k("6",  "6",             topText = "^"),
                k("7",  "7",             topText = "&"),
                k("8",  "8",             topText = "*"),
                k("9",  "9",             topText = "("),
                k("0",  "0",             topText = ")"),
                k("-",  "MINUS",         topText = "_"),
                k("=",  "EQUALS",        topText = "+"),
                k("⌫",  "BACKSPACE", 1.5f)
            ),
            listOf(
                k("Tab", "TAB", 1.5f),
                k("Q", "Q"), k("W", "W"), k("E", "E"), k("R", "R"),
                k("T", "T"), k("Y", "Y"), k("U", "U"), k("I", "I"), k("O", "O"), k("P", "P"),
                k("[",  "LEFT_BRACKET",  topText = "{"),
                k("]",  "RIGHT_BRACKET", topText = "}"),
                k("\\", "BACKSLASH", 1.5f, topText = "|")
            ),
            listOf(
                k("Caps", "CAPS_LOCK", 1.5f),
                k("A", "A"), k("S", "S"), k("D", "D"), k("F", "F"),
                k("G", "G"), k("H", "H"), k("J", "J"), k("K", "K"), k("L", "L"),
                k(";",  "SEMICOLON",  topText = ":"),
                k("'",  "APOSTROPHE", topText = "\""),
                k("↵",  "ENTER", 2.0f)
            ),
            listOf(
                k("Shift", "SHIFT_LEFT", 2.0f),
                k("Z", "Z"), k("X", "X"), k("C", "C"), k("V", "V"),
                k("B", "B"), k("N", "N"), k("M", "M"),
                k(",",  "COMMA",  topText = "<"),
                k(".",  "PERIOD", topText = ">"),
                k("/",  "SLASH",  topText = "?"),
                k("Shift", "SHIFT_RIGHT", 2.0f)
            ),
            listOf(
                k("Ctrl", "CTRL_LEFT"), k("Win", "META_LEFT"),
                k("Alt", "ALT_LEFT"), k("Space", "SPACE", 4f),
                k("Alt", "ALT_RIGHT"), k("Menu", "MENU"),
                k("Ctrl", "CTRL_RIGHT")
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
