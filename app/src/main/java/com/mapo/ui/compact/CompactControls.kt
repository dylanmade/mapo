package com.mapo.ui.compact

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A compact [Switch]. The M3 Switch exposes no size token (track 52×32, thumb up to 28dp), so
 * the lever is [CompactDensity.switchScale] — but applied via [scaledLayout], which scales the
 * switch's *measured bounds* too, not just its drawing. That's the key difference from a plain
 * `Modifier.scale`: a 0.85× switch genuinely measures ~27dp tall and actually reclaims row
 * height, instead of leaving a phantom full-size box behind.
 *
 * When [CompactDensity.switchReserveMinTouch] is false, the switch also drops its 48dp
 * minimum-interactive reservation (via [LocalMinimumInteractiveComponentSize]). That's what
 * keeps a single-line row with a trailing switch from being pushed taller than a two-line text
 * row — the row height follows the text, not the switch. The trade-off is a smaller tap target
 * on the switch itself; rows that are wholly clickable still get a full-height hit area.
 */
@Composable
fun CompactSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val density = LocalCompactDensity.current
    val switch = @Composable {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier.scaledLayout(density.switchScale),
            enabled = enabled,
        )
    }
    if (density.switchReserveMinTouch) {
        switch()
    } else {
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
            content = switch,
        )
    }
}

/**
 * Scale [scale]× around the center, reporting the *scaled* size to the parent layout (so the
 * shrunk widget actually frees up space). A plain `Modifier.scale` only transforms drawing and
 * leaves the original measured bounds, which is exactly what we don't want for a dense row.
 */
/**
 * A compact [Checkbox]. M3's checkbox has no size token (its box is ~18dp inside a 40–48dp
 * minimum-interactive footprint), so this scales the box via [scaledLayout] (which shrinks the
 * measured bounds, reclaiming row space — not just the drawing) and drops the min-interactive
 * reservation. Intended for decorative use where the *row* owns the toggle (`onCheckedChange = null`),
 * so the smaller tap target on the box itself is fine.
 */
@Composable
fun CompactCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    scale: Float = 0.8f,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier.scaledLayout(scale),
            enabled = enabled,
        )
    }
}

private fun Modifier.scaledLayout(scale: Float): Modifier =
    if (scale == 1f) this
    else this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val w = (placeable.width * scale).roundToInt()
        val h = (placeable.height * scale).roundToInt()
        layout(w, h) {
            // Place the full-size content centered in the scaled box, then scale around the
            // box center so it lands aligned and crisp.
            placeable.placeWithLayer(
                x = (w - placeable.width) / 2,
                y = (h - placeable.height) / 2,
            ) {
                scaleX = scale
                scaleY = scale
            }
        }
    }

/**
 * A compact [Slider]. Two looks, chosen by [CompactDensity.sliderUseDefaultThumb]:
 *
 *  - **Default (stock M3)** — the ordinary Material line-handle thumb + default track, with the
 *    handle height overridden to [CompactDensity.sliderThumbHeight] (M3 default is 44dp). This
 *    is the "ordinary slider, just a slightly shorter handle" look.
 *  - **Custom (compact)** — a small circular thumb ([CompactDensity.sliderThumbSize]) over a
 *    flat thin pill track ([CompactDensity.sliderTrackHeight]). The fill fraction is derived
 *    from the hoisted [value]/[valueRange] in scope, deliberately *not* from the slot's
 *    `SliderState`, whose internal shape shifts across material3 alphas.
 */
@Composable
fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val density = LocalCompactDensity.current
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }

    if (density.sliderUseDefaultThumb) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            interactionSource = interactionSource,
            // Stock line handle, shortened. Width stays at the M3 default (4dp).
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    enabled = enabled,
                    thumbSize = DpSize(4.dp, density.sliderThumbHeight),
                )
            },
            // Default track (passes the SliderState the stock track needs).
            track = { sliderState ->
                SliderDefaults.Track(sliderState = sliderState, enabled = enabled)
            },
        )
        return
    }

    val span = (valueRange.endInclusive - valueRange.start).takeIf { it != 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        thumb = {
            Box(
                modifier = Modifier
                    .size(density.sliderThumbSize)
                    .clip(CircleShape)
                    .background(if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.38f)),
            )
        },
        track = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(density.sliderTrackHeight)
                    .clip(CircleShape)
                    .background(colors.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .clip(CircleShape)
                        .background(if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.38f)),
                )
            }
        },
    )
}

/**
 * A compact dropdown menu item for use inside an M3 `DropdownMenu`. The stock `DropdownMenuItem`
 * floors at 48dp and only exposes `contentPadding`, so this is hand-rolled to honor
 * [CompactDensity.menuItemMinHeight] (which can drop below 48dp at [CompactDensity.Dense]).
 */
@Composable
fun CompactDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val density = LocalCompactDensity.current
    val colors = MaterialTheme.colorScheme
    val contentColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = density.menuItemMinHeight)
            .padding(density.menuItemContentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingIcon != null) {
            CompositionLocalProvider(
                LocalContentColor provides colors.onSurfaceVariant,
                content = leadingIcon,
            )
        }
        ProvideTextStyle(compactLabelStyle()) {
            Text(text = text, color = contentColor, modifier = Modifier.weight(1f))
        }
        if (trailingIcon != null) {
            CompositionLocalProvider(
                LocalContentColor provides colors.onSurfaceVariant,
                content = trailingIcon,
            )
        }
    }
}
