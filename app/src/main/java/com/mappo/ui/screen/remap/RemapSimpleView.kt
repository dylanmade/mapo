package com.mappo.ui.screen.remap

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mappo.R
import com.mappo.data.model.steam.ActionLayerGraph
import com.mappo.data.model.steam.ActionSetGraph
import com.mappo.data.model.steam.ActivatorType
import com.mappo.data.model.steam.BindingGroupGraph
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.BindingOutput
import com.mappo.data.model.steam.ControllerConfig
import com.mappo.data.model.steam.InputSource
import com.mappo.data.model.steam.displayLabel
import com.mappo.data.model.steam.displayNameFor
import com.mappo.ui.glyph.InputGlyphs

/**
 * The simplified ("basic") remap view: a controller diagram in the middle flanked by one
 * tappable box per input group, each row showing just the input glyph + what its **standard
 * press** currently does. No inline editing — tapping a box opens the advanced editor dialog
 * (the rail + detail pane) on that group's section. The top-center **Map** button is the future
 * home of the input-mapping wizard (UI-only for now).
 *
 * Box styling mirrors the home d-pad flower's petal cards (accent-tinted rounded box + border)
 * so the two "launcher" surfaces read as one family.
 */
@Composable
internal fun RemapSimpleView(
    viewingSet: ActionSetGraph?,
    viewingLayer: ActionLayerGraph?,
    config: ControllerConfig?,
    onMap: () -> Unit,
    onOpenGroup: (RemapSimpleGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        // Wide gutter keeps the side group boxes off the controller image.
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Left column, counterclockwise start: shoulder → d-pad → stick. Boxes anchor toward
        // the screen center (the controller), i.e. this column's END edge.
        Column(
            modifier = Modifier.weight(1f).fillMaxSize(),
            // Boxes cluster toward the vertical center rather than spreading edge-to-edge.
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.End,
        ) {
            GroupBox(RemapSimpleGroup.LEFT_SHOULDER, viewingSet, viewingLayer, config, onOpenGroup)
            GroupBox(RemapSimpleGroup.DPAD, viewingSet, viewingLayer, config, onOpenGroup)
            GroupBox(RemapSimpleGroup.LEFT_STICK, viewingSet, viewingLayer, config, onOpenGroup)
        }
        // Middle column: Map CTA above the controller, utility buttons beneath it.
        Column(
            modifier = Modifier.weight(1.25f).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onMap,
                modifier = Modifier.testTag("map-button"),
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Map")
            }
            Spacer(Modifier.weight(1f))
            Image(
                painter = painterResource(R.drawable.controller_placeholder),
                contentDescription = "Controller",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.weight(1f))
            GroupBox(RemapSimpleGroup.UTILITY, viewingSet, viewingLayer, config, onOpenGroup)
        }
        // Right column: shoulder → face buttons → stick. Boxes anchor toward the screen
        // center (this column's START edge).
        Column(
            modifier = Modifier.weight(1f).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.Start,
        ) {
            GroupBox(RemapSimpleGroup.RIGHT_SHOULDER, viewingSet, viewingLayer, config, onOpenGroup)
            GroupBox(RemapSimpleGroup.FACE, viewingSet, viewingLayer, config, onOpenGroup)
            GroupBox(RemapSimpleGroup.RIGHT_STICK, viewingSet, viewingLayer, config, onOpenGroup)
        }
    }
}

/** One display row in a group box: which sub-input to summarize. */
internal data class SimpleRowSpec(val source: InputSource, val subInputKey: String)

/**
 * The simple view's input groups. Each opens the advanced editor on [sectionId]; rows summarize
 * the standard-press assignment only (hold/double/etc. live in the advanced editor).
 */
