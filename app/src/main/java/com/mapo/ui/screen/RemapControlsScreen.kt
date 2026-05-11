package com.mapo.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mapo.data.model.DeviceButton
import com.mapo.data.model.RemapTarget
import com.mapo.data.model.displayLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemapControlsScreen(
    mappings: Map<String, RemapTarget>,
    pickerResult: RemapTarget?,
    onConsumePickerResult: () -> Unit,
    onPickResult: (DeviceButton, RemapTarget) -> Unit,
    onBack: () -> Unit,
    onOpenPicker: (title: String, current: RemapTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    // rememberSaveable so the in-flight edit survives the full-screen picker round-trip;
    // a plain remember would be destroyed when this screen leaves composition while the
    // picker is on top, dropping the result on return.
    var editingButtonName by rememberSaveable { mutableStateOf<String?>(null) }
    val editingButton = editingButtonName?.let { runCatching { DeviceButton.valueOf(it) }.getOrNull() }

    LaunchedEffect(pickerResult) {
        val target = pickerResult ?: return@LaunchedEffect
        editingButton?.let { btn -> onPickResult(btn, target) }
        editingButtonName = null
        onConsumePickerResult()
    }

    val openPickerFor: (DeviceButton) -> Unit = { btn ->
        editingButtonName = btn.name
        val current = mappings[btn.name] ?: RemapTarget.Unbound
        onOpenPicker("Remap: ${btn.displayName}", current)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Remap Controls") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(contentPadding = innerPadding) {
            itemsIndexed(DeviceButton.entries) { index, btn ->
                val target = mappings[btn.name] ?: RemapTarget.Unbound
                ListItem(
                    headlineContent = { Text(btn.displayName) },
                    supportingContent = {
                        Text(
                            target.displayLabel(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (target is RemapTarget.Unbound) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        OutlinedButton(
                            onClick = { openPickerFor(btn) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Edit")
                        }
                    },
                    modifier = Modifier.clickable { openPickerFor(btn) },
                )
                if (index < DeviceButton.entries.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}
