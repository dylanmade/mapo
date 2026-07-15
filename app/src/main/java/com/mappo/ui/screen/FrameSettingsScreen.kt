package com.mappo.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mappo.data.settings.FrameStyle
import com.mappo.ui.component.colorpicker.ColorPickerButton
import com.mappo.ui.component.colorpicker.MappoColorPickerDialog
import com.mappo.ui.viewmodel.MainViewModel
import kotlin.math.roundToInt

/**
 * Frame style settings: the handheld frame's faux-hardware finish (2026-07-14). Three core
 * colors — shell / glass / bezel — each with the intensity sliders for the detail passes that
 * derive from it (see [FrameStyle] for the color hierarchy). Sliders preview LIVE while
 * dragging (no persistence) and commit on release; the frame around this very screen is the
 * preview surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameSettingsScreen(
    onBack: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val style by viewModel.frameStyle.collectAsStateWithLifecycle()
    var pickingColor by remember { mutableStateOf<FrameColorTarget?>(null) }
    BackHandler { onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Frame style") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Outer shell")
            ColorRow(
                label = "Shell color",
                helper = "Core plastic color — edge lighting and the well derive from it",
                color = style.shellColor,
                onClick = { pickingColor = FrameColorTarget.SHELL },
            )
            IntensityRow(
                label = "Texture",
                helper = "Plastic grain across the shell",
                value = style.shellTexture,
                onPreview = { viewModel.previewFrameStyle(style.copy(shellTexture = it)) },
                onCommit = { viewModel.setFrameStyle(style.copy(shellTexture = it)) },
            )
            IntensityRow(
                label = "Edge highlight",
                helper = "Light catching the top of the shell's rounded edges",
                value = style.shellHighlight,
                onPreview = { viewModel.previewFrameStyle(style.copy(shellHighlight = it)) },
                onCommit = { viewModel.setFrameStyle(style.copy(shellHighlight = it)) },
            )
            IntensityRow(
                label = "Edge shadow",
                helper = "Darkening where the shell's edges finish rounding away",
                value = style.shellShadow,
                onPreview = { viewModel.previewFrameStyle(style.copy(shellShadow = it)) },
                onCommit = { viewModel.setFrameStyle(style.copy(shellShadow = it)) },
            )
            IntensityRow(
                label = "Well",
                helper = "Shadowed gap where the glass meets the shell",
                value = style.well,
                onPreview = { viewModel.previewFrameStyle(style.copy(well = it)) },
                onCommit = { viewModel.setFrameStyle(style.copy(well = it)) },
            )
            HorizontalDivider()

            SectionHeader("Glass frame")
            ColorRow(
                label = "Glass color",
                helper = "Core color of the inner glass — its edge light derives from it",
                color = style.glassColor,
                onClick = { pickingColor = FrameColorTarget.GLASS },
            )
            IntensityRow(
                label = "Edge highlight",
                helper = "Light on the glass frame's slightly rounded edges",
                value = style.glassHighlight,
                onPreview = { viewModel.previewFrameStyle(style.copy(glassHighlight = it)) },
                onCommit = { viewModel.setFrameStyle(style.copy(glassHighlight = it)) },
            )
            HorizontalDivider()

            SectionHeader("Screen bezel")
            ColorRow(
                label = "Bezel color",
                helper = "Flat border around the screen — its own color, derived from neither",
                color = style.bezelColor,
                onClick = { pickingColor = FrameColorTarget.BEZEL },
            )
            IntensityRow(
                label = "Vignette",
                helper = "Corner shading on the screen's edges, like a passive LCD",
                value = style.vignette,
                onPreview = { viewModel.previewFrameStyle(style.copy(vignette = it)) },
                onCommit = { viewModel.setFrameStyle(style.copy(vignette = it)) },
            )
            HorizontalDivider()

            TextButton(
                onClick = { viewModel.resetFrameStyle() },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) { Text("Reset to defaults") }
        }
    }

    pickingColor?.let { target ->
        MappoColorPickerDialog(
            initialColor = when (target) {
                FrameColorTarget.SHELL -> style.shellColor
                FrameColorTarget.GLASS -> style.glassColor
                FrameColorTarget.BEZEL -> style.bezelColor
            },
            title = when (target) {
                FrameColorTarget.SHELL -> "Shell color"
                FrameColorTarget.GLASS -> "Glass color"
                FrameColorTarget.BEZEL -> "Bezel color"
            },
            // Hardware isn't translucent — alpha would punch holes in the device.
            supportAlpha = false,
            onConfirm = { picked ->
                viewModel.setFrameStyle(
                    when (target) {
                        FrameColorTarget.SHELL -> style.copy(shellColor = picked)
                        FrameColorTarget.GLASS -> style.copy(glassColor = picked)
                        FrameColorTarget.BEZEL -> style.copy(bezelColor = picked)
                    },
                )
                pickingColor = null
            },
            onDismiss = { pickingColor = null },
        )
    }
}

private enum class FrameColorTarget { SHELL, GLASS, BEZEL }

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ColorRow(
    label: String,
    helper: String,
    color: Color,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(helper) },
        trailingContent = { ColorPickerButton(color = color, onClick = onClick) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

/**
 * A 0..1 intensity slider row: label + live percentage, helper subtext, slider. Drags stream
 * through [onPreview] (flow-only restyle — the frame updates live at zero write cost) and
 * [onCommit] persists on release, per the slider draft-commit convention.
 */
@Composable
private fun IntensityRow(
    label: String,
    helper: String,
    value: Float,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${(draft * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = helper,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = draft,
            onValueChange = {
                draft = it
                onPreview(it)
            },
            onValueChangeFinished = { onCommit(draft) },
            valueRange = 0f..1f,
        )
    }
}
