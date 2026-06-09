package com.mapo.ui.compact

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Compact wrappers over the M3 button family. M3 buttons already expose `contentPadding`,
 * so the only forking needed is a smaller padding + a height floor — both read from
 * [LocalCompactDensity]. Labels use [compactLabelStyle] so the type tracks the density's
 * smaller-text choice. Signatures intentionally mirror the M3 originals so call sites can be
 * swapped one-for-one.
 */

@Composable
fun CompactButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    val density = LocalCompactDensity.current
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = density.buttonMinHeight),
        enabled = enabled,
        colors = colors,
        contentPadding = density.buttonContentPadding,
        interactionSource = interactionSource,
    ) {
        val scope = this
        ProvideTextStyle(compactLabelStyle()) { scope.content() }
    }
}

@Composable
fun CompactOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    val density = LocalCompactDensity.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = density.buttonMinHeight),
        enabled = enabled,
        contentPadding = density.buttonContentPadding,
        interactionSource = interactionSource,
    ) {
        val scope = this
        ProvideTextStyle(compactLabelStyle()) { scope.content() }
    }
}

@Composable
fun CompactTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    val density = LocalCompactDensity.current
    // Text buttons sit tighter than contained buttons in M3 (half the horizontal padding).
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = density.buttonMinHeight),
        enabled = enabled,
        contentPadding = ButtonDefaults.TextButtonContentPadding,
        interactionSource = interactionSource,
    ) {
        val scope = this
        ProvideTextStyle(compactLabelStyle()) { scope.content() }
    }
}

@Composable
fun CompactFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    val density = LocalCompactDensity.current
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = density.buttonMinHeight),
        enabled = enabled,
        contentPadding = density.buttonContentPadding,
        interactionSource = interactionSource,
    ) {
        val scope = this
        ProvideTextStyle(compactLabelStyle()) { scope.content() }
    }
}

/**
 * A compact icon button.
 *
 * The M3 [androidx.compose.material3.IconButton] hard-sets its own 40dp state-layer size
 * *after* the caller's modifier, so a passed-in size can't actually shrink it — which is why
 * this is hand-rolled as a clickable, circular state-layer box sized to
 * [CompactDensity.iconButtonSize]. Whether the *hit* target still expands to 48dp is governed
 * by [CompactDensity.enforceMinTouchTarget] via the ambient minimum-interactive size, which
 * the surrounding [ProvideCompactDensity] sets.
 */
@Composable
fun CompactIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    val density = LocalCompactDensity.current
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(density.iconButtonSize)
            .clip(CircleShape)
            .clickable(
                onClick = onClick,
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = ripple(bounded = false, radius = density.iconButtonSize / 2),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val contentColor =
            if (enabled) LocalContentColor.current
            else LocalContentColor.current.copy(alpha = 0.38f)
        CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
    }
}

/**
 * Convenience: a [CompactIconButton] wrapping a single [Icon] sized to the density, so call
 * sites don't repeat the icon-sizing boilerplate.
 */
@Composable
fun CompactIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val density = LocalCompactDensity.current
    // Glyph at ~55% of the box, matching M3's 24dp-glyph-in-~40dp-layer ratio.
    val glyph = (density.iconButtonSize.value * 0.55f).coerceAtLeast(16f).dp
    CompactIconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(glyph),
        )
    }
}
