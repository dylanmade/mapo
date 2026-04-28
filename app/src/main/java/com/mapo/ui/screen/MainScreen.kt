package com.mapo.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.mapo.data.model.GridLayout
import com.mapo.ui.theme.TabAccent
import com.mapo.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val selectedIndex by viewModel.selectedIndex.collectAsState()
    val isEditMode by viewModel.isEditMode.collectAsState()
    val layouts by viewModel.layouts.collectAsState()
    val displayLayout by viewModel.displayLayout.collectAsState()
    val selectedButtonId by viewModel.selectedButtonId.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val profiles by viewModel.profiles.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val showRemapControls by viewModel.showRemapControls.collectAsState()
    val activeProfileMappings by viewModel.activeProfileMappings.collectAsState()
    val remapEnabled by viewModel.remapEnabled.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var showButtonDialog by remember { mutableStateOf(false) }
    var dialogLabel by remember { mutableStateOf("") }
    var dialogCode by remember { mutableStateOf("") }
    var dialogIsEdit by remember { mutableStateOf(false) }

    if (showButtonDialog) {
        AlertDialog(
            onDismissRequest = { showButtonDialog = false },
            title = { Text(if (dialogIsEdit) "Edit Button" else "Add Button") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dialogLabel,
                        onValueChange = { dialogLabel = it },
                        label = { Text("Label") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = dialogCode,
                        onValueChange = { dialogCode = it },
                        label = { Text("Key Code") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dialogIsEdit) {
                        viewModel.updateSelectedButton(dialogLabel, dialogCode)
                    } else {
                        viewModel.addButton(dialogLabel, dialogCode)
                    }
                    showButtonDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showButtonDialog = false }) { Text("Cancel") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ProfileDrawerContent(
                profiles = profiles,
                activeProfile = activeProfile,
                onSelectProfile = { profile ->
                    viewModel.selectProfile(profile)
                    scope.launch { drawerState.close() }
                },
                onAddProfile = { name -> viewModel.addProfile(name) },
                onDuplicateProfile = { profile -> viewModel.duplicateProfile(profile) },
                onDeleteProfile = { profile -> viewModel.deleteProfile(profile) },
                onOpenRemapControls = {
                    scope.launch { drawerState.close() }
                    viewModel.openRemapControls()
                }
            )
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)
        ) { _ ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                if (isEditMode) {
                    EditModeBar(
                        hasSelection = selectedButtonId != null,
                        onAdd = {
                            dialogLabel = ""
                            dialogCode = ""
                            dialogIsEdit = false
                            showButtonDialog = true
                        },
                        onEdit = {
                            val btn = displayLayout.buttons.find { it.id == selectedButtonId }
                            if (btn != null) {
                                dialogLabel = btn.label
                                dialogCode = btn.code
                                dialogIsEdit = true
                                showButtonDialog = true
                            }
                        },
                        onDelete = { viewModel.deleteSelectedButton() },
                        onSave = { viewModel.saveEdits() },
                        onCancel = { viewModel.cancelEdits() }
                    )
                } else {
                    NormalModeBar(
                        layouts = layouts,
                        selectedIndex = selectedIndex,
                        remapEnabled = remapEnabled,
                        onSelectLayout = { viewModel.selectLayout(it) },
                        onEnterEditMode = { viewModel.enterEditMode() },
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onToggleRemap = { viewModel.toggleRemap() }
                    )
                }

                KeyGrid(
                    layout = displayLayout,
                    isEditMode = isEditMode,
                    selectedButtonId = selectedButtonId,
                    onKeyPress = viewModel::onKeyPress,
                    onSelectButton = viewModel::selectButton,
                    onMoveButton = viewModel::moveButton,
                    onResizeButton = viewModel::resizeButton,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(4.dp)
                )
            }
        }
    }
    if (showRemapControls) {
        RemapControlsScreen(
            initialMappings = activeProfileMappings,
            onSave = { viewModel.saveRemapMappings(it) },
            onBack = { viewModel.closeRemapControls() },
            modifier = Modifier.fillMaxSize()
        )
    }
    } // end Box
}

