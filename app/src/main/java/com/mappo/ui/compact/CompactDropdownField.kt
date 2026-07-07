package com.mappo.ui.compact

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A compact read-only dropdown field with the label notched into the outline — the M3
 * `ExposedDropdownMenuBox` look, but at a density-driven height instead of stock
 * `OutlinedTextField`'s baked-in ~56dp.
 *
 * M3's `OutlinedTextField` reserves the tall height precisely *because* of the floating
 * label and its notch animation, so there's no compact knob for it (the same reason
 * [CompactTextField] drops the floating label entirely). Here we keep the notched label
 * but hand-roll it: the field is a bordered [Row] whose height comes from
 * [LocalCompactDensity], and the label is a sibling [Text] with an **opaque background**
 * ([labelBackground]) that straddles and masks the top border segment — the cheap, stable
 * way to fake the notch without the decoration-box machinery that churns across material3
 * alphas.
 *
 * Selection only: there is no text entry. The simple form takes [options] as `(key, label)`
 * pairs (the [selectedKey] entry gets a trailing check). For a richer menu — leading icons,
 * per-row kebabs, footer actions — pass [menuContent] instead and render your own rows
 * (e.g. [CompactDropdownMenuItem]s); it receives a `dismiss` callback to close the menu.
 *
 * @param labelBackground the color the notch paints over to "cut" the border — must match
 *   whatever is *behind* the field (e.g. the app-bar / surface color) or the mask shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactDropdownField(
    label: String,
    selectedText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fieldTestTag: String? = null,
    labelBackground: Color = MaterialTheme.colorScheme.surface,
    options: List<Pair<String, String>> = emptyList(),
    selectedKey: String? = null,
    onPick: (String) -> Unit = {},
    /** Optional glyph shown at the leading edge of the field, before [selectedText]. */
    selectedLeadingIcon: (@Composable () -> Unit)? = null,
    menuContent: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
) {
    val density = LocalCompactDensity.current
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraSmall
    var expanded by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.outline.copy(alpha = 0.38f)
            expanded -> colors.primary
            else -> colors.outline
        },
        label = "compactDropdownBorder",
    )
    val contentColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)
    val labelColor = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
        expanded -> colors.primary
        else -> colors.onSurfaceVariant
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        // Box stacks the notched label over the field's top border. Reserve [LabelStraddle]
        // of top padding so the label has room to poke above the border line.
        Box {
            Row(
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                    .then(if (fieldTestTag != null) Modifier.testTag(fieldTestTag) else Modifier)
                    .padding(top = LabelStraddle)
                    .fillMaxWidth()
                    .border(width = if (expanded) 2.dp else 1.dp, color = borderColor, shape = shape)
                    .heightIn(min = density.fieldMinHeight)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectedLeadingIcon != null) {
                    selectedLeadingIcon()
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = selectedText,
                    // Match the menu rows (CompactDropdownMenuItem also uses compactLabelStyle).
                    style = compactLabelStyle(),
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = labelColor,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                )
            }
            // The notch: an opaque label that overlaps + masks the border behind it.
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = LabelInset)
                    .background(labelBackground)
                    .padding(horizontal = 4.dp),
            )
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (menuContent != null) {
                menuContent { expanded = false }
            } else {
                options.forEach { (key, text) ->
                    CompactDropdownMenuItem(
                        text = text,
                        onClick = {
                            expanded = false
                            if (key != selectedKey) onPick(key)
                        },
                        trailingIcon = if (key == selectedKey) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.padding(start = 8.dp)) }
                        } else null,
                    )
                }
            }
        }
    }
}

/** Vertical overlap of the notched label onto the top border. */
private val LabelStraddle = 6.dp

/** Horizontal inset of the notch from the field's leading edge (mirrors M3's ~12dp label x). */
private val LabelInset = 8.dp
