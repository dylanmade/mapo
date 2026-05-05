package com.themestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Single role row: name on the left, current effective color swatch on the
 * right, dot indicator if the role currently has an override. Tapping
 * anywhere on the row opens the bottom-sheet picker.
 */
@Composable
internal fun RoleRow(
    name: String,
    effectiveColor: Color,
    isOverridden: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(effectiveColor, RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isOverridden) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(4.dp),
                    ),
            )
        }
    }
}
