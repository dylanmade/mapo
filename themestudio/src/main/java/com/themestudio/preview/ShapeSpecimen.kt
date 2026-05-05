package com.themestudio.preview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Specimen for the 5 Material 3 shape tokens. Each token is rendered as a
 * filled rectangle with that token's shape applied. Tap a tile to open the
 * shape editor for that token.
 *
 * Shows the resolved corner radius next to each tile so the dev sees the
 * effective value at a glance.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShapeSpecimen(
    modifier: Modifier = Modifier,
    onPickRole: ((roleName: String) -> Unit)? = null,
) {
    val shapes = MaterialTheme.shapes
    val tokens: List<Triple<String, androidx.compose.ui.graphics.Shape, String>> = listOf(
        Triple("extraSmall", shapes.extraSmall, "4dp default"),
        Triple("small", shapes.small, "8dp default"),
        Triple("medium", shapes.medium, "12dp default"),
        Triple("large", shapes.large, "16dp default"),
        Triple("extraLarge", shapes.extraLarge, "28dp default"),
    )
    Column(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tokens.forEach { (name, shape, hint) ->
            val tap = onPickRole?.let { { it(name) } }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = (if (tap != null) Modifier.combinedClickable(onClick = tap, onLongClick = tap)
                    else Modifier)
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.primaryContainer, shape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, shape),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.width(120.dp)) {
                    Text(
                        text = name,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = hint,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

