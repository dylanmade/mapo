package com.themestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions

/**
 * Inline RGB+hex color editor. Calls [onChange] live as the user drags any
 * slider or commits a hex value, so the surrounding theme updates in real
 * time.
 *
 * v1 deliberately uses RGB sliders + hex over HSV: it's ~30 lines of code
 * instead of ~150 for a saturation/value gradient canvas, and hex covers the
 * "I want this exact color" workflow which is what most theme work actually
 * needs.
 */
@Composable
internal fun ColorPicker(
    color: Color,
    onChange: (Color) -> Unit,
    onClearOverride: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val argb = color.toArgb()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF

    var hexInput by remember(argb) { mutableStateOf(argb.toHexNoAlpha()) }
    LaunchedEffect(argb) { hexInput = argb.toHexNoAlpha() }

    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(color, RoundedCornerShape(6.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = hexInput,
                onValueChange = { typed ->
                    hexInput = typed
                    parseHex(typed)?.let { parsed -> onChange(Color(parsed)) }
                },
                label = { Text("Hex", fontSize = 11.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        ChannelSlider("R", r) { onChange(Color(it, g, b)) }
        ChannelSlider("G", g) { onChange(Color(r, it, b)) }
        ChannelSlider("B", b) { onChange(Color(r, g, it)) }
        if (onClearOverride != null) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onClearOverride) { Text("Clear override") }
            }
        }
    }
}

@Composable
private fun ChannelSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(20.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f),
        )
        Text(value.toString(), fontSize = 12.sp, modifier = Modifier.width(32.dp))
    }
}

/** Construct a [Color] from 0..255 channel values, alpha forced to 255. */
private fun Color(r: Int, g: Int, b: Int): Color =
    Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = 1f)

private fun Int.toHexNoAlpha(): String =
    "#%06X".format(this and 0xFFFFFF)

private fun parseHex(input: String): Int? {
    val s = input.trim().removePrefix("#")
    return when (s.length) {
        6 -> runCatching { (0xFF000000.toInt()) or s.toInt(16) }.getOrNull()
        8 -> runCatching { s.toLong(16).toInt() }.getOrNull()
        else -> null
    }
}
