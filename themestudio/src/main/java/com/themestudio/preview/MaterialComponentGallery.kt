package com.themestudio.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Comprehensive gallery of Material 3 components rendered under the active
 * theme. Covers the standard component surface (buttons, FABs, chips, cards,
 * selection controls, sliders, progress, text fields, list items, app bars,
 * navigation, tabs, badges, dividers) and interactive overlays via trigger
 * buttons (dialog, bottom sheet, snackbar, dropdown menu, tooltips).
 *
 * Components I deliberately leave out (out-of-scope for theme review): date
 * picker, time picker, search bar, modal navigation drawer, scaffold —
 * these have heavy state/structure that doesn't add color/shape signal
 * beyond what's already shown by simpler primitives.
 */
@OptIn(
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun MaterialComponentGallery(modifier: Modifier = Modifier) {
    var showDialog by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Buttons ────────────────────────────────────────────────────────
        SectionHeader("Buttons")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledComponent(roles = "primary · onPrimary") {
                Button(onClick = {}) { Text("Filled") }
            }
            LabeledComponent(roles = "secondaryContainer · onSecondaryContainer") {
                FilledTonalButton(onClick = {}) { Text("Tonal") }
            }
            LabeledComponent(roles = "surfaceContainerLow · primary · surfaceTint") {
                ElevatedButton(onClick = {}) { Text("Elevated") }
            }
            LabeledComponent(roles = "outline · primary") {
                OutlinedButton(onClick = {}) { Text("Outlined") }
            }
            LabeledComponent(roles = "primary") {
                TextButton(onClick = {}) { Text("Text") }
            }
        }

        // ── Icon Buttons ───────────────────────────────────────────────────
        // The default IconButton and OutlinedIconButton resolve content color
        // via LocalContentColor, which is set by the enclosing Surface (the
        // gallery's preview pane wraps everything in Surface(surface), so
        // LocalContentColor = onSurface here).
        SectionHeader("Icon buttons")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledComponent(roles = "LocalContentColor (onSurface here)") {
                IconButton(onClick = {}) { Icon(Icons.Default.Favorite, null) }
            }
            LabeledComponent(roles = "primary · onPrimary") {
                FilledIconButton(onClick = {}) { Icon(Icons.Default.Star, null) }
            }
            LabeledComponent(roles = "secondaryContainer · onSecondaryContainer") {
                FilledTonalIconButton(onClick = {}) { Icon(Icons.Default.Edit, null) }
            }
            LabeledComponent(roles = "outline · LocalContentColor") {
                OutlinedIconButton(onClick = {}) { Icon(Icons.Default.Settings, null) }
            }
        }

        // ── Expressive button group ───────────────────────────────────────
        // The ButtonGroupScope provides clickableItem/toggleableItem which
        // are pre-wired to participate in the press-expand animation. The
        // older approach of building children manually with `animateWidth`
        // is the deprecated overload — overflowIndicator is required on the
        // current API.
        SectionHeader("Button group (Expressive) — press a button to expand it")
        ButtonGroup(
            overflowIndicator = { state ->
                ButtonGroupDefaults.OverflowIndicator(menuState = state)
            },
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            clickableItem(onClick = {}, label = "One")
            clickableItem(onClick = {}, label = "Two")
            clickableItem(onClick = {}, label = "Three")
        }

        // ── Expressive split button ───────────────────────────────────────
        SectionHeader("Split button (Expressive)")
        var splitChecked by remember { mutableStateOf(false) }
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(onClick = {}) {
                    Text("Action")
                }
            },
            trailingButton = {
                SplitButtonDefaults.TrailingButton(
                    checked = splitChecked,
                    onCheckedChange = { splitChecked = it },
                ) {
                    Icon(Icons.Default.Add, null)
                }
            },
        )

        // ── FABs ──────────────────────────────────────────────────────────
        SectionHeader("Floating action buttons")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallFloatingActionButton(onClick = {}) { Icon(Icons.Default.Add, null) }
            FloatingActionButton(onClick = {}) { Icon(Icons.Default.Add, null) }
            LargeFloatingActionButton(onClick = {}) { Icon(Icons.Default.Add, null) }
            ExtendedFloatingActionButton(onClick = {}, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Extended") })
        }

        // ── Chips ─────────────────────────────────────────────────────────
        SectionHeader("Chips")
        var filterChecked by remember { mutableStateOf(true) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = {}, label = { Text("Assist") })
            FilterChip(selected = filterChecked, onClick = { filterChecked = !filterChecked }, label = { Text("Filter") })
            InputChip(selected = false, onClick = {}, label = { Text("Input") })
            SuggestionChip(onClick = {}, label = { Text("Suggestion") })
        }

        // ── Cards ─────────────────────────────────────────────────────────
        SectionHeader("Cards")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Filled card", style = MaterialTheme.typography.titleSmall)
                    Text("On surfaceContainerHighest by default.", style = MaterialTheme.typography.bodySmall)
                }
            }
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Elevated card", style = MaterialTheme.typography.titleSmall)
                    Text("Tonal elevation lifts it visually.", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Outlined card", style = MaterialTheme.typography.titleSmall)
                    Text("Bordered with the outline color.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ── Selection controls ────────────────────────────────────────────
        SectionHeader("Selection controls")
        var sw by remember { mutableStateOf(true) }
        var ck by remember { mutableStateOf(true) }
        var ck2 by remember { mutableStateOf(false) }
        var rb by remember { mutableStateOf(true) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = sw, onCheckedChange = { sw = it })
            Switch(checked = !sw, onCheckedChange = { sw = !it })
            Checkbox(checked = ck, onCheckedChange = { ck = it })
            Checkbox(checked = ck2, onCheckedChange = { ck2 = it })
            RadioButton(selected = rb, onClick = { rb = !rb })
            RadioButton(selected = !rb, onClick = { rb = !rb })
        }

        // ── Sliders ───────────────────────────────────────────────────────
        SectionHeader("Sliders")
        var sliderValue by remember { mutableStateOf(0.4f) }
        var rangeValue by remember { mutableStateOf(0.2f..0.7f) }
        Slider(value = sliderValue, onValueChange = { sliderValue = it })
        RangeSlider(value = rangeValue, onValueChange = { rangeValue = it })

        // ── Progress ──────────────────────────────────────────────────────
        SectionHeader("Progress")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            CircularProgressIndicator(progress = { 0.65f })
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                LinearProgressIndicator(progress = { 0.65f }, modifier = Modifier.fillMaxWidth())
            }
        }
        SectionHeader("Loading indicator (Expressive)")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LoadingIndicator()
            ContainedLoadingIndicator()
        }

        // ── Text fields ───────────────────────────────────────────────────
        SectionHeader("Text fields")
        var f1 by remember { mutableStateOf("Filled value") }
        var f2 by remember { mutableStateOf("Outlined value") }
        TextField(
            value = f1,
            onValueChange = { f1 = it },
            label = { Text("Filled label") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = f2,
            onValueChange = { f2 = it },
            label = { Text("Outlined label") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth(),
        )

        // ── List items ────────────────────────────────────────────────────
        SectionHeader("List items")
        Column(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text("One-line") },
                leadingContent = { Icon(Icons.Default.Person, null) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Two-line") },
                supportingContent = { Text("Supporting text") },
                leadingContent = { Icon(Icons.Default.Person, null) },
                trailingContent = { Text("meta") },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Three-line") },
                overlineContent = { Text("OVERLINE") },
                supportingContent = { Text("Supporting text wrapping to a second line if needed.") },
                leadingContent = { Icon(Icons.Default.Person, null) },
            )
        }

        // ── App bars ──────────────────────────────────────────────────────
        SectionHeader("Top app bars")
        TopAppBar(
            title = { Text("Small") },
            navigationIcon = { IconButton(onClick = {}) { Icon(Icons.Default.Home, null) } },
            actions = { IconButton(onClick = {}) { Icon(Icons.Default.Search, null) } },
        )
        CenterAlignedTopAppBar(
            title = { Text("Center aligned") },
            navigationIcon = { IconButton(onClick = {}) { Icon(Icons.Default.Home, null) } },
            actions = { IconButton(onClick = {}) { Icon(Icons.Default.Search, null) } },
        )
        SectionHeader("Bottom app bar")
        BottomAppBar(
            actions = {
                IconButton(onClick = {}) { Icon(Icons.Default.Edit, null) }
                IconButton(onClick = {}) { Icon(Icons.Default.Favorite, null) }
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {}) { Icon(Icons.Default.Add, null) }
            },
        )

        // ── Navigation bar ────────────────────────────────────────────────
        SectionHeader("Navigation bar")
        var navIdx by remember { mutableIntStateOf(0) }
        NavigationBar {
            listOf("Home" to Icons.Default.Home, "Search" to Icons.Default.Search,
                "Profile" to Icons.Default.Person, "Settings" to Icons.Default.Settings,
            ).forEachIndexed { i, (label, icon) ->
                NavigationBarItem(
                    selected = navIdx == i,
                    onClick = { navIdx = i },
                    icon = { Icon(icon, null) },
                    label = { Text(label) },
                )
            }
        }

        // ── Tabs ──────────────────────────────────────────────────────────
        SectionHeader("Tabs")
        var primaryIdx by remember { mutableIntStateOf(0) }
        PrimaryTabRow(selectedTabIndex = primaryIdx) {
            listOf("Primary", "Tabs", "Demo").forEachIndexed { i, label ->
                Tab(selected = primaryIdx == i, onClick = { primaryIdx = i }, text = { Text(label) })
            }
        }
        var secIdx by remember { mutableIntStateOf(0) }
        SecondaryTabRow(selectedTabIndex = secIdx) {
            listOf("Secondary", "Tabs", "Here").forEachIndexed { i, label ->
                Tab(selected = secIdx == i, onClick = { secIdx = i }, text = { Text(label) })
            }
        }

        // ── Badges + dividers ─────────────────────────────────────────────
        SectionHeader("Badges + dividers")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BadgedBox(badge = { Badge { Text("8") } }) {
                Icon(Icons.Default.Favorite, null)
            }
            BadgedBox(badge = { Badge() }) {
                Icon(Icons.Default.Info, null)
            }
            VerticalDivider(modifier = Modifier.height(24.dp))
            HorizontalDivider(modifier = Modifier.fillMaxWidth(0.4f))
        }

        // ── Tooltips ──────────────────────────────────────────────────────
        SectionHeader("Tooltips (long-press the icons)")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Plain tooltip") } },
                state = rememberTooltipState(),
            ) {
                Icon(Icons.Default.Info, null)
            }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
                tooltip = {
                    RichTooltip(
                        title = { Text("Rich tooltip") },
                        text = { Text("Has a title and supporting text.") },
                    )
                },
                state = rememberTooltipState(isPersistent = true),
            ) {
                Icon(Icons.Default.Info, null)
            }
        }

        // ── Snackbars (rendered inline, all variants) ────────────────────
        SectionHeader("Snackbars (inverseSurface · inverseOnSurface · inversePrimary)")
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Snackbar { Text("Single line message.") }
            Snackbar(
                action = { TextButton(onClick = {}) { Text("Action") } },
            ) { Text("With action button.") }
            Snackbar(
                action = { TextButton(onClick = {}) { Text("Retry") } },
                dismissAction = { TextButton(onClick = {}) { Text("Dismiss") } },
                actionOnNewLine = true,
            ) { Text("Wraps to two lines because the message is longer than will fit on one.") }
        }

        // ── Overlay triggers (dialog, sheet, menu) ───────────────────────
        SectionHeader("Overlays (tap to open)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { showDialog = true }) { Text("Dialog") }
            Button(onClick = { showSheet = true }) { Text("Bottom sheet") }
            Box {
                Button(onClick = { menuOpen = true }) { Text("Dropdown menu") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    listOf("Item one", "Item two", "Item three").forEach { label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false })
                    }
                }
            }
        }

        // ── Floating toolbar (Expressive) ────────────────────────────────
        SectionHeader("Floating toolbar (Expressive)")
        HorizontalFloatingToolbar(expanded = true) {
            IconButton(onClick = {}) { Icon(Icons.Default.Edit, null) }
            IconButton(onClick = {}) { Icon(Icons.Default.Favorite, null) }
            IconButton(onClick = {}) { Icon(Icons.Default.Star, null) }
            IconButton(onClick = {}) { Icon(Icons.Default.Settings, null) }
        }

        // ── Carousel (Expressive) ────────────────────────────────────────
        SectionHeader("Carousel (Expressive) — swipe horizontally")
        val carouselItems = remember {
            listOf(
                MaterialThemeColorRef.Primary,
                MaterialThemeColorRef.Secondary,
                MaterialThemeColorRef.Tertiary,
                MaterialThemeColorRef.PrimaryContainer,
                MaterialThemeColorRef.SecondaryContainer,
                MaterialThemeColorRef.TertiaryContainer,
            )
        }
        val carouselState = rememberCarouselState(itemCount = { carouselItems.size })
        HorizontalMultiBrowseCarousel(
            state = carouselState,
            preferredItemWidth = 120.dp,
            itemSpacing = 6.dp,
            modifier = Modifier.fillMaxWidth().height(120.dp),
        ) { i ->
            val ref = carouselItems[i]
            val (bg, fg, label) = ref.resolve()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(bg, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = fg, style = MaterialTheme.typography.titleSmall)
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Alert dialog") },
            text = { Text("Body content for an alert dialog. Shows containerColor and surface tint.") },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Dismiss") } },
        )
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(sheetState = sheetState, onDismissRequest = { showSheet = false }) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Modal bottom sheet", style = MaterialTheme.typography.titleMedium)
                Text("Renders on the surfaceContainerLow surface by default.")
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Wraps a component with a small label below it naming the color roles it uses. */
@Composable
private fun LabeledComponent(roles: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Text(
            text = roles,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * Carousel demo content. Each item references a primary container role from
 * the active scheme so the carousel visually tracks edits to those roles.
 */
private enum class MaterialThemeColorRef { Primary, Secondary, Tertiary, PrimaryContainer, SecondaryContainer, TertiaryContainer }

@Composable
private fun MaterialThemeColorRef.resolve(): Triple<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color, String> {
    val s = MaterialTheme.colorScheme
    return when (this) {
        MaterialThemeColorRef.Primary -> Triple(s.primary, s.onPrimary, "primary")
        MaterialThemeColorRef.Secondary -> Triple(s.secondary, s.onSecondary, "secondary")
        MaterialThemeColorRef.Tertiary -> Triple(s.tertiary, s.onTertiary, "tertiary")
        MaterialThemeColorRef.PrimaryContainer -> Triple(s.primaryContainer, s.onPrimaryContainer, "primaryContainer")
        MaterialThemeColorRef.SecondaryContainer -> Triple(s.secondaryContainer, s.onSecondaryContainer, "secondaryContainer")
        MaterialThemeColorRef.TertiaryContainer -> Triple(s.tertiaryContainer, s.onTertiaryContainer, "tertiaryContainer")
    }
}
