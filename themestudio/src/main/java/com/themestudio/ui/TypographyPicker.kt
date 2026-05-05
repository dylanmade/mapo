package com.themestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.themestudio.core.EDITABLE_FONT_WEIGHTS
import com.themestudio.core.TextStyleOverride
import com.themestudio.core.TypographyRole
import com.themestudio.core.toSpFloat

/**
 * Inline editor for one typography role: font size, font weight, letter
 * spacing. The base [TextStyle] supplies starting values when the role has
 * no override yet.
 *
 * The live preview line at the top shows the current effective style — it
 * updates as the user drags sliders or picks a weight chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TypographyPicker(
    role: TypographyRole,
    baseStyle: TextStyle,
    current: TextStyleOverride,
    onChange: (TextStyleOverride) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val effectiveSize = (current.fontSize ?: baseStyle.fontSize).toSpFloat()
    val effectiveWeight = current.fontWeight ?: baseStyle.fontWeight ?: FontWeight.Normal
    val effectiveLetterSpacing = (current.letterSpacing ?: baseStyle.letterSpacing).toSpFloat()
    val isOverridden = current.fontSize != null || current.fontWeight != null ||
        current.letterSpacing != null

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        // Live specimen line — sample text in the current effective style
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .padding(12.dp),
        ) {
            Text(
                text = "The quick brown fox jumps",
                style = baseStyle.copy(
                    fontSize = effectiveSize.sp,
                    fontWeight = effectiveWeight,
                    letterSpacing = effectiveLetterSpacing.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(16.dp))

        // Font size
        LabeledSlider(
            label = "Size",
            value = effectiveSize,
            range = 8f..72f,
            valueLabel = "${effectiveSize.toInt()}sp",
            onChange = { onChange(current.copy(fontSize = it.sp)) },
        )

        // Letter spacing
        LabeledSlider(
            label = "Tracking",
            value = effectiveLetterSpacing,
            range = -2f..6f,
            valueLabel = "%.1fsp".format(effectiveLetterSpacing),
            onChange = { onChange(current.copy(letterSpacing = it.sp)) },
        )

        Spacer(Modifier.height(8.dp))

        // Font weight chips
        Text(
            text = "Weight",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            EDITABLE_FONT_WEIGHTS.forEach { (label, weight) ->
                FilterChip(
                    selected = weight == effectiveWeight,
                    onClick = { onChange(current.copy(fontWeight = weight)) },
                    label = { Text(label, fontSize = 10.sp) },
                )
            }
        }

        if (isOverridden) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onClear) { Text("Clear override") }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onChange: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(64.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = valueLabel.padStart(7, ' '),
            fontSize = 11.sp,
            modifier = Modifier.width(56.dp),
        )
    }
}
