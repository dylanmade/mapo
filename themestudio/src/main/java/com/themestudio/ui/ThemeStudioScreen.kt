package com.themestudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.themestudio.core.ColorGenerationOverrides
import com.themestudio.core.ColorRoles
import com.themestudio.core.LocalThemeStudioController
import com.themestudio.core.LocalThemeStudioVariantOverride
import com.themestudio.core.ShapeRoles
import com.themestudio.core.TextStyleOverride
import com.themestudio.core.TypographyRoles
import com.themestudio.core.UmbrellaRoles
import com.themestudio.preview.ColorRoleList
import com.themestudio.preview.MaterialComponentGallery
import com.themestudio.preview.ShapeSpecimen
import com.themestudio.preview.TypographySpecimen

private enum class StudioTab(val label: String) {
    Colors("Colors"),
    Typography("Typography"),
    Shapes("Shapes"),
}

private enum class FontFamilyKind(val title: String) {
    Display("Display family"),
    Body("Body family"),
}

/**
 * Top-level editor screen. Hosts three tabs (Colors, Typography, Shapes),
 * each pairing a tappable specimen with a sticky preview gallery so changes
 * flow through to real components live.
 *
 * The screen wraps each tab's preview content in [theme] so the consumer's
 * theme function picks up [LocalThemeStudioVariantOverride] (and any color
 * overrides) — this is what lets the dev preview the *opposite* variant
 * from the device's current setting and see edits applied to every
 * component.
 *
 * Defaults wire up library-provided specimens + [MaterialComponentGallery];
 * consumers can swap in app-specific previews per tab if they want.
 *
 * Typical wiring:
 * ```
 * ThemeStudioScreen(
 *     onClose = { ... },
 *     theme = { content -> MapoTheme { content() } },
 * )
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeStudioScreen(
    onClose: () -> Unit,
    theme: @Composable (content: @Composable () -> Unit) -> Unit,
    /**
     * Consumer's baked-in default font for the Display family (display /
     * headline / title roles). When provided, the typography pickers show
     * this as the selected font whenever no override is set, instead of a
     * generic "(theme default)" placeholder.
     */
    defaultDisplayFontName: String? = null,
    /** Counterpart to [defaultDisplayFontName] for body / label roles. */
    defaultBodyFontName: String? = null,
    colorsPreview: @Composable (onPickRole: (String) -> Unit) -> Unit = { onPick ->
        ColorRoleList(onPickRole = onPick)
        MaterialComponentGallery()
    },
    typographyPreview: @Composable (onPickRole: (String) -> Unit) -> Unit = { onPick ->
        TypographySpecimen(onPickRole = onPick)
        MaterialComponentGallery()
    },
    shapesPreview: @Composable (onPickRole: (String) -> Unit) -> Unit = { onPick ->
        ShapeSpecimen(onPickRole = onPick)
        MaterialComponentGallery()
    },
) {
    val controller = LocalThemeStudioController.current
    val overrides = controller.overrides

    // Default to dark since that's the only mode Mapo currently uses; the
    // segmented button below still lets the user flip to light for previewing.
    var editingDark by remember { mutableStateOf(true) }
    var tabIndex by remember { mutableIntStateOf(0) }
    var showExport by remember { mutableStateOf(false) }
    var pickerColorRole by remember { mutableStateOf<String?>(null) }
    var pickerSeed by remember { mutableStateOf(false) }
    var pickerTypoRole by remember { mutableStateOf<String?>(null) }
    var pickerShapeRole by remember { mutableStateOf<String?>(null) }
    var pickerFontFamilyKind by remember { mutableStateOf<FontFamilyKind?>(null) }
    var pickerUmbrellaKind by remember { mutableStateOf<FontFamilyKind?>(null) }

    Scaffold(
        topBar = {
            // TopAppBar + tab row are stacked together so the tabs sit
            // directly under the chrome and scroll with neither — Scaffold
            // treats this whole Column as the top-bar slot.
            Column {
                TopAppBar(
                    title = { Text("Theme Studio") },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                    actions = {
                        SingleChoiceSegmentedButtonRow {
                            SegmentedButton(
                                selected = !editingDark,
                                onClick = { editingDark = false },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            ) { Text("Light", fontSize = 11.sp) }
                            SegmentedButton(
                                selected = editingDark,
                                onClick = { editingDark = true },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            ) { Text("Dark", fontSize = 11.sp) }
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { showExport = true }) {
                            Text("Export", fontSize = 12.sp)
                        }
                        TextButton(onClick = { controller.reset() }) {
                            Text("Reset", fontSize = 12.sp)
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = tabIndex) {
                    StudioTab.values().forEachIndexed { i, tab ->
                        Tab(
                            selected = tabIndex == i,
                            onClick = { tabIndex = i },
                            text = { Text(tab.label, fontSize = 12.sp) },
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        // Preview pane wraps in the consumer's theme so overrides + variant
        // override propagate to children. Scaffold is itself a Surface, so
        // LocalContentColor is already set correctly inside the content slot.
        Box(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize(),
        ) {
            CompositionLocalProvider(LocalThemeStudioVariantOverride provides editingDark) {
                theme {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (StudioTab.values()[tabIndex]) {
                            StudioTab.Colors -> {
                                ColorGenerationSection(
                                    gen = overrides.colorGeneration,
                                    onPickSeed = { pickerSeed = true },
                                    onStyle = { controller.setPaletteStyle(it) },
                                    onContrast = { controller.setColorContrast(it) },
                                    onResetContrast = { controller.setColorContrast(null) },
                                )
                                colorsPreview { name -> pickerColorRole = name }
                            }
                            StudioTab.Typography -> {
                                // Family chooser cards sit above the per-role specimen so swapping
                                // the Display or Body family is the most discoverable action — the
                                // specimen below then re-renders in the chosen fonts immediately.
                                // Each card previews fonts at the scale they'll actually be used:
                                // titleLarge for Display, bodyLarge for Body.
                                val displayName = overrides.typography.displayFontFamilyName
                                val bodyName = overrides.typography.bodyFontFamilyName
                                val displayUmbrella = overrides.typography.displayUmbrella
                                val bodyUmbrella = overrides.typography.bodyUmbrella
                                val emptyOverride = TextStyleOverride()
                                val hasDisplayCascade = displayName != null || displayUmbrella != emptyOverride
                                val hasBodyCascade = bodyName != null || bodyUmbrella != emptyOverride
                                FamilyChooserCard(
                                    label = "Display family (display / headline / title)",
                                    currentName = displayName,
                                    defaultLabel = "(theme default)",
                                    previewStyle = MaterialTheme.typography.titleLarge,
                                    onTap = { pickerUmbrellaKind = FontFamilyKind.Display },
                                    defaultFontName = defaultDisplayFontName,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(
                                        onClick = {
                                            controller.setBodyFontFamilyName(displayName)
                                            controller.setTypographyRole(UmbrellaRoles.body, displayUmbrella)
                                        },
                                        enabled = hasDisplayCascade,
                                    ) { Text("Copy Display → Body", fontSize = 12.sp) }
                                    TextButton(
                                        onClick = {
                                            controller.setDisplayFontFamilyName(bodyName)
                                            controller.setTypographyRole(UmbrellaRoles.display, bodyUmbrella)
                                        },
                                        enabled = hasBodyCascade,
                                    ) { Text("Copy Body → Display", fontSize = 12.sp) }
                                }
                                FamilyChooserCard(
                                    label = "Body family (body / label)",
                                    currentName = bodyName,
                                    defaultLabel = "(theme default)",
                                    previewStyle = MaterialTheme.typography.bodyLarge,
                                    onTap = { pickerUmbrellaKind = FontFamilyKind.Body },
                                    defaultFontName = defaultBodyFontName,
                                )
                                typographyPreview { name -> pickerTypoRole = name }
                            }
                            StudioTab.Shapes -> shapesPreview { name -> pickerShapeRole = name }
                        }
                    }
                }
            }
        }
    }

    // ── Color picker sheet ────────────────────────────────────────────────
    pickerColorRole?.let { roleName ->
        val role = remember(roleName) { ColorRoles.all.first { it.name == roleName } }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = { pickerColorRole = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                SheetHeader(title = role.name, subtitle = if (editingDark) "Editing: dark" else "Editing: light")
                val variant = if (editingDark) overrides.colors.dark else overrides.colors.light
                val effective = role.readOverride(variant) ?: role.read(MaterialTheme.colorScheme)
                val isOverridden = role.readOverride(variant) != null
                ColorPicker(
                    color = effective,
                    onChange = { c ->
                        if (editingDark) controller.setDarkRole(role, c)
                        else controller.setLightRole(role, c)
                    },
                    onClearOverride = if (isOverridden) {
                        {
                            if (editingDark) controller.setDarkRole(role, null)
                            else controller.setLightRole(role, null)
                        }
                    } else null,
                    pickerKey = "$roleName.${if (editingDark) "dark" else "light"}",
                )
            }
        }
    }

    // ── Seed color picker sheet ───────────────────────────────────────────
    if (pickerSeed) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(sheetState = sheetState, onDismissRequest = { pickerSeed = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                SheetHeader(title = "Seed color", subtitle = "Generates the whole palette")
                val seed = overrides.colorGeneration.seed
                ColorPicker(
                    color = seed ?: MaterialTheme.colorScheme.primary,
                    onChange = { controller.setSeedColor(it) },
                    onClearOverride = if (seed != null) { { controller.setSeedColor(null) } } else null,
                    pickerKey = "seed",
                )
            }
        }
    }

    // ── Typography picker sheet ───────────────────────────────────────────
    pickerTypoRole?.let { roleName ->
        val role = remember(roleName) { TypographyRoles.all.first { it.name == roleName } }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = { pickerTypoRole = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                SheetHeader(title = role.name, subtitle = "Typography")
                val baseStyle = role.read(MaterialTheme.typography)
                val current = role.readOverride(overrides.typography)
                TypographyPicker(
                    role = role,
                    baseStyle = baseStyle,
                    current = current,
                    onChange = { v -> controller.setTypographyRole(role, v) },
                    onClear = { controller.setTypographyRole(role, com.themestudio.core.TextStyleOverride()) },
                )
            }
        }
    }

    // ── Shape picker sheet ────────────────────────────────────────────────
    pickerShapeRole?.let { roleName ->
        val role = remember(roleName) { ShapeRoles.all.first { it.name == roleName } }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = { pickerShapeRole = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                SheetHeader(title = role.name, subtitle = "Shape (corner radius)")
                val current = role.readOverride(overrides.shapes)
                val baseRadius = defaultShapeRadius(roleName)
                ShapePicker(
                    baseRadius = baseRadius,
                    current = current,
                    onChange = { dp -> controller.setShapeRole(role, dp) },
                    onClear = { controller.setShapeRole(role, null) },
                )
            }
        }
    }

    // ── Umbrella ("set-all") picker sheet ─────────────────────────────────
    pickerUmbrellaKind?.let { kind ->
        val umbrellaRole = when (kind) {
            FontFamilyKind.Display -> UmbrellaRoles.display
            FontFamilyKind.Body -> UmbrellaRoles.body
        }
        val familyName = when (kind) {
            FontFamilyKind.Display -> overrides.typography.displayFontFamilyName
            FontFamilyKind.Body -> overrides.typography.bodyFontFamilyName
        }
        val defaultFamilyName = when (kind) {
            FontFamilyKind.Display -> defaultDisplayFontName
            FontFamilyKind.Body -> defaultBodyFontName
        }
        val cascadeSummary = when (kind) {
            FontFamilyKind.Display -> "Cascades to display, headline, title roles."
            FontFamilyKind.Body -> "Cascades to body, label roles."
        }
        val baseStyle = umbrellaRole.read(MaterialTheme.typography)
        val current = umbrellaRole.readOverride(overrides.typography)
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = { pickerUmbrellaKind = null },
        ) {
            UmbrellaPickerSheet(
                title = kind.title,
                cascadeSummary = cascadeSummary,
                currentFamilyName = familyName,
                role = umbrellaRole,
                baseStyle = baseStyle,
                current = current,
                onPickFamily = { pickerFontFamilyKind = kind },
                onChange = { v -> controller.setTypographyRole(umbrellaRole, v) },
                onClearOverrides = {
                    controller.setTypographyRole(umbrellaRole, TextStyleOverride())
                },
                defaultFontName = defaultFamilyName,
            )
        }
    }

    // ── Font-family picker sheet ──────────────────────────────────────────
    pickerFontFamilyKind?.let { kind ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val current = when (kind) {
            FontFamilyKind.Display -> overrides.typography.displayFontFamilyName
            FontFamilyKind.Body -> overrides.typography.bodyFontFamilyName
        }
        val defaultName = when (kind) {
            FontFamilyKind.Display -> defaultDisplayFontName
            FontFamilyKind.Body -> defaultBodyFontName
        }
        // Each row previews in the role-group's actual scale: titleLarge for
        // Display so users see headline-feel sizing, bodyLarge for Body so
        // they see paragraph-feel sizing.
        val rowStyle = when (kind) {
            FontFamilyKind.Display -> MaterialTheme.typography.titleLarge
            FontFamilyKind.Body -> MaterialTheme.typography.bodyLarge
        }
        ModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = { pickerFontFamilyKind = null },
        ) {
            FontFamilyPickerSheet(
                title = kind.title,
                currentName = current,
                rowStyle = rowStyle,
                onApply = { name ->
                    when (kind) {
                        FontFamilyKind.Display -> controller.setDisplayFontFamilyName(name)
                        FontFamilyKind.Body -> controller.setBodyFontFamilyName(name)
                    }
                    pickerFontFamilyKind = null
                },
                onClear = {
                    when (kind) {
                        FontFamilyKind.Display -> controller.setDisplayFontFamilyName(null)
                        FontFamilyKind.Body -> controller.setBodyFontFamilyName(null)
                    }
                    pickerFontFamilyKind = null
                },
                defaultFontName = defaultName,
            )
        }
    }

    if (showExport) {
        ExportDialog(overrides = overrides, onDismiss = { showExport = false })
    }
}

