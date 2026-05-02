package com.mapo.ui.screen

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import com.mapo.R
import com.mapo.data.model.GridButton
import com.mapo.data.model.GridLayout
import com.mapo.data.model.RemapTarget
import com.mapo.data.model.TrackpadGesture
import com.mapo.data.model.defaultTarget
import com.mapo.data.model.gestureTarget
import com.mapo.data.model.isTrackpad
import com.mapo.data.model.displayLabel
import com.mapo.data.model.wouldOverlap
import com.mapo.service.InputAccessibilityService
import com.mapo.service.autoswitch.ProfileAutoSwitcher
import com.mapo.ui.theme.TabAccent
import com.mapo.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val context = LocalContext.current
    var pendingPrompt by remember {
        mutableStateOf<ProfileAutoSwitcher.UiEvent.PromptCreate?>(null)
    }
    LaunchedEffect(Unit) {
        viewModel.autoSwitchEvents.collect { event ->
            when (event) {
                is ProfileAutoSwitcher.UiEvent.Switched -> {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.auto_switch_snackbar_switched, event.profileName, event.appLabel)
                    )
                }
                is ProfileAutoSwitcher.UiEvent.PromptCreate -> {
                    pendingPrompt = event
                }
            }
        }
    }

    val showRemapControls by viewModel.showRemapControls.collectAsState()
    val activeProfileMappings by viewModel.activeProfileMappings.collectAsState()
    val remapEnabled by viewModel.remapEnabled.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showAutoSwitch by remember { mutableStateOf(false) }

    var accessibilityGranted by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var overlayGranted by remember { mutableStateOf(isOverlayPermissionGranted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityGranted = isAccessibilityServiceEnabled(context)
                overlayGranted = isOverlayPermissionGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!accessibilityGranted || !overlayGranted) {
        PermissionsRequiredDialog(
            accessibilityGranted = accessibilityGranted,
            overlayGranted = overlayGranted
        )
    }

    var showButtonDialog by remember { mutableStateOf(false) }
    var dialogLabel by remember { mutableStateOf("") }
    var dialogCode by remember { mutableStateOf("") }
    var dialogTopText by remember { mutableStateOf("") }
    var dialogTopAlign by remember { mutableStateOf("CENTER") }
    var dialogBottomText by remember { mutableStateOf("") }
    var dialogBottomAlign by remember { mutableStateOf("CENTER") }
    var dialogIsEdit by remember { mutableStateOf(false) }
    var dialogIsTrackpad by remember { mutableStateOf(false) }
    var dialogSensitivity by remember { mutableStateOf(TRACKPAD_SENSITIVITY) }
    var dialogTapTarget by remember { mutableStateOf<RemapTarget>(TrackpadGesture.TAP.defaultTarget()) }
    var dialogDoubleTapTarget by remember { mutableStateOf<RemapTarget>(TrackpadGesture.DOUBLE_TAP.defaultTarget()) }
    var dialogLongPressTarget by remember { mutableStateOf<RemapTarget>(TrackpadGesture.LONG_PRESS.defaultTarget()) }
    var editingGesture by remember { mutableStateOf<TrackpadGesture?>(null) }

    if (editingGesture != null) {
        val current = when (editingGesture) {
            TrackpadGesture.TAP        -> dialogTapTarget
            TrackpadGesture.DOUBLE_TAP -> dialogDoubleTapTarget
            TrackpadGesture.LONG_PRESS -> dialogLongPressTarget
            null -> RemapTarget.Unbound
        }
        RemapTargetPickerDialog(
            title = editingGesture!!.displayName,
            current = current,
            onSelect = { target ->
                when (editingGesture) {
                    TrackpadGesture.TAP        -> dialogTapTarget = target
                    TrackpadGesture.DOUBLE_TAP -> dialogDoubleTapTarget = target
                    TrackpadGesture.LONG_PRESS -> dialogLongPressTarget = target
                    null -> {}
                }
                editingGesture = null
            },
            onDismiss = { editingGesture = null }
        )
    }

    if (showButtonDialog) {
        AlertDialog(
            onDismissRequest = { showButtonDialog = false },
            title = { Text(if (dialogIsEdit) "Edit Button" else "Add Button") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    // Key / Trackpad toggle
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Key" to false, "Trackpad" to true).forEach { (label, isTrackpad) ->
                            val selected = dialogIsTrackpad == isTrackpad
                            OutlinedButton(
                                onClick = { dialogIsTrackpad = isTrackpad },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                                                     else MaterialTheme.colorScheme.surface
                                ),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) { Text(label, fontSize = 13.sp) }
                        }
                    }
                    if (!dialogIsTrackpad) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = dialogTopText,
                                onValueChange = { dialogTopText = it },
                                label = { Text("Top text") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            AlignSelector(selected = dialogTopAlign, onSelect = { dialogTopAlign = it })
                        }
                    }
                    OutlinedTextField(
                        value = dialogLabel,
                        onValueChange = { dialogLabel = it },
                        label = { Text("Label") },
                        singleLine = true
                    )
                    if (dialogIsTrackpad) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Sensitivity", fontSize = 13.sp, modifier = Modifier.width(80.dp))
                            Slider(
                                value = dialogSensitivity,
                                onValueChange = { dialogSensitivity = it },
                                valueRange = 0.5f..4.0f,
                                modifier = Modifier.weight(1f)
                            )
                            Text("%.1f×".format(dialogSensitivity), fontSize = 12.sp, modifier = Modifier.width(34.dp))
                        }
                        GestureMappingRow(
                            label = TrackpadGesture.TAP.displayName,
                            target = dialogTapTarget,
                            onClick = { editingGesture = TrackpadGesture.TAP }
                        )
                        GestureMappingRow(
                            label = TrackpadGesture.DOUBLE_TAP.displayName,
                            target = dialogDoubleTapTarget,
                            onClick = { editingGesture = TrackpadGesture.DOUBLE_TAP }
                        )
                        GestureMappingRow(
                            label = TrackpadGesture.LONG_PRESS.displayName,
                            target = dialogLongPressTarget,
                            onClick = { editingGesture = TrackpadGesture.LONG_PRESS }
                        )
                    }
                    if (!dialogIsTrackpad) {
                        OutlinedTextField(
                            value = dialogCode,
                            onValueChange = { dialogCode = it },
                            label = { Text("Key Code") },
                            singleLine = true
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = dialogBottomText,
                                onValueChange = { dialogBottomText = it },
                                label = { Text("Bottom text") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            AlignSelector(selected = dialogBottomAlign, onSelect = { dialogBottomAlign = it })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val type = if (dialogIsTrackpad) "trackpad" else "key"
                    val sens = if (dialogIsTrackpad) dialogSensitivity else null
                    val gestureMappings = if (dialogIsTrackpad) mapOf(
                        TrackpadGesture.TAP.name to dialogTapTarget.encode(),
                        TrackpadGesture.DOUBLE_TAP.name to dialogDoubleTapTarget.encode(),
                        TrackpadGesture.LONG_PRESS.name to dialogLongPressTarget.encode()
                    ) else null
                    if (dialogIsEdit) {
                        viewModel.updateSelectedButton(
                            dialogLabel, dialogCode,
                            dialogTopText, dialogTopAlign,
                            dialogBottomText, dialogBottomAlign,
                            type, sens, gestureMappings
                        )
                    } else {
                        viewModel.addButton(
                            dialogLabel, dialogCode,
                            dialogTopText, dialogTopAlign,
                            dialogBottomText, dialogBottomAlign,
                            type, sens, gestureMappings
                        )
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
        gesturesEnabled = drawerState.isOpen,
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
                },
                onOpenAutoSwitch = {
                    scope.launch { drawerState.close() }
                    showAutoSwitch = true
                }
            )
        }
    ) {
        Scaffold(
            snackbarHost = {
                val prompt = pendingPrompt
                if (prompt != null) {
                    Snackbar(
                        modifier = Modifier.padding(12.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        actionOnNewLine = true,
                        action = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = {
                                        viewModel.ignorePackageForever(prompt.pkg)
                                        pendingPrompt = null
                                    },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) { Text(stringResource(R.string.auto_switch_prompt_never)) }
                                TextButton(
                                    onClick = { pendingPrompt = null },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) { Text(stringResource(R.string.auto_switch_prompt_no)) }
                                TextButton(
                                    onClick = {
                                        viewModel.acceptCreateProfilePrompt(prompt.pkg, prompt.appLabel)
                                        pendingPrompt = null
                                    }
                                ) { Text(stringResource(R.string.auto_switch_prompt_yes)) }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.auto_switch_prompt_title, prompt.appLabel))
                    }
                } else {
                    SnackbarHost(snackbarHostState) { data ->
                        Snackbar(
                            snackbarData = data,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            actionColor = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
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
                            dialogTopText = ""
                            dialogTopAlign = "CENTER"
                            dialogBottomText = ""
                            dialogBottomAlign = "CENTER"
                            dialogIsTrackpad = false
                            dialogSensitivity = TRACKPAD_SENSITIVITY
                            dialogTapTarget = TrackpadGesture.TAP.defaultTarget()
                            dialogDoubleTapTarget = TrackpadGesture.DOUBLE_TAP.defaultTarget()
                            dialogLongPressTarget = TrackpadGesture.LONG_PRESS.defaultTarget()
                            dialogIsEdit = false
                            showButtonDialog = true
                        },
                        onEdit = {
                            val btn = displayLayout.buttons.find { it.id == selectedButtonId }
                            if (btn != null) {
                                dialogLabel = btn.label
                                dialogCode = btn.code
                                dialogTopText = btn.topText ?: ""
                                dialogTopAlign = btn.topAlign ?: "CENTER"
                                dialogBottomText = btn.bottomText ?: ""
                                dialogBottomAlign = btn.bottomAlign ?: "CENTER"
                                dialogIsTrackpad = btn.isTrackpad
                                dialogSensitivity = btn.sensitivity ?: TRACKPAD_SENSITIVITY
                                dialogTapTarget = btn.gestureTarget(TrackpadGesture.TAP)
                                dialogDoubleTapTarget = btn.gestureTarget(TrackpadGesture.DOUBLE_TAP)
                                dialogLongPressTarget = btn.gestureTarget(TrackpadGesture.LONG_PRESS)
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
                    onDragStart = viewModel::onDragStart,
                    onMouseMove = viewModel::onMouseMove,
                    onDragEnd = viewModel::onDragEnd,
                    onTrackpadGesture = viewModel::onTrackpadGesture,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(4.dp)
                )
                BottomBar(onQuit = { (context as? Activity)?.finish() })
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
    if (showAutoSwitch) {
        AutoSwitchScreen(
            onBack = { showAutoSwitch = false }
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
    onDragStart: () -> Unit,
    onMouseMove: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onTrackpadGesture: (GridButton, TrackpadGesture) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val gridScope = rememberCoroutineScope()
    val handleSize = 20.dp
    val gap = 3.dp
    val currentSelectedId by rememberUpdatedState(selectedButtonId)

    var draggingId by remember { mutableStateOf<String?>(null) }
    var dropTargetCol by remember { mutableStateOf(0) }
    var dropTargetRow by remember { mutableStateOf(0) }
    var dropIsValid by remember { mutableStateOf(true) }

    BoxWithConstraints(modifier = modifier) {
        val cellW = maxWidth / layout.columns
        val cellH = maxHeight / layout.rows
        val cellWPx = with(density) { cellW.toPx() }
        val cellHPx = with(density) { cellH.toPx() }

        // ── Drop indicator ────────────────────────────────────────────────────
        val draggingButton = if (draggingId != null) layout.buttons.find { it.id == draggingId } else null
        if (isEditMode && draggingButton != null) {
            Box(
                modifier = Modifier
                    .absoluteOffset(
                        x = cellW * dropTargetCol,
                        y = cellH * dropTargetRow
                    )
                    .size(
                        width = cellW * draggingButton.colSpan - gap,
                        height = cellH * draggingButton.rowSpan - gap
                    )
                    .zIndex(5f)
                    .background(
                        if (dropIsValid) Color(0x6000C853) else Color(0x60FF1744),
                        RoundedCornerShape(8.dp)
                    )
            )
        }

        layout.buttons.forEach { button ->
            // Always-fresh references inside gesture handlers (fixes stale-capture bug)
            val currentButton by rememberUpdatedState(button)
            val currentLayout by rememberUpdatedState(layout)

            var dragOffset by remember(button.id) { mutableStateOf(Offset.Zero) }
            var isDragging by remember(button.id) { mutableStateOf(false) }
            var resizeDragPx by remember(button.id) { mutableStateOf(Offset.Zero) }

            val bx = cellW * button.col
            val by = cellH * button.row

            // Live size preview while resize handle is being dragged
            val displayColSpan = (button.colSpan + (resizeDragPx.x / cellWPx).roundToInt())
                .coerceIn(1, layout.columns - button.col)
            val displayRowSpan = (button.rowSpan + (resizeDragPx.y / cellHPx).roundToInt())
                .coerceIn(1, layout.rows - button.row)
            val bw = cellW * displayColSpan - gap
            val bh = cellH * displayRowSpan - gap

            val isSelected = button.id == selectedButtonId

            if (!isEditMode && button.isTrackpad) {
                // ── Trackpad (normal mode) ────────────────────────────────────
                Box(
                    modifier = Modifier
                        .absoluteOffset(x = bx, y = by)
                        .size(width = bw, height = bh)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .pointerInput(button.id + "_tp") {
                            var lastTapTimeMs = 0L
                            awaitPointerEventScope {
                                while (true) {
                                    // Wait for finger down
                                    var down = awaitPointerEvent().changes
                                        .firstOrNull { it.pressed && !it.previousPressed }
                                    while (down == null) {
                                        down = awaitPointerEvent().changes
                                            .firstOrNull { it.pressed && !it.previousPressed }
                                    }
                                    down.consume()

                                    val downPos = down.position
                                    var prevPos = down.position
                                    var hasMoved = false
                                    var dragStarted = false
                                    var longPressFired = false

                                    val longPressJob: Job = gridScope.launch {
                                        delay(LONG_PRESS_DURATION_MS)
                                        longPressFired = true
                                        Log.d(TAG, "trackpad: long press → ${currentButton.gestureTarget(TrackpadGesture.LONG_PRESS)}")
                                        onTrackpadGesture(currentButton, TrackpadGesture.LONG_PRESS)
                                        // Reset double-tap window so a tap immediately after long-press
                                        // doesn't get incorrectly paired with a previous tap.
                                        lastTapTimeMs = 0L
                                    }

                                    var active = true
                                    while (active) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        if (!change.pressed) {
                                            longPressJob.cancel()
                                            if (dragStarted) onDragEnd()
                                            if (!hasMoved && !longPressFired) {
                                                val now = System.currentTimeMillis()
                                                // Always fire single tap immediately. If this turns out
                                                // to be the second of a double tap, also fire double tap.
                                                Log.d(TAG, "trackpad: tap → ${currentButton.gestureTarget(TrackpadGesture.TAP)}")
                                                onTrackpadGesture(currentButton, TrackpadGesture.TAP)
                                                if (lastTapTimeMs > 0L && (now - lastTapTimeMs) <= DOUBLE_TAP_INTERVAL_MS) {
                                                    Log.d(TAG, "trackpad: double tap → ${currentButton.gestureTarget(TrackpadGesture.DOUBLE_TAP)}")
                                                    onTrackpadGesture(currentButton, TrackpadGesture.DOUBLE_TAP)
                                                    lastTapTimeMs = 0L
                                                } else {
                                                    lastTapTimeMs = now
                                                }
                                            }
                                            active = false
                                        } else {
                                            val totalDelta = change.position - downPos
                                            val distSq = totalDelta.x * totalDelta.x + totalDelta.y * totalDelta.y
                                            if (!hasMoved && distSq > TAP_MOVEMENT_THRESHOLD_PX * TAP_MOVEMENT_THRESHOLD_PX) {
                                                Log.d(TAG, "trackpad: movement threshold crossed → drag start")
                                                longPressJob.cancel()
                                                hasMoved = true
                                                dragStarted = true
                                                prevPos = change.position
                                                onDragStart()
                                            }
                                            if (hasMoved) {
                                                val delta = change.position - prevPos
                                                val sens = currentButton.sensitivity ?: TRACKPAD_SENSITIVITY
                                                onMouseMove(delta.x * sens, delta.y * sens)
                                                prevPos = change.position
                                            }
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            Icons.Default.Mouse,
                            contentDescription = "Trackpad",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (button.label.isNotEmpty()) {
                            Text(
                                text = button.label,
                                fontSize = 9.sp,
                                lineHeight = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Clip
                            )
                        }
                    }
                }
            } else {
                // ── Key button (all modes) / trackpad in edit mode ────────────
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
                                        draggingId = currentButton.id
                                        dropTargetCol = currentButton.col
                                        dropTargetRow = currentButton.row
                                        dropIsValid = true
                                        if (currentSelectedId != currentButton.id) {
                                            onSelectButton(currentButton.id)
                                        }
                                    },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        dragOffset += delta
                                        val rawCol = ((currentButton.col * cellWPx + dragOffset.x) / cellWPx).roundToInt()
                                        val rawRow = ((currentButton.row * cellHPx + dragOffset.y) / cellHPx).roundToInt()
                                        dropTargetCol = rawCol.coerceIn(0, currentLayout.columns - currentButton.colSpan)
                                        dropTargetRow = rawRow.coerceIn(0, currentLayout.rows - currentButton.rowSpan)
                                        dropIsValid = !currentLayout.wouldOverlap(
                                            currentButton.id, dropTargetCol, dropTargetRow,
                                            currentButton.colSpan, currentButton.rowSpan
                                        )
                                    },
                                    onDragEnd = {
                                        if (dropIsValid) {
                                            onMoveButton(currentButton.id, dropTargetCol, dropTargetRow)
                                        }
                                        isDragging = false
                                        draggingId = null
                                        dragOffset = Offset.Zero
                                    },
                                    onDragCancel = {
                                        isDragging = false
                                        draggingId = null
                                        dragOffset = Offset.Zero
                                    }
                                )
                            } else Modifier
                        ),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    if (button.isTrackpad) {
                        // Edit mode trackpad preview
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(
                                    Icons.Default.Mouse,
                                    contentDescription = "Trackpad",
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = button.label.ifEmpty { "Trackpad" },
                                    fontSize = 9.sp,
                                    lineHeight = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize().padding(2.dp)) {
                            val topText = button.topText
                            if (!topText.isNullOrEmpty()) {
                                Text(
                                    text = topText,
                                    fontSize = 8.sp,
                                    lineHeight = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    textAlign = button.topAlign.toTextAlign(),
                                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                                )
                            }
                            Text(
                                text = button.label,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Clip,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().align(Alignment.Center)
                            )
                            val bottomText = button.bottomText
                            if (!bottomText.isNullOrEmpty()) {
                                Text(
                                    text = bottomText,
                                    fontSize = 8.sp,
                                    lineHeight = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    textAlign = button.bottomAlign.toTextAlign(),
                                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                                )
                            }
                        }
                    }
                }
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
                                    onResizeButton(
                                        currentButton.id,
                                        currentButton.colSpan + dCols,
                                        currentButton.rowSpan + dRows
                                    )
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

private const val TAG = "MapoInput"
private const val TRACKPAD_SENSITIVITY = 1.5f
private const val TAP_MOVEMENT_THRESHOLD_PX = 12f
private const val DOUBLE_TAP_INTERVAL_MS = 250L
private const val LONG_PRESS_DURATION_MS = 500L

@Composable
private fun GestureMappingRow(
    label: String,
    target: RemapTarget,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(110.dp))
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(target.displayLabel(), fontSize = 12.sp)
        }
    }
}

@Composable
private fun BottomBar(onQuit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onQuit,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(40.dp)
        ) {
            Text("Quit", fontSize = 12.sp)
        }
    }
}

private fun String?.toTextAlign() = when (this) {
    "LEFT"  -> TextAlign.Left
    "RIGHT" -> TextAlign.Right
    else    -> TextAlign.Center
}

@Composable
private fun AlignSelector(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("LEFT" to "L", "CENTER" to "C", "RIGHT" to "R").forEach { (align, label) ->
            val isSelected = align == selected
            OutlinedButton(
                onClick = { onSelect(align) },
                modifier = Modifier.size(32.dp),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                     else MaterialTheme.colorScheme.surface
                )
            ) {
                Text(label, fontSize = 12.sp)
            }
        }
    }
}
