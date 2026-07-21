package com.mappo.ui.control

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mappo.ui.compact.CompactFieldSize
import com.mappo.ui.compact.CompactSlider
import com.mappo.ui.compact.CompactTextField
import com.mappo.ui.imeActivation
import com.mappo.ui.mappoKeyboardOptions
import kotlin.math.roundToInt

/**
 * Mappo's slider control: a header row (label left, editable current-value field right)
 * over the slider itself, flanked by -/+ [MappoIconButton]s that step the value one [step]
 * per tap. The value field accepts typed values (committed on Done / focus loss, clamped
 * to [valueRange]).
 *
 * The slider works in the value's RAW units; [valueText]/[parseValue] translate to and from
 * the display representation (e.g. a 0..1 fraction shown as "35" percent).
 *
 * @param step the unit one -/+ tap moves the value by, in raw units.
 * @param valueText formats the raw value for the value field (no unit suffix — keep it
 *   editable); pair it with [unitLabel] for the suffix shown after the field.
 * @param parseValue parses field text back to a raw value; null = invalid input (reverts).
 * @param unitLabel optional short unit caption after the value field ("%", "dp", "°").
 */
@Composable
fun MappoSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueText: (Float) -> String = { trimFloat(it) },
    parseValue: (String) -> Float? = { it.toFloatOrNull() },
    unitLabel: String? = null,
    onChangeFinished: (() -> Unit)? = null,
    valueFieldWidth: Dp = MappoSliderValueFieldWidth,
) {
    fun commitValue(v: Float) {
        onChange(v.coerceIn(valueRange))
        onChangeFinished?.invoke()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // ── Header: label left, editable value right ──
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = mappoMiniTextStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            SliderValueField(
                value = value,
                valueText = valueText,
                parseValue = parseValue,
                onCommit = ::commitValue,
                enabled = enabled,
                width = valueFieldWidth,
            )
            if (unitLabel != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unitLabel,
                    style = mappoMiniTextStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // ── Track row: -/+ unit steppers flanking the slider ──
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            MappoIconButton(
                icon = Icons.Filled.Remove,
                contentDescription = "Decrease $label",
                onClick = { commitValue(value - step) },
                enabled = enabled && value > valueRange.start,
            )
            CompactSlider(
                value = value,
                onValueChange = { onChange(it.coerceIn(valueRange)) },
                valueRange = valueRange,
                enabled = enabled,
                onValueChangeFinished = onChangeFinished,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            MappoIconButton(
                icon = Icons.Filled.Add,
                contentDescription = "Increase $label",
                onClick = { commitValue(value + step) },
                enabled = enabled && value < valueRange.endInclusive,
            )
        }
    }
}

/**
 * The header's editable value. Shows the formatted live value while idle; while focused it
 * holds the user's draft and commits on Done or focus loss (invalid input reverts to the
 * live value). Never auto-focused (feedback_no_keyboard_autospawn).
 */
@Composable
private fun SliderValueField(
    value: Float,
    valueText: (Float) -> String,
    parseValue: (String) -> Float?,
    onCommit: (Float) -> Unit,
    enabled: Boolean,
    width: Dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var draft by remember { mutableStateOf(valueText(value)) }
    fun commitDraft() {
        parseValue(draft.trim())?.let(onCommit)
    }
    // Entering focus seeds the draft from the live value; leaving focus commits it.
    var wasFocused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused && !wasFocused) draft = valueText(value)
        if (!focused && wasFocused) commitDraft()
        wasFocused = focused
    }
    CompactTextField(
        value = if (focused) draft else valueText(value),
        onValueChange = { draft = it },
        enabled = enabled,
        size = CompactFieldSize.Slim,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        keyboardOptions = mappoKeyboardOptions(
            KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        ),
        keyboardActions = KeyboardActions(onDone = { commitDraft() }),
        modifier = Modifier.width(width).imeActivation(),
    )
}

/**
 * [MappoSlider] over a 0..1 fraction displayed as whole percent ("35" + "%"), stepping 1%
 * per -/+ tap. The convenience form for the app's many fraction-backed settings.
 */
@Composable
fun MappoPercentSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    onChangeFinished: (() -> Unit)? = null,
) {
    MappoSlider(
        label = label,
        value = value,
        onChange = onChange,
        valueRange = valueRange,
        step = 0.01f,
        modifier = modifier,
        enabled = enabled,
        valueText = { (it * 100).roundToInt().toString() },
        parseValue = { it.toFloatOrNull()?.div(100f) },
        unitLabel = "%",
        onChangeFinished = onChangeFinished,
    )
}

/** "12.5" but "12" when the fraction is zero — keeps typed round-trips clean. */
private fun trimFloat(v: Float): String =
    if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v)

/** Width of the value field — sized for 4 digits + sign. */
private val MappoSliderValueFieldWidth = 64.dp
