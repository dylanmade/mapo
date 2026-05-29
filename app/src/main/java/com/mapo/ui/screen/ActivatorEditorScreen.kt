package com.mapo.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingOutputType
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.data.model.steam.displayName
import com.mapo.data.model.steam.resolveActionSet
import com.mapo.service.input.CompiledActivatorSettings
import kotlin.math.roundToLong

/**
 * Per-activator settings editor. Reached from the cog button on each row in
 * `InputEditorScreen`. Shows the per-type timing controls plus the universal-settings
 * panel (toggle, hold-to-repeat / turbo, fire start/end delays, cycle bindings) and
 * interruption controls (FULL_PRESS / RELEASE_PRESS only).
 *
 * Instant-commit: every slider drag-end or switch toggle calls [onSettingsChange] which
 * writes through to the repo. No draft / Save / Cancel layer — the rest of Mapo's editing
 * screens behave the same way (per `RemapControlsScreen` and `InputEditorScreen`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivatorEditorScreen(
    activatorId: Long,
    title: String,
    config: ControllerConfig?,
    viewingActionSetId: Long? = null,
    onSettingsChange: (Long, CompiledActivatorSettings) -> Unit,
    onPickChordPartner: (Long) -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activatorContext = remember(config, activatorId, viewingActionSetId) {
        findActivatorContext(config, activatorId, viewingActionSetId)
    }

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

        val type = activatorContext.type
        val settings = activatorContext.settings
        val hasMouseBinding = activatorContext.hasMouseBinding
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
            // ── Chord partner (CHORDED_PRESS only) ─────────────────────────────
            if (type == ActivatorType.CHORDED_PRESS) {
                SectionHeader("Chord partner")
                ChordPartnerRow(
                    partnerSourceLabel = settings.chordPartnerSource?.displayName(),
                    partnerKey = settings.chordPartnerKey,
                    onPick = { onPickChordPartner(activatorId) },
                )
            }

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

            // ── Universal settings (Brick 3.3 — live) ─────────────────────────
            SectionHeader("Universal settings")
            SettingsSwitchRow(
                label = "Toggle",
                helper = "Stays active after release until pressed again.",
                checked = settings.toggle,
                onCheckedChange = { newValue ->
                    onSettingsChange(activatorId, settings.copy(toggle = newValue))
                },
            )
            SettingsSwitchRow(
                label = "Hold-to-repeat (Turbo)",
                helper = "Pulses the command repeatedly while held.",
                checked = settings.holdToRepeat,
                onCheckedChange = { newValue ->
                    onSettingsChange(activatorId, settings.copy(holdToRepeat = newValue))
                },
            )
            if (settings.holdToRepeat) {
                TimingSlider(
                    label = "Repeat rate",
                    helper = "Time between turbo pulses.",
                    currentMs = settings.repeatRateMs,
                    rangeMs = 20f..1000f,
                    onCommit = { newMs ->
                        onSettingsChange(activatorId, settings.copy(repeatRateMs = newMs))
                    },
                )
            }
            TimingSlider(
                label = "Fire start delay",
                helper = "Delay before the command fires once activation conditions are met.",
                currentMs = settings.fireStartDelayMs,
                rangeMs = 0f..2000f,
                onCommit = { newMs ->
                    onSettingsChange(activatorId, settings.copy(fireStartDelayMs = newMs))
                },
            )
            TimingSlider(
                label = "Fire end delay",
                helper = "Keeps the command active past its physical release.",
                currentMs = settings.fireEndDelayMs,
                rangeMs = 0f..2000f,
                onCommit = { newMs ->
                    onSettingsChange(activatorId, settings.copy(fireEndDelayMs = newMs))
                },
            )
            SettingsSwitchRow(
                label = "Cycle commands",
                helper = "Cycles through this activator's commands sequentially on each fire.",
                checked = settings.cycleBindings,
                onCheckedChange = { newValue ->
                    onSettingsChange(activatorId, settings.copy(cycleBindings = newValue))
                },
            )

            // Interruptable: only valid on FULL_PRESS / RELEASE_PRESS per Steam.
            if (type == ActivatorType.FULL_PRESS || type == ActivatorType.RELEASE_PRESS) {
                SectionHeader("Interruption")
                SettingsSwitchRow(
                    label = "Interruptable",
                    helper = "Lets longer/double activators suppress this one when they fire.",
                    checked = settings.interruptable,
                    onCheckedChange = { newValue ->
                        onSettingsChange(activatorId, settings.copy(interruptable = newValue))
                    },
                )
            }

            // Mouse output: only relevant when at least one of this activator's bindings
            // is mouse-shaped (MouseButton / MouseWheel). Lets the user pick between a
            // real mouse event (uinput BTN_LEFT / REL_WHEEL — works in standard Android
            // apps) and a synthetic touch event (dispatchGesture — works in emulator
            // frontends like RetroArch with libretro-pointer cores or GameNative's touch
            // wrapper that ignore real mouse buttons).
            if (hasMouseBinding) {
                SectionHeader("Mouse output")
                SettingsSwitchRow(
                    label = "Send as gesture",
                    helper = "Emit as a synthetic touch event instead of a real mouse click. " +
                        "Try enabling this if an app doesn't respond to mouse inputs.",
                    checked = settings.sendAsGesture,
                    onCheckedChange = { newValue ->
                        onSettingsChange(activatorId, settings.copy(sendAsGesture = newValue))
                    },
                )
            }
        }
    }
}

/**
 * Resolve [activatorId] against [config] to return its type + parsed settings. Walks the
 * action set selected by [viewingActionSetId] (falling back to the controller_profile
 * default) in O(N) of that set's graph; called once per recomposition with `remember`
 * keying so it's cheap. An activator id from a different set won't resolve here — the
 * caller is expected to keep the viewing pointer aligned with how the editor was opened.
 */
