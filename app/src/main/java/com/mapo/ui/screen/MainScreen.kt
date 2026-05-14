package com.mapo.ui.screen

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.Dp
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
import com.mapo.data.model.steam.resolveActionSet
import com.mapo.data.model.TrackpadGesture
import com.mapo.data.model.ButtonRegion
import com.mapo.data.model.RegionPosition
import com.mapo.data.model.gestureTarget
import com.mapo.data.model.onDoubleTapTarget
import com.mapo.data.model.onHoldTarget
import com.mapo.data.model.onTapTarget
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Shape
import com.mapo.ui.component.MapoIcons
import com.mapo.ui.util.keyboardButtonParentColor
import com.mapo.ui.util.resolveAutoColors
import com.mapo.ui.util.resolveAutoLayoutColors
import com.mapo.data.model.isTrackpad
import com.mapo.data.model.displayLabel
import com.mapo.data.model.wouldOverlap
import com.mapo.data.model.TemplateRef
import com.mapo.service.InputAccessibilityService
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mapo.ui.MapoGesture
import com.mapo.ui.nav.MapoRoute
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

    val activeControllerConfig by viewModel.activeControllerConfig.collectAsStateWithLifecycle()
    val viewingActionSetId by viewModel.viewingActionSetId.collectAsStateWithLifecycle()
    val remapEnabled by viewModel.remapEnabled.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()

    // Toggle window flags + back-gesture suppression based on which destination is showing
    // and whether the drawer is open. Keeping these conditional (vs. unconditional in
    // MainActivity) is what makes back-gesture / back-button work on the secondary screens
    // and on the drawer (drawer-open implies the user is choosing nav, not playing a game).
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val isMainRoute = currentBackStackEntry?.destination?.route == MapoRoute.MAIN
    val drawerOpen = drawerState.isOpen
    val keyboardViewActive = isMainRoute && !drawerOpen
    ApplyMainScreenWindowBehavior(
        notFocusable = keyboardViewActive,
        suppressBackGesture = keyboardViewActive,
    )
    // While the keyboard view is showing the window is not focusable, so KEYCODE_BACK
    // has nowhere to deliver and the input dispatcher would ANR. Have the accessibility
    // service consume the back key in that exact state. Matches user intent (back is a
    // no-op on the keyboard view to prevent accidental dismissal during typing/trackpad).
    LaunchedEffect(keyboardViewActive) {
        viewModel.setConsumeSystemBack(keyboardViewActive)
    }
    // M3's ModalNavigationDrawer doesn't ship an internal BackHandler, so without this
    // the press falls through to the activity default (moveTaskToBack) and the whole
    // app minimizes. Close the drawer instead, which is what the user expects.
    BackHandler(enabled = drawerOpen) {
        scope.launch { drawerState.close() }
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ProfileDrawerContent(
                activeProfile = activeProfile,
                onOpenChangeProfile = {
                    scope.launch { drawerState.close() }
                    navController.navigate(MapoRoute.CHANGE_PROFILE)
                },
                onOpenRemapControls = {
                    scope.launch { drawerState.close() }
                    navController.navigate(MapoRoute.REMAP_CONTROLS)
                },
                onOpenAutoSwitch = {
                    scope.launch { drawerState.close() }
                    navController.navigate(MapoRoute.AUTO_SWITCH)
                },
                onOpenBlocklist = {
                    scope.launch { drawerState.close() }
                    navController.navigate(MapoRoute.BLOCKLIST)
                },
                onOpenThemeStudio = {
                    scope.launch { drawerState.close() }
                    navController.navigate(MapoRoute.THEME_STUDIO)
                },
            )
        }
    ) {
        NavHost(
            navController = navController,
            startDestination = MapoRoute.MAIN,
            // Background matches the home screen's Scaffold (and the secondary destinations'
            // visual base in this theme), so the crossfade middle no longer reveals the
            // activity window beneath. Without this the partial alpha of both screens lets
            // the activity background bleed through mid-transition.
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            // Crossfade at 250 ms with default FastOutSlowInEasing.
            enterTransition = { fadeIn(tween(250)) },
            exitTransition = { fadeOut(tween(250)) },
            popEnterTransition = { fadeIn(tween(250)) },
            popExitTransition = { fadeOut(tween(250)) },
        ) {
            composable(MapoRoute.MAIN) {
                // surfaceContainerLowest — root app Scaffold (M3 default; do not override to colorScheme.background)
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
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)
                ) { _ ->
                    Column(
                        // No manual inset padding: MainActivity does NOT call enableEdgeToEdge,
                        // so the OS sizes the activity window below the status bar where one
                        // exists (phone, Thor primary screen) and leaves it alone where one
                        // doesn't (Thor bottom bezel screen). Adding statusBarsPadding here
                        // would double-reserve and reintroduce the stale-inset shift bug.
                        //
                        // The vertical 4.dp here is plain visual padding (not inset-aware), so
                        // it doesn't conflict with the rule above. It gives the top/bottom bars
                        // breathing room from the screen edge — without it, the bar controls
                        // appeared to be clipped by the Thor's bottom bezel screen.
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 4.dp)
                    ) {
                        KeyboardTopBar(
                            layouts = layouts,
                            selectedIndex = selectedIndex,
                            isEditMode = isEditMode,
                            tabContextMenuFor = tabContextMenuFor,
                            onSelectIndex = { viewModel.selectLayout(it) },
                            onLongPressMenu = { id -> viewModel.openTabMenu(id) },
                            onReorder = { from, to -> viewModel.reorderTabs(from, to) },
                            onCloseMenu = { viewModel.closeTabMenu() },
                            onMenuEditButtons = { id -> viewModel.enterEditMode(id) },
                            onToggleEditMode = {
                                if (isEditMode) viewModel.exitEditMode()
                                else viewModel.enterEditMode(displayLayout.id)
                            },
                            onMenuConfigure = { id ->
                                navController.navigate(MapoRoute.configureKeyboard(id))
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

                        KeyboardSurface(
                            layout = displayLayout,
                            themeFallback = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(4.dp),
                        ) {
                            KeyGrid(
                                layout = displayLayout,
                                isEditMode = isEditMode,
                                selectedButtonId = selectedButtonId,
                                onButtonTap = viewModel::onButtonTap,
                                onButtonDoubleTap = viewModel::onButtonDoubleTap,
                                onButtonHold = viewModel::onButtonHold,
                                onSelectButton = viewModel::selectButton,
                                onMoveButton = viewModel::moveButton,
                                onResizeButton = { id, c, r, cs, rs ->
                                    viewModel.resizeButton(id, c, r, cs, rs)
                                },
                                onDragStart = viewModel::onDragStart,
                                onMouseMove = viewModel::onMouseMove,
                                onDragEnd = viewModel::onDragEnd,
                                onTrackpadGesture = viewModel::onTrackpadGesture,
                                onConfigureButton = { id ->
                                    val btn = displayLayout.buttons.find { it.id == id }
                                    if (btn != null) {
                                        // The configure screen is instant-commit; setting selectedButtonId
                                        // here makes viewModel.updateSelectedButton apply to the right one.
                                        viewModel.selectButtonOnly(id)
                                        navController.navigate(MapoRoute.configureButton(id))
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
                                    // Instant-commit add: create a default key-button at the cell first,
                                    // then navigate to its config screen for further editing. Backing out
                                    // leaves the button in place; the user removes it via long-press if
                                    // they didn't actually want it.
                                    viewModel.addButtonAt(col, row, GridButton(col = col, row = row, type = "key"))
                                    viewModel.selectedButtonId.value?.let { newId ->
                                        navController.navigate(MapoRoute.configureButton(newId))
                                    }
                                },
                                onLongPressEmptyArea = { viewModel.enterEditMode(displayLayout.id) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        BottomBar(
                            remapEnabled = remapEnabled,
                            onToggleRemap = { viewModel.toggleRemap() },
                            onQuit = { (context as? Activity)?.finish() }
                        )
                    }
                }
            }
            composable(MapoRoute.CHANGE_PROFILE) {
                ChangeProfileScreen(
                    profiles = profiles,
                    activeProfile = activeProfile,
                    onSelectProfile = { profile ->
                        viewModel.selectProfile(profile)
                        navController.popBackStack()
                    },
                    onAddProfile = { name -> viewModel.addProfile(name) },
                    onDuplicateProfile = { profile -> viewModel.duplicateProfile(profile) },
                    onDeleteProfile = { profile -> viewModel.deleteProfile(profile) },
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            composable(MapoRoute.REMAP_CONTROLS) {
                RemapControlsScreen(
                    config = activeControllerConfig,
                    viewingActionSetId = viewingActionSetId,
                    onSelectActionSet = viewModel::setViewingActionSet,
                    onAddActionSet = { title, inheritFromSetId ->
                        viewModel.addControllerActionSet(
                            name = com.mapo.ui.screen.deriveActionSetName(title),
                            title = title,
                            inheritFromSetId = inheritFromSetId,
                        )
                    },
                    onRenameActionSet = { setId, newTitle ->
                        viewModel.renameControllerActionSet(
                            actionSetId = setId,
                            name = com.mapo.ui.screen.deriveActionSetName(newTitle),
                            title = newTitle,
                        )
                    },
                    onDuplicateActionSet = { sourceSetId, newTitle ->
                        viewModel.duplicateControllerActionSet(
                            sourceSetId = sourceSetId,
                            name = com.mapo.ui.screen.deriveActionSetName(newTitle),
                            title = newTitle,
                        )
                    },
                    onDeleteActionSet = viewModel::deleteControllerActionSet,
                    onBack = { navController.popBackStack() },
                    onOpenInputEditor = { inputSource, groupInputKey, label ->
                        navController.navigate(
                            MapoRoute.inputEditor(inputSource.name, groupInputKey, label)
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            composable(
                route = MapoRoute.INPUT_EDITOR,
                arguments = listOf(
                    navArgument(MapoRoute.ARG_INPUT_SOURCE) { type = NavType.StringType },
                    navArgument(MapoRoute.ARG_GROUP_INPUT_KEY) { type = NavType.StringType },
                    navArgument(MapoRoute.ARG_INPUT_LABEL) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val inputSourceName = entry.arguments?.getString(MapoRoute.ARG_INPUT_SOURCE) ?: return@composable
                val groupInputKey = entry.arguments?.getString(MapoRoute.ARG_GROUP_INPUT_KEY) ?: return@composable
                val label = entry.arguments?.getString(MapoRoute.ARG_INPUT_LABEL).orEmpty()
                val inputSource = runCatching {
                    com.mapo.data.model.steam.InputSource.valueOf(inputSourceName)
                }.getOrNull() ?: return@composable
                val pickerResult by entry.savedStateHandle
                    .getStateFlow<String?>(MapoRoute.PICKER_RESULT_KEY, null)
                    .collectAsStateWithLifecycle()
                InputEditorScreen(
                    inputLabel = label.ifEmpty { groupInputKey },
                    inputSource = inputSource,
                    groupInputKey = groupInputKey,
                    config = activeControllerConfig,
                    viewingActionSetId = viewingActionSetId,
                    pickerResult = pickerResult?.let { RemapTarget.decode(it) },
                    onConsumePickerResult = {
                        entry.savedStateHandle.remove<String>(MapoRoute.PICKER_RESULT_KEY)
                    },
                    onPickResult = { bindingId, output ->
                        viewModel.setControllerCommand(bindingId, output)
                    },
                    onOpenPicker = { title, current ->
                        navController.navigate(MapoRoute.remapTargetPicker(title, current.encode()))
                    },
                    onAddActivator = { groupInputId, type ->
                        viewModel.addControllerActivator(groupInputId, type)
                    },
                    onRemoveActivator = { activatorId ->
                        viewModel.removeControllerActivator(activatorId)
                    },
                    onSetActivatorType = { activatorId, type ->
                        viewModel.setControllerActivatorType(activatorId, type)
                    },
                    onOpenActivatorSettings = { activatorId, label ->
                        navController.navigate(MapoRoute.activatorEditor(activatorId, label))
                    },
                    onAddCommand = { activatorId ->
                        viewModel.addControllerCommand(activatorId)
                    },
                    onRemoveCommand = { bindingId ->
                        viewModel.removeControllerCommand(bindingId)
                    },
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            composable(
                route = MapoRoute.ACTIVATOR_EDITOR,
                arguments = listOf(
                    navArgument(MapoRoute.ARG_ACTIVATOR_ID) { type = NavType.LongType },
                    navArgument(MapoRoute.ARG_ACTIVATOR_LABEL) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val activatorId = entry.arguments?.getLong(MapoRoute.ARG_ACTIVATOR_ID) ?: return@composable
                val label = entry.arguments?.getString(MapoRoute.ARG_ACTIVATOR_LABEL).orEmpty()
                // Brick 3.3.e: chord-partner picker writes its result here and pops back.
                // Apply via VM, then clear the savedStateHandle so recomposition doesn't re-apply.
                val chordResult by entry.savedStateHandle
                    .getStateFlow<String?>(MapoRoute.CHORD_PARTNER_RESULT_KEY, null)
                    .collectAsStateWithLifecycle()
                LaunchedEffect(chordResult, activeControllerConfig) {
                    val encoded = chordResult ?: return@LaunchedEffect
                    val parts = encoded.split("|", limit = 2)
                    if (parts.size != 2) return@LaunchedEffect
                    val source = runCatching {
                        com.mapo.data.model.steam.InputSource.valueOf(parts[0])
                    }.getOrNull() ?: return@LaunchedEffect
                    // Resolve current settings off the viewed set so we don't clobber other knobs.
                    val current = activeControllerConfig
                        ?.resolveActionSet(viewingActionSetId)
                        ?.preset
                        ?.flatMap { p -> p.group.inputs.flatMap { it.activators } }
                        ?.firstOrNull { it.activator.id == activatorId }
                        ?.let { com.mapo.service.input.CompiledActivatorSettings.parse(it.activator.settingsJson) }
                        ?: com.mapo.service.input.CompiledActivatorSettings.DEFAULTS
                    viewModel.setControllerActivatorSettings(
                        activatorId,
                        current.copy(chordPartnerSource = source, chordPartnerKey = parts[1]),
                    )
                    entry.savedStateHandle.remove<String>(MapoRoute.CHORD_PARTNER_RESULT_KEY)
                }
                ActivatorEditorScreen(
                    activatorId = activatorId,
                    title = label.ifEmpty { "Activator" },
                    config = activeControllerConfig,
                    viewingActionSetId = viewingActionSetId,
                    onSettingsChange = { id, settings ->
                        viewModel.setControllerActivatorSettings(id, settings)
                    },
                    onPickChordPartner = { id ->
                        navController.navigate(MapoRoute.chordPartnerPicker(id))
                    },
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            composable(
                route = MapoRoute.CHORD_PARTNER_PICKER,
                arguments = listOf(
                    navArgument(MapoRoute.ARG_ACTIVATOR_ID) { type = NavType.LongType },
                ),
            ) { _ ->
                ChordPartnerPickerScreen(
                    capturedInputs = viewModel.capturedInputs,
                    setCaptureMode = { viewModel.setCaptureMode(it) },
                    onPartnerCaptured = { address ->
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set(MapoRoute.CHORD_PARTNER_RESULT_KEY, "${address.source.name}|${address.inputKey}")
                        navController.popBackStack()
                    },
                    onCancel = { navController.popBackStack() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            composable(MapoRoute.AUTO_SWITCH) {
                AutoSwitchScreen(onBack = { navController.popBackStack() })
            }
            composable(MapoRoute.BLOCKLIST) {
                BlocklistScreen(onBack = { navController.popBackStack() })
            }
            composable(MapoRoute.THEME_STUDIO) {
                com.themestudio.ui.ThemeStudioScreen(
                    onClose = { navController.popBackStack() },
                    theme = { content -> com.mapo.ui.theme.MapoTheme { content() } },
                    defaultDisplayFontName = com.mapo.ui.theme.DEFAULT_DISPLAY_FONT_NAME,
                    defaultBodyFontName = com.mapo.ui.theme.DEFAULT_BODY_FONT_NAME,
                )
            }
            composable(
                route = MapoRoute.CONFIGURE_BUTTON,
                arguments = listOf(navArgument(MapoRoute.ARG_BUTTON_ID) { type = NavType.StringType }),
            ) { entry ->
                val buttonId = entry.arguments?.getString(MapoRoute.ARG_BUTTON_ID) ?: return@composable
                // Resolve the button live from the current display layout. If it's gone (e.g. user
                // navigated back from a delete), pop ourselves.
                val button = displayLayout.buttons.find { it.id == buttonId }
                if (button == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                    return@composable
                }
                val pickerResult by entry.savedStateHandle
                    .getStateFlow<String?>(MapoRoute.PICKER_RESULT_KEY, null)
                    .collectAsStateWithLifecycle()
                ConfigureButtonScreen(
                    button = button,
                    keyboardThemeColor = keyboardButtonParentColor(
                        layout = displayLayout,
                        themeFallback = MaterialTheme.colorScheme.surface,
                    ),
                    pickerResult = pickerResult?.let { RemapTarget.decode(it) },
                    onConsumePickerResult = {
                        entry.savedStateHandle.remove<String>(MapoRoute.PICKER_RESULT_KEY)
                    },
                    onUpdate = { updated ->
                        // selectedButtonId was set at the navigation-entry point and persists
                        // for the lifetime of this destination, so updateSelectedButton routes
                        // to the right button without a defensive re-selection here.
                        viewModel.updateSelectedButton(updated)
                    },
                    onOpenPicker = { title, current ->
                        navController.navigate(MapoRoute.remapTargetPicker(title, current.encode()))
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = MapoRoute.CONFIGURE_KEYBOARD,
                arguments = listOf(navArgument(MapoRoute.ARG_LAYOUT_ID) { type = NavType.LongType }),
            ) { entry ->
                val layoutId = entry.arguments?.getLong(MapoRoute.ARG_LAYOUT_ID) ?: return@composable
                val configuredLayout = layouts.find { it.id == layoutId }
                if (configuredLayout == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                    return@composable
                }
                ConfigureKeyboardScreen(
                    layout = configuredLayout,
                    themeFallback = MaterialTheme.colorScheme.surface,
                    onUpdate = { viewModel.updateLayoutInstant(it) },
                    onTryResize = { cols, rows -> viewModel.tryResizeLayout(layoutId, cols, rows) },
                    onApplyResizeWithAutoFit = { cols, rows ->
                        viewModel.applyResizeWithAutoFit(layoutId, cols, rows)
                    },
                    onReset = { viewModel.resetKeyboard(layoutId) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = MapoRoute.REMAP_TARGET_PICKER,
                arguments = listOf(
                    navArgument(MapoRoute.ARG_TITLE) { type = NavType.StringType },
                    navArgument(MapoRoute.ARG_CURRENT) { type = NavType.StringType },
                ),
            ) { entry ->
                val title = entry.arguments?.getString(MapoRoute.ARG_TITLE) ?: ""
                val currentEncoded = entry.arguments?.getString(MapoRoute.ARG_CURRENT) ?: "none"
                RemapTargetPickerScreen(
                    title = title,
                    currentEncoded = currentEncoded,
                    onSelect = { target ->
                        // Write the result to the previous destination's saved state and pop. The
                        // caller observes its own saved-state handle and applies the result to
                        // whatever it's editing (RemapControlsScreen's editingButton, or
                        // ConfigureButtonScreen's editingGesture).
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set(MapoRoute.PICKER_RESULT_KEY, target.encode())
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }

    TabActionDialogHost(
        state = tabActionDialog,
        profileName = activeProfile?.name ?: "",
        userTemplates = userTemplates,
        allTemplates = templates,
        profiles = profiles,
        activeProfileId = activeProfile?.id,
        onStateChange = { tabActionDialog = it },
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

/**
 * Per-destination window behavior. Replaces the unconditional setup in MainActivity so the
 * back-gesture exclusion + FLAG_NOT_FOCUSABLE only apply on the keyboard view (Main + drawer
 * closed). On secondary destinations (and on Main with the drawer open) both are cleared, so
 * the back gesture / button can navigate normally.
 *
 * **Why this exists:**
 * - `FLAG_NOT_FOCUSABLE` keeps unmapped gamepad inputs flowing to the game on the primary
 *   screen — load-bearing for the dual-screen design — but it also blocks the back button
 *   from reaching the activity, causing a 5 s input-dispatch ANR. Clearing it on secondary
 *   screens lets back work without sacrificing the gamepad routing on Main (where it matters).
 * - `systemGestureExclusionRects` covering the full window prevents accidental back-gesture
 *   swipes during virtual-keyboard / trackpad use on the keyboard view. On secondary screens
 *   (settings) there's no virtual-keyboard concern, so we let the gesture work for navigation.
 */
@Composable
private fun ApplyMainScreenWindowBehavior(
    notFocusable: Boolean,
    suppressBackGesture: Boolean,
) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return

    LaunchedEffect(notFocusable) {
        if (notFocusable) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // The exclusion rect needs to follow the view's current bounds (which can change with
        // configuration / display swaps), so we both apply on suppress-state changes AND
        // re-apply on every layout pass while suppression is active.
        val suppressState = rememberUpdatedState(suppressBackGesture)
        DisposableEffect(view) {
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                view.systemGestureExclusionRects = if (suppressState.value) {
                    listOf(Rect(0, 0, view.width, view.height))
                } else {
                    emptyList()
                }
            }
            view.viewTreeObserver.addOnGlobalLayoutListener(listener)
            onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
        }
        LaunchedEffect(suppressBackGesture) {
            view.systemGestureExclusionRects = if (suppressBackGesture) {
                listOf(Rect(0, 0, view.width, view.height))
            } else {
                emptyList()
            }
        }
    }
}

@Composable
private fun KeyboardTopBar(
    layouts: ImmutableList<GridLayout>,
    selectedIndex: Int,
    isEditMode: Boolean,
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
    onAddKeyboard: () -> Unit,
    onToggleEditMode: () -> Unit,
) {
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
        // Edit / done toggle. Outside edit mode this is the only way besides the per-tab
        // long-press menu to enter edit mode; inside it, this is the only top-level exit
        // that doesn't navigate away (tab-switch and drawer-open also exit).
        IconButton(onClick = onToggleEditMode, modifier = Modifier.size(40.dp)) {
            Icon(
                if (isEditMode) Icons.Default.Check else Icons.Default.Edit,
                contentDescription = if (isEditMode) "Exit edit mode" else "Edit buttons",
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
    onButtonTap: (GridButton) -> Unit,
    onButtonDoubleTap: (GridButton) -> Unit,
    onButtonHold: (GridButton) -> Unit,
    onSelectButton: (String) -> Unit,
    onMoveButton: (String, Int, Int) -> Unit,
    onResizeButton: (String, Int, Int, Int, Int) -> Unit,
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
        // Buttons are sized `cellW * span - gap` / `cellH * span - gap`, leaving a
        // `gap`-wide shortfall at the end of each cell. Without compensation that
        // shortfall accumulates only on the right/bottom edges of the grid (left/top
        // columns start at 0). Shifting every position by gap/2 distributes the
        // outer margin evenly: gap/2 on every side, full `gap` between buttons.
        val halfGap = gap / 2

        // ── Plus-icon underlay ────────────────────────────────────────────────
        // A complete grid of "+" affordances rendered BEFORE the buttons in source
        // order, so buttons (at the same zIndex 0) draw on top and occlude the plus
        // sitting beneath them. When a button is being dragged its visual is
        // translated away from its original cell — the underlay then becomes visible
        // there, even though the OUTER hit-test box is still anchored to the source
        // (so taps still belong to the button, not the plus). Pluses are non-
        // interactable while any button is being dragged or resized.
        if (isEditMode && cellW >= 24.dp && cellH >= 24.dp) {
            val isAnyDragging = draggingId != null
            val interactionBlocked = isAnyDragging || isAnyResizing
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
                    val cellOccupied = (c to r) in occupied
                    val canInteract = !interactionBlocked && !cellOccupied
                    val ix = cellW * c + halfGap
                    val iy = cellH * r + halfGap
                    Box(
                        modifier = Modifier
                            .absoluteOffset(x = ix, y = iy)
                            .size(width = cellW - gap, height = cellH - gap)
                            .then(
                                if (canInteract) Modifier.pointerInput(c, r) {
                                    detectTapGestures(
                                        onLongPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onAddAtCell(c, r)
                                        }
                                    )
                                } else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = if (canInteract) "Add button at $c, $r" else null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (interactionBlocked) 0.2f else 0.4f
                            )
                        )
                    }
                }
            }
        }

        // ── Drop indicator ────────────────────────────────────────────────────
        val draggingButton = if (draggingId != null) layout.buttons.find { it.id == draggingId } else null
        if (isEditMode && draggingButton != null) {
            val extraColors = com.mapo.ui.theme.LocalMapoExtraColors.current
            val validColor = extraColors.dropZoneValid
            val invalidColor = extraColors.dropZoneInvalid
            Box(
                modifier = Modifier
                    .absoluteOffset(
                        x = cellW * dropTargetCol + halfGap,
                        y = cellH * dropTargetRow + halfGap
                    )
                    .size(
                        width = cellW * draggingButton.colSpan - gap,
                        height = cellH * draggingButton.rowSpan - gap
                    )
                    .zIndex(5f)
                    .background(
                        (if (dropIsValid) validColor else invalidColor).copy(alpha = 0.38f),
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
            var resizeCorner by remember(button.id) { mutableStateOf<ResizeCorner?>(null) }

            // Three sets of bounds coexist during a resize:
            //   1. COMMITTED  (bx/by/bw/bh, == orig*): button.col/row/spans. The button
            //      itself renders here and stays put during the entire drag — it commits
            //      only on release.
            //   2. SNAPPED PREVIEW (previewBx/By/Bw/Bh): the gridded destination the
            //      release would commit to. Shown as a green (valid) or red (overlapping)
            //      landing zone so the user sees exactly which cells they're claiming.
            //   3. SMOOTH (smoothBx/By/Bw/Bh): raw finger position in px. Drives the
            //      selection outline and the active handle's visual offset so the resize
            //      feels physical instead of stuttery.
            // Each corner moves only its two adjacent edges; the opposite two edges stay
            // pinned. When no resize is active, all three sets collapse to the committed
            // bounds.
            val dCols = (resizeDragPx.x / cellWPx).roundToInt()
            val dRows = (resizeDragPx.y / cellHPx).roundToInt()
            val origR = button.col + button.colSpan
            val origB = button.row + button.rowSpan
            val moveLeft = resizeCorner == ResizeCorner.TOP_LEFT || resizeCorner == ResizeCorner.BOTTOM_LEFT
            val moveTop = resizeCorner == ResizeCorner.TOP_LEFT || resizeCorner == ResizeCorner.TOP_RIGHT
            val moveRight = resizeCorner == ResizeCorner.TOP_RIGHT || resizeCorner == ResizeCorner.BOTTOM_RIGHT
            val moveBottom = resizeCorner == ResizeCorner.BOTTOM_LEFT || resizeCorner == ResizeCorner.BOTTOM_RIGHT
            val previewL = if (moveLeft) (button.col + dCols).coerceIn(0, origR - 1) else button.col
            val previewT = if (moveTop) (button.row + dRows).coerceIn(0, origB - 1) else button.row
            val previewR = if (moveRight) (origR + dCols).coerceIn(button.col + 1, layout.columns) else origR
            val previewB = if (moveBottom) (origB + dRows).coerceIn(button.row + 1, layout.rows) else origB
            val previewColSpan = previewR - previewL
            val previewRowSpan = previewB - previewT
            val previewBx = cellW * previewL + halfGap
            val previewBy = cellH * previewT + halfGap
            val previewBw = cellW * previewColSpan - gap
            val previewBh = cellH * previewRowSpan - gap

            // Committed bounds — button renders here, dropdown menus anchor here.
            val origBx = cellW * button.col + halfGap
            val origBy = cellH * button.row + halfGap
            val origBw = cellW * button.colSpan - gap
            val origBh = cellH * button.rowSpan - gap
            val bx = origBx
            val by = origBy
            val bw = origBw
            val bh = origBh
            val dxDp = with(density) { resizeDragPx.x.toDp() }
            val dyDp = with(density) { resizeDragPx.y.toDp() }
            // Clamp so the outline can't be flipped inside-out: the moving edge can't
            // pass the fixed opposite edge minus a one-cell minimum (matches the snapped
            // preview's minimum span of 1).
            val smoothBx = when {
                moveLeft -> (origBx + dxDp).coerceIn(0.dp, origBx + origBw - cellW)
                else -> origBx
            }
            val smoothBy = when {
                moveTop -> (origBy + dyDp).coerceIn(0.dp, origBy + origBh - cellH)
                else -> origBy
            }
            val smoothRight = when {
                moveRight -> (origBx + origBw + dxDp).coerceIn(smoothBx + cellW, maxWidth)
                else -> origBx + origBw
            }
            val smoothBottom = when {
                moveBottom -> (origBy + origBh + dyDp).coerceIn(smoothBy + cellH, maxHeight)
                else -> origBy + origBh
            }
            val smoothBw = smoothRight - smoothBx
            val smoothBh = smoothBottom - smoothBy

            val isSelected = button.id == selectedButtonId

            if (!isEditMode && button.isTrackpad) {
                // ── Trackpad (normal mode) ────────────────────────────────────
                val keyboardTheme = keyboardButtonParentColor(
                    layout = layout,
                    themeFallback = MaterialTheme.colorScheme.surface,
                )
                Box(
                    modifier = Modifier
                        .absoluteOffset(x = bx, y = by)
                        .size(width = bw, height = bh)
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
                ) {
                    KeyButtonShape(
                        button = button,
                        keyboardThemeColor = keyboardTheme,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        ButtonContent(button = button, modifier = Modifier.fillMaxSize())
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
                                    var releasedOrMoved = false
                                    val longPressed: Boolean = try {
                                        withTimeout(longPressMs) {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id }
                                                    ?: continue
                                                if (!change.pressed) {
                                                    releasedOrMoved = true
                                                    break
                                                }
                                                val moved = (change.position - downPos).getDistance()
                                                if (moved > touchSlop) {
                                                    releasedOrMoved = true
                                                    break
                                                }
                                            }
                                        }
                                        !releasedOrMoved
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
                    val keyboardTheme = keyboardButtonParentColor(
                        layout = layout,
                        themeFallback = MaterialTheme.colorScheme.surface,
                    )
                    // Only register double/long handlers when targets are configured —
                    // an idle onDoubleClick handler would delay every single tap by the
                    // double-tap window, even on buttons without a configured double-tap.
                    val hasDouble = button.onDoubleTapTarget !is RemapTarget.Unbound
                    val hasHold = button.onHoldTarget !is RemapTarget.Unbound
                    KeyButtonShape(
                        button = button,
                        keyboardThemeColor = keyboardTheme,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = dragOffset.x
                                translationY = dragOffset.y
                            },
                        surfaceModifier = Modifier.combinedClickable(
                            onClick = {
                                if (isEditMode) onSelectButton(button.id)
                                else onButtonTap(button)
                            },
                            onDoubleClick = if (!isEditMode && hasDouble) {
                                { onButtonDoubleTap(button) }
                            } else null,
                            onLongClick = if (!isEditMode && hasHold) {
                                { onButtonHold(button) }
                            } else null,
                        ),
                    ) {
                        ButtonContent(button = button, modifier = Modifier.fillMaxSize())
                    }
                }
            }

            // ── Selection outline (smooth) + resize handles (selected, not dragging) ─
            if (isEditMode && isSelected && !isDragging) {
                val extraColors = com.mapo.ui.theme.LocalMapoExtraColors.current
                val outlineShape = RoundedCornerShape(BUTTON_CORNER)

                // Resize destination: the snapped cells that pressing release right now
                // would commit to. Green = within bounds and no overlap; red = overlaps
                // another button (commit will be rejected). Mirrors the drag-to-move
                // drop indicator so the visual language stays consistent. Only renders
                // while a resize is in progress.
                if (resizeCorner != null) {
                    val previewWouldOverlap = layout.wouldOverlap(
                        button.id, previewL, previewT,
                        previewColSpan, previewRowSpan,
                    )
                    val zoneColor = if (previewWouldOverlap) extraColors.dropZoneInvalid
                                    else extraColors.dropZoneValid
                    Box(
                        modifier = Modifier
                            .absoluteOffset(x = previewBx, y = previewBy)
                            .size(width = previewBw, height = previewBh)
                            .zIndex(15f)
                            .background(zoneColor.copy(alpha = 0.38f), outlineShape)
                    )
                }

                // Selection outline at smooth (finger-tracking) bounds. The drop shadow
                // is drawn per-line inside selectionOutline rather than as a single
                // rectangle behind the whole box — that way the bracket reads as four
                // thin floating elements with shadows on either side of each line,
                // instead of a hazy fill across the button's face.
                Box(
                    modifier = Modifier
                        .absoluteOffset(x = smoothBx, y = smoothBy)
                        .size(width = smoothBw, height = smoothBh)
                        .zIndex(25f)
                        .selectionOutline(extraColors.selectionOutline)
                )

                // One circular handle per corner.
                //
                // Hit area (HANDLE_HIT_SIZE) is anchored to the button's ORIGINAL committed
                // corner (origBx/origBy/origBw/origBh) so the pointerInput's coordinate
                // frame stays stable for the entire drag. If we anchored to the smooth
                // bounds instead, the gesture-source modifier would move with the finger
                // and `detectDragGestures` would report zero-deltas in that moving frame
                // (the classic "follow the finger" feedback loop). The visible disc lives
                // inside the hit box and uses `graphicsLayer.translation*` to follow the
                // smooth corner — translation is a draw-time transform that doesn't shift
                // the pointer frame, so we get smooth visuals AND correct deltas.
                //
                // Handles sit at zIndex 50 (> dragging button's 10) so a neighboring button
                // can never swallow a touch meant for a handle. While one corner is grabbed
                // the other three fade out and drop their pointerInput so a stray touch on
                // a faded handle can't hand the drag to the wrong corner mid-gesture.
                ResizeCorner.values().forEach { corner ->
                    val anchorX = when (corner) {
                        ResizeCorner.TOP_LEFT, ResizeCorner.BOTTOM_LEFT -> origBx
                        ResizeCorner.TOP_RIGHT, ResizeCorner.BOTTOM_RIGHT -> origBx + origBw
                    }
                    val anchorY = when (corner) {
                        ResizeCorner.TOP_LEFT, ResizeCorner.TOP_RIGHT -> origBy
                        ResizeCorner.BOTTOM_LEFT, ResizeCorner.BOTTOM_RIGHT -> origBy + origBh
                    }
                    val smoothCornerX = when (corner) {
                        ResizeCorner.TOP_LEFT, ResizeCorner.BOTTOM_LEFT -> smoothBx
                        ResizeCorner.TOP_RIGHT, ResizeCorner.BOTTOM_RIGHT -> smoothBx + smoothBw
                    }
                    val smoothCornerY = when (corner) {
                        ResizeCorner.TOP_LEFT, ResizeCorner.TOP_RIGHT -> smoothBy
                        ResizeCorner.BOTTOM_LEFT, ResizeCorner.BOTTOM_RIGHT -> smoothBy + smoothBh
                    }
                    val translationXDp = smoothCornerX - anchorX
                    val translationYDp = smoothCornerY - anchorY
                    val translationXPx = with(density) { translationXDp.toPx() }
                    val translationYPx = with(density) { translationYDp.toPx() }

                    val isThisActive = resizeCorner == corner
                    val isAnyActive = resizeCorner != null
                    val targetAlpha = if (!isAnyActive || isThisActive) 1f else 0f
                    val alpha by animateFloatAsState(
                        targetValue = targetAlpha,
                        animationSpec = tween(durationMillis = 40),
                        label = "resizeHandleAlpha",
                    )
                    Box(
                        modifier = Modifier
                            .absoluteOffset(
                                x = anchorX - HANDLE_HIT_SIZE / 2,
                                y = anchorY - HANDLE_HIT_SIZE / 2,
                            )
                            .size(HANDLE_HIT_SIZE)
                            .zIndex(50f)
                            .then(
                                if (!isAnyActive || isThisActive) Modifier.pointerInput(button.id, corner) {
                                    detectDragGestures(
                                        onDragStart = {
                                            resizeCorner = corner
                                            isAnyResizing = true
                                        },
                                        onDragEnd = {
                                            val finalDCols = (resizeDragPx.x / cellWPx).roundToInt()
                                            val finalDRows = (resizeDragPx.y / cellHPx).roundToInt()
                                            val ml = corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.BOTTOM_LEFT
                                            val mt = corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.TOP_RIGHT
                                            val mr = corner == ResizeCorner.TOP_RIGHT || corner == ResizeCorner.BOTTOM_RIGHT
                                            val mb = corner == ResizeCorner.BOTTOM_LEFT || corner == ResizeCorner.BOTTOM_RIGHT
                                            val origRight = currentButton.col + currentButton.colSpan
                                            val origBottom = currentButton.row + currentButton.rowSpan
                                            val newL = if (ml) currentButton.col + finalDCols else currentButton.col
                                            val newT = if (mt) currentButton.row + finalDRows else currentButton.row
                                            val newR = if (mr) origRight + finalDCols else origRight
                                            val newB = if (mb) origBottom + finalDRows else origBottom
                                            onResizeButton(
                                                currentButton.id,
                                                newL, newT,
                                                newR - newL, newB - newT,
                                            )
                                            resizeDragPx = Offset.Zero
                                            resizeCorner = null
                                            isAnyResizing = false
                                        },
                                        onDragCancel = {
                                            resizeDragPx = Offset.Zero
                                            resizeCorner = null
                                            isAnyResizing = false
                                        },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            resizeDragPx += delta
                                        }
                                    )
                                } else Modifier
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(HANDLE_VISUAL_SIZE)
                                .graphicsLayer {
                                    this.alpha = alpha
                                    translationX = translationXPx
                                    translationY = translationYPx
                                }
                                .circleDropShadow()
                                .background(extraColors.selectionOutline, CircleShape)
                        )
                    }
                }
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

    }
}

private const val TAG = "MapoInput"
private const val TRACKPAD_SENSITIVITY = 1.5f
private const val TAP_MOVEMENT_THRESHOLD_PX = 12f
private const val DOUBLE_TAP_INTERVAL_MS = 250L
private const val LONG_PRESS_DURATION_MS = 500L

/** Which corner of a selected button is currently being dragged for resize. */
private enum class ResizeCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

// Resize handle: the touch target is intentionally larger than the visible disc so the
// handle is easy to grab without the disc itself dominating the corner of a small button.
private val HANDLE_HIT_SIZE = 32.dp
private val HANDLE_VISUAL_SIZE = 12.dp

// Soft drop shadow rendered for the selection outline. Uniform direction (purely below)
// and Gaussian falloff. Android's built-in Modifier.shadow uses the View elevation
// system, whose simulated light source position varies with where the View sits on the
// screen — so the same shape would render visibly different shadows depending on which
// button the user selected. Stamping the shadow with a BlurMaskFilter gives us a
// position-independent, predictable result.
private val SELECTION_SHADOW_BLUR = 10.dp
private val SELECTION_SHADOW_OFFSET_Y = 3.dp
private val SELECTION_SHADOW_COLOR = Color.Black.copy(alpha = 0.32f)

private val SELECTION_OUTLINE_STROKE = 2.dp

/**
 * Draws the selection outline as four straight line segments running corner-to-corner.
 * Each line gets a soft Gaussian shadow (via [android.graphics.BlurMaskFilter]) drawn
 * BEFORE the line itself, so the shadow falls on both sides of the line — the bracket
 * reads as a thin element floating slightly above the button face with an even overhead
 * light, instead of as a hard rectangle pasted on top.
 *
 * The corner discs are drawn separately by the handle composables and get their own
 * matching per-circle shadow via [circleDropShadow].
 */
private fun Modifier.selectionOutline(
    color: Color,
    strokeWidth: Dp = SELECTION_OUTLINE_STROKE,
): Modifier = this.drawBehind {
    val strokePx = strokeWidth.toPx()
    val w = size.width
    val h = size.height
    val blurPx = SELECTION_SHADOW_BLUR.toPx()
    val offsetYPx = SELECTION_SHADOW_OFFSET_Y.toPx()

    val shadowPaint = android.graphics.Paint().apply {
        this.color = SELECTION_SHADOW_COLOR.toArgb()
        // NORMAL blur fuzzes both sides of the stroke — the shadow is visible on both
        // sides of each thin line, like a real overhead light striking a floating wire.
        maskFilter = android.graphics.BlurMaskFilter(blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        this.strokeWidth = strokePx
        strokeCap = android.graphics.Paint.Cap.BUTT
    }

    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        // top
        nc.drawLine(0f, offsetYPx, w, offsetYPx, shadowPaint)
        // bottom
        nc.drawLine(0f, h + offsetYPx, w, h + offsetYPx, shadowPaint)
        // left
        nc.drawLine(0f, offsetYPx, 0f, h + offsetYPx, shadowPaint)
        // right
        nc.drawLine(w, offsetYPx, w, h + offsetYPx, shadowPaint)
    }

    // Foreground lines (Compose's anti-aliased drawLine).
    drawLine(color, Offset(0f, 0f), Offset(w, 0f), strokePx)
    drawLine(color, Offset(0f, h), Offset(w, h), strokePx)
    drawLine(color, Offset(0f, 0f), Offset(0f, h), strokePx)
    drawLine(color, Offset(w, 0f), Offset(w, h), strokePx)
}

/**
 * Draws a soft circular drop shadow behind a disc, matching the visual style of
 * [selectionOutline]'s per-line shadows. Use on the corner handle discs.
 */
private fun Modifier.circleDropShadow(
    color: Color = SELECTION_SHADOW_COLOR,
    blurRadius: Dp = SELECTION_SHADOW_BLUR,
    offsetY: Dp = SELECTION_SHADOW_OFFSET_Y,
): Modifier = this.drawBehind {
    val blurPx = blurRadius.toPx()
    val offsetYPx = offsetY.toPx()
    val radiusPx = size.minDimension / 2f
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        maskFilter = android.graphics.BlurMaskFilter(blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
        isAntiAlias = true
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawCircle(
            size.width / 2f,
            size.height / 2f + offsetYPx,
            radiusPx,
            paint,
        )
    }
}

/**
 * Draws a soft, blurred drop shadow behind the content using [android.graphics.BlurMaskFilter].
 * The shadow is a rounded rect of the same size as the content, offset by [offsetY] (positive
 * = below, simulating an overhead light source) and blurred by [blurRadius].
 *
 * Necessary because [Modifier.shadow] uses Android elevation, whose light source position
 * depends on the View's location on the screen — that produces inconsistent shadow
 * direction across multiple identical elements at different positions.
 */
private fun Modifier.softDropShadow(
    cornerRadius: Dp,
    blurRadius: Dp = SELECTION_SHADOW_BLUR,
    offsetY: Dp = SELECTION_SHADOW_OFFSET_Y,
    color: Color = SELECTION_SHADOW_COLOR,
): Modifier = this.drawBehind {
    val blurPx = blurRadius.toPx()
    val offsetYPx = offsetY.toPx()
    val cornerPx = cornerRadius.toPx()
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        // OUTER blur draws ONLY outside the shape, with the interior fully transparent.
        // NORMAL would blur both sides of the edge, leaving a haze across the rect's
        // interior (and thus across the selected button's face).
        maskFilter = android.graphics.BlurMaskFilter(blurPx, android.graphics.BlurMaskFilter.Blur.OUTER)
        isAntiAlias = true
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawRoundRect(
            0f, offsetYPx,
            size.width, size.height + offsetYPx,
            cornerPx, cornerPx,
            paint,
        )
    }
}

/**
 * Layered visual surface for one button. Layers, bottom-up:
 *  1. Drop shadow (when shadow slot enabled).
 *  2. Bevel — fills the full rounded shape with the bevel color.
 *  3. Surface — clipped to a rounded-top / flat-bottom shape, inset above the bevel
 *     so the bevel band peeks out at the bottom. Fills with the fill color when fill
 *     is enabled. The outline (when enabled) and the selection ring stroke just this
 *     surface, so the stroke encompasses only the surface, not surface+bevel.
 *  4. Content — placed inside the surface, so labels/icons center on the surface and
 *     end up shifted up by half the bevel height when bevel is enabled.
 *
 * [modifier] applies to the outer (shadow + clip) box; pass [graphicsLayer] / size /
 * offset here. [surfaceModifier] applies to the inner clipped surface box; pass
 * gesture modifiers (combinedClickable, pointerInput) here so ripples + hit-testing
 * stay inside the visible surface.
 */
@Composable
private fun KeyButtonShape(
    button: GridButton,
    keyboardThemeColor: Color,
    modifier: Modifier = Modifier,
    surfaceModifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val resolved = resolveAutoColors(button, keyboardThemeColor)
    // Surface uses the SAME fully-rounded shape as the outer button when bevel is enabled.
    // The surface sits at the top of the outer-clipped box with bottom-padding equal to
    // BEVEL_HEIGHT, so the bevel (painted by the outer Box's background) shows in two
    // places: the bottom band (with rounded outer-bottom corners) AND the surface's
    // rounded bottom-corner cutouts (with the same radius). Both pairs of bevel corners
    // are cut-off arcs of identical radius — visually the bevel "wraps" the surface's
    // bottom curve.
    val outerShape: Shape = RoundedCornerShape(BUTTON_CORNER)

    Box(modifier = modifier) {
        // Shape Box: shadow + outer clip + bevel background + click handling. The clip
        // applies to children (surface + content). Click ripples expand within the
        // outer rounded shape, so taps on the bevel band also register as button taps.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (resolved.shadowEnabled) Modifier.softDropShadow(
                        cornerRadius = BUTTON_CORNER,
                        blurRadius = BUTTON_SHADOW_BLUR,
                        offsetY = BUTTON_SHADOW_OFFSET_Y,
                        color = resolved.shadow,
                    ) else Modifier
                )
                .clip(outerShape)
                .then(if (resolved.bevelEnabled) Modifier.background(resolved.bevel) else Modifier)
                .then(surfaceModifier),
        ) {
            // Surface: fully rounded so the bottom corners expose bevel "wings" that
            // wrap the surface. The user-facing outline (when enabled) strokes only the
            // surface — per spec, it encompasses the surface, not surface+bevel.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (resolved.bevelEnabled) Modifier.padding(bottom = BEVEL_HEIGHT) else Modifier)
                    .clip(outerShape)
                    .then(if (resolved.fillEnabled) Modifier.background(resolved.fill) else Modifier)
                    .then(
                        if (resolved.outlineEnabled) Modifier.border(1.dp, resolved.outline, outerShape)
                        else Modifier
                    ),
                content = content,
            )
        }

        // Selection ring lives outside this composable now (rendered separately in the
        // per-button grid loop), so it can use SMOOTH (un-snapped) bounds during a resize
        // while the button itself keeps its gridded preview.
    }
}

// BEVEL_HEIGHT >= BUTTON_CORNER keeps the bevel band visually grounded — below that,
// the band lives entirely inside the outer bottom-corner curve and pinches at the
// midline. Surface and outer share the corner radius so the bevel's top "wings"
// (revealed by the surface's rounded bottom corners) match the bevel's bottom-corner
// arcs (clipped by the outer shape) — both radius BUTTON_CORNER, same direction.
// BEVEL_HEIGHT is set at the floor (== BUTTON_CORNER) to keep the bevel subtle.
private val BUTTON_CORNER = 8.dp
private val BEVEL_HEIGHT = 8.dp
// Button drop shadow: subtler than the selection-outline shadow because keyboards have
// many buttons and a heavy shadow on each would read as noisy.
private val BUTTON_SHADOW_BLUR = 6.dp
private val BUTTON_SHADOW_OFFSET_Y = 2.dp

// Keyboard-scale equivalents for [KeyboardSurface]. Larger than the button constants
// because the surface is the full grid area; subtle button-scale shadows/bevels would
// disappear at this size.
private val KEYBOARD_CORNER = 16.dp
private val KEYBOARD_BEVEL_HEIGHT = 16.dp
private val KEYBOARD_SHADOW_BLUR = 18.dp
private val KEYBOARD_SHADOW_OFFSET_Y = 6.dp

/**
 * Layered visual surface for the keyboard's outer container. Mirrors [KeyButtonShape]
 * one level up: shadow → bevel → fill+outline → content. The content is the [KeyGrid].
 * All four slots are independently toggleable; with defaults (fill on+auto, others off)
 * the surface paints exactly the M3 theme surface — matching pre-refactor visuals.
 *
 * The themeFallback parameter is the color used when fill is in auto mode; pass
 * `MaterialTheme.colorScheme.surface` to match the bottom-screen background.
 */
@Composable
private fun KeyboardSurface(
    layout: GridLayout,
    themeFallback: Color,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val resolved = resolveAutoLayoutColors(layout, themeFallback)
    val outerShape: Shape = RoundedCornerShape(KEYBOARD_CORNER)

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (resolved.shadowEnabled) Modifier.softDropShadow(
                        cornerRadius = KEYBOARD_CORNER,
                        blurRadius = KEYBOARD_SHADOW_BLUR,
                        offsetY = KEYBOARD_SHADOW_OFFSET_Y,
                        color = resolved.shadow,
                    ) else Modifier
                )
                .clip(outerShape)
                .then(if (resolved.bevelEnabled) Modifier.background(resolved.bevel) else Modifier),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (resolved.bevelEnabled) Modifier.padding(bottom = KEYBOARD_BEVEL_HEIGHT) else Modifier)
                    .clip(outerShape)
                    .then(if (resolved.fillEnabled) Modifier.background(resolved.fill) else Modifier)
                    .then(
                        if (resolved.outlineEnabled) Modifier.border(1.dp, resolved.outline, outerShape)
                        else Modifier
                    ),
                content = content,
            )
        }
    }
}

/**
 * Renders a button's nine drawable regions. CENTER falls back to [GridButton.label]
 * when no explicit CENTER region is set, so a freshly-created button still shows its
 * canonical name. Each region's label falls back to the onTap target string when the
 * region exists but its label is null.
 */
@Composable
private fun ButtonContent(button: GridButton, modifier: Modifier = Modifier) {
    val onTapPreview = remember(button.onTap, button.label) {
        when (val t = button.onTapTarget) {
            is RemapTarget.Unbound  -> button.label
            is RemapTarget.Gamepad  -> t.button
            is RemapTarget.Keyboard -> t.code
            is RemapTarget.Mouse    -> t.code
        }
    }
    Box(modifier = modifier.padding(2.dp)) {
        RegionPosition.values().forEach { pos ->
            val region = button.regions[pos.name]
                ?: if (pos == RegionPosition.CENTER && button.label.isNotEmpty()) {
                    ButtonRegion(label = button.label, sizeSp = 11f)
                } else null
            if (region != null) {
                RegionView(
                    region = region,
                    fallbackLabel = onTapPreview,
                    modifier = Modifier.align(pos.alignment()),
                )
            }
        }
    }
}

@Composable
private fun RegionView(
    region: ButtonRegion,
    fallbackLabel: String,
    modifier: Modifier = Modifier,
) {
    val text = region.label ?: fallbackLabel
    val labelColor = region.labelColorArgb?.let { Color(it) } ?: Color.Unspecified
    // Icon's `tint = Color.Unspecified` means "draw the source asset's intrinsic colors"
    // (typically black for Material vector icons), unlike Text which falls back to
    // LocalContentColor. Resolve the inheritance ourselves so an unset iconColorArgb
    // tracks the label color instead of rendering as a flat black sprite.
    val iconColor = region.iconColorArgb?.let { Color(it) } ?: LocalContentColor.current
    val iconVec = MapoIcons.resolve(region.icon)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (iconVec != null) {
            Icon(
                iconVec,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size((region.sizeSp * 1.2f).dp),
            )
        }
        if (text.isNotEmpty()) {
            Text(
                text = text,
                fontSize = region.sizeSp.sp,
                lineHeight = (region.sizeSp + 2f).sp,
                color = labelColor,
                maxLines = 2,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

private fun RegionPosition.alignment(): Alignment = when (this) {
    RegionPosition.CENTER        -> Alignment.Center
    RegionPosition.TOP_LEFT      -> Alignment.TopStart
    RegionPosition.TOP_CENTER    -> Alignment.TopCenter
    RegionPosition.TOP_RIGHT     -> Alignment.TopEnd
    RegionPosition.CENTER_LEFT   -> Alignment.CenterStart
    RegionPosition.CENTER_RIGHT  -> Alignment.CenterEnd
    RegionPosition.BOTTOM_LEFT   -> Alignment.BottomStart
    RegionPosition.BOTTOM_CENTER -> Alignment.BottomCenter
    RegionPosition.BOTTOM_RIGHT  -> Alignment.BottomEnd
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
            Text("Quit", style = MaterialTheme.typography.labelLarge)
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

