package com.themestudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.themestudio.core.TextStyleOverride
import com.themestudio.core.TypographyRole

/**
 * Umbrella ("set-all") picker sheet for an entire role group. Composed of
 * the family chooser card (tapping opens the font-family picker sheet on
 * top) and the standard [TypographyPicker] bound to the umbrella role —
 * so size/weight/tracking edits cascade to every role in the group via
 * the layering rule in `TypographyOverrides.applyOverrides`.
 *
 * Per-role overrides are still surfaced through the per-role specimens
 * lower on the screen; they reassert as soon as the corresponding umbrella
 * field is cleared.
 */
@Composable
internal fun UmbrellaPickerSheet(
    title: String,
    cascadeSummary: String,
    currentFamilyName: String?,
    role: TypographyRole,
    baseStyle: TextStyle,
    current: TextStyleOverride,
    onPickFamily: () -> Unit,
    onChange: (TextStyleOverride) -> Unit,
    onClearOverrides: () -> Unit,
    defaultFontName: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = cascadeSummary,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        FamilyChooserCard(
            label = "Family",
            currentName = currentFamilyName,
            defaultLabel = "(theme default)",
            previewStyle = baseStyle,
            onTap = onPickFamily,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            defaultFontName = defaultFontName,
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        TypographyPicker(
            role = role,
            baseStyle = baseStyle,
            current = current,
            onChange = onChange,
            onClear = onClearOverrides,
        )
    }
}
