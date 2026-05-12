package com.themestudio.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/**
 * Standard M3 list of every editable color role. Each row pairs a
 * plain-language label with a one-line description of what the color
 * controls, plus a trailing hex + swatch chip.
 *
 * "on*" roles render indented under their parent (e.g. On Primary under
 * Primary) so the hierarchy is visible at a glance. Tapping any row fires
 * [onPickRole] with the role's stable variable name (e.g. "onPrimary"),
 * matching [com.themestudio.core.ColorRoles] entries.
 *
 * The list is not lazy because it composes inside the Theme Studio's
 * vertical-scroll content area; the row count (~50) is fixed and well
 * inside what eager layout can handle.
 */
@Composable
fun ColorRoleList(
    modifier: Modifier = Modifier,
    onPickRole: ((roleName: String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val entries = remember { COLOR_ROLE_ENTRIES }
    Column(modifier = modifier.fillMaxWidth()) {
        entries.forEach { entry ->
            val color = entry.read(scheme)
            ColorRoleListItem(
                entry = entry,
                color = color,
                onClick = onPickRole?.let { { it(entry.roleName) } },
            )
        }
    }
}

@Composable
private fun ColorRoleListItem(
    entry: ColorRoleEntry,
    color: Color,
    onClick: (() -> Unit)?,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m }
        // Indent "on*" sub-roles so the hierarchy reads at a glance.
        .padding(start = if (entry.isChild) 24.dp else 0.dp)
    ListItem(
        modifier = rowModifier,
        headlineContent = { Text(entry.friendlyName) },
        supportingContent = { Text(entry.description) },
        trailingContent = { HexAndSwatch(color = color) },
    )
}

/** Trailing element: monospace hex string plus a bordered color chip. */
@Composable
private fun HexAndSwatch(color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "#%08X".format(color.toArgb()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(Color(0xFFCCCCCC), RoundedCornerShape(6.dp))
                .padding(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
            )
        }
    }
}

private data class ColorRoleEntry(
    /** Matches the [com.themestudio.core.ColorRole] name used by the editor. */
    val roleName: String,
    val friendlyName: String,
    val description: String,
    val isChild: Boolean,
    val read: (ColorScheme) -> Color,
)

