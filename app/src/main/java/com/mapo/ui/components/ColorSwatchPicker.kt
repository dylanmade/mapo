package com.mapo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

private val PRESETS: List<Color> = listOf(
    Color(0xFFEF5350), // red
    Color(0xFFFF9800), // orange
    Color(0xFFFFEB3B), // yellow
    Color(0xFF66BB6A), // green
    Color(0xFF26C6DA), // cyan
    Color(0xFF42A5F5), // blue
    Color(0xFFAB47BC), // purple
    Color(0xFF8D6E63)  // brown
)

@Composable
fun ColorSwatchPicker(
    selectedArgb: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { DefaultSwatch(selected = selectedArgb == null, onClick = { onSelect(null) }) }
        items(PRESETS) { color ->
            val argb = color.toArgb()
            ColorDot(
                color = color,
                selected = selectedArgb == argb,
                onClick = { onSelect(argb) }
            )
        }
    }
}

@Composable
private fun DefaultSwatch(selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outline
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .border(if (selected) 2.dp else 1.dp, borderColor, CircleShape)
            .clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = Icons.Default.Block,
            contentDescription = "Default",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outline
    Row {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .background(color, CircleShape)
                .border(if (selected) 2.dp else 1.dp, borderColor, CircleShape)
                .clickable(onClick = onClick)
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
