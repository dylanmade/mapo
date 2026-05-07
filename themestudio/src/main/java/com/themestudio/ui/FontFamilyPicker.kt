package com.themestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.themestudio.core.LocalFontFamilySpec
import com.themestudio.core.LocalFontRegistry
import com.themestudio.core.ThemeFontResolver
import com.themestudio.core.rememberGoogleFontsCatalog
import com.themestudio.core.rememberThemeFontResolver

/**
 * Inline card shown in the Typography tab for one family group (Display or
 * Body). Displays the currently-applied family name (or "(default)" when
 * none is overridden) with a sample line rendered in that family.
 *
 * The preview line uses [previewStyle] — Display callers pass titleLarge,
 * Body callers pass bodyLarge — so each card shows the chosen font at the
 * scale it would actually be used.
 */
@Composable
internal fun FamilyChooserCard(
    label: String,
    currentName: String?,
    defaultLabel: String,
    onTap: () -> Unit,
    previewStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val effectiveName = currentName?.takeIf { it.isNotBlank() }
    val resolve = rememberThemeFontResolver()
    val previewFamily = effectiveName?.let { remember(it, resolve) { resolve(it) } }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = effectiveName ?: defaultLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = SAMPLE,
            style = previewStyle.copy(fontFamily = previewFamily ?: previewStyle.fontFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Picker sheet body. Contents are: a search/free-text field, a "live"
 * preview of whatever's typed, and a LazyColumn that pins locally-bundled
 * font families ([LocalFontRegistry]) at the top above the GMS Fonts
 * catalog. Tapping any row applies that family. Free-text input also
 * supports an "Apply" affordance when no exact match exists in either set.
 *
 * Catalog rows render their preview text in their own font via [rowStyle];
 * that's what triggers the on-demand GMS download for Google entries.
 * LazyColumn only composes visible rows, so scrolling fast doesn't request
 * the whole list — and the resolver caches keep memory bounded regardless.
 */
@Composable
internal fun FontFamilyPickerSheet(
    title: String,
    currentName: String?,
    rowStyle: TextStyle,
    onApply: (String) -> Unit,
    onClear: () -> Unit,
) {
    var query by remember { mutableStateOf(currentName.orEmpty()) }
    val resolve = rememberThemeFontResolver()
    val catalog = rememberGoogleFontsCatalog()
    val localFonts = remember { LocalFontRegistry.all }
    val filteredLocal = remember(query, localFonts) {
        if (query.isBlank()) localFonts
        else localFonts.filter { it.displayName.contains(query, ignoreCase = true) }
    }
    val filteredCatalog = remember(query, catalog) {
        if (query.isBlank()) catalog
        else catalog.filter { it.contains(query, ignoreCase = true) }
    }
    val trimmed = query.trim()
    val exactMatch = catalog.any { it.equals(trimmed, ignoreCase = true) } ||
        localFonts.any { it.displayName.equals(trimmed, ignoreCase = true) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Local fonts at top; Google Fonts download on demand.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (currentName != null) {
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search or type a font name", fontSize = 12.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // "Apply 'X' as a custom font" affordance — only shown when the input
        // is non-blank and not already a catalog entry, so users can reach
        // fonts added to Google Fonts after this snapshot was taken.
        if (trimmed.isNotEmpty() && !exactMatch) {
            TextButton(
                onClick = { onApply(trimmed) },
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth(),
            ) {
                Text("Apply \"$trimmed\" as a custom font", fontSize = 12.sp)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .heightIn(min = 200.dp),
        ) {
            if (filteredLocal.isNotEmpty()) {
                item(key = "__header_local__") { SectionHeader("Local") }
                items(filteredLocal, key = { "local:${it.displayName}" }) { spec ->
                    LocalFontRow(
                        spec = spec,
                        rowStyle = rowStyle,
                        resolve = resolve,
                        selected = spec.displayName.equals(currentName, ignoreCase = true),
                        onClick = { onApply(spec.displayName) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (filteredCatalog.isNotEmpty()) {
                item(key = "__header_google__") { SectionHeader("Google Fonts") }
                items(filteredCatalog, key = { "google:$it" }) { name ->
                    FontRow(
                        name = name,
                        rowStyle = rowStyle,
                        resolve = resolve,
                        selected = name.equals(currentName, ignoreCase = true),
                        onClick = { onApply(name) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun FontRow(
    name: String,
    rowStyle: TextStyle,
    resolve: ThemeFontResolver,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val family = remember(name, resolve) { resolve(name) }
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = SAMPLE,
                style = rowStyle.copy(fontFamily = family),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LocalFontRow(
    spec: LocalFontFamilySpec,
    rowStyle: TextStyle,
    resolve: ThemeFontResolver,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val family = remember(spec.displayName, resolve) { resolve(spec.displayName) }
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = spec.displayName,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                LicenseChip(redistributable = spec.redistributable)
            }
            Text(
                text = SAMPLE,
                style = rowStyle.copy(fontFamily = family),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LicenseChip(redistributable: Boolean) {
    val (label, container, onContainer) = if (redistributable) {
        Triple("Local", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
    } else {
        Triple("Personal use only", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    }
    Text(
        text = label,
        fontSize = 9.sp,
        color = onContainer,
        modifier = Modifier
            .background(container, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private const val SAMPLE = "The quick brown fox jumps over 0123"
