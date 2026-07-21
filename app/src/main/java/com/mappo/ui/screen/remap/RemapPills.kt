package com.mappo.ui.screen.remap

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.InputSource
import com.mappo.data.model.steam.displayNameFor
import com.mappo.ui.control.MappoPillDropdown
import com.mappo.ui.glyph.InputGlyphs

/**
 * Remap-specific pill chrome. The general control family (beveled pill buttons, icon
 * buttons, the generic pill dropdown, motion, and every shared metric) lives in
 * `com.mappo.ui.control` — this file holds only what's inherently about the remap domain.
 */

/** FIXED width of the Gyro/Overlay strip pills — a static, unified footprint (both pickers
 *  identical) instead of flexing to the selected value's label. */
internal val RemapStripPillWidth = 116.dp

/** The mode-selection pill: current mode glyph + name + dropdown arrow → menu of valid modes.
 *  [overline] renders the text in the overline treatment (uppercase, tracked out) for the
 *  group editor's header. [fixedWidth] pins the pill to a static footprint (the Gyro/Overlay
 *  strip) instead of flexing to the label. [leadingIcon] swaps the mode concept glyph for a
 *  FIXED identity icon (the strip pickers carry their element's identity inside the pill),
 *  shown for every mode including None. */
@Composable
internal fun ModePillDropdown(
    source: InputSource,
    currentMode: BindingMode,
    validModes: List<BindingMode>,
    enabled: Boolean,
    onPick: (BindingMode) -> Unit,
    overline: Boolean = false,
    elevated: Boolean = false,
    fixedWidth: Dp? = null,
    leadingIcon: androidx.compose.ui.graphics.painter.Painter? = null,
    modifier: Modifier = Modifier,
) {
    // "None" shows bare on the pill — an absence carries no concept glyph there. (The menu
    // ITEM keeps its glyph; in a list, the icon column reads as part of the option, not as a
    // claim about current state.) A fixed [leadingIcon] overrides both rules: it's the
    // element's identity, not the mode's.
    val pillIcon = leadingIcon
        ?: if (currentMode != BindingMode.NONE) InputGlyphs.modePainter(currentMode) else null
    MappoPillDropdown(
        current = currentMode,
        options = validModes,
        optionLabel = { it.displayNameFor(source) },
        onPick = onPick,
        enabled = enabled,
        optionIcon = { InputGlyphs.modePainter(it) },
        pillIcon = pillIcon,
        overline = overline,
        elevated = elevated,
        fixedWidth = fixedWidth,
        onClickLabel = "Change input mode",
        modifier = modifier,
    )
}