internal enum class RemapSimpleGroup(val sectionId: String, val rows: List<SimpleRowSpec>) {
    LEFT_SHOULDER(
        RemapSections.SECTION_TRIGGERS,
        listOf(
            SimpleRowSpec(InputSource.LEFT_TRIGGER, "full_pull"),
            SimpleRowSpec(InputSource.LEFT_BUMPER, "click"),
        ),
    ),
    DPAD(
        RemapSections.SECTION_DPAD,
        listOf(
            SimpleRowSpec(InputSource.DPAD, "dpad_up"),
            SimpleRowSpec(InputSource.DPAD, "dpad_left"),
            SimpleRowSpec(InputSource.DPAD, "dpad_right"),
            SimpleRowSpec(InputSource.DPAD, "dpad_down"),
        ),
    ),
    LEFT_STICK(
        RemapSections.SECTION_JOYSTICKS,
        listOf(
            SimpleRowSpec(InputSource.LEFT_JOYSTICK, "dpad_up"),
            SimpleRowSpec(InputSource.LEFT_JOYSTICK, "dpad_left"),
            SimpleRowSpec(InputSource.LEFT_JOYSTICK, "dpad_right"),
            SimpleRowSpec(InputSource.LEFT_JOYSTICK, "dpad_down"),
            SimpleRowSpec(InputSource.LEFT_JOYSTICK, "click"),
        ),
    ),
    UTILITY(
        RemapSections.SECTION_BUTTONS,
        listOf(
            SimpleRowSpec(InputSource.SWITCH_START, "click"),
            SimpleRowSpec(InputSource.SWITCH_SELECT, "click"),
        ),
    ),
    RIGHT_STICK(
        RemapSections.SECTION_JOYSTICKS,
        listOf(
            SimpleRowSpec(InputSource.RIGHT_JOYSTICK, "dpad_up"),
            SimpleRowSpec(InputSource.RIGHT_JOYSTICK, "dpad_left"),
            SimpleRowSpec(InputSource.RIGHT_JOYSTICK, "dpad_right"),
            SimpleRowSpec(InputSource.RIGHT_JOYSTICK, "dpad_down"),
            SimpleRowSpec(InputSource.RIGHT_JOYSTICK, "click"),
        ),
    ),
    FACE(
        RemapSections.SECTION_BUTTONS,
        listOf(
            SimpleRowSpec(InputSource.BUTTON_DIAMOND, "button_y"),
            SimpleRowSpec(InputSource.BUTTON_DIAMOND, "button_x"),
            SimpleRowSpec(InputSource.BUTTON_DIAMOND, "button_b"),
            SimpleRowSpec(InputSource.BUTTON_DIAMOND, "button_a"),
        ),
    ),
    RIGHT_SHOULDER(
        RemapSections.SECTION_TRIGGERS,
        listOf(
            SimpleRowSpec(InputSource.RIGHT_TRIGGER, "full_pull"),
            SimpleRowSpec(InputSource.RIGHT_BUMPER, "click"),
        ),
    ),
}

/**
 * Resolve what a row's standard press currently does, as a short label:
 * `(Device default)` mode → "Default"; `None` → "None"; a bound FULL_PRESS command → its
 * display label; an active mode with no per-input row (analog modes) → the mode's name.
 * Layer view resolves the override first and falls through to the base set (ghost semantics).
 */
internal fun simpleRowLabel(
    viewingSet: ActionSetGraph?,
    viewingLayer: ActionLayerGraph?,
    config: ControllerConfig?,
    spec: SimpleRowSpec,
): String {
    val layerGroup: BindingGroupGraph? = viewingLayer?.presetFor(spec.source)?.group
    val baseGroup: BindingGroupGraph? = viewingSet?.presetFor(spec.source)?.group
    val effective = layerGroup ?: baseGroup ?: return "Default"
    when (effective.group.mode) {
        BindingMode.DEVICE_DEFAULT -> return "Default"
        BindingMode.NONE -> return "None"
        else -> Unit
    }
    val groupInput = layerGroup?.inputByKey(spec.subInputKey)
        ?: baseGroup?.inputByKey(spec.subInputKey)
    val primary = groupInput?.activators?.firstOrNull { it.activator.type == ActivatorType.FULL_PRESS }
        ?: groupInput?.activators?.firstOrNull()
    val output = primary?.primaryOutput
    return when {
        // Unbound displays as "(Device default)" in the editor; the summary reads "Default".
        output != null && output != BindingOutput.Unbound -> output.displayLabel(config)
        groupInput != null -> "Default"
        else -> effective.group.mode.displayNameFor(spec.source)
    }
}

/**
 * One tappable group box: glyph + standard-press label per row. Petal-card styling (see
 * HomeFlower.PetalCard) with the primary accent; the whole box is the tap target that opens
 * the advanced editor for the group's section.
 */
@Composable
private fun GroupBox(
    group: RemapSimpleGroup,
    viewingSet: ActionSetGraph?,
    viewingLayer: ActionLayerGraph?,
    config: ControllerConfig?,
    onOpenGroup: (RemapSimpleGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val accent = MaterialTheme.colorScheme.primary
    // Accent tint composited over surfaceContainerLow — same identity treatment as the home
    // flower's petal cards.
    val container = accent.copy(alpha = 0.08f)
        .compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
    Column(
        modifier = modifier
            .clip(shape)
            .background(container)
            .border(1.dp, accent.copy(alpha = 0.35f), shape)
            .clickable { onOpenGroup(group) }
            .testTag("simple-group:${group.name}")
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        group.rows.forEach { spec ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.height(14.dp),
            ) {
                InputGlyphs.SubInputGlyph(spec.source, spec.subInputKey, size = 12.dp)
                Text(
                    text = simpleRowLabel(viewingSet, viewingLayer, config, spec),
                    style = remapMiniTextStyle(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
