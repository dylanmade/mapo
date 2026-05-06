package com.mapo.ui.screen

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.mapo.data.model.TemplateRef
import com.mapo.service.InputAccessibilityService
import com.mapo.ui.MapoGesture
import com.mapo.service.autoswitch.ProfileAutoSwitcher
import com.mapo.ui.screen.keyboard.KeyboardTabBar
import com.mapo.ui.screen.keyboard.TabActionDialog
import com.mapo.ui.screen.keyboard.TabActionDialogHost
import com.mapo.ui.viewmodel.MainViewModel
import com.mapo.ui.viewmodel.TabUiEvent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val selectedIndex by viewModel.selectedIndex.collectAsStateWithLifecycle()
    val isEditMode by viewModel.isEditMode.collectAsStateWithLifecycle()
    val layouts by viewModel.layouts.collectAsStateWithLifecycle()
    val displayLayout by viewModel.displayLayout.collectAsStateWithLifecycle()
    val selectedButtonId by viewModel.selectedButtonId.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val tabContextMenuFor by viewModel.tabContextMenuFor.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val userTemplates = remember(templates) {
        templates.filterIsInstance<TemplateRef.User>().toImmutableList()
    }

    var tabActionDialog by remember { mutableStateOf<TabActionDialog?>(null) }

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
            // Defensive fallback only: when the overlay permission is granted, the
            // OverlayCoordinator renders these on the primary screen and we don't
            // want a second copy on Mapo's screen.
            if (isOverlayPermissionGranted(context)) return@collect
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

    LaunchedEffect(Unit) {
        viewModel.tabUiEvents.collect { event ->
            when (event) {
                is TabUiEvent.ConfigureConflict -> {
                    val draft = TabActionDialog.Configure(
                        layoutId = event.layoutId,
                        name = event.name,
                        cols = event.cols,
                        rows = event.rows,
                        bgColor = event.bgColor,
                        originalName = (tabActionDialog as? TabActionDialog.Configure)?.originalName
                    )
                    tabActionDialog = TabActionDialog.ResizeConflict(
                        layoutId = event.layoutId,
                        draft = draft,
                        offendingLabels = event.offendingLabels
                    )
                }
                is TabUiEvent.TemplateNameConflict -> {
                    tabActionDialog = TabActionDialog.TemplateNameConflict(
                        layoutId = event.layoutId,
                        keyboardName = (tabActionDialog as? TabActionDialog.SaveAsNewTemplate)
                            ?.keyboardName ?: "",
                        templateName = event.templateName,
                        existing = event.existing
                    )
                }
            }
        }
    }

    val showRemapControls by viewModel.showRemapControls.collectAsStateWithLifecycle()
    val activeProfileMappings by viewModel.activeProfileMappings.collectAsStateWithLifecycle()
    val remapEnabled by viewModel.remapEnabled.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showAutoSwitch by remember { mutableStateOf(false) }
    var showBlocklist by remember { mutableStateOf(false) }
    var showThemeStudio by remember { mutableStateOf(false) }

    var accessibilityGranted by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var overlayGranted by remember { mutableStateOf(isOverlayPermissionGranted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityGranted = isAccessibilityServiceEnabled(context)
                overlayGranted = isOverlayPermissionGranted(context)
                // Dual-screen flow: opening Mapo while a bound app is already running
                // on the primary screen wouldn't fire a fresh WINDOW_STATE_CHANGED for
                // the game, so the auto-switcher's distinctUntilChanged would suppress
                // it. Force a re-evaluation on every resume.
                viewModel.reevaluateAutoSwitch()
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
    // When non-null, the button-config dialog's OK handler will create a button at this exact
    // (col, row) instead of at the next first-empty cell. Set by the in-grid + icons.
    var newButtonCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
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
                        val cell = newButtonCell
                        if (cell != null) {
                            viewModel.addButtonAt(
                                cell.first, cell.second,
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
                        newButtonCell = null
                    }
                    showButtonDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showButtonDialog = false
                    newButtonCell = null
                }) { Text("Cancel") }
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
                },
                onOpenBlocklist = {
                    scope.launch { drawerState.close() }
                    showBlocklist = true
                },
                onOpenThemeStudio = {
                    scope.launch { drawerState.close() }
                    showThemeStudio = true
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
                // No manual inset padding: MainActivity does NOT call enableEdgeToEdge,
                // so the OS sizes the activity window below the status bar where one
                // exists (phone, Thor primary screen) and leaves it alone where one
                // doesn't (Thor bottom bezel screen). Adding statusBarsPadding here
                // would double-reserve and reintroduce the stale-inset shift bug.
                modifier = Modifier.fillMaxSize()
            ) {
                KeyboardTopBar(
                    layouts = layouts,
                    selectedIndex = selectedIndex,
                    tabContextMenuFor = tabContextMenuFor,
                    onSelectIndex = { viewModel.selectLayout(it) },
                    onLongPressMenu = { id -> viewModel.openTabMenu(id) },
                    onReorder = { from, to -> viewModel.reorderTabs(from, to) },
                    onCloseMenu = { viewModel.closeTabMenu() },
                    onMenuEditButtons = { id -> viewModel.enterEditMode(id) },
                    onMenuConfigure = { id ->
                        val layout = layouts.find { it.id == id }
                        if (layout != null) {
                            tabActionDialog = TabActionDialog.Configure(
                                layoutId = id,
                                name = layout.name,
                                cols = layout.columns,
                                rows = layout.rows,
                                bgColor = layout.backgroundColorArgb,
                                originalName = viewModel.originalNames.value[id]
                            )
                        }
                    },
                    onMenuDuplicate = { id -> viewModel.duplicateKeyboard(id) },
                    onMenuRemove = { id ->
                        val layout = layouts.find { it.id == id }
                        val profileName = activeProfile?.name ?: ""
                        if (layout != null) {
                            tabActionDialog = TabActionDialog.RemoveConfirm(
                                layoutId = id,
                                name = layout.name,
                                profileName = profileName
                            )
                        }
                    },
                    onMenuSaveTemplate = { id ->
                        val layout = layouts.find { it.id == id }
                        if (layout != null) {
                            tabActionDialog = TabActionDialog.SaveTemplateChooser(
                                layoutId = id,
                                keyboardName = layout.name
                            )
                        }
                    },
                    onOpenDrawer = {
                        // Drawer surfaces global navigation; opening it ends any per-tab
                        // edit context. Add-keyboard cancellation, by contrast, leaves
                        // edit mode intact (handled in the VM funnel).
                        viewModel.exitEditMode()
                        scope.launch { drawerState.open() }
                    },
                    onAddKeyboard = { tabActionDialog = TabActionDialog.AddKeyboardChooser }
                )

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
                    onConfigureButton = { id ->
                        val btn = displayLayout.buttons.find { it.id == id }
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
                            viewModel.selectButtonOnly(id)
                            showButtonDialog = true
                        }
                    },
                    onDuplicateButton = { id -> viewModel.duplicateButton(id) },
                    onRemoveButton = { id ->
                        val btn = displayLayout.buttons.find { it.id == id }
                        if (btn != null) {
                            tabActionDialog = TabActionDialog.RemoveButtonConfirm(
                                buttonId = id,
                                buttonLabel = btn.label
                            )
                        }
                    },
                    onAddAtCell = { col, row ->
                        newButtonCell = col to row
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
                    onLongPressEmptyArea = { viewModel.enterEditMode(displayLayout.id) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(4.dp)
                )
                BottomBar(
                    remapEnabled = remapEnabled,
                    onToggleRemap = { viewModel.toggleRemap() },
                    onQuit = { (context as? Activity)?.finish() }
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
    if (showAutoSwitch) {
        AutoSwitchScreen(
            onBack = { showAutoSwitch = false }
        )
    }
    if (showBlocklist) {
        BlocklistScreen(
            onBack = { showBlocklist = false }
        )
    }
    if (showThemeStudio) {
        com.themestudio.ui.ThemeStudioScreen(
            onClose = { showThemeStudio = false },
            theme = { content -> com.mapo.ui.theme.MapoTheme { content() } },
        )
    }

    TabActionDialogHost(
        state = tabActionDialog,
        profileName = activeProfile?.name ?: "",
        userTemplates = userTemplates,
        allTemplates = templates,
        profiles = profiles,
        activeProfileId = activeProfile?.id,
        onStateChange = { tabActionDialog = it },
        onApplyConfigure = { id, name, cols, rows, bgColor ->
            viewModel.configureKeyboard(id, name, cols, rows, bgColor)
        },
        onApplyAutoResize = { id, name, cols, rows, bgColor ->
            viewModel.applyConfigureWithAutoResize(id, name, cols, rows, bgColor)
        },
        onConfirmReset = { id -> viewModel.resetKeyboard(id) },
        onConfirmRemove = { id -> viewModel.removeKeyboard(id) },
        onSaveAsNewTemplate = { id, templateName ->
            viewModel.saveAsNewTemplate(id, templateName)
        },
        onUpdateExistingTemplate = { id, target ->
            viewModel.updateExistingTemplate(id, target)
        },
        onTemplateSaveCanceled = {
            tabActionDialog = null
            viewModel.emitToast("Keyboard template save cancelled")
        },
        onAddBlankKeyboard = { viewModel.addBlankKeyboard() },
        onAddFromTemplate = { template -> viewModel.addKeyboardFromTemplate(template) },
        onAddFromProfile = { sourceLayoutId -> viewModel.addKeyboardFromProfile(sourceLayoutId) },
        fetchProfileLayouts = { profileId -> viewModel.layoutsForProfile(profileId) },
        onConfirmDeleteButton = { id -> viewModel.deleteButton(id) }
    )
    } // end Box
}

