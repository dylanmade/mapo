package com.themestudio.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Grid of all Material 3 color roles in the active scheme. For each role, we
 * pair the role color with its semantic on-color when present (e.g. primary +
 * onPrimary) so contrast is visible. Drop this into a [ThemeStudioScreen]'s
 * preview slot to see every token's current value at a glance.
 */
@Composable
fun ColorRoleSwatches(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val pairs = remember(scheme) { buildPairs(scheme) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(pairs, key = { it.name }) { pair ->
            SwatchChip(pair)
        }
    }
}

@Composable
private fun SwatchChip(pair: SwatchPair) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(pair.background, RoundedCornerShape(6.dp))
            .border(1.dp, outline, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Text(
            text = pair.name,
            color = pair.foreground,
            fontSize = 10.sp,
        )
    }
}

private data class SwatchPair(
    val name: String,
    val background: Color,
    val foreground: Color,
)

private fun buildPairs(s: ColorScheme): List<SwatchPair> = listOf(
    SwatchPair("primary", s.primary, s.onPrimary),
    SwatchPair("primaryContainer", s.primaryContainer, s.onPrimaryContainer),
    SwatchPair("secondary", s.secondary, s.onSecondary),
    SwatchPair("secondaryContainer", s.secondaryContainer, s.onSecondaryContainer),
    SwatchPair("tertiary", s.tertiary, s.onTertiary),
    SwatchPair("tertiaryContainer", s.tertiaryContainer, s.onTertiaryContainer),
    SwatchPair("background", s.background, s.onBackground),
    SwatchPair("surface", s.surface, s.onSurface),
    SwatchPair("surfaceVariant", s.surfaceVariant, s.onSurfaceVariant),
    SwatchPair("surfaceContainerLowest", s.surfaceContainerLowest, s.onSurface),
    SwatchPair("surfaceContainerLow", s.surfaceContainerLow, s.onSurface),
    SwatchPair("surfaceContainer", s.surfaceContainer, s.onSurface),
    SwatchPair("surfaceContainerHigh", s.surfaceContainerHigh, s.onSurface),
    SwatchPair("surfaceContainerHighest", s.surfaceContainerHighest, s.onSurface),
    SwatchPair("surfaceBright", s.surfaceBright, s.onSurface),
    SwatchPair("surfaceDim", s.surfaceDim, s.onSurface),
    SwatchPair("error", s.error, s.onError),
    SwatchPair("errorContainer", s.errorContainer, s.onErrorContainer),
    SwatchPair("inverseSurface", s.inverseSurface, s.inverseOnSurface),
    SwatchPair("outline", s.outline, autoForeground(s.outline)),
    SwatchPair("outlineVariant", s.outlineVariant, autoForeground(s.outlineVariant)),
    SwatchPair("scrim", s.scrim, autoForeground(s.scrim)),
)

private fun autoForeground(bg: Color): Color =
    if (bg.luminance() > 0.5f) Color.Black else Color.White
