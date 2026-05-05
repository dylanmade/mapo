package com.themestudio.preview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 * Wrapping grid of all Material 3 color roles in the active scheme. Each chip
 * pairs the role color with its semantic on-color (e.g. primary + onPrimary)
 * so contrast is visible.
 *
 * Uses [FlowRow] (not [androidx.compose.foundation.lazy.grid.LazyVerticalGrid])
 * specifically so the helper composes safely inside parents that use
 * `Modifier.verticalScroll` — the [com.themestudio.ui.ThemeStudioScreen]'s
 * preview pane is one such parent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorRoleSwatches(
    modifier: Modifier = Modifier,
    onPickRole: ((roleName: String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val pairs = remember(scheme) { buildPairs(scheme) }

    FlowRow(
        modifier = modifier.fillMaxWidth().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (pair in pairs) {
            SwatchChip(pair, onPickRole)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwatchChip(pair: SwatchPair, onPickRole: ((String) -> Unit)?) {
    val outline = MaterialTheme.colorScheme.outline
    val baseModifier = Modifier
        .width(110.dp)
        .background(pair.background, RoundedCornerShape(6.dp))
        .border(1.dp, outline, RoundedCornerShape(6.dp))
    val interactiveModifier = if (onPickRole != null) {
        baseModifier.combinedClickable(
            onClick = { onPickRole(pair.name) },
            onLongClick = { onPickRole(pair.name) },
        )
    } else {
        baseModifier
    }
    Box(
        modifier = interactiveModifier
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
