package com.mappo.ui.screen.remap

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.AccountTree
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mappo.data.model.steam.ActionSetGraph
import com.mappo.data.model.steam.BindingMode
import com.mappo.data.model.steam.InputSource
import com.mappo.R
import com.mappo.service.input.modes.SourceModeCatalog

/**
 * The control strip beneath the simple remap view's band: three set-scoped pickers, each a
 * bare caption label + a pill carrying the element's identity icon and current value
 * (restyled 2026-07-13 — the icon moved off the caption INTO the pill).
 *
 * - **Inherit** — make this action set inherit from another set. This generalizes Steam's
 *   action layers: a layer must be a direct child of one action set, while Mappo inheritance
 *   is a free set→set relation. DELIBERATE STRUCTURAL DEVIATION from Steam Input — noted in
 *   the parity plan's post-parity section; VDF import will map Steam layers onto it.
 *   UI-only scaffolding for now (no persistence target).
 * - **Overlay** — associate an overlay with the set. UI-only scaffolding: overlays have no
 *   named grouping entity yet (elements scope directly to sets/layers); wiring lands with
 *   the overlay-naming work.
 * - **Gyro** — the gyro mode picker. Gyro lives here rather than in a group box — it has no
 *   bindable sub-inputs; its whole configuration is the mode (deeper tuning moves elsewhere
 *   once the wizard lands).
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
        // ── Inherit ─────────────────────────────────────────────────────────
        StripCaption("Inherit")
        Spacer(Modifier.width(StripCaptionGap))
        PlaceholderStripPill(
            icon = rememberVectorPainter(Icons.Filled.AccountTree),
            onClickLabel = "Choose set to inherit",
            emptyHint = "Nothing to inherit yet",
        )

        Spacer(Modifier.width(StripGroupGap))

        // ── Overlay ─────────────────────────────────────────────────────────
        StripCaption("Overlay")
        Spacer(Modifier.width(StripCaptionGap))
        PlaceholderStripPill(
            icon = rememberVectorPainter(Icons.Outlined.Layers),
            onClickLabel = "Choose overlay",
            emptyHint = "No overlays yet",
        )

        Spacer(Modifier.width(StripGroupGap))

        // ── Gyro ────────────────────────────────────────────────────────────
        StripCaption("Gyro")
        Spacer(Modifier.width(StripCaptionGap))
        if (gyroGroup != null && gyroModes.isNotEmpty()) {
            ModePillDropdown(
                source = InputSource.GYRO,
                currentMode = gyroGroup.mode,
                validModes = gyroModes,
                enabled = !viewingLayerSelected && gyroModes.size > 1,
                onPick = { mode -> onSetGyroMode(gyroGroup.id, mode) },
                fixedWidth = RemapStripPillWidth,
                // Lucide rotate-3d — the Material ScreenRotation glyph read oversized and
                // off-style; identity icon lives INSIDE the pill (strip convention).
                leadingIcon = painterResource(R.drawable.lucide_rotate_3d),
            )
        } else {
            Text(
                text = "—",
                style = remapMiniTextStyle(),
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** A strip caption: bare label in the muted caption treatment (no leading icon — the
 *  element's identity icon lives inside its pill). */
@Composable
private fun StripCaption(text: String) {
    Text(
        text = text,
        style = remapMiniTextStyle(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Placeholder strip picker pill (Inherit / Overlay): identity icon + current value, menu of
 * "None" + a disabled empty-state entry. Selection is local UI state only — no persistence
 * target exists yet for either element.
 */
@Composable
private fun PlaceholderStripPill(
    icon: Painter,
    onClickLabel: String,
    emptyHint: String,
) {
    var open by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf("None") }
    Box {
        // Shared box treatment — pill-style dropdown button, no trailing arrow. Static width,
        // unified across the strip's pills.
        val container = remapBoxContainer()
        val interaction = remember { MutableInteractionSource() }
        Surface(
            shape = RoundedCornerShape(50),
            color = container,
            border = remapBevelBorder(container, RemapPillHeight / 2),
            modifier = Modifier
                .remapInteractiveScale(interaction)
                .heightIn(min = RemapPillHeight)
                .width(RemapStripPillWidth)
                .clip(RoundedCornerShape(50))
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClickLabel = onClickLabel,
                ) { open = true },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = RemapPillContentPadding),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(RemapPillIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(RemapGlyphLabelGap))
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
                        emptyHint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                },
                onClick = {},
            )
        }
    }
}

/** Gap between a picker's caption label and its pill. */
private val StripCaptionGap = 6.dp

/** Gap between the strip's picker groups. Tightened from 24dp when the third picker landed. */
private val StripGroupGap = 16.dp
