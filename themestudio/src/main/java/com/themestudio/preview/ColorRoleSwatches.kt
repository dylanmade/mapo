package com.themestudio.preview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Comprehensive grid of every Material 3 color role in the active scheme.
 *
 * Each "group" is rendered as a stack: the role color as a chip background
 * with its `on*` color as the chip text. Both halves are independently
 * interactive — tap the chip to edit the role; tap the small "label area"
 * to edit the on-color counterpart. The result: every one of the ~48 M3
 * color roles is reachable from this grid.
 *
 * Lays out as a 2-column non-lazy grid that fills the available width, so
 * chip text doesn't wrap and the helper composes safely inside parents
 * using `Modifier.verticalScroll`.
 */
@Composable
fun ColorRoleSwatches(
    modifier: Modifier = Modifier,
    onPickRole: ((roleName: String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val groups = remember(scheme) { buildGroups(scheme) }

    Column(
        modifier = modifier.fillMaxWidth().padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (rowChunk in groups.chunked(4)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rowChunk.forEach { group ->
                    SwatchChip(group, onPickRole, modifier = Modifier.weight(1f))
                }
                if (rowChunk.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwatchChip(
    group: SwatchGroup,
    onPickRole: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outline
    val onTapMain: (() -> Unit)? = onPickRole?.let { { it(group.mainName) } }
    val onTapOn: (() -> Unit)? = group.onName?.let { name -> onPickRole?.let { { it(name) } } }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(group.background, RoundedCornerShape(6.dp))
            .border(1.dp, outline, RoundedCornerShape(6.dp)),
    ) {
        // Top half: main role label, taps edit main color
        Box(
            modifier = (if (onTapMain != null)
                Modifier.combinedClickable(
                    onClick = onTapMain,
                    onLongClick = onTapMain,
                ) else Modifier)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                text = group.mainName,
                color = group.foreground,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Bottom half (only if on-color exists): on-role label on a thin
        // strip of the on-color, taps edit on-color
        if (group.onName != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(group.foreground)
                    .let { base ->
                        if (onTapOn != null)
                            base.combinedClickable(
                                onClick = onTapOn,
                                onLongClick = onTapOn,
                            )
                        else base
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = group.onName,
                    color = group.background,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class SwatchGroup(
    val mainName: String,
    val background: Color,
    val onName: String?,
    val foreground: Color,
)

private fun buildGroups(s: ColorScheme): List<SwatchGroup> = listOf(
    SwatchGroup("primary", s.primary, "onPrimary", s.onPrimary),
    SwatchGroup("primaryContainer", s.primaryContainer, "onPrimaryContainer", s.onPrimaryContainer),
    SwatchGroup("secondary", s.secondary, "onSecondary", s.onSecondary),
    SwatchGroup("secondaryContainer", s.secondaryContainer, "onSecondaryContainer", s.onSecondaryContainer),
    SwatchGroup("tertiary", s.tertiary, "onTertiary", s.onTertiary),
    SwatchGroup("tertiaryContainer", s.tertiaryContainer, "onTertiaryContainer", s.onTertiaryContainer),
    SwatchGroup("background", s.background, "onBackground", s.onBackground),
    SwatchGroup("surface", s.surface, "onSurface", s.onSurface),
    SwatchGroup("surfaceVariant", s.surfaceVariant, "onSurfaceVariant", s.onSurfaceVariant),
    SwatchGroup("surfaceContainerLowest", s.surfaceContainerLowest, null, s.onSurface),
    SwatchGroup("surfaceContainerLow", s.surfaceContainerLow, null, s.onSurface),
    SwatchGroup("surfaceContainer", s.surfaceContainer, null, s.onSurface),
    SwatchGroup("surfaceContainerHigh", s.surfaceContainerHigh, null, s.onSurface),
    SwatchGroup("surfaceContainerHighest", s.surfaceContainerHighest, null, s.onSurface),
    SwatchGroup("surfaceBright", s.surfaceBright, null, s.onSurface),
    SwatchGroup("surfaceDim", s.surfaceDim, null, s.onSurface),
    SwatchGroup("surfaceTint", s.surfaceTint, null, autoForeground(s.surfaceTint)),
    SwatchGroup("inverseSurface", s.inverseSurface, "inverseOnSurface", s.inverseOnSurface),
    SwatchGroup("inversePrimary", s.inversePrimary, null, autoForeground(s.inversePrimary)),
    SwatchGroup("error", s.error, "onError", s.onError),
    SwatchGroup("errorContainer", s.errorContainer, "onErrorContainer", s.onErrorContainer),
    SwatchGroup("outline", s.outline, null, autoForeground(s.outline)),
    SwatchGroup("outlineVariant", s.outlineVariant, null, autoForeground(s.outlineVariant)),
    SwatchGroup("scrim", s.scrim, null, autoForeground(s.scrim)),
    SwatchGroup("primaryFixed", s.primaryFixed, "onPrimaryFixed", s.onPrimaryFixed),
    SwatchGroup("primaryFixedDim", s.primaryFixedDim, "onPrimaryFixedVariant", s.onPrimaryFixedVariant),
    SwatchGroup("secondaryFixed", s.secondaryFixed, "onSecondaryFixed", s.onSecondaryFixed),
    SwatchGroup("secondaryFixedDim", s.secondaryFixedDim, "onSecondaryFixedVariant", s.onSecondaryFixedVariant),
    SwatchGroup("tertiaryFixed", s.tertiaryFixed, "onTertiaryFixed", s.onTertiaryFixed),
    SwatchGroup("tertiaryFixedDim", s.tertiaryFixedDim, "onTertiaryFixedVariant", s.onTertiaryFixedVariant),
)

private fun autoForeground(bg: Color): Color =
    if (bg.luminance() > 0.5f) Color.Black else Color.White
