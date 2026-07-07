package com.mappo.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mappo.ui.component.colorpicker.ColorPickerButton
import com.mappo.ui.component.colorpicker.MappoColorPickerDialog

/**
 * Dev harness for the new [MappoColorPickerDialog]. Demonstrates the intended integration: a
 * settings-style [ListItem] whose trailing [ColorPickerButton] (a circular swatch reflecting
 * the current color) opens the dialog — and tapping the row does the same thing. Reachable
 * from the main drawer; not a shipping screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerDemoScreen(onBack: () -> Unit) {
    var fill by remember { mutableStateOf(Color(0xFF3A7BD5)) }
    var accent by remember { mutableStateOf(Color(0xFFEF5350)) }
    var tint by remember { mutableStateOf(Color(0x80AB47BC)) } // translucent → exercises alpha

    // Which row's dialog is open (null = none).
    var editing by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Color picker") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            ColorRow("Fill color", "Tap the row or the swatch to edit", fill) { editing = 0 }
            HorizontalDivider()
            ColorRow("Accent color", "Opaque example", accent) { editing = 1 }
            HorizontalDivider()
            ColorRow("Overlay tint", "Translucent — shows the alpha slider", tint) { editing = 2 }
        }
    }

    when (editing) {
        0 -> MappoColorPickerDialog(
            initialColor = fill,
            title = "Fill color",
            onConfirm = { fill = it; editing = null },
            onDismiss = { editing = null },
        )
        1 -> MappoColorPickerDialog(
            initialColor = accent,
            title = "Accent color",
            onConfirm = { accent = it; editing = null },
            onDismiss = { editing = null },
        )
        2 -> MappoColorPickerDialog(
            initialColor = tint,
            title = "Overlay tint",
            onConfirm = { tint = it; editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ColorRow(label: String, supporting: String, color: Color, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(supporting) },
        trailingContent = { ColorPickerButton(color = color, onClick = onClick) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
