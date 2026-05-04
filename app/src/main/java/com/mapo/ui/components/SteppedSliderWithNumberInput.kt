package com.mapo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun SteppedSliderWithNumberInput(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    min: Int,
    max: Int,
    modifier: Modifier = Modifier,
    labelWidth: Int = 80
) {
    var textInput by remember(value) { mutableStateOf(value.toString()) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, modifier = Modifier.width(labelWidth.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> onValueChange(v.toInt().coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = textInput,
            onValueChange = { raw ->
                textInput = raw.filter { it.isDigit() }.take(3)
                textInput.toIntOrNull()?.let { parsed ->
                    val clamped = parsed.coerceIn(min, max)
                    if (clamped != value) onValueChange(clamped)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(64.dp)
        )
    }
}
