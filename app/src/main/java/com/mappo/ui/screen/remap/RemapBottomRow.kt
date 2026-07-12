package com.mappo.ui.screen.remap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mappo.data.model.steam.ActionSetGraph
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.InputSource
import com.mappo.service.input.modes.SourceModeCatalog
import com.mappo.ui.screen.remap.settings.SourceModeSettingsSchema

/**
 * The control strip under the simple remap view: the Gyro mode picker (+ settings cog) and the
 * Overlay association picker (+ edit button). Gyro lives here rather than in a group box — it
 * has no bindable sub-inputs; its whole configuration is the mode + the cog menu.
 *
 * The Overlay picker is UI-only scaffolding for now: overlays have no named grouping entity yet
 * (overlay elements scope directly to sets/layers), so there is nothing real to list or persist.
 * It renders the intended chrome so the layout is final; wiring lands with the overlay-naming
 * work.
 */
@Composable
internal fun RemapBottomRow(
    viewingSet: ActionSetGraph?,
    viewingLayerSelected: Boolean,
    onSetGyroMode: (bindingGroupId: Long, mode: BindingMode) -> Unit,
    onOpenModeSettings: (bindingGroupId: Long, source: InputSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gyroGroup = viewingSet?.presetFor(InputSource.GYRO)?.group?.group
    val gyroModes = SourceModeCatalog.modesValidFor(InputSource.GYRO)

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── Gyro ────────────────────────────────────────────────────────────
        Text(
            text = "Gyro",
            style = remapMiniTextStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        if (gyroGroup != null && gyroModes.isNotEmpty()) {
            ModePillDropdown(
                source = InputSource.GYRO,
                currentMode = gyroGroup.mode,
                validModes = gyroModes,
                enabled = !viewingLayerSelected && gyroModes.size > 1,
                onPick = { mode -> onSetGyroMode(gyroGroup.id, mode) },
            )
            Spacer(Modifier.width(3.dp))
            val cogEnabled = SourceModeSettingsSchema.hasSettings(InputSource.GYRO, gyroGroup.mode)
            RemapMiniIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = "Gyro mode settings",
                enabled = cogEnabled,
                onClick = { onOpenModeSettings(gyroGroup.id, InputSource.GYRO) },
            )
        } else {
            Text(
                text = "—",
                style = remapMiniTextStyle(),
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Spacer(Modifier.width(24.dp))

        // ── Overlay ─────────────────────────────────────────────────────────
        Text(
            text = "Overlay",
            style = remapMiniTextStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        OverlayPillDropdown()
        Spacer(Modifier.width(3.dp))
        RemapMiniIconButton(
            icon = Icons.Filled.Edit,
            contentDescription = "Edit overlay",
            enabled = false, // UI-only until named overlays exist
            onClick = {},
        )
    }
}

/** Placeholder overlay picker: "(None)" + a disabled empty-state entry. Selection is local UI
 *  state only — no persistence target exists yet. */
@Composable
private fun OverlayPillDropdown() {
    var open by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf("(None)") }
    Box {
        // Shared box treatment — pill-style dropdown button, no trailing arrow.
        val container = remapBoxContainer()
        Surface(
            shape = RoundedCornerShape(50),
            color = container,
            border = remapBevelBorder(container, RemapPillHeight / 2),
            modifier = Modifier
                .heightIn(min = RemapPillHeight)
                .clip(RoundedCornerShape(50))
                .clickable(onClickLabel = "Choose overlay") { open = true },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Icon(
                    Icons.Filled.Layers,
                    contentDescription = null,
                    modifier = Modifier.size(RemapPillIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(text = selected, style = remapMiniTextStyle())
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("(None)") },
                trailingIcon = if (selected == "(None)") {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else null,
                onClick = { selected = "(None)"; open = false },
            )
            DropdownMenuItem(
                enabled = false,
                text = {
                    Text(
                        "No overlays yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                },
                onClick = {},
            )
        }
    }
}
