package com.mappo.ui.compact

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.mappo.ui.imeActivation
import com.mappo.ui.mappoKeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Which height/padding a [CompactTextField] uses. [Standard] follows the ambient
 * [LocalCompactDensity] (so it matches the screen's chosen density); [Slim] is always the
 * fixed ~40dp field, for the occasional call site that wants a shorter field than the
 * surrounding screen — part of the component repertoire, not the default.
 */
enum class CompactFieldSize { Standard, Slim }

/** Height of the [CompactFieldSize.Slim] field. */
private val SlimFieldMinHeight = 40.dp

/**
 * Padding for the [CompactFieldSize.Slim] field. Horizontal inset matches the standard field
 * (16dp) so placeholder/text start at the same x; vertical is tightened to keep it short.
 */
private val SlimFieldContentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/**
 * A compact single-line-friendly text field.
 *
 * M3's `OutlinedTextField` / `TextField` have no compact variant — their ~56dp height is
 * baked into the decoration box and the floating-label animation, and the only public knob
 * (`OutlinedTextFieldDefaults.DecorationBox(contentPadding = …)`) churns across material3
 * alphas. So this is built directly on [BasicTextField] inside a themed container, which
 * gives full control of height + padding from [LocalCompactDensity] and stays stable across
 * library bumps.
 *
 * Deliberate compactness trade-off vs stock M3: there is **no floating label**. A floating
 * label needs reserved vertical space above the text and is the single biggest reason the
 * stock field is tall. Use [placeholder] for in-field hint text, or [label] for a small
 * static caption rendered *above* the field (it does not animate into the border).
 *
 * @param outlined true for an outlined container (border), false for a filled container
 *   (tonal background, no border) — mirrors the M3 OutlinedTextField / TextField split.
 * @param size [CompactFieldSize.Standard] takes its height + padding from the ambient density;
 *   [CompactFieldSize.Slim] forces the always-compact (~40dp) field regardless of density, for
 *   when a particular call site wants a tighter field than the screen's default.
 * @param contentPadding overrides the size-derived inner padding when non-null — e.g. to trim the
 *   padding on the icon side of a trailing-icon field.
 */
@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    outlined: Boolean = true,
    size: CompactFieldSize = CompactFieldSize.Standard,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val density = LocalCompactDensity.current
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraSmall

    // Standard = follow the ambient density; Slim = always the fixed slim metrics, so a single
    // call can opt into a shorter field than the surrounding screen uses. Slim keeps the same
    // 16dp horizontal inset as the standard field (so placeholder/text start at the same x);
    // only its height + vertical padding are tightened.
    val fieldMinHeight = when (size) {
        CompactFieldSize.Standard -> density.fieldMinHeight
        CompactFieldSize.Slim -> SlimFieldMinHeight
    }
    // [contentPadding] overrides the size-derived inset when a call site needs to tune it (e.g.
    // a trailing-icon field that wants less padding on the icon side).
    val fieldContentPadding = contentPadding ?: when (size) {
        CompactFieldSize.Standard -> density.fieldContentPadding
        CompactFieldSize.Slim -> SlimFieldContentPadding
    }

    val focused by interactionSource.collectIsFocusedAsState()

    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.outline.copy(alpha = 0.38f)
            isError -> colors.error
            focused -> colors.primary
            else -> colors.outline
        },
        label = "compactFieldBorder",
    )
    val contentColor =
        if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)

    val baseStyle: TextStyle = compactBodyStyle().merge(LocalTextStyle.current)
    val textStyle = baseStyle.copy(color = contentColor)

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isError) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }

        // Container box: outlined → 1dp border (2dp focused); filled → tonal background.
        val containerModifier = if (outlined) {
            Modifier.border(
                width = if (focused) 2.dp else 1.dp,
                color = borderColor,
                shape = shape,
            )
        } else {
            Modifier.background(
                // surfaceContainerHighest — filled text-field container role.
                color = colors.surfaceContainerHighest.copy(
                    alpha = if (enabled) 1f else 0.38f,
                ).compositeOver(colors.surface),
                shape = shape,
            )
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            // App-wide IME policy: no keyboard on gamepad focus; activator key opens it.
            modifier = Modifier.imeActivation(),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle,
            cursorBrush = SolidColor(if (isError) colors.error else colors.primary),
            keyboardOptions = mappoKeyboardOptions(keyboardOptions),
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            singleLine = singleLine,
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .then(containerModifier)
                        .heightIn(min = fieldMinHeight)
                        .padding(fieldContentPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (leadingIcon != null) {
                        DecorationIcon(enabled, isError, leadingIcon)
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = textStyle.copy(color = colors.onSurfaceVariant),
                                maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                            )
                        }
                        innerTextField()
                    }
                    if (trailingIcon != null) {
                        DecorationIcon(enabled, isError, trailingIcon)
                    }
                }
            },
        )
    }
}

@Composable
private fun DecorationIcon(
    enabled: Boolean,
    isError: Boolean,
    icon: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
        isError -> colors.error
        else -> colors.onSurfaceVariant
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides tint,
        content = icon,
    )
}