@Composable
private fun NormalModeBar(
    layouts: List<GridLayout>,
    selectedIndex: Int,
    remapEnabled: Boolean,
    onSelectLayout: (Int) -> Unit,
    onEnterEditMode: () -> Unit,
    onOpenDrawer: () -> Unit,
    onToggleRemap: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onOpenDrawer, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Menu, contentDescription = "Open menu", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onToggleRemap, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.SportsEsports,
                contentDescription = if (remapEnabled) "Disable remapping" else "Enable remapping",
                modifier = Modifier.size(20.dp),
                tint = if (remapEnabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TabRow(selectedTabIndex = selectedIndex, modifier = Modifier.weight(1f)) {
            layouts.forEachIndexed { index, layout ->
                Tab(
                    selected = index == selectedIndex,
                    onClick = { onSelectLayout(index) },
                    modifier = Modifier.height(40.dp),
                    text = { Text(layout.name, fontSize = 12.sp) }
                )
            }
        }
        TextButton(
            onClick = onEnterEditMode,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(40.dp)
        ) {
            Text("Edit", color = TabAccent, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EditModeBar(
    hasSelection: Boolean,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val btnPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onAdd, contentPadding = btnPadding) { Text("Add", fontSize = 12.sp) }
        TextButton(onClick = onEdit, enabled = hasSelection, contentPadding = btnPadding) { Text("Edit", fontSize = 12.sp) }
        TextButton(onClick = onDelete, enabled = hasSelection, contentPadding = btnPadding) { Text("Delete", fontSize = 12.sp) }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onSave, contentPadding = btnPadding) { Text("Save", fontSize = 12.sp) }
        TextButton(onClick = onCancel, contentPadding = btnPadding) { Text("Cancel", fontSize = 12.sp) }
    }
}

@Composable
private fun KeyGrid(
    layout: GridLayout,
    isEditMode: Boolean,
    selectedButtonId: String?,
    onKeyPress: (String) -> Unit,
    onSelectButton: (String) -> Unit,
    onMoveButton: (String, Int, Int) -> Unit,
    onResizeButton: (String, Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val handleSize = 20.dp
    val gap = 3.dp

    BoxWithConstraints(modifier = modifier) {
        val cellW = maxWidth / layout.columns
        val cellH = maxHeight / layout.rows
        val cellWPx = with(density) { cellW.toPx() }
        val cellHPx = with(density) { cellH.toPx() }

        layout.buttons.forEach { button ->
            var dragOffset by remember(button.id) { mutableStateOf(Offset.Zero) }
            var isDragging by remember(button.id) { mutableStateOf(false) }
            var resizeDragPx by remember(button.id) { mutableStateOf(Offset.Zero) }

            val bx = cellW * button.col
            val by = cellH * button.row
            val bw = cellW * button.colSpan - gap
            val bh = cellH * button.rowSpan - gap
            val isSelected = button.id == selectedButtonId

            // ── Button ────────────────────────────────────────────────────────
            OutlinedButton(
                onClick = {
                    if (isEditMode) onSelectButton(button.id) else onKeyPress(button.code)
                },
                modifier = Modifier
                    .absoluteOffset(x = bx, y = by)
                    .size(width = bw, height = bh)
                    .zIndex(if (isDragging) 10f else 0f)
                    .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                    .then(
                        if (isEditMode) Modifier.pointerInput(button.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    isDragging = true
                                    onSelectButton(button.id)
                                },
                                onDrag = { change, delta ->
                                    change.consume()
                                    dragOffset += delta
                                },
                                onDragEnd = {
                                    val newCol = ((button.col * cellWPx + dragOffset.x) / cellWPx).roundToInt()
                                    val newRow = ((button.row * cellHPx + dragOffset.y) / cellHPx).roundToInt()
                                    onMoveButton(button.id, newCol, newRow)
                                    isDragging = false
                                    dragOffset = Offset.Zero
                                },
                                onDragCancel = {
                                    isDragging = false
                                    dragOffset = Offset.Zero
                                }
                            )
                        } else Modifier
                    ),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(if (isSelected) 2.dp else 1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                contentPadding = PaddingValues(2.dp)
            ) {
                Text(
                    text = button.label,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center
                )
            }

            // ── Resize handle (selected button only, not while dragging) ──────
            if (isEditMode && isSelected && !isDragging) {
                Box(
                    modifier = Modifier
                        .absoluteOffset(
                            x = bx + bw - handleSize + gap,
                            y = by + bh - handleSize + gap
                        )
                        .size(handleSize)
                        .zIndex(20f)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        .pointerInput(button.id + "_resize") {
                            detectDragGestures(
                                onDragEnd = {
                                    val dCols = (resizeDragPx.x / cellWPx).roundToInt()
                                    val dRows = (resizeDragPx.y / cellHPx).roundToInt()
                                    onResizeButton(button.id, button.colSpan + dCols, button.rowSpan + dRows)
                                    resizeDragPx = Offset.Zero
                                },
                                onDragCancel = { resizeDragPx = Offset.Zero },
                                onDrag = { change, delta ->
                                    change.consume()
                                    resizeDragPx += delta
                                }
                            )
                        }
                )
            }
        }
    }
}