/** M3 default corner radii — used to seed the shape picker when no override is set. */
private fun defaultShapeRadius(name: String): androidx.compose.ui.unit.Dp = when (name) {
    "extraSmall" -> 4.dp
    "small" -> 8.dp
    "medium" -> 12.dp
    "large" -> 16.dp
    "extraLarge" -> 28.dp
    else -> 0.dp
}

@Composable
private fun SheetHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = subtitle,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Seed-based color-generation controls at the top of the Colors tab: the seed color, palette style,
 * and contrast. These drive the consumer's generator (e.g. MaterialKolor) via the
 * [ColorGenerationOverrides]; the per-role overrides below still layer on top.
 */
@Composable
private fun ColorGenerationSection(
    gen: ColorGenerationOverrides,
    onPickSeed: () -> Unit,
    onStyle: (String) -> Unit,
    onContrast: (Float) -> Unit,
    onResetContrast: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Color generation", style = MaterialTheme.typography.titleSmall)

        // Seed color — tap to open the color picker.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { onPickSeed() }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(gen.seed ?: MaterialTheme.colorScheme.primary)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
            Column(Modifier.weight(1f)) {
                Text("Seed color", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (gen.seed != null) "Custom seed" else "Default seed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.Colorize, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Palette style — dropdown of generic style names.
        var styleMenu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { styleMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Style: ${gen.style ?: "TonalSpot"}", modifier = Modifier.weight(1f), fontSize = 13.sp)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                ColorGenerationOverrides.STYLE_NAMES.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = { styleMenu = false; onStyle(name) },
                    )
                }
            }
        }

        // Contrast — −1 (low) … +1 (high).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Contrast", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
            Slider(
                value = (gen.contrast ?: 0f).coerceIn(-1f, 1f),
                onValueChange = onContrast,
                valueRange = -1f..1f,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResetContrast) { Text("Reset", fontSize = 12.sp) }
        }
    }
}
