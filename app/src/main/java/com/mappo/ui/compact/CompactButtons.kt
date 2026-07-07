package com.mappo.ui.compact

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Compact wrappers over the M3 button family. M3 buttons already expose `contentPadding`,
 * so the only forking needed is a smaller padding + a height floor — both read from
 * [LocalCompactDensity]. Labels use [compactLabelStyle] so the type tracks the density's
 * smaller-text choice. Signatures intentionally mirror the M3 originals so call sites can be
 * swapped one-for-one.
 */

/**
 * Which height/padding a compact button uses. [Standard] follows the ambient
 * [LocalCompactDensity] (so it matches the screen's chosen density); [Slim] is always the
 * fixed, tighter button, for the occasional call site that wants a smaller button than the
 * surrounding screen — part of the component repertoire, not the default. Parallels
 * [CompactFieldSize].
 */
enum class CompactButtonSize { Standard, Slim }

// Fixed metrics for [CompactButtonSize.Slim], decoupled from any density preset.
private val SlimButtonMinHeight = 36.dp
private val SlimButtonContentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
private val SlimTextButtonContentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
private val SlimIconButtonSize = 36.dp

private fun CompactButtonSize.minHeight(density: CompactDensity): Dp = when (this) {
    CompactButtonSize.Standard -> density.buttonMinHeight
    CompactButtonSize.Slim -> SlimButtonMinHeight
}

private fun CompactButtonSize.contentPadding(density: CompactDensity): PaddingValues = when (this) {
    CompactButtonSize.Standard -> density.buttonContentPadding
    CompactButtonSize.Slim -> SlimButtonContentPadding
}

private fun CompactButtonSize.textContentPadding(): PaddingValues = when (this) {
    CompactButtonSize.Standard -> ButtonDefaults.TextButtonContentPadding
    CompactButtonSize.Slim -> SlimTextButtonContentPadding
}

private fun CompactButtonSize.iconButtonSize(density: CompactDensity): Dp = when (this) {
    CompactButtonSize.Standard -> density.iconButtonSize
    CompactButtonSize.Slim -> SlimIconButtonSize
}

@Composable
fun CompactButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: CompactButtonSize = CompactButtonSize.Standard,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    val density = LocalCompactDensity.current
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = size.minHeight(density)),
        enabled = enabled,
        colors = colors,
        contentPadding = size.contentPadding(density),
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
    size: CompactButtonSize = CompactButtonSize.Standard,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    val density = LocalCompactDensity.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = size.minHeight(density)),
        enabled = enabled,
        contentPadding = size.contentPadding(density),
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
    size: CompactButtonSize = CompactButtonSize.Standard,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    val density = LocalCompactDensity.current
    // Text buttons sit tighter than contained buttons in M3 (half the horizontal padding).
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = size.minHeight(density)),
        enabled = enabled,
        contentPadding = size.textContentPadding(),
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
    size: CompactButtonSize = CompactButtonSize.Standard,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    val density = LocalCompactDensity.current
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = size.minHeight(density)),
        enabled = enabled,
        contentPadding = size.contentPadding(density),
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
 * this is hand-rolled as a clickable, circular state-layer box sized to the resolved icon-button
 * size ([CompactDensity.iconButtonSize] for [CompactButtonSize.Standard], a fixed smaller size
 * for [CompactButtonSize.Slim]). Whether the *hit* target still expands to 48dp is governed by
 * [CompactDensity.enforceMinTouchTarget] via the ambient minimum-interactive size, which the
 * surrounding [ProvideCompactDensity] sets.
 */
@Composable
fun CompactIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: CompactButtonSize = CompactButtonSize.Standard,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    val density = LocalCompactDensity.current
    val boxSize = size.iconButtonSize(density)
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(boxSize)
            .clip(CircleShape)
            .clickable(
                onClick = onClick,
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = ripple(bounded = false, radius = boxSize / 2),
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
 * Convenience: a [CompactIconButton] wrapping a single [Icon] sized to the button, so call
 * sites don't repeat the icon-sizing boilerplate.
 */
@Composable
fun CompactIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: CompactButtonSize = CompactButtonSize.Standard,
) {
    val density = LocalCompactDensity.current
    // Glyph at ~55% of the box, matching M3's 24dp-glyph-in-~40dp-layer ratio.
    val glyph = (size.iconButtonSize(density).value * 0.55f).coerceAtLeast(16f).dp
    CompactIconButton(onClick = onClick, modifier = modifier, enabled = enabled, size = size) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(glyph),
        )
    }
}

/**
 * A compact **filled-tonal** icon button — like [CompactIconButton] but with a `secondaryContainer`
 * pill behind the glyph (M3's `FilledTonalIconButton` look) at compact metrics. The stock
 * `FilledTonalIconButton` hard-sets its own size, so this is hand-rolled the same way [CompactIconButton] is.
 */
@Composable
fun CompactFilledTonalIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: CompactButtonSize = CompactButtonSize.Standard,
) {
    val density = LocalCompactDensity.current
    val boxSize = size.iconButtonSize(density)
    val glyph = (boxSize.value * 0.5f).coerceAtLeast(16f).dp
    val container = if (enabled) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val contentColor = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(boxSize)
            .clip(CircleShape)
            .background(container)
            .clickable(
                onClick = onClick,
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = boxSize / 2),
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(glyph))
        }
    }
}