@Composable
private fun KeyboardTopBar(
    layouts: ImmutableList<GridLayout>,
    selectedIndex: Int,
    tabContextMenuFor: Long?,
    onSelectIndex: (Int) -> Unit,
    onLongPressMenu: (Long) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onCloseMenu: () -> Unit,
    onMenuEditButtons: (Long) -> Unit,
    onMenuConfigure: (Long) -> Unit,
    onMenuDuplicate: (Long) -> Unit,
    onMenuRemove: (Long) -> Unit,
    onMenuSaveTemplate: (Long) -> Unit,
    onOpenDrawer: () -> Unit,
    onAddKeyboard: () -> Unit
) {
    // Top bar is identical in normal and edit modes. All button-level CRUD now
    // routes through the per-button long-press menu and the +icons in empty cells,
    // so the edit-mode-only Add / Delete / Edit / Save / Cancel toolbar is gone.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(40.dp)
    ) {
        IconButton(onClick = onOpenDrawer, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Menu, contentDescription = "Open menu", modifier = Modifier.size(20.dp))
        }
        KeyboardTabBar(
            layouts = layouts,
            selectedIndex = selectedIndex,
            tabContextMenuFor = tabContextMenuFor,
            onSelectIndex = onSelectIndex,
            onLongPressMenu = onLongPressMenu,
            onReorder = onReorder,
            onCloseMenu = onCloseMenu,
            onMenuEditButtons = onMenuEditButtons,
            onMenuConfigure = onMenuConfigure,
            onMenuDuplicate = onMenuDuplicate,
            onMenuRemove = onMenuRemove,
            onMenuSaveTemplate = onMenuSaveTemplate,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onAddKeyboard, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add keyboard",
                modifier = Modifier.size(20.dp)
            )
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
    onDragStart: () -> Unit,
    onMouseMove: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onTrackpadGesture: (GridButton, TrackpadGesture) -> Unit,
    onConfigureButton: (String) -> Unit,
    onDuplicateButton: (String) -> Unit,
    onRemoveButton: (String) -> Unit,
    onAddAtCell: (Int, Int) -> Unit,
    onLongPressEmptyArea: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val gridScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val handleSize = 20.dp
    val gap = 3.dp
    val currentSelectedId by rememberUpdatedState(selectedButtonId)

    var draggingId by remember { mutableStateOf<String?>(null) }
    var dropTargetCol by remember { mutableStateOf(0) }
    var dropTargetRow by remember { mutableStateOf(0) }
    var dropIsValid by remember { mutableStateOf(true) }
    // Per-button long-press contextual menu — local UI state, mirrors the tab-bar pattern.
    var buttonContextMenuFor by remember { mutableStateOf<String?>(null) }
    // Hoisted out of the per-button loop so the empty-cell "+" affordances can hide
    // while any button is being resized — otherwise the resize drag passes over them
    // and a stray tap adds an unwanted button.
    var isAnyResizing by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            // Background long-press = shortcut into edit mode. Children (buttons, +icons)
            // consume their own pointer events, so this only fires when the press lands
            // on truly empty grid space. Skipped while already in edit mode — there's
            // nothing to enter, and we don't want a re-haptic mid-edit.
            .then(
                if (!isEditMode) Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        val touchSlop = viewConfiguration.touchSlop
                        val longPressMs = viewConfiguration.longPressTimeoutMillis
                        val downPos = down.position
                        try {
                            withTimeout(longPressMs) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: continue
                                    if (!change.pressed) return@withTimeout
                                    if ((change.position - downPos).getDistance() > touchSlop) {
                                        return@withTimeout
                                    }
                                }
                            }
                        } catch (_: PointerEventTimeoutCancellationException) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPressEmptyArea()
                        }
                    }
                } else Modifier
            )
    ) {
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
                // OUTER Box: natural layout slot. Hosts pointerInput WITHOUT graphicsLayer,
                // so pointer-event coordinates stay in a stable frame during drag. The
                // INNER OutlinedButton applies the graphicsLayer drag translation — which
                // affects drawing only, not the gesture-coord frame.
                Box(
                    modifier = Modifier
                        .absoluteOffset(x = bx, y = by)
                        .size(width = bw, height = bh)
                        .zIndex(if (isDragging) 10f else 0f)
                        .then(
                            if (isEditMode) Modifier.pointerInput(button.id) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val touchSlop = viewConfiguration.touchSlop
                                    val reorderSlop = MapoGesture.reorderSlopPx(viewConfiguration)
                                    val longPressMs = viewConfiguration.longPressTimeoutMillis
                                    val downPos = down.position

                                    // Phase 1: race long-press timer vs. up vs. drag-before-timer.
                                    // Crucially we do NOT consume on tap — we let OutlinedButton's
                                    // onClick handle the tap (preserves ripple + select behavior).
                                    val longPressed: Boolean = try {
                                        withTimeout(longPressMs) {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id }
                                                    ?: continue
                                                if (!change.pressed) return@withTimeout false
                                                val moved = (change.position - downPos).getDistance()
                                                if (moved > touchSlop) return@withTimeout false
                                            }
                                            @Suppress("UNREACHABLE_CODE") false
                                        }
                                    } catch (_: PointerEventTimeoutCancellationException) {
                                        true
                                    }

                                    if (!longPressed) return@awaitEachGesture

                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    buttonContextMenuFor = currentButton.id
                                    if (currentSelectedId != currentButton.id) {
                                        onSelectButton(currentButton.id)
                                    }

                                    // Phase 2: lifted — drag becomes a move; release w/o drag keeps menu open.
                                    var dragStarted = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: continue
                                        change.consume()
                                        if (!change.pressed) {
                                            if (dragStarted) {
                                                if (dropIsValid) {
                                                    onMoveButton(currentButton.id, dropTargetCol, dropTargetRow)
                                                }
                                                isDragging = false
                                                draggingId = null
                                                dragOffset = Offset.Zero
                                            }
                                            break
                                        }
                                        val totalMoved = (change.position - downPos).getDistance()
                                        if (!dragStarted && totalMoved > reorderSlop) {
                                            dragStarted = true
                                            buttonContextMenuFor = null  // close menu when drag begins
                                            isDragging = true
                                            draggingId = currentButton.id
                                            dropTargetCol = currentButton.col
                                            dropTargetRow = currentButton.row
                                            dropIsValid = true
                                        }
                                        if (dragStarted) {
                                            dragOffset = change.position - downPos
                                            val rawCol = ((currentButton.col * cellWPx + dragOffset.x) / cellWPx).roundToInt()
                                            val rawRow = ((currentButton.row * cellHPx + dragOffset.y) / cellHPx).roundToInt()
                                            dropTargetCol = rawCol.coerceIn(0, currentLayout.columns - currentButton.colSpan)
                                            dropTargetRow = rawRow.coerceIn(0, currentLayout.rows - currentButton.rowSpan)
                                            dropIsValid = !currentLayout.wouldOverlap(
                                                currentButton.id, dropTargetCol, dropTargetRow,
                                                currentButton.colSpan, currentButton.rowSpan
                                            )
                                        }
                                    }
                                }
                            } else Modifier
                        )
                ) {
                    OutlinedButton(
                        onClick = {
                            if (isEditMode) onSelectButton(button.id) else onKeyPress(button.code)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = dragOffset.x
                                translationY = dragOffset.y
                            },
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
                                onDragStart = { isAnyResizing = true },
                                onDragEnd = {
                                    val dCols = (resizeDragPx.x / cellWPx).roundToInt()
                                    val dRows = (resizeDragPx.y / cellHPx).roundToInt()
                                    onResizeButton(
                                        currentButton.id,
                                        currentButton.colSpan + dCols,
                                        currentButton.rowSpan + dRows
                                    )
                                    resizeDragPx = Offset.Zero
                                    isAnyResizing = false
                                },
                                onDragCancel = {
                                    resizeDragPx = Offset.Zero
                                    isAnyResizing = false
                                },
                                onDrag = { change, delta ->
                                    change.consume()
                                    resizeDragPx += delta
                                }
                            )
                        }
                )
            }

            // ── Long-press contextual menu (anchored at the button's slot) ────
            if (isEditMode) {
                Box(
                    modifier = Modifier
                        .absoluteOffset(x = bx, y = by)
                        .size(width = bw.coerceAtLeast(1.dp), height = bh.coerceAtLeast(1.dp))
                        .zIndex(30f)
                ) {
                    DropdownMenu(
                        expanded = buttonContextMenuFor == button.id,
                        onDismissRequest = { buttonContextMenuFor = null }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Configure button") },
                            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                            onClick = {
                                buttonContextMenuFor = null
                                onConfigureButton(currentButton.id)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate button") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = {
                                buttonContextMenuFor = null
                                onDuplicateButton(currentButton.id)
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Delete button",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                buttonContextMenuFor = null
                                onRemoveButton(currentButton.id)
                            }
                        )
                    }
                }
            }
        }

        // ── Plus icons in unoccupied 1x1 cells (edit mode, when cells are big enough) ─
        if (isEditMode && !isAnyResizing && cellW >= 24.dp && cellH >= 24.dp) {
            val occupied = remember(layout.buttons) {
                buildSet {
                    for (btn in layout.buttons) {
                        for (r in btn.row until btn.row + btn.rowSpan) {
                            for (c in btn.col until btn.col + btn.colSpan) {
                                add(c to r)
                            }
                        }
                    }
                }
            }
            for (r in 0 until layout.rows) {
                for (c in 0 until layout.columns) {
                    if ((c to r) in occupied) continue
                    val ix = cellW * c
                    val iy = cellH * r
                    Box(
                        modifier = Modifier
                            .absoluteOffset(x = ix, y = iy)
                            .size(width = cellW - gap, height = cellH - gap)
                            .clickable { onAddAtCell(c, r) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add button at $c, $r",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
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
private fun BottomBar(
    remapEnabled: Boolean,
    onToggleRemap: () -> Unit,
    onQuit: () -> Unit
) {
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
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            Icons.Default.SportsEsports,
            contentDescription = if (remapEnabled) "Remapping enabled" else "Remapping disabled",
            modifier = Modifier.size(20.dp),
            tint = if (remapEnabled) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = remapEnabled,
            onCheckedChange = { onToggleRemap() },
            modifier = Modifier.padding(end = 4.dp)
        )
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
