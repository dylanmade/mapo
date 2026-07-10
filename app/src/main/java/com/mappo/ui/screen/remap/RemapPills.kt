package com.mappo.ui.screen.remap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.InputSource
import com.mappo.data.model.steam.displayNameFor
import com.mappo.ui.glyph.InputGlyphs

/**
 * Compact hand-rolled chrome shared across the simplified Remap Controls screen (group boxes,
 * Gyro/Overlay strip). All metrics live here — never re-derive a private size constant per call
 * site. DELIBERATE M3 DEVIATION: sub-touch-target scale, accepted to fit the 1:1 viewport.
 */

/** Compressed body text for the simple view's rows and pills. */
@Composable
internal fun remapMiniTextStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp)

/** Height of the pill dropdowns (mode / overlay pickers). */
internal val RemapPillHeight = 20.dp

/** Icon edge inside the pills. */
internal val RemapPillIconSize = 11.dp

/** A hand-rolled miniature icon button (cogs etc.) — ripple-clipped circle, no 48dp halo. */
@Composable
internal fun RemapMiniIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier.alpha(0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The mode-selection pill: current mode glyph + name + dropdown arrow → menu of valid modes. */
@Composable
internal fun ModePillDropdown(
    source: InputSource,
    currentMode: BindingMode,
    validModes: List<BindingMode>,
    enabled: Boolean,
    onPick: (BindingMode) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        // surfaceContainerHigh — pill-style dropdown button, per the picker-pill convention.
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .heightIn(min = RemapPillHeight)
                .then(if (enabled) Modifier.clickable { open = true } else Modifier.alpha(0.6f)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 3.dp),
            ) {
                Icon(
                    InputGlyphs.modeIcon(currentMode),
                    contentDescription = null,
                    modifier = Modifier.size(RemapPillIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = currentMode.displayNameFor(source),
                    style = remapMiniTextStyle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 110.dp),
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = "Change input mode",
                    modifier = Modifier.size(13.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.outline,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            validModes.forEach { mode ->
                DropdownMenuItem(
                    leadingIcon = { Icon(InputGlyphs.modeIcon(mode), contentDescription = null) },
                    text = { Text(mode.displayNameFor(source)) },
                    trailingIcon = if (mode == currentMode) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                    onClick = { open = false; if (mode != currentMode) onPick(mode) },
                )
            }
        }
    }
}