/**
 * Resolved view of an activator for the editor: its type, parsed settings, and a
 * derived flag that tells the UI whether the "Send as gesture" toggle should appear
 * (only when at least one of the activator's bindings emits a mouse-shaped output —
 * the toggle is a no-op for keyboard/gamepad bindings).
 */
private data class ActivatorContext(
    val type: ActivatorType,
    val settings: CompiledActivatorSettings,
    val hasMouseBinding: Boolean,
)

private fun findActivatorContext(
    config: ControllerConfig?,
    activatorId: Long,
    viewingActionSetId: Long? = null,
): ActivatorContext? {
    val activeSet = config?.resolveActionSet(viewingActionSetId) ?: return null

    fun resolve(graph: com.mapo.data.model.steam.ActivatorGraph): ActivatorContext {
        val settings = CompiledActivatorSettings.parse(graph.activator.settingsJson)
        val hasMouseBinding = graph.bindings.any {
            it.outputType == BindingOutputType.MOUSE_BUTTON ||
                it.outputType == BindingOutputType.MOUSE_WHEEL
        }
        return ActivatorContext(
            type = graph.activator.type,
            settings = settings,
            hasMouseBinding = hasMouseBinding,
        )
    }

    fun scan(groups: Sequence<com.mapo.data.model.steam.BindingGroupGraph>): ActivatorContext? {
        for (group in groups) {
            for (input in group.inputs) {
                for (graph in input.activators) {
                    if (graph.activator.id == activatorId) return resolve(graph)
                }
            }
        }
        return null
    }

    // Search every binding-group bucket reachable from the active set:
    //  1. Set's preset entries (base source bindings)
    //  2. Set's mode-shift target groups (Brick B.6)
    //  3. Every action layer's preset entries (overrides) and mode-shift target groups
    val setPresetGroups = activeSet.preset.asSequence().map { it.group }
    val setModeShiftGroups = activeSet.modeShifts.asSequence().map { it.group }
    val layerPresetGroups = activeSet.layers.asSequence().flatMap { it.preset.asSequence().map { p -> p.group } }
    val layerModeShiftGroups = activeSet.layers.asSequence().flatMap { it.modeShifts.asSequence().map { s -> s.group } }

    return scan(setPresetGroups)
        ?: scan(setModeShiftGroups)
        ?: scan(layerPresetGroups)
        ?: scan(layerModeShiftGroups)
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
 * Chord-partner row. Shows the current partner (source · key) and a "Pick / Change"
 * button that opens the listen-for-press capture screen. While that screen is up, the
 * next physical button DOWN becomes the new partner.
 */
@Composable
private fun ChordPartnerRow(
    partnerSourceLabel: String?,
    partnerKey: String?,
    onPick: () -> Unit,
) {
    // Settings-row treatment (row-doctrine #4): ListItem with the partner value as headline,
    // helper as supporting text, and the Set/Change button in the trailing slot.
    ListItem(
        headlineContent = {
            Text(
                text = if (partnerSourceLabel != null && partnerKey != null)
                    "$partnerSourceLabel · $partnerKey"
                else "Not set",
                color = if (partnerSourceLabel != null)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        supportingContent = {
            Text(text = "The chord activator only fires when this partner is currently held.")
        },
        trailingContent = {
            FilledTonalButton(onClick = onPick) {
                Text(
                    text = if (partnerSourceLabel == null) "Set" else "Change",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Live universal-settings row: M3 switch on the right, label + helper subtext on the left.
 * Tapping anywhere on the row toggles the switch (matches M3 settings-screen idiom; tap
 * target is the whole row, not just the switch thumb).
 */
@Composable
private fun SettingsSwitchRow(
    label: String,
    helper: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // Settings-row treatment (row-doctrine #4): ListItem with label headline, helper as
    // supporting text, and trailing Switch. Whole row is the tap target so the row
    // owns the toggle (onCheckedChange = null on the Switch itself).
    ListItem(
        headlineContent = { Text(text = label) },
        supportingContent = { Text(text = helper) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
    )
}
