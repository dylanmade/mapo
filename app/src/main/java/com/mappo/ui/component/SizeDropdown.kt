package com.mappo.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import com.mappo.ui.imeActivation
import com.mappo.ui.mappoKeyboardOptions

/**
 * Numeric size picker. Presents a dropdown of preset values but the field is editable,
 * so users can type any positive number. Calls [onChange] only with values that parse;
 * partial typing leaves the underlying [value] alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SizeDropdown(
    value: Float,
    onChange: (Float) -> Unit,
    presets: List<Int> = listOf(8, 10, 12, 14, 16, 20, 24, 32),
    label: String = "Size",
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // Local text buffer that only re-syncs from [value] while NOT focused, so an
    // async round-trip can't clobber what the user is typing mid-edit.
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(formatSize(value)) }
    LaunchedEffect(value, focused) { if (!focused) text = formatSize(value) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { typed ->
                text = typed
                typed.trim().toFloatOrNull()?.takeIf { it > 0f }?.let(onChange)
            },
            singleLine = true,
            label = { Text(label) },
            keyboardOptions = mappoKeyboardOptions(KeyboardOptions(keyboardType = KeyboardType.Number)),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .onFocusChanged { focused = it.isFocused }
                .fillMaxWidth().imeActivation(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.toString()) },
                    onClick = {
                        text = preset.toString()
                        onChange(preset.toFloat())
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun formatSize(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
