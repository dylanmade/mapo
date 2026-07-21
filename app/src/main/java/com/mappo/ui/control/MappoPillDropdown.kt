package com.mappo.ui.control

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Mappo's pill-style dropdown picker: the current option's (optional) glyph + label on a
 * beveled pill, opening a menu of options with a check on the current one. The generic
 * behind the remap screen's mode/strip pickers.
 *
 * @param optionLabel label for an option (pill + menu rows).
 * @param optionIcon menu-row leading glyph per option; null lambda result = no glyph.
 * @param pillIcon glyph shown ON the pill — the caller decides (e.g. current option's
 *   glyph, a fixed identity icon, or null for none); the pill derives nothing itself.
 * @param overline renders the pill label in the overline treatment (uppercase, tracked out).
 * @param elevated topmost-plane fill for pills sitting on a box/card background.
 * @param fixedWidth pins the pill to a static footprint instead of flexing to the label.
 */
@Composable
fun <T> MappoPillDropdown(
    current: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    optionIcon: (@Composable (T) -> Painter?)? = null,
    pillIcon: Painter? = null,
    overline: Boolean = false,
    elevated: Boolean = false,
    fixedWidth: Dp? = null,
    onClickLabel: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val container = if (elevated) MappoElevatedContainer else mappoBoxContainer()
    val interaction = remember { MutableInteractionSource() }
    // Fixed-width pills center their content, which exposes the icon's live-area padding as a
    // visibly wider left flank — bias the block toward the icon ([MappoPillIconSideBias]).
    val iconBias = if (fixedWidth != null && pillIcon != null) MappoPillIconSideBias else 0.dp
    Box {
        // Shared box treatment — pill-style dropdown button, no trailing arrow.
        Surface(
            shape = RoundedCornerShape(50),
            color = container,
            border = mappoBevelBorder(container, MappoPillHeight / 2),
            modifier = modifier
                .mappoInteractiveMotion(interaction)
                .heightIn(min = MappoPillHeight)
                .then(
                    if (fixedWidth != null) Modifier.width(fixedWidth)
                    else Modifier.widthIn(min = MappoPillMinWidth),
                )
                .then(
                    if (enabled) {
                        Modifier.clip(RoundedCornerShape(50)).clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                            onClickLabel = onClickLabel,
                        ) { open = true }
                    } else Modifier.alpha(0.6f),
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(
                    start = MappoPillContentPadding - iconBias / 2,
                    end = MappoPillContentPadding + iconBias / 2,
                ),
            ) {
                if (pillIcon != null) {
                    Icon(
                        pillIcon,
                        contentDescription = null,
                        modifier = Modifier.size(MappoPillIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(MappoGlyphLabelGap))
                }
                Text(
                    text = optionLabel(current).let { if (overline) it.uppercase() else it },
                    style = if (overline) mappoOverlineTextStyle() else mappoMiniTextStyle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = MappoPillLabelMaxWidth),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                val menuIcon = optionIcon?.invoke(option)
                DropdownMenuItem(
                    leadingIcon = menuIcon?.let { { Icon(it, contentDescription = null) } },
                    text = { Text(optionLabel(option)) },
                    trailingIcon = if (option == current) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                    onClick = { open = false; if (option != current) onPick(option) },
                )
            }
        }
    }
}
