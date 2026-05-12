package com.mapo.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.mapo.data.model.RemapTarget
import com.mapo.data.model.displayLabel
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.data.model.steam.displayLabel
import com.mapo.data.model.steam.displayName
import com.mapo.data.model.steam.findActivator
import com.mapo.data.model.steam.toRemapTarget
import com.mapo.ui.component.layout.SectionedListDetailPane
import com.mapo.ui.screen.remap.RemapPaneItem
import com.mapo.ui.screen.remap.RemapSections

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemapControlsScreen(
    config: ControllerConfig?,
    pickerResult: RemapTarget?,
    onConsumePickerResult: () -> Unit,
    onPickResult: (activatorId: Long, output: BindingOutput) -> Unit,
    onOpenPicker: (title: String, current: RemapTarget) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedSectionId by rememberSaveable { mutableStateOf(RemapSections.SECTION_BUTTONS) }
    // rememberSaveable so the in-flight edit survives the full-screen picker round-trip.
    var editingActivatorId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(pickerResult) {
        val target = pickerResult ?: return@LaunchedEffect
        val activatorId = editingActivatorId
        if (activatorId != null) {
            onPickResult(activatorId, BindingOutput.fromRemapTarget(target))
        }
        editingActivatorId = null
        onConsumePickerResult()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Remap Controls") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                ActionSetAndLayersBar(config)
            }
        },
    ) { innerPadding ->
        SectionedListDetailPane(
            sections = RemapSections.rail,
            selectedSectionId = selectedSectionId,
            onSectionSelected = { selectedSectionId = it },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { sectionId, firstRowFocusRequester ->
            RemapDetailPane(
                sectionId = sectionId,
                config = config,
                firstRowFocusRequester = firstRowFocusRequester,
                onRequestPicker = { activatorId, currentOutput, label ->
                    editingActivatorId = activatorId
                    onOpenPicker("Remap: $label", currentOutput.toRemapTarget())
                },
            )
        }
    }
}

/**
 * Inert top bar: action-set tabs + layer chips. Brick 1.3 ships these as visible-but-disabled
 * affordances so the eventual Phase 4 (action sets) and Phase 5 (layers) plumbing has a place
 * to land without UI churn. The tab row reflects whatever's in the active config.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionSetAndLayersBar(config: ControllerConfig?) {
    val sets = config?.actionSets.orEmpty()
    val activeId = config?.activeActionSet?.actionSet?.id
    val activeIndex = sets.indexOfFirst { it.actionSet.id == activeId }.coerceAtLeast(0)

    // M3 role: surfaceContainer — same plane as the rail below.
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            if (sets.isNotEmpty()) {
                PrimaryTabRow(selectedTabIndex = activeIndex) {
                    sets.forEach { setGraph ->
                        Tab(
                            selected = setGraph.actionSet.id == activeId,
                            // Action-set switching is Phase 4 — disabled for now.
                            enabled = false,
                            onClick = { /* no-op until Phase 4 */ },
                            text = {
                                Text(
                                    text = setGraph.actionSet.title,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Layers:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                AssistChip(
                    onClick = { /* no-op until Phase 5 */ },
                    enabled = false,
                    label = { Text("(no layers)") },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
        }
    }
}

@Composable
private fun RemapDetailPane(
    sectionId: String,
    config: ControllerConfig?,
    firstRowFocusRequester: FocusRequester,
    onRequestPicker: (activatorId: Long, current: BindingOutput, rowLabel: String) -> Unit,
) {
    val items = RemapSections.contentBySection[sectionId]

    if (items == null) {
        // Gyro and any future un-implemented sections route here.
        DetailPlaceholder(RemapSections.GYRO_PLACEHOLDER)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        // First focusable row gets the cross-pane focus requester so D-pad Right from the
        // rail lands on the first interactable element of this section.
        val firstBindingRowKey = items.firstOrNull { it is RemapPaneItem.BindingRow }?.key
        items(items = items, key = { it.key }) { item ->
            val focusModifier = if (item.key == firstBindingRowKey) {
                Modifier.focusRequester(firstRowFocusRequester)
            } else Modifier
            when (item) {
                is RemapPaneItem.Subheader -> SubheaderRow(item)
                is RemapPaneItem.BindingRow -> BindingRowItem(
                    item = item,
                    config = config,
                    modifier = focusModifier,
                    onRequestPicker = onRequestPicker,
                )
                is RemapPaneItem.DisabledRow -> DisabledRowItem(item)
            }
        }
    }
}

@Composable
private fun SubheaderRow(item: RemapPaneItem.Subheader) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        // Disabled mode-dropdown affordance. Lands the visual real estate now; Phase 6
        // wires it to a real picker.
        DisabledModeDropdown(item.modeDropdownLabel)
    }
}

@Composable
private fun DisabledModeDropdown(label: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.alpha(0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Mode: $label",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BindingRowItem(
    item: RemapPaneItem.BindingRow,
    config: ControllerConfig?,
    modifier: Modifier = Modifier,
    onRequestPicker: (activatorId: Long, current: BindingOutput, rowLabel: String) -> Unit,
) {
    val activator = config?.findActivator(item.inputSource, item.groupInputKey, item.activatorType)
    val output = activator?.primaryOutput ?: BindingOutput.Unbound
    val ready = activator != null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = ready) {
                activator?.activator?.id?.let { onRequestPicker(it, output, item.label) }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Helper subtext per memory: short, one-line, tutorializing.
            Text(
                text = item.inputSource.displayName() + " · " + item.groupInputKey,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = output.displayLabel(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (output == BindingOutput.Unbound)
                MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
        )
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun DisabledRowItem(item: RemapPaneItem.DisabledRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0.38f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Coming soon",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun DetailPlaceholder(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
