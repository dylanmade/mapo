package com.pcpad.ui.screen

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.pcpad.data.model.GridLayout
import com.pcpad.ui.theme.TabAccent
import com.pcpad.ui.viewmodel.MainViewModel
import kotlin.math.roundToInt

@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val selectedIndex by viewModel.selectedIndex.collectAsState()
    val isEditMode by viewModel.isEditMode.collectAsState()
    val layouts by viewModel.layouts.collectAsState()
    val displayLayout by viewModel.displayLayout.collectAsState()
    val selectedButtonId by viewModel.selectedButtonId.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

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
                    onSelectLayout = { viewModel.selectLayout(it) },
                    onEnterEditMode = { viewModel.enterEditMode() }
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

@Composable
private fun NormalModeBar(
    layouts: List<GridLayout>,
    selectedIndex: Int,
    onSelectLayout: (Int) -> Unit,
    onEnterEditMode: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TabRow(selectedTabIndex = selectedIndex, modifier = Modifier.weight(1f)) {
            layouts.forEachIndexed { index, layout ->
                Tab(
                    selected = index == selectedIndex,
                    onClick = { onSelectLayout(index) },
                    text = { Text(layout.name) }
                )
            }
        }
        TextButton(onClick = onEnterEditMode) {
            Text("Edit", color = TabAccent)
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
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onAdd) { Text("Add") }
            TextButton(onClick = onEdit, enabled = hasSelection) { Text("Edit") }
            TextButton(onClick = onDelete, enabled = hasSelection) { Text("Delete") }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onSave) { Text("Save") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
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
