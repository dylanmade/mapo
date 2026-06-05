package com.mapo.ui.screen.remap.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            for (category in categories) {
                val visible = category.settings.filter { it.visibleWhen?.invoke(lookup) ?: true }
                if (visible.isEmpty()) continue
                item(key = "cat:${category.title}") {
                    Text(
                        text = category.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(items = visible, key = { "s:${it.key}" }) { spec ->
                    SettingRow(spec = spec, obj = obj, settingsJson = settingsJson, onChange = onChange)
                }
            }
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
    decimals: Int,
    unitSuffix: String,
    onValueChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(formatNumber(value, decimals)) }
    LaunchedEffect(value, focused) {
        if (!focused) text = formatNumber(value, decimals)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s
            s.toFloatOrNull()?.let { onValueChange(it) }
        },
        singleLine = true,
        suffix = if (unitSuffix.isNotEmpty()) ({ Text(unitSuffix) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit(text.toFloatOrNull() ?: value) }),
        modifier = Modifier
            .width(116.dp)
            .onFocusChanged { fs ->
                val wasFocused = focused
                focused = fs.isFocused
                if (wasFocused && !fs.isFocused) onCommit(text.toFloatOrNull() ?: value)
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
