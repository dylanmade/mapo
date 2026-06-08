package com.mapo.ui.screen.remap.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.data.model.steam.InputSource
import com.mapo.data.model.steam.displayNameFor
import com.mapo.data.model.steam.resolveActionSet
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * Full-screen, schema-driven settings editor for one (source x mode) menu. Reached
 * by tapping the settings cog on a source row in Remap Controls (parallels the
 * activator-settings cog -> [com.mapo.ui.screen.ActivatorEditorScreen]).
 *
 * Instant-commit, no draft/save: reads the binding group live from [config] and
 * dispatches each change through [onSettingsChange]; the new value flows back via
 * the config flow. A full screen (not a bottom sheet) so long settings lists scroll
 * without the swipe-to-dismiss-vs-scroll conflict.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSettingsScreen(
    bindingGroupId: Long,
    source: InputSource,
    config: ControllerConfig?,
    viewingActionSetId: Long?,
    onSettingsChange: (bindingGroupId: Long, settingsJson: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val group = config?.resolveActionSet(viewingActionSetId)?.presetFor(source)?.group?.group
    val mode: BindingMode? = group?.mode
    val settingsJson = group?.settingsJson ?: "{}"
    val categories = remember(source, mode) {
        if (mode == null) emptyList() else SourceModeSettingsSchema.categoriesFor(source, mode)
    }
    val title = mode?.let { "${it.displayNameFor(source)} settings" } ?: "Settings"

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
                actions = {
                    // Reset clears the group's settings JSON; absent keys fall back to
                    // each spec's default, so this restores the whole menu to defaults.
                    TextButton(onClick = { onSettingsChange(bindingGroupId, "{}") }) {
                        Text("Reset")
                    }
                },
            )
        },
    ) { innerPadding ->
        val obj = remember(settingsJson) {
            try {
                JSONObject(settingsJson)
            } catch (_: Exception) {
                JSONObject()
            }
        }
        val lookup = SettingsLookup { key -> if (obj.has(key)) obj.get(key).toString() else null }
        val onChange: (String) -> Unit = { json -> onSettingsChange(bindingGroupId, json) }

        // A plain scrolling Column rather than a LazyColumn: these menus are short
        // (a handful of rows) so lazy recycling buys nothing, and text fields inside
        // a LazyColumn item hit a focus/IME reconciliation quirk where typed
        // characters don't render until focus is lost. A non-lazy Column avoids it.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            for (category in categories) {
                val visible = category.settings.filter { it.visibleWhen?.invoke(lookup) ?: true }
                if (visible.isEmpty()) continue
                Text(
                    text = category.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                for (spec in visible) {
                    SettingRow(spec = spec, obj = obj, settingsJson = settingsJson, onChange = onChange)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingRow(
    spec: SettingSpec,
    obj: JSONObject,
    settingsJson: String,
    onChange: (String) -> Unit,
) {
    when (val control = spec.control) {
        is SettingControl.Toggle -> ToggleSettingRow(
            spec = spec,
            current = obj.optBoolean(spec.key, control.default),
            onChange = { onChange(settingsWith(settingsJson, spec.key, it)) },
        )
        is SettingControl.Dropdown -> DropdownSettingRow(
            spec = spec,
            control = control,
            currentId = obj.optString(spec.key, control.defaultId),
            onChange = { onChange(settingsWith(settingsJson, spec.key, it)) },
        )
        is SettingControl.Slider -> SliderSettingRow(
            spec = spec,
            control = control,
            current = obj.optDouble(spec.key, control.default.toDouble()).toFloat(),
            onChange = { onChange(settingsWith(settingsJson, spec.key, it)) },
        )
        is SettingControl.RangeSlider -> RangeSliderSettingRow(
            spec = spec,
            control = control,
            start = obj.optDouble(control.startKey, control.startDefault.toDouble()).toFloat(),
            end = obj.optDouble(control.endKey, control.endDefault.toDouble()).toFloat(),
            onChange = { s, e ->
                onChange(
                    settingsWithAll(
                        settingsJson,
                        mapOf(control.startKey to s.toDouble(), control.endKey to e.toDouble()),
                    )
                )
            },
        )
    }
}

@Composable
private fun SettingHeader(label: String, helper: String?) {
    Text(label, style = MaterialTheme.typography.bodyLarge)
    if (helper != null) {
        Text(
            helper,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleSettingRow(
    spec: SettingSpec,
    current: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!current) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) { SettingHeader(spec.label, spec.helper) }
        Spacer(Modifier.width(12.dp))
        Switch(checked = current, onCheckedChange = { onChange(it) })
    }
}

@Composable
private fun DropdownSettingRow(
    spec: SettingSpec,
    control: SettingControl.Dropdown,
    currentId: String,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = control.options.firstOrNull { it.id == currentId }
        ?: control.options.first { it.id == control.defaultId }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        SettingHeader(spec.label, spec.helper)
        Spacer(Modifier.size(6.dp))
        // surfaceContainerLow chip — same idiom as the mode picker on the source row.
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.clickable { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selected.label, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            control.options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            if (option.helper != null) {
                                Text(
                                    option.helper,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        if (option.id != currentId) onChange(option.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun SliderSettingRow(
    spec: SettingSpec,
    control: SettingControl.Slider,
    current: Float,
    onChange: (Float) -> Unit,
) {
    // Shared draft drives both the slider and the manual-entry field; commit on
    // slider release / field done so we don't round-trip every delta through the DB.
    var draft by remember(current) { mutableStateOf(current) }
    val steps = sliderSteps(control.min, control.max, control.step)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) { SettingHeader(spec.label, spec.helper) }
            Spacer(Modifier.width(12.dp))
            NumericEntryField(
                value = draft,
                min = control.min,
                max = control.max,
                decimals = control.decimals,
                unitSuffix = control.unitSuffix,
                onValueChange = { draft = it.coerceIn(control.min, control.max) },
                onCommit = { onChange(it.coerceIn(control.min, control.max)) },
            )
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onChange(draft) },
            valueRange = control.min..control.max,
            steps = steps,
        )
    }
}

@Composable
private fun RangeSliderSettingRow(
    spec: SettingSpec,
    control: SettingControl.RangeSlider,
    start: Float,
    end: Float,
    onChange: (Float, Float) -> Unit,
) {
    var lo by remember(start) { mutableStateOf(start) }
    var hi by remember(end) { mutableStateOf(end) }
    val steps = sliderSteps(control.min, control.max, control.step)

    fun commit(newLo: Float, newHi: Float) {
        // Lower handle can't pass the upper.
        val cLo = newLo.coerceIn(control.min, control.max)
        val cHi = newHi.coerceIn(control.min, control.max)
        val finalLo = minOf(cLo, cHi)
        val finalHi = maxOf(cLo, cHi)
        lo = finalLo; hi = finalHi
        onChange(finalLo, finalHi)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        SettingHeader(spec.label, spec.helper)
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumericEntryField(
                value = lo,
                min = control.min,
                max = control.max,
                decimals = control.decimals,
                unitSuffix = control.unitSuffix,
                onValueChange = { lo = it.coerceIn(control.min, control.max) },
                onCommit = { commit(it, hi) },
            )
            Spacer(Modifier.width(8.dp))
            Text("to", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            NumericEntryField(
                value = hi,
                min = control.min,
                max = control.max,
                decimals = control.decimals,
                unitSuffix = control.unitSuffix,
                onValueChange = { hi = it.coerceIn(control.min, control.max) },
                onCommit = { commit(lo, it) },
            )
        }
        RangeSlider(
            value = lo..hi,
            onValueChange = { range -> lo = range.start; hi = range.endInclusive },
            onValueChangeFinished = { commit(lo, hi) },
            valueRange = control.min..control.max,
            steps = steps,
        )
    }
}

/**
 * Compact numeric input for slider rows' manual entry. Never auto-focuses.
 *
 * Owns its own text buffer and only re-syncs from [value] while NOT focused, so an
 * async source update (slider drag, Reset, repo round-trip) can't clobber what the
 * user is typing mid-edit — the root cause of the app-wide "typing doesn't show"
 * bug. [onValueChange] fires live (for slider tracking); [onCommit] fires on
 * keyboard-done and on focus loss (guarded so it doesn't fire on first compose).
 */
@Composable
private fun NumericEntryField(
    value: Float,
    min: Float,
    max: Float,
    decimals: Int,
    unitSuffix: String,
    onValueChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    // State-based text field: the field owns its buffer (TextFieldState) directly, so
    // edits render without the value/onValueChange IME↔state round-trip that was
    // desyncing the display (backspace not repainting, digits appearing only on Done).
    val state = rememberTextFieldState(formatNumber(value, decimals))

    // Mirror the external value (slider drag, Reset, repo round-trip) into the buffer
    // only while the field isn't focused — never clobber what the user is typing.
    LaunchedEffect(value, focused) {
        if (!focused) {
            val formatted = formatNumber(value, decimals)
            if (formatted != state.text.toString()) state.setTextAndPlaceCursorAtEnd(formatted)
        }
    }
    // Track the slider live for in-range edits; out-of-range typing leaves the slider
    // put and just shows the warning.
    LaunchedEffect(Unit) {
        snapshotFlow { state.text.toString() }.collect { s ->
            s.toFloatOrNull()?.let { if (it in min..max) onValueChange(it) }
        }
    }
    // Free typing: the buffer accepts any input. Out-of-range / unparseable entries are
    // flagged with the field's native error state + a range hint rather than blocked or
    // rewritten mid-keystroke; the value is clamped only on commit (Done / focus loss),
    // so what's persisted is always valid and the user saw the warning first.
    val current = state.text.toString()
    val parsed = current.toFloatOrNull()
    val invalid = focused && current.isNotBlank() && (parsed == null || parsed < min || parsed > max)

    OutlinedTextField(
        state = state,
        lineLimits = TextFieldLineLimits.SingleLine,
        isError = invalid,
        suffix = if (unitSuffix.isNotEmpty()) ({ Text(unitSuffix) }) else null,
        supportingText = if (invalid) {
            { Text("Allowed: ${formatNumber(min, decimals)}–${formatNumber(max, decimals)}$unitSuffix") }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        // Done hides the keyboard by clearing focus, which runs the commit path below.
        onKeyboardAction = { focusManager.clearFocus() },
        modifier = Modifier
            .width(116.dp)
            .onFocusChanged { fs ->
                val wasFocused = focused
                focused = fs.isFocused
                // On blur, commit the clamped value; the field then resyncs (via the
                // LaunchedEffect above) to the formatted in-range result, so an
                // out-of-range entry visibly snaps to the nearest valid value.
                if (wasFocused && !fs.isFocused) {
                    onCommit((state.text.toString().toFloatOrNull() ?: value).coerceIn(min, max))
                }
            },
    )
}

private fun sliderSteps(min: Float, max: Float, step: Float?): Int =
    step?.let { (((max - min) / it).roundToInt() - 1).coerceAtLeast(0) } ?: 0

private fun formatNumber(value: Float, decimals: Int): String =
    if (decimals <= 0) value.roundToInt().toString() else "%.${decimals}f".format(value)

/** Returns [currentJson] with [key] set to [value] (string / boolean / number). */
private fun settingsWith(currentJson: String, key: String, value: Any): String =
    settingsWithAll(currentJson, mapOf(key to value))

/** Returns [currentJson] with every (key → value) in [entries] applied. */
private fun settingsWithAll(currentJson: String, entries: Map<String, Any>): String {
    val obj = try {
        JSONObject(currentJson)
    } catch (_: Exception) {
        JSONObject()
    }
    for ((key, value) in entries) {
        when (value) {
            is Float -> obj.put(key, value.toDouble())
            else -> obj.put(key, value)
        }
    }
    return obj.toString()
}
