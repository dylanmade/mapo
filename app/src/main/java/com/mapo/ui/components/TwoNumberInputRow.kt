package com.mapo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * One-row list item with a label on the left and two number-input fields on the right,
 * captioned "W" and "H". Used for keyboard size and default button size — both edit
 * a (width, height) pair without the slider affordance of [SteppedSliderWithNumberInput].
 *
 * Input is digit-filtered and clamped to (min..max) for each dimension; the caller is
 * only notified when the parsed value actually changes.
 */
@Composable
fun TwoNumberInputRow(
    label: String,
    width: Int,
    height: Int,
    onChange: (width: Int, height: Int) -> Unit,
    minWidth: Int,
    maxWidth: Int,
    minHeight: Int,
    maxHeight: Int,
    modifier: Modifier = Modifier,
    labelWidth: Int = 160,
) {
    // Local text buffers that only re-sync from their source while NOT focused, so an
    // async round-trip can't clobber what the user is typing mid-edit.
    var widthFocused by remember { mutableStateOf(false) }
    var heightFocused by remember { mutableStateOf(false) }
    var widthText by remember { mutableStateOf(width.toString()) }
    var heightText by remember { mutableStateOf(height.toString()) }
    LaunchedEffect(width, widthFocused) { if (!widthFocused) widthText = width.toString() }
    LaunchedEffect(height, heightFocused) { if (!heightFocused) heightText = height.toString() }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.width(labelWidth.dp),
        )
        OutlinedTextField(
            value = widthText,
            onValueChange = { raw ->
                widthText = raw.filter { it.isDigit() }.take(3)
                widthText.toIntOrNull()?.let { parsed ->
                    val clamped = parsed.coerceIn(minWidth, maxWidth)
                    if (clamped != width) onChange(clamped, height)
                }
            },
            label = { Text("W") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .width(72.dp)
                .onFocusChanged { widthFocused = it.isFocused },
        )
        Text(
            "×",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = heightText,
            onValueChange = { raw ->
                heightText = raw.filter { it.isDigit() }.take(3)
                heightText.toIntOrNull()?.let { parsed ->
                    val clamped = parsed.coerceIn(minHeight, maxHeight)
                    if (clamped != height) onChange(width, clamped)
                }
            },
            label = { Text("H") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .width(72.dp)
                .onFocusChanged { heightFocused = it.isFocused },
        )
    }
}
