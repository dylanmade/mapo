package com.mappo.ui.compact

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider

/**
 * A dense list / settings row.
 *
 * The M3 `ListItem` bakes its 56dp (one-line) / 72dp (two-line) / 88dp (three-line) heights
 * into non-overridable tokens, so it's the textbook case where teams hand-roll a row. This is
 * that row, parameterised off [LocalCompactDensity]: a leading slot, a headline + optional
 * supporting line, and a trailing slot, all vertically centered with a density-driven height
 * floor and insets.
 *
 * Per the project's list-row doctrine this is the **settings / browse** treatment in compact
 * form — pick it when a screen has many short rows that the stock ListItem makes too tall. It
 * is not a replacement for the selection-state row (use the M3 one where selection coloring
 * matters).
 *
 * @param onClick when non-null the whole row is clickable; the hit target obeys the density's
 *   [CompactDensity.enforceMinTouchTarget] choice via the ambient minimum-interactive size.
 */
@Composable
fun CompactListItem(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
) {
    val density = LocalCompactDensity.current
    val colors = MaterialTheme.colorScheme

    // Modifier order matters here: the height floor is applied *outside* the padding (before it
    // in the chain), so it bounds the whole row, not the content. That keeps the vertical
    // padding constant on every row — the row grows naturally with its content (multi-line text,
    // a trailing switch) while the top/bottom whitespace stays identical. [listItemMinHeight] is
    // only a tappability floor that adds slack for rows shorter than it (a one-line text row at
    // this padding typically already clears it). Background/clickable sit outermost so the fill +
    // tap target cover the whole row including any floor-added height.
    val rowModifier = modifier
        .fillMaxWidth()
        .then(
            if (containerColor != Color.Unspecified) Modifier.background(containerColor)
            else Modifier,
        )
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .heightIn(min = density.listItemMinHeight)
        .padding(
            horizontal = density.listItemPaddingHorizontal,
            vertical = density.listItemPaddingVertical,
        )

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        // spacedBy only inserts gaps *between* adjacent children, so it's a no-op when the
        // headline column is the only slot present.
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) {
            CompositionLocalProvider(
                LocalContentColor provides colors.onSurfaceVariant,
                content = leading,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(density.listItemTextSpacing),
        ) {
            ProvideTextStyle(compactBodyStyle()) {
                Text(headline, color = colors.onSurface)
            }
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            CompositionLocalProvider(
                LocalContentColor provides colors.onSurfaceVariant,
                content = trailing,
            )
        }
    }
}
