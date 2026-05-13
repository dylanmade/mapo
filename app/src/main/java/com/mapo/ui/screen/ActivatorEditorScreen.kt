package com.mapo.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.service.input.CompiledActivatorSettings
import kotlin.math.roundToLong

/**
 * Per-activator settings editor. Reached from the cog button on each row in
 * `InputEditorScreen`. Shows the per-type timing controls that are live today plus the
 * universal-settings panel as disabled placeholders that come alive in Brick 3.3.
 *
 * Instant-commit: slider drag-end calls [onSettingsChange] which writes through to the
 * repo. No draft / Save / Cancel layer — the rest of Mapo's editing screens behave the
 * same way (per `RemapControlsScreen` and `InputEditorScreen`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivatorEditorScreen(
    activatorId: Long,
    title: String,
    config: ControllerConfig?,
    onSettingsChange: (Long, CompiledActivatorSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activatorContext = remember(config, activatorId) { findActivatorContext(config, activatorId) }

    // Stale route (activator deleted from elsewhere mid-edit) — pop without crashing.
    if (config != null && activatorContext == null) {
        LaunchedEffect(Unit) { onBack() }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (activatorContext == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        val (type, settings) = activatorContext
        // Column + verticalScroll over LazyColumn here because the section count is small
        // and fixed — LazyColumn's below-the-viewport lazy composition would defeat
        // assertExists in Robolectric (per feedback_robolectric_compose_pitfalls).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Type-specific timing (live in 3.5) ─────────────────────────────
            when (type) {
                ActivatorType.LONG_PRESS -> {
                    SectionHeader("Activation timing")
                    TimingSlider(
                        label = "Hold time",
                        helper = "Time the button must be held before this activator fires.",
                        currentMs = settings.longPressTimeMs,
                        rangeMs = 50f..2000f,
                        onCommit = { newMs ->
                            onSettingsChange(activatorId, settings.copy(longPressTimeMs = newMs))
                        },
                    )
                }
                ActivatorType.DOUBLE_PRESS -> {
                    SectionHeader("Activation timing")
                    TimingSlider(
                        label = "Max time between taps",
                        helper = "Two taps inside this window count as a double-press.",
                        currentMs = settings.doubleTapTimeMs,
                        rangeMs = 50f..1000f,
                        onCommit = { newMs ->
                            onSettingsChange(activatorId, settings.copy(doubleTapTimeMs = newMs))
                        },
                    )
                }
                else -> { /* No type-specific timing for FULL/START/RELEASE/SOFT/CHORDED. */ }
            }

            // ── Universal settings (placeholders for 3.3) ──────────────────────
            SectionHeader("Universal settings")
            ComingSoonRow(
                label = "Toggle",
                helper = "Stays active after release until pressed again.",
            )
            ComingSoonRow(
                label = "Hold-to-repeat (Turbo)",
                helper = "Pulses the binding repeatedly while held.",
            )
            ComingSoonRow(
                label = "Fire start delay",
                helper = "Delay before the binding fires once activation conditions are met.",
            )
            ComingSoonRow(
                label = "Fire end delay",
                helper = "Keeps the binding active past its physical release.",
            )
            ComingSoonRow(
                label = "Cycle bindings",
                helper = "Cycles through multiple bindings sequentially on each fire.",
            )

            // Interruptable: only valid on FULL_PRESS / RELEASE_PRESS per Steam.
            if (type == ActivatorType.FULL_PRESS || type == ActivatorType.RELEASE_PRESS) {
                SectionHeader("Interruption")
                ComingSoonRow(
                    label = "Interruptable",
                    helper = "Lets longer/double activators suppress this one when they fire.",
                )
            }
        }
    }
}

/**
 * Resolve [activatorId] against [config] to return its type + parsed settings. Walks the
 * active action set's preset entries / binding groups / inputs in O(N) of the active-set
 * graph; called once per recomposition with `remember` keying so it's cheap.
 */
private fun findActivatorContext(
    config: ControllerConfig?,
    activatorId: Long,
): Pair<ActivatorType, CompiledActivatorSettings>? {
    val activeSet = config?.activeActionSet ?: return null
    for (preset in activeSet.preset) {
        for (input in preset.group.inputs) {
            for (graph in input.activators) {
                if (graph.activator.id == activatorId) {
                    val settings = CompiledActivatorSettings.parse(graph.activator.settingsJson)
                    return graph.activator.type to settings
                }
            }
        }
    }
    return null
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Slider that displays the current value as seconds (with 2-decimal precision) and commits
 * on drag-end via [onCommit]. Values are rounded to 10 ms granularity on commit; intermediate
 * drag state lives in local Compose state for smooth tracking. The seek thumb's
 * `onValueChange` fires per frame during drag — we don't write to the repo on every frame
 * because that's not the actual cadence of user intent (the user lifts their finger when
 * they're done; that's the commit edge).
 */
@Composable
private fun TimingSlider(
    label: String,
    helper: String,
    currentMs: Long,
    rangeMs: ClosedFloatingPointRange<Float>,
    onCommit: (Long) -> Unit,
) {
    // Keyed on the persisted value so a fresh navigation or external edit resets the slider.
    var draft by remember(currentMs) { mutableStateOf(currentMs.toFloat()) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatSeconds(draft.roundToLong()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = {
                // Round to nearest 10 ms for clean storage (no 432.7… ms values).
                val rounded = ((draft / 10f).roundToLong() * 10L).coerceIn(
                    rangeMs.start.toLong(),
                    rangeMs.endInclusive.toLong(),
                )
                onCommit(rounded)
            },
            valueRange = rangeMs,
        )
        Text(
            text = helper,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatSeconds(ms: Long): String = "%.2f s".format(ms / 1000.0)

/**
 * Disabled-placeholder row for universal settings that 3.3 will wire to runtime. Renders
 * a switch (always off, not interactive) + label + "Coming in 3.3" helper subtext. Stays
 * visually consistent with the live rows so 3.3's diff is just "remove the alpha and the
 * Coming-soon line."
 */
@Composable
private fun ComingSoonRow(label: String, helper: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().alpha(0.6f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = helper,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Coming in 3.3",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Switch(
                checked = false,
                onCheckedChange = null,
                enabled = false,
            )
        }
    }
}
