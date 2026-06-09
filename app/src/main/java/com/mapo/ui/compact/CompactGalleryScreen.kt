package com.mapo.ui.compact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mapo.ui.theme.MapoTheme

/**
 * A scratchpad screen that renders the whole compact-component set under a runtime-selectable
 * [CompactDensity], so the density levels can be A/B'd on a real device before one is adopted
 * as the app default. Reached from the Mapo drawer. Not a shipping feature — a design harness.
 *
 * The segmented control at the top swaps which [CompactDensity] is provided to the body via
 * [ProvideCompactDensity]; everything below re-lays-out instantly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactGalleryScreen(onBack: () -> Unit) {
    var density by remember { mutableStateOf(CompactDensity.DylansCut) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compact component gallery") },
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
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Density selector — drives the whole body below.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                CompactDensity.all.forEachIndexed { index, preset ->
                    SegmentedButton(
                        selected = density.label == preset.label,
                        onClick = { density = preset },
                        shape = SegmentedButtonDefaults.itemShape(index, CompactDensity.all.size),
                    ) {
                        Text(preset.label)
                    }
                }
            }
            Text(
                text = densityBlurb(density),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            ProvideCompactDensity(density) {
                CompactGalleryBody()
            }
        }
    }
}

private fun densityBlurb(d: CompactDensity): String = when (d.label) {
    "Comfortable" -> "≈ stock M3. Reference baseline; 48dp touch targets, full-size switch."
    "Compact" -> "M3-dense. Tighter but still thumb-friendly (48dp targets kept)."
    "Dylan's Cut" -> "Stock-M3 proportions + full-size switch, but every row reserves switch " +
        "height so rows stay uniform + stock slider with a 40dp handle. The intended app default."
    else -> ""
}

/**
 * The component catalog itself — density-agnostic; reads everything from [LocalCompactDensity].
 * Pulled out so the [@Preview] functions can render it under each preset side-by-side.
 */
@Composable
fun CompactGalleryBody(modifier: Modifier = Modifier) {
    var text1 by remember { mutableStateOf("") }
    var text2 by remember { mutableStateOf("Editable value") }
    var switchOn by remember { mutableStateOf(true) }
    var sliderValue by remember { mutableFloatStateOf(0.4f) }
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GallerySection("Text fields") {
            CompactTextField(
                value = text1,
                onValueChange = { text1 = it },
                placeholder = "Outlined, placeholder hint",
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            CompactTextField(
                value = text2,
                onValueChange = { text2 = it },
                label = "Static label",
                modifier = Modifier.fillMaxWidth(),
            )
            CompactTextField(
                value = "",
                onValueChange = {},
                outlined = false,
                placeholder = "Filled variant",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        GallerySection("Buttons") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactButton(onClick = {}) { Text("Filled") }
                CompactFilledTonalButton(onClick = {}) { Text("Tonal") }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactOutlinedButton(onClick = {}) { Text("Outlined") }
                CompactTextButton(onClick = {}) { Text("Text") }
                CompactIconButton(
                    icon = Icons.Default.Settings,
                    contentDescription = "Settings",
                    onClick = {},
                )
            }
        }

        GallerySection("List rows") {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    CompactListItem(
                        headline = "Single line row",
                        trailing = { CompactSwitch(checked = switchOn, onCheckedChange = { switchOn = it }) },
                        onClick = {},
                    )
                    HorizontalDivider()
                    CompactListItem(
                        headline = "Two line row",
                        supporting = "Supporting text under the headline",
                        leading = { Icon(Icons.Default.Settings, contentDescription = null) },
                        onClick = {},
                    )
                    HorizontalDivider()
                    CompactListItem(
                        headline = "Row with trailing value",
                        trailing = { Text("Value", style = MaterialTheme.typography.bodyMedium) },
                        onClick = {},
                    )
                    HorizontalDivider()
                    CompactListItem(
                        headline = "Two line row, long supporting text",
                        supporting = "This supporting text is intentionally long enough to wrap " +
                            "onto a second line, so the headline-to-support gap and overall row " +
                            "spacing can be checked when the description spans multiple lines.",
                        leading = { Icon(Icons.Default.Settings, contentDescription = null) },
                        onClick = {},
                    )
                }
            }
        }

        GallerySection("Controls") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Switch", modifier = Modifier.weight(1f))
                CompactSwitch(checked = switchOn, onCheckedChange = { switchOn = it })
            }
            CompactSlider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        GallerySection("Menu") {
            CompactOutlinedButton(onClick = { menuOpen = true }) { Text("Open compact menu") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                listOf("First option", "Second option", "Third option").forEach { label ->
                    CompactDropdownMenuItem(
                        text = label,
                        onClick = { menuOpen = false },
                        trailingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GallerySection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

// ── Previews: the same body under each density, for the Android Studio preview pane ──

@Preview(name = "Comfortable", showBackground = true, widthDp = 360)
@Composable
private fun PreviewComfortable() = MapoTheme {
    Surface { ProvideCompactDensity(CompactDensity.Comfortable) {
        CompactGalleryBody(Modifier.padding(16.dp))
    } }
}

@Preview(name = "Compact", showBackground = true, widthDp = 360)
@Composable
private fun PreviewCompact() = MapoTheme {
    Surface { ProvideCompactDensity(CompactDensity.Compact) {
        CompactGalleryBody(Modifier.padding(16.dp))
    } }
}

@Preview(name = "Dylan's Cut", showBackground = true, widthDp = 360)
@Composable
private fun PreviewDylansCut() = MapoTheme {
    Surface { ProvideCompactDensity(CompactDensity.DylansCut) {
        CompactGalleryBody(Modifier.padding(16.dp))
    } }
}
