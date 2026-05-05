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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Inline editor for one shape token: a single corner-radius slider and a
 * live preview tile.
 */
@Composable
internal fun ShapePicker(
    baseRadius: Dp,
    current: Dp?,
    onChange: (Dp) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val effective = current ?: baseRadius
    val isOverridden = current != null
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        // Live preview tile
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(effective),
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(effective),
                ),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Corner",
                fontSize = 12.sp,
                modifier = Modifier.width(72.dp),
            )
            Slider(
                value = effective.value.coerceIn(0f, 48f),
                onValueChange = { onChange(it.dp) },
                valueRange = 0f..48f,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${effective.value.toInt()}dp".padStart(5, ' '),
                fontSize = 11.sp,
                modifier = Modifier.width(48.dp),
            )
        }
        if (isOverridden) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onClear) { Text("Clear override") }
            }
        }
    }
}