private val COLOR_ROLE_ENTRIES: List<ColorRoleEntry> = listOf(
    // ── Primary ───────────────────────────────────────────────────────────
    ColorRoleEntry(
        "primary", "Primary",
        "High-emphasis fills: FAB, prominent buttons, active states.",
        isChild = false, read = { it.primary },
    ),
    ColorRoleEntry(
        "onPrimary", "On Primary",
        "Text and icons drawn on top of Primary fills.",
        isChild = true, read = { it.onPrimary },
    ),
    ColorRoleEntry(
        "primaryContainer", "Primary Container",
        "Lower-emphasis surfaces tinted with primary (tonal buttons, selected items).",
        isChild = false, read = { it.primaryContainer },
    ),
    ColorRoleEntry(
        "onPrimaryContainer", "On Primary Container",
        "Text and icons drawn on Primary Container surfaces.",
        isChild = true, read = { it.onPrimaryContainer },
    ),
    ColorRoleEntry(
        "inversePrimary", "Inverse Primary",
        "Action accent on inverted surfaces (e.g. snackbar buttons).",
        isChild = false, read = { it.inversePrimary },
    ),

    // ── Secondary ─────────────────────────────────────────────────────────
    ColorRoleEntry(
        "secondary", "Secondary",
        "Less prominent components: filter chips, secondary accents.",
        isChild = false, read = { it.secondary },
    ),
    ColorRoleEntry(
        "onSecondary", "On Secondary",
        "Text and icons drawn on Secondary fills.",
        isChild = true, read = { it.onSecondary },
    ),
    ColorRoleEntry(
        "secondaryContainer", "Secondary Container",
        "Lower-emphasis secondary surfaces (selected list items, chip backgrounds).",
        isChild = false, read = { it.secondaryContainer },
    ),
    ColorRoleEntry(
        "onSecondaryContainer", "On Secondary Container",
        "Text and icons on Secondary Container surfaces.",
        isChild = true, read = { it.onSecondaryContainer },
    ),

    // ── Tertiary ──────────────────────────────────────────────────────────
    ColorRoleEntry(
        "tertiary", "Tertiary",
        "Contrasting accent used to balance primary and secondary.",
        isChild = false, read = { it.tertiary },
    ),
    ColorRoleEntry(
        "onTertiary", "On Tertiary",
        "Text and icons drawn on Tertiary fills.",
        isChild = true, read = { it.onTertiary },
    ),
    ColorRoleEntry(
        "tertiaryContainer", "Tertiary Container",
        "Lower-emphasis tertiary surfaces.",
        isChild = false, read = { it.tertiaryContainer },
    ),
    ColorRoleEntry(
        "onTertiaryContainer", "On Tertiary Container",
        "Text and icons on Tertiary Container surfaces.",
        isChild = true, read = { it.onTertiaryContainer },
    ),

    // ── Error ─────────────────────────────────────────────────────────────
    ColorRoleEntry(
        "error", "Error",
        "Error states and destructive actions.",
        isChild = false, read = { it.error },
    ),
    ColorRoleEntry(
        "onError", "On Error",
        "Text and icons drawn on Error fills.",
        isChild = true, read = { it.onError },
    ),
    ColorRoleEntry(
        "errorContainer", "Error Container",
        "Lower-emphasis error surfaces (input errors, error banners).",
        isChild = false, read = { it.errorContainer },
    ),
    ColorRoleEntry(
        "onErrorContainer", "On Error Container",
        "Text and icons on Error Container surfaces.",
        isChild = true, read = { it.onErrorContainer },
    ),

    // ── Background ────────────────────────────────────────────────────────
    ColorRoleEntry(
        "background", "Background",
        "Default app background (largely superseded by Surface in M3).",
        isChild = false, read = { it.background },
    ),
    ColorRoleEntry(
        "onBackground", "On Background",
        "Text and icons drawn on Background.",
        isChild = true, read = { it.onBackground },
    ),

    // ── Surface ───────────────────────────────────────────────────────────
    ColorRoleEntry(
        "surface", "Surface",
        "Default surface color for screens, sheets, and most containers.",
        isChild = false, read = { it.surface },
    ),
    ColorRoleEntry(
        "onSurface", "On Surface",
        "Default text and icons drawn on Surface.",
        isChild = true, read = { it.onSurface },
    ),
    ColorRoleEntry(
        "surfaceVariant", "Surface Variant",
        "Surface tone for lower-emphasis containers.",
        isChild = false, read = { it.surfaceVariant },
    ),
    ColorRoleEntry(
        "onSurfaceVariant", "On Surface Variant",
        "Secondary text, icons, and inactive elements.",
        isChild = true, read = { it.onSurfaceVariant },
    ),
    ColorRoleEntry(
        "surfaceTint", "Surface Tint",
        "Tint blended into elevated surfaces (typically equals Primary).",
        isChild = false, read = { it.surfaceTint },
    ),

    // ── Surface containers (stepped tones) ────────────────────────────────
    ColorRoleEntry(
        "surfaceContainerLowest", "Surface Container Lowest",
        "Deepest container fill in the surface tonal stack.",
        isChild = false, read = { it.surfaceContainerLowest },
    ),
    ColorRoleEntry(
        "surfaceContainerLow", "Surface Container Low",
        "Subtle container fill, one step above the base surface.",
        isChild = false, read = { it.surfaceContainerLow },
    ),
    ColorRoleEntry(
        "surfaceContainer", "Surface Container",
        "Default container fill: cards, dialogs, bottom sheets.",
        isChild = false, read = { it.surfaceContainer },
    ),
    ColorRoleEntry(
        "surfaceContainerHigh", "Surface Container High",
        "Raised container fill: drawers, modal sheets.",
        isChild = false, read = { it.surfaceContainerHigh },
    ),
    ColorRoleEntry(
        "surfaceContainerHighest", "Surface Container Highest",
        "Highest-prominence container fill.",
        isChild = false, read = { it.surfaceContainerHighest },
    ),
    ColorRoleEntry(
        "surfaceBright", "Surface Bright",
        "Brightest surface tone (most visible in light mode).",
        isChild = false, read = { it.surfaceBright },
    ),
    ColorRoleEntry(
        "surfaceDim", "Surface Dim",
        "Dimmest surface tone (most visible in dark mode).",
        isChild = false, read = { it.surfaceDim },
    ),

    // ── Inverse Surface ───────────────────────────────────────────────────
    ColorRoleEntry(
        "inverseSurface", "Inverse Surface",
        "Inverted background for high-contrast surfaces (snackbars, tooltips).",
        isChild = false, read = { it.inverseSurface },
    ),
    ColorRoleEntry(
        "inverseOnSurface", "Inverse On Surface",
        "Text and icons on Inverse Surface.",
        isChild = true, read = { it.inverseOnSurface },
    ),

    // ── Outline / Scrim ───────────────────────────────────────────────────
    ColorRoleEntry(
        "outline", "Outline",
        "Borders, dividers, and decorative strokes.",
        isChild = false, read = { it.outline },
    ),
    ColorRoleEntry(
        "outlineVariant", "Outline Variant",
        "Lower-emphasis dividers and component outlines.",
        isChild = false, read = { it.outlineVariant },
    ),
    ColorRoleEntry(
        "scrim", "Scrim",
        "Dim overlay drawn behind modals and sheets.",
        isChild = false, read = { it.scrim },
    ),

    // ── Fixed (cross-mode constant) ───────────────────────────────────────
    ColorRoleEntry(
        "primaryFixed", "Primary Fixed",
        "Primary that stays constant across light and dark modes.",
        isChild = false, read = { it.primaryFixed },
    ),
    ColorRoleEntry(
        "onPrimaryFixed", "On Primary Fixed",
        "Text and icons on Primary Fixed.",
        isChild = true, read = { it.onPrimaryFixed },
    ),
    ColorRoleEntry(
        "primaryFixedDim", "Primary Fixed Dim",
        "Dimmer variant of Primary Fixed.",
        isChild = false, read = { it.primaryFixedDim },
    ),
    ColorRoleEntry(
        "onPrimaryFixedVariant", "On Primary Fixed Variant",
        "Lower-emphasis text on Primary Fixed surfaces.",
        isChild = true, read = { it.onPrimaryFixedVariant },
    ),
    ColorRoleEntry(
        "secondaryFixed", "Secondary Fixed",
        "Secondary that stays constant across light and dark modes.",
        isChild = false, read = { it.secondaryFixed },
    ),
    ColorRoleEntry(
        "onSecondaryFixed", "On Secondary Fixed",
        "Text and icons on Secondary Fixed.",
        isChild = true, read = { it.onSecondaryFixed },
    ),
    ColorRoleEntry(
        "secondaryFixedDim", "Secondary Fixed Dim",
        "Dimmer variant of Secondary Fixed.",
        isChild = false, read = { it.secondaryFixedDim },
    ),
    ColorRoleEntry(
        "onSecondaryFixedVariant", "On Secondary Fixed Variant",
        "Lower-emphasis text on Secondary Fixed surfaces.",
        isChild = true, read = { it.onSecondaryFixedVariant },
    ),
    ColorRoleEntry(
        "tertiaryFixed", "Tertiary Fixed",
        "Tertiary that stays constant across light and dark modes.",
        isChild = false, read = { it.tertiaryFixed },
    ),
    ColorRoleEntry(
        "onTertiaryFixed", "On Tertiary Fixed",
        "Text and icons on Tertiary Fixed.",
        isChild = true, read = { it.onTertiaryFixed },
    ),
    ColorRoleEntry(
        "tertiaryFixedDim", "Tertiary Fixed Dim",
        "Dimmer variant of Tertiary Fixed.",
        isChild = false, read = { it.tertiaryFixedDim },
    ),
    ColorRoleEntry(
        "onTertiaryFixedVariant", "On Tertiary Fixed Variant",
        "Lower-emphasis text on Tertiary Fixed surfaces.",
        isChild = true, read = { it.onTertiaryFixedVariant },
    ),
)
