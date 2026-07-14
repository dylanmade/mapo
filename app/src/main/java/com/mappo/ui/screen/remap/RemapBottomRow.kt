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
import androidx.compose.material.icons.outlined.Layers
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mappo.data.model.steam.ActionSetGraph
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.InputSource
import com.mappo.R
import com.mappo.service.input.modes.SourceModeCatalog

/**
 * The control strip beneath the simple remap view's band: the Gyro mode picker and the Overlay
 * association picker, each with a leading glyph on its caption. Gyro lives here rather than in
 * a group box — it has no bindable sub-inputs; its whole configuration is the mode (deeper
 * tuning moves elsewhere once the wizard lands).
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
        // Lucide rotate-3d — the Material ScreenRotation glyph read oversized and off-style.
        StripCaption(icon = painterResource(R.drawable.lucide_rotate_3d), text = "Gyro")
        Spacer(Modifier.width(6.dp))
        if (gyroGroup != null && gyroModes.isNotEmpty()) {
            ModePillDropdown(
                source = InputSource.GYRO,
                currentMode = gyroGroup.mode,
                validModes = gyroModes,
                enabled = !viewingLayerSelected && gyroModes.size > 1,
                onPick = { mode -> onSetGyroMode(gyroGroup.id, mode) },
                fixedWidth = RemapStripPillWidth,
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
        StripCaption(icon = rememberVectorPainter(Icons.Outlined.Layers), text = "Overlay")
        Spacer(Modifier.width(6.dp))
        OverlayPillDropdown()
    }
}

/** A strip caption: small leading glyph + label, in the muted caption treatment. */
@Composable
private fun StripCaption(icon: androidx.compose.ui.graphics.painter.Painter, text: String) {
    Icon(
        icon,
        contentDescription = null,
        modifier = Modifier.size(RemapPillIconSize),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.width(RemapGlyphLabelGap))
    Text(
        text = text,
        style = remapMiniTextStyle(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Placeholder overlay picker: "None" + a disabled empty-state entry. Selection is local UI
 *  state only — no persistence target exists yet. */
@Composable
private fun OverlayPillDropdown() {
    var open by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf("None") }
    Box {
        // Shared box treatment — pill-style dropdown button, no trailing arrow. Static width,
        // unified with the Gyro pill.
        val container = remapBoxContainer()
        Surface(
            shape = RoundedCornerShape(50),
            color = container,
            border = remapBevelBorder(container, RemapPillHeight / 2),
            modifier = Modifier
                .remapFocusScale()
                .heightIn(min = RemapPillHeight)
                .width(RemapStripPillWidth)
                .clip(RoundedCornerShape(50))
                .clickable(onClickLabel = "Choose overlay") { open = true },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = RemapPillContentPadding),
            ) {
                Text(text = selected, style = remapMiniTextStyle())
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("None") },
                trailingIcon = if (selected == "None") {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else null,
                onClick = { selected = "None"; open = false },
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
