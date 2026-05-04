package com.themestudio.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Compact gallery of common Material 3 components. Drop into a
 * [ThemeStudioScreen] preview slot to see how button/chip/control colors
 * resolve under the active overrides.
 */
@Composable
fun MaterialComponentGallery(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Buttons", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {}) { Text("Filled") }
            FilledTonalButton(onClick = {}) { Text("Tonal") }
            ElevatedButton(onClick = {}) { Text("Elev") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {}) { Text("Outlined") }
            TextButton(onClick = {}) { Text("Text") }
            AssistChip(onClick = {}, label = { Text("Chip") })
        }

        HorizontalDivider()
        Text("Controls", style = MaterialTheme.typography.labelMedium)
        var switched by remember { mutableStateOf(true) }
        var checked by remember { mutableStateOf(false) }
        var radioed by remember { mutableStateOf(true) }
        var sliderValue by remember { mutableStateOf(0.5f) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Switch(checked = switched, onCheckedChange = { switched = it })
            Checkbox(checked = checked, onCheckedChange = { checked = it })
            RadioButton(selected = radioed, onClick = { radioed = !radioed })
        }
        Slider(value = sliderValue, onValueChange = { sliderValue = it })
        LinearProgressIndicator(
            progress = { 0.6f },
            modifier = Modifier.fillMaxWidth().height(4.dp),
        )

        HorizontalDivider()
        Text("Surfaces", style = MaterialTheme.typography.labelMedium)
        var fieldValue by remember { mutableStateOf("Sample text") }
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { fieldValue = it },
            label = { Text("Field") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("Card title", style = MaterialTheme.typography.titleSmall)
                Text("Body content on a card surface.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
