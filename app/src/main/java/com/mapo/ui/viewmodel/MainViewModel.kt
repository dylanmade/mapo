package com.mapo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mapo.data.defaults.DefaultLayouts
import com.mapo.data.model.AppProfileBinding
import com.mapo.data.model.GridButton
import com.mapo.data.model.GridLayout
import com.mapo.data.model.LayoutSnapshot
import com.mapo.data.model.Profile
import com.mapo.data.model.RemapTarget
import com.mapo.data.model.TemplateRef
import com.mapo.data.model.TrackpadGesture
import com.mapo.data.model.buttonsExceeding
import com.mapo.data.model.withFreshButtonIds
import com.mapo.data.model.gestureTarget
import com.mapo.data.model.onDoubleTapTarget
import com.mapo.data.model.onHoldTarget
import com.mapo.data.model.onTapTarget
import com.mapo.data.model.findFirstEmptyArea
import com.mapo.data.model.findFirstEmptyCell
import com.mapo.data.model.isTrackpad
import com.mapo.data.model.seedNewButton
import com.mapo.data.model.parseOriginalSnapshot
import com.mapo.data.model.toGridLayout
import com.mapo.data.model.toJson
import com.mapo.data.model.toKeyLayout
import com.mapo.data.model.toSnapshot
import com.mapo.data.model.wouldOverlap
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.data.model.steam.resolveActionSet
import com.mapo.data.repository.AppProfileBindingRepository
import com.mapo.data.repository.ControllerConfigRepository
import com.mapo.data.repository.KeyboardTemplateRepository
import com.mapo.data.repository.LayoutRepository
import com.mapo.data.repository.ProfileRepository
import com.mapo.data.settings.AutoSwitchSettings
import com.mapo.di.IoDispatcher
import com.mapo.service.autoswitch.ProfileAutoSwitcher
import com.mapo.service.foreground.ForegroundAppFilter
import com.mapo.service.input.CompiledConfig
import com.mapo.service.input.InputDispatcher
import com.mapo.service.input.MotionProbeAppOverlay
import com.mapo.service.input.toCompiled
import com.mapo.service.keyboard.KeyboardController
import com.mapo.service.overlay.keyboard.KeyboardOverlayPresenter
import com.mapo.ui.screen.keyboard.KeyboardHostState
import dagger.hilt.android.lifecycle.HiltViewModel
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class TabUiEvent {
    data class TemplateNameConflict(
        val layoutId: Long,
        val templateName: String,
        val existing: TemplateRef
    ) : TabUiEvent()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val layoutRepository: LayoutRepository,
    private val profileRepository: ProfileRepository,
    private val controllerConfigRepository: ControllerConfigRepository,
    private val appProfileBindingRepository: AppProfileBindingRepository,
    private val autoSwitchSettings: AutoSwitchSettings,
    private val autoSwitcher: ProfileAutoSwitcher,
    private val foregroundAppFilter: ForegroundAppFilter,
    private val keyboardTemplateRepository: KeyboardTemplateRepository,
    private val inputDispatcher: InputDispatcher,
    private val keyboardOverlayPresenter: KeyboardOverlayPresenter,
    private val motionProbeAppOverlay: MotionProbeAppOverlay,
    private val keyboardController: KeyboardController,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel(), KeyboardHostState {

    // Source of truth lives in KeyboardController (Brick 2 of single-screen refactor).
    // Re-exposed here so the activity surface — MainScreen, tests, drawer wiring —
    // sees the same `layouts` / `selectedIndex` flows it always has. Writes inside
    // this VM go through `keyboardController.replaceLayouts` / `replaceLayoutById` /
    // `setSelectedIndex`; reads use `keyboardController.layouts.value` etc.
    override val layouts: StateFlow<ImmutableList<GridLayout>> = keyboardController.layouts
    override val selectedIndex: StateFlow<Int> = keyboardController.selectedIndex

    // Single source of truth for "is some tab being edited?". Replaces the previous
    // (_isEditMode, _editingLayout) pair: there's no buffered draft anymore — every
    // edit op writes through to _layouts and the DB immediately. `null` = not editing.
    private val _editingLayoutId = MutableStateFlow<Long?>(null)
    val editingLayoutId: StateFlow<Long?> = _editingLayoutId.asStateFlow()
    val isEditMode: StateFlow<Boolean> = _editingLayoutId
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _selectedButtonId = MutableStateFlow<String?>(null)
    val selectedButtonId: StateFlow<String?> = _selectedButtonId.asStateFlow()

    private val _tabContextMenuFor = MutableStateFlow<Long?>(null)
    val tabContextMenuFor: StateFlow<Long?> = _tabContextMenuFor.asStateFlow()

    // Bursts during duplicate/reorder/template flows would otherwise drop with a 1-slot buffer.
    private val _toastMessage = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private val _tabUiEvents = MutableSharedFlow<TabUiEvent>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val tabUiEvents: SharedFlow<TabUiEvent> = _tabUiEvents.asSharedFlow()

    val activeProfile: StateFlow<Profile?> = profileRepository.activeProfile

    private val _profiles = MutableStateFlow<ImmutableList<Profile>>(persistentListOf())
    val profiles: StateFlow<ImmutableList<Profile>> = _profiles.asStateFlow()

    override val remapEnabled: StateFlow<Boolean> = keyboardController.remapEnabled

    val autoSwitchEnabled: StateFlow<Boolean> = autoSwitchSettings.autoSwitchEnabled

    val autoCreateProfilesEnabled: StateFlow<Boolean> = autoSwitchSettings.autoCreateProfilesEnabled

    val ignoredPackages: StateFlow<Set<String>> = autoSwitchSettings.ignoredPackages

    val appProfileBindings: StateFlow<ImmutableList<AppProfileBinding>> =
        appProfileBindingRepository.getAll()
            .map { it.toImmutableList() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    // Cached app labels for packages referenced by bindings or the blocklist. Resolved
    // off the main thread (PackageManager calls aren't free) and looked up by Compose
    // via `appLabels[pkg] ?: pkg` — never call PackageManager from composition.
    private val _appLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val appLabels: StateFlow<Map<String, String>> = _appLabels.asStateFlow()

    val autoSwitchEvents: SharedFlow<ProfileAutoSwitcher.UiEvent> = autoSwitcher.events

    /**
     * The materialized binding graph for the active profile's active controller.
     * Auto-seeds a default config on first observation if none exists.
     * `RemapControlsScreen` reads this and writes back via [setControllerBinding].
     *
     * Compiled into [InputDispatcher.compiledConfig] by the collector below — that's the
     * runtime path the evaluator reads on every key/motion event.
     */
    val activeControllerConfig: StateFlow<ControllerConfig?> =
        activeProfile.filterNotNull()
            .flatMapLatest { controllerConfigRepository.observeActiveConfig(it.id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Brick 4.3: which action set the editor is currently *viewing*. Independent of the
     * runtime active set (which lives in the evaluator and only changes via `CHANGE_PRESET`
     * bindings). Null means "follow the controller_profile's starting set (first by order)"
     * — so the editor always lands somewhere sensible without the VM having to chase the
     * config's first set whenever the config changes.
     *
     * Maintenance: reset to null when the active profile changes (different controller's
     * sets are unaddressable from here) or when the currently-viewed set disappears from
     * the config (e.g., user deleted it — Brick 4.4 territory). The cleanup collector is
     * cheap because the only state read is `actionSets.map { it.id }`.
     */
    private val _viewingActionSetId = MutableStateFlow<Long?>(null)
    val viewingActionSetId: StateFlow<Long?> = _viewingActionSetId.asStateFlow()

    /**
     * Brick 5.3: which [com.mapo.data.model.steam.ActionLayer] within the currently-viewed
     * action set the editor is focused on. Null = base set (no layer overlay focus); a
     * non-null id targets a specific layer for overlay editing (5.5) and is the source of
     * truth for the layer pill row's selected state (5.4).
     *
     * Layers are *per-set*: each [ActionSet] has its own layer namespace. Maintenance:
     *  - Reset to null when the active profile changes (different controller's layers).
     *  - Reset to null when the viewing action set changes (sibling sets' ids are unrelated).
     *  - Reset to null when the layer disappears from the viewing set (user deleted it).
     *
     * Unlike `viewingActionSetId` (which has a starting-set fallback), null here is a
     * meaningful editor state — "I'm editing the base set's bindings, not any overlay."
     */
    private val _viewingLayerId = MutableStateFlow<Long?>(null)
    val viewingLayerId: StateFlow<Long?> = _viewingLayerId.asStateFlow()

    /**
     * Brick 5.3: layers available on the currently-viewed action set as
     * `(layerId, title)` pairs in order. Drives both the layer pill row (5.4) and the
     * layer-activation picker categories (5.6). Empty when no controller config is loaded
     * yet or the viewing set has no layers.
     */
    val availableLayers: StateFlow<List<Pair<Long, String>>> =
        combine(activeControllerConfig, _viewingActionSetId) { config, viewingId ->
            val set = config?.resolveActionSet(viewingId) ?: return@combine emptyList()
            set.layers.map { it.layer.id to it.layer.title }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val templates: StateFlow<ImmutableList<TemplateRef>> = keyboardTemplateRepository.allTemplates
        .map { it.toImmutableList() }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            keyboardTemplateRepository.builtIns.toImmutableList()
        )

    // FC1 seam upstream: KeyboardController.displayLayout is `StateFlow<GridLayout?>`
    // (opaque, nullable — "what's actually rendered, if anything"). The activity
    // surface here keeps the pre-refactor non-null contract by falling back to
    // DefaultLayouts.all[0] on null, matching the prior WhileSubscribed/initial-value
    // behavior at the VM boundary.
    override val displayLayout: StateFlow<GridLayout> = keyboardController.displayLayout
        .map { it ?: DefaultLayouts.all[0] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefaultLayouts.all[0])

    init {
        viewModelScope.launch {
            profileRepository.getAllProfiles().collect { _profiles.value = it.toImmutableList() }
        }
        // Relay run-mode dispatch errors from the controller into this VM's toast stream
        // so MainScreen's existing `toastMessage` collector keeps surfacing them.
        viewModelScope.launch {
            keyboardController.errorMessages.collect { _toastMessage.tryEmit(it) }
        }
        viewModelScope.launch {
            activeControllerConfig.collect { config ->
                val compiled = config?.toCompiled() ?: CompiledConfig.EMPTY
                inputDispatcher.setCompiledConfig(compiled)
                android.util.Log.d(
                    "MainViewModel",
                    "Published CompiledConfig: startingSet=${compiled.startingActionSetId} sets=${compiled.sets.size}",
                )
                // Stale-id cleanup: if the user deleted or migrated away from the set
                // they were viewing, drop back to the controller_profile default.
                val currentViewing = _viewingActionSetId.value
                if (currentViewing != null && config?.actionSets?.any { it.actionSet.id == currentViewing } != true) {
                    _viewingActionSetId.value = null
                }
                // 5.3: same stale-id check for the viewing layer pointer. We compare
                // against the resolved viewing set (which may be the starting-set
                // fallback when _viewingActionSetId is null), since that's the
                // namespace the layer id is scoped to.
                val currentLayer = _viewingLayerId.value
                if (currentLayer != null) {
                    val resolvedSet = config?.resolveActionSet(_viewingActionSetId.value)
                    val stillPresent = resolvedSet?.layers?.any { it.layer.id == currentLayer } == true
                    if (!stillPresent) _viewingLayerId.value = null
                }
            }
        }
        viewModelScope.launch {
            // Profile change → forget the previous controller's set + layer selection.
            activeProfile.collect {
                _viewingActionSetId.value = null
                _viewingLayerId.value = null
            }
        }
        viewModelScope.launch {
            // 5.3: layers are per-set. When the user switches which set is being viewed,
            // any focused-layer selection from the prior set is meaningless and is reset.
            _viewingActionSetId.collect { _viewingLayerId.value = null }
        }
        viewModelScope.launch {
            combine(appProfileBindings, ignoredPackages) { bindings, ignored ->
                bindings.mapTo(mutableSetOf()) { it.packageName }.apply { addAll(ignored) }
            }.collect { packages ->
                val current = _appLabels.value
                val missing = packages - current.keys
                if (missing.isEmpty()) return@collect
                val resolved = withContext(ioDispatcher) {
                    missing.associateWith { foregroundAppFilter.appLabel(it) }
                }
                _appLabels.value = current + resolved
            }
        }
    }

    // ── Profile ───────────────────────────────────────────────────────────────

    fun selectProfile(profile: Profile) {
        profileRepository.setActiveProfile(profile)
        keyboardController.setSelectedIndex(0)
    }

    fun addProfile(name: String) {
        viewModelScope.launch { profileRepository.addProfile(name) }
    }

    fun duplicateProfile(source: Profile) {
        viewModelScope.launch {
            profileRepository.duplicateProfile(source, "Copy of ${source.name}")
        }
    }

    fun deleteProfile(profile: Profile) {
        val defaultProfile = _profiles.value.firstOrNull { it.isDefault }
        viewModelScope.launch {
            profileRepository.deleteProfile(profile)
            if (activeProfile.value?.id == profile.id && defaultProfile != null) {
                profileRepository.setActiveProfile(defaultProfile)
                keyboardController.setSelectedIndex(0)
            }
        }
    }

    override fun toggleRemap() = keyboardController.toggleRemap()

    /**
     * Listen-for-press capture mode used by the chord partner picker (Brick 3.3.e). While
     * true, the accessibility service forwards the next physical button DOWN to
     * [capturedInputs] instead of running it through the remap evaluator.
     */
    fun setCaptureMode(enabled: Boolean) {
        inputDispatcher.setCaptureMode(enabled)
    }

    /** Captured physical inputs while [setCaptureMode] is on. Picker subscribes; takes first. */
    val capturedInputs: kotlinx.coroutines.flow.SharedFlow<com.mapo.service.input.InputAddress>
        get() = inputDispatcher.capturedInputs

    // ── Auto-switch ───────────────────────────────────────────────────────────

    fun setAutoSwitchEnabled(enabled: Boolean) {
        autoSwitchSettings.setAutoSwitchEnabled(enabled)
    }

    fun setAutoCreateProfilesEnabled(enabled: Boolean) {
        autoSwitchSettings.setAutoCreateProfilesEnabled(enabled)
    }

    /** Re-fire auto-switch against the cached foreground package; called on activity resume. */
    fun reevaluateAutoSwitch() {
        autoSwitcher.reevaluate()
    }

    fun acceptCreateProfilePrompt(pkg: String, appLabel: String) {
        viewModelScope.launch { autoSwitcher.createProfileAndBind(pkg, appLabel) }
    }

    fun ignorePackageForever(pkg: String) {
        autoSwitcher.ignorePackage(pkg)
    }

    fun unignorePackage(pkg: String) {
        autoSwitchSettings.removeIgnoredPackage(pkg)
    }

    fun deleteBinding(packageName: String, subId: String = "") {
        viewModelScope.launch { appProfileBindingRepository.unbind(packageName, subId) }
    }

    /**
     * Replaces the single binding on [activatorId] with [output]. Active-profile guard
     * means picker round-trips that fire after the profile is gone become no-ops
     * rather than throwing.
     */
    fun setControllerBinding(activatorId: Long, output: BindingOutput) {
        if (activeProfile.value == null) return
        viewModelScope.launch { controllerConfigRepository.setBinding(activatorId, output) }
    }

    /**
     * Brick 3.6 multi-command path: update a specific binding row by its [bindingId]
     * rather than replacing all bindings on the activator. Called from the per-input
     * editor when each command row owns its own picker result.
     */
    fun setControllerCommand(bindingId: Long, output: BindingOutput) {
        if (activeProfile.value == null) return
        viewModelScope.launch { controllerConfigRepository.setCommand(bindingId, output) }
    }

    /** Append a new Unbound command to [activatorId]. See `addCommand` in the repository. */
    fun addControllerCommand(activatorId: Long) {
        if (activeProfile.value == null) return
        viewModelScope.launch { controllerConfigRepository.addCommand(activatorId) }
    }

    /** Delete a specific command (Binding row). The UI guards against removing the last. */
    fun removeControllerCommand(bindingId: Long) {
        if (activeProfile.value == null) return
        viewModelScope.launch { controllerConfigRepository.removeCommand(bindingId) }
    }

    /**
     * Brick 4.3: editor-side viewing selection. Pass a set id to view that set in the
     * `RemapControlsScreen` overview / row previews, or null to fall back to the
     * controller_profile's starting set. This is *not* the runtime active set —
     * `CHANGE_PRESET` bindings drive that, independently.
     */
    fun setViewingActionSet(actionSetId: Long?) {
        _viewingActionSetId.value = actionSetId
    }

    /**
     * Brick 4.4: add a new [com.mapo.data.model.steam.ActionSet] under the active
     * controller_profile. When [inheritFromSetId] is non-null the new set is a deep-clone
     * of that source; null seeds a default-shaped set. After creation the editor's
     * viewing pointer flips to the new set so the user lands in what they just made.
     */
    fun addControllerActionSet(name: String, title: String, inheritFromSetId: Long? = null) {
        val cpId = activeControllerConfig.value?.controllerProfile?.id ?: return
        if (activeProfile.value == null) return
        viewModelScope.launch {
            val newId = controllerConfigRepository.addActionSet(cpId, name, title, inheritFromSetId)
            _viewingActionSetId.value = newId
        }
    }

    /** Rename action set [actionSetId]. No-op for unknown ids or when no profile is active. */
    fun renameControllerActionSet(actionSetId: Long, name: String, title: String) {
        if (activeProfile.value == null) return
        viewModelScope.launch { controllerConfigRepository.renameActionSet(actionSetId, name, title) }
    }

    /**
     * Deep-clone action set [sourceSetId] with new [name] / [title]. The editor's viewing
     * pointer flips to the duplicate, so the user can immediately tweak the copy without
     * hunting for it.
     */
    fun duplicateControllerActionSet(sourceSetId: Long, name: String, title: String) {
        if (activeProfile.value == null) return
        viewModelScope.launch {
            val newId = controllerConfigRepository.duplicateActionSet(sourceSetId, name, title)
            _viewingActionSetId.value = newId
        }
    }

    /**
     * Delete action set [actionSetId]. The repo refuses to delete the last set on a
     * controller_profile, so the UI must keep its Delete affordance disabled when only
     * one set remains. Viewing-pointer cleanup runs through the existing
     * `activeControllerConfig` collector when the deletion lands.
     */
    fun deleteControllerActionSet(actionSetId: Long) {
        if (activeProfile.value == null) return
        viewModelScope.launch { controllerConfigRepository.deleteActionSet(actionSetId) }
    }

    // ── Action layers (Brick 5.3) ────────────────────────────────────────────

    /**
     * Brick 5.3: focus a specific [com.mapo.data.model.steam.ActionLayer] for overlay
     * editing (5.5) and pill-row selection (5.4). Pass null to drop focus and return
     * to base-set editing. The id is interpreted within the currently-viewed set's
     * layer namespace; switching `viewingActionSetId` clears this automatically.
     */
    fun setViewingLayer(layerId: Long?) {
        _viewingLayerId.value = layerId
    }

    /**
     * Append a new empty [com.mapo.data.model.steam.ActionLayer] to [actionSetId] and
     * focus it (so the user lands on the layer they just created, mirroring how
     * `addControllerActionSet` flips the set pointer to the new set).
     */
    fun addControllerActionLayer(actionSetId: Long, name: String, title: String) {
        if (activeProfile.value == null) return
        viewModelScope.launch {
            val newId = controllerConfigRepository.addLayer(actionSetId, name, title)
            _viewingLayerId.value = newId
        }
    }

    /** Rename layer [layerId]. No-op for unknown ids or when no profile is active. */
    fun renameControllerActionLayer(layerId: Long, name: String, title: String) {
        if (activeProfile.value == null) return
        viewModelScope.launch { controllerConfigRepository.renameLayer(layerId, name, title) }
    }

    /**
     * Deep-clone layer [sourceLayerId] with new [name] / [title]. Focuses the duplicate
     * so the user can immediately tweak the copy.
     */
    fun duplicateControllerActionLayer(sourceLayerId: Long, name: String, title: String) {
        if (activeProfile.value == null) return
        viewModelScope.launch {
            val newId = controllerConfigRepository.duplicateLayer(sourceLayerId, name, title)
            _viewingLayerId.value = newId
        }
    }

    /**
     * Delete layer [layerId]. The viewing-pointer cleanup runs through the existing
     * `activeControllerConfig` collector when the deletion lands — no need to clear
     * here.
     */
    fun deleteControllerActionLayer(layerId: Long) {
        if (activeProfile.value == null) return
        viewModelScope.launch { controllerConfigRepository.deleteLayer(layerId) }
    }

    /**
     * Brick 5.5.b: materialize a layer-side override scaffold for
     * `(layerId, inputSource, groupInputKey)`. Suspending — the repo persists the
     * chain before returning; the next `activeControllerConfig` emission carries the
     * new GroupInput. Returns the materialized [com.mapo.data.model.steam.GroupInput]
     * id so callers (e.g., the per-input editor) can immediately route picker results
     * to the new override.
     *
     * No-op (returns null) when no profile is active.
     */
    suspend fun materializeLayerOverride(
        layerId: Long,
        inputSource: com.mapo.data.model.steam.InputSource,
        groupInputKey: String,
    ): Long? {
        if (activeProfile.value == null) return null
        return controllerConfigRepository.materializeLayerOverride(
            layerId = layerId,
            inputSource = inputSource,
            groupInputKey = groupInputKey,
        )
    }

    /**
     * Brick 5.5.b: drop the layer override at `(layerId, inputSource, groupInputKey)`,
     * returning that row to inheritance from the parent set. If the layer's overlay
     * group is left with no remaining inputs, the repo also removes the overlay
     * group + preset pointer.
     */
    fun clearLayerOverride(
        layerId: Long,
        inputSource: com.mapo.data.model.steam.InputSource,
        groupInputKey: String,
    ) {
        if (activeProfile.value == null) return
        viewModelScope.launch {
            controllerConfigRepository.clearLayerOverride(layerId, inputSource, groupInputKey)
        }
    }

    /**
     * Phase 6 Brick 1: change the [BindingMode] of an existing binding group. Wired from
     * the Remap Controls subheader's mode dropdown. The repository handles the dedupe
     * (no-op when the mode hasn't changed); group inputs that aren't valid for the new
     * mode aren't deleted — the compile step's `SourceMode.accepts()` check silently
     * filters them, so a mode switch is reversible by picking the original back.
     */
    fun setBindingGroupMode(bindingGroupId: Long, mode: com.mapo.data.model.steam.BindingMode) {
        if (activeProfile.value == null) return
        viewModelScope.launch {
            controllerConfigRepository.updateBindingGroupMode(bindingGroupId, mode)
        }
    }

    /**
     * Append a new activator of [type] to the input identified by [groupInputId].
     * Used by the per-input editor screen's `[+ Add Activator]` action.
     */
    fun addControllerActivator(groupInputId: Long, type: com.mapo.data.model.steam.ActivatorType) {
        if (activeProfile.value == null) return
        viewModelScope.launch { controllerConfigRepository.addActivator(groupInputId, type) }
    }

    /** Delete an activator from the active config. */
    fun removeControllerActivator(activatorId: Long) {
        if (activeProfile.value == null) return
        viewModelScope.launch { controllerConfigRepository.removeActivator(activatorId) }
    }

    /** Change an activator's [com.mapo.data.model.steam.ActivatorType]. Bindings preserved. */
    fun setControllerActivatorType(activatorId: Long, type: com.mapo.data.model.steam.ActivatorType) {
        if (activeProfile.value == null) return
        viewModelScope.launch { controllerConfigRepository.updateActivatorType(activatorId, type) }
    }

    /**
     * Replace an activator's settings (long_press_time, double_tap_time, etc.). Driven by
     * the per-activator editor screen on slider drag-end. Serializes [settings] to JSON via
     * its `toJson()` so the persisted shape stays in sync with the parser.
     */
    fun setControllerActivatorSettings(
        activatorId: Long,
        settings: com.mapo.service.input.CompiledActivatorSettings,
    ) {
        if (activeProfile.value == null) return
        viewModelScope.launch {
            controllerConfigRepository.updateActivatorSettings(activatorId, settings.toJson())
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    override fun selectLayout(index: Int) {
        // Visiting any tab exits edit mode for the previously-edited tab. Per design,
        // only one tab can be in edit mode at a time and tab navigation is always free.
        keyboardController.setSelectedIndex(index)
        if (_editingLayoutId.value != null &&
            keyboardController.layouts.value.getOrNull(index)?.id != _editingLayoutId.value) {
            _editingLayoutId.value = null
            _selectedButtonId.value = null
        }
    }

    // ── Normal mode (delegates to KeyboardController) ─────────────────────────

    override fun onButtonTap(button: GridButton) = keyboardController.onButtonTap(button)
    override fun onButtonDoubleTap(button: GridButton) = keyboardController.onButtonDoubleTap(button)
    override fun onButtonHold(button: GridButton) = keyboardController.onButtonHold(button)

    override fun onTrackpadGesture(button: GridButton, gesture: TrackpadGesture) =
        keyboardController.onTrackpadGesture(button, gesture)

    override fun onDragStart() = keyboardController.onDragStart()
    override fun onMouseMove(dx: Float, dy: Float) = keyboardController.onMouseMove(dx, dy)
    override fun onDragEnd() = keyboardController.onDragEnd()

    // ── Tab context menu ──────────────────────────────────────────────────────

    fun openTabMenu(layoutId: Long) {
        // Tab gestures are unrestricted in edit mode — long-press still opens the menu.
        _tabContextMenuFor.value = layoutId
    }

    fun closeTabMenu() {
        _tabContextMenuFor.value = null
    }

    // ── Edit mode lifecycle ───────────────────────────────────────────────────

    fun enterEditMode(layoutId: Long) {
        val targetIdx = keyboardController.layouts.value.indexOfFirst { it.id == layoutId }
        if (targetIdx < 0) return
        keyboardController.setSelectedIndex(targetIdx)
        _selectedButtonId.value = null
        _editingLayoutId.value = layoutId
        _tabContextMenuFor.value = null
    }

    fun exitEditMode() {
        _editingLayoutId.value = null
        _selectedButtonId.value = null
    }

    // ── Button selection ──────────────────────────────────────────────────────

    fun selectButton(id: String) {
        _selectedButtonId.value = if (_selectedButtonId.value == id) null else id
    }

    /** Force-select [id] (no toggle). Used by the long-press menu's "Configure" action. */
    fun selectButtonOnly(id: String) {
        _selectedButtonId.value = id
    }

    // ── Button CRUD ───────────────────────────────────────────────────────────
    //
    // All button mutations write through to the DB via persistLayoutFields. Edits are
    // permanent the moment they're made — there is no draft/Save/Cancel layer. Edit
    // mode (`_editingLayoutId`) is purely a UI-affordance flag (drag handles, +icons,
    // long-press menus) and does NOT gate writes; otherwise instant-commit paths like
    // ConfigureButtonScreen would silently no-op when edit mode happened to be off.

    /**
     * Apply [transform] to whichever layout currently owns [buttonId]. Used by per-
     * button operations (update/delete/duplicate/move/resize) where the relevant
     * layout is unambiguously the one containing the targeted button.
     */
    private inline fun mutateLayoutContaining(
        buttonId: String,
        transform: (GridLayout) -> GridLayout?,
    ) {
        val profileId = activeProfile.value?.id ?: return
        val current = keyboardController.layouts.value.find { l -> l.buttons.any { it.id == buttonId } }
            ?: return
        val updated = transform(current) ?: return
        persistLayoutFields(updated, profileId)
    }

    /**
     * Apply [transform] to the currently-displayed layout. Used by add-style operations
     * where the only meaningful target is the visible tab — these are only triggered
     * from the visible keyboard's edit-mode UI in the first place.
     *
     * Resolves the displayed layout from `_layouts` + `_selectedIndex` directly rather
     * than reading [displayLayout].value — that StateFlow uses WhileSubscribed and
     * returns its initial fallback when there are no active collectors (e.g. in unit
     * tests), which would silently route writes to the default layout instead of the
     * one the user is editing.
     */
    private inline fun mutateDisplayedLayout(transform: (GridLayout) -> GridLayout?) {
        val profileId = activeProfile.value?.id ?: return
        val layouts = keyboardController.layouts.value
        val current = layouts.getOrNull(keyboardController.selectedIndex.value)
            ?: layouts.firstOrNull() ?: return
        val updated = transform(current) ?: return
        persistLayoutFields(updated, profileId)
    }

    /**
     * Add [spec] to the displayed layout at the first available cell. The spec's
     * `id`, `col`, and `row` are overwritten with a fresh UUID and the chosen cell.
     */
    fun addButton(spec: GridButton) {
        mutateDisplayedLayout { layout ->
            val cell = layout.findFirstEmptyCell()
            if (cell == null) {
                emitError("No empty space available in this layout")
                return@mutateDisplayedLayout null
            }
            val placed = spec.copy(
                id = java.util.UUID.randomUUID().toString(),
                col = cell.first,
                row = cell.second,
            )
            _selectedButtonId.value = placed.id
            layout.copy(buttons = layout.buttons + placed)
        }
    }

    /** Replace the currently-selected button with [updated]. The button's id must match. */
    fun updateSelectedButton(updated: GridButton) {
        val id = _selectedButtonId.value ?: return
        mutateLayoutContaining(id) { layout ->
            layout.copy(buttons = layout.buttons.map { btn ->
                if (btn.id == id) updated.copy(id = id) else btn
            })
        }
    }

    fun deleteSelectedButton() {
        val id = _selectedButtonId.value ?: return
        mutateLayoutContaining(id) { layout ->
            _selectedButtonId.value = null
            layout.copy(buttons = layout.buttons.filter { it.id != id })
        }
    }

    fun deleteButton(id: String) {
        mutateLayoutContaining(id) { layout ->
            if (_selectedButtonId.value == id) _selectedButtonId.value = null
            layout.copy(buttons = layout.buttons.filter { it.id != id })
        }
    }

    fun duplicateButton(id: String) {
        mutateLayoutContaining(id) { layout ->
            val source = layout.buttons.find { it.id == id }
                ?: return@mutateLayoutContaining null
            // Try the source's original size first; only downscale if no empty area
            // that big exists. Falling all the way to 1×1 (always last attempt) is
            // better than refusing the duplicate when only smaller gaps remain.
            val originalFit = layout.findFirstEmptyArea(source.colSpan, source.rowSpan)
            val (col, row, cs, rs) = if (originalFit != null) {
                Placement(originalFit.first, originalFit.second, source.colSpan, source.rowSpan)
            } else {
                val small = layout.findFirstEmptyCell()
                if (small == null) {
                    emitError("No empty space available in this layout")
                    return@mutateLayoutContaining null
                }
                Placement(small.first, small.second, 1, 1)
            }
            val copy = source.copy(
                id = java.util.UUID.randomUUID().toString(),
                col = col,
                row = row,
                colSpan = cs,
                rowSpan = rs
            )
            _selectedButtonId.value = copy.id
            layout.copy(buttons = layout.buttons + copy)
        }
    }

    private data class Placement(val col: Int, val row: Int, val colSpan: Int, val rowSpan: Int)

    fun addButtonAt(col: Int, row: Int, spec: GridButton) {
        mutateDisplayedLayout { layout ->
            if (col !in 0 until layout.columns || row !in 0 until layout.rows) {
                return@mutateDisplayedLayout null
            }
            // Seed from the keyboard's Buttons-tab defaults (size + appearance + regions)
            // for normal buttons. Trackpads bypass this — their appearance comes from the
            // trackpad-specific preset, not from per-keyboard button defaults.
            val seeded = if (spec.isTrackpad) spec else layout.seedNewButton(spec)
            // The default size may not fit at the tapped anchor (against grid edge or
            // adjacent to another button). Shrink to the largest rectangle that does fit,
            // preserving user intent better than always falling back to 1×1.
            val (cs, rs) = fitSizeAtAnchor(layout, col, row, seeded.colSpan, seeded.rowSpan)
            if (layout.wouldOverlap("__new__", col, row, cs, rs)) {
                emitError("Cell already occupied")
                return@mutateDisplayedLayout null
            }
            val placed = seeded.copy(
                id = java.util.UUID.randomUUID().toString(),
                col = col,
                row = row,
                colSpan = cs,
                rowSpan = rs,
            )
            _selectedButtonId.value = placed.id
            layout.copy(buttons = layout.buttons + placed)
        }
    }

    /**
     * Largest rectangle anchored at (col,row) that fits in the grid AND doesn't overlap
     * any existing button. Shrinks the longer dimension first (ties shrink colSpan).
     * Returns (1,1) as the floor; the caller still checks wouldOverlap to detect a fully
     * occupied anchor cell.
     */
    private fun fitSizeAtAnchor(layout: GridLayout, col: Int, row: Int, cs: Int, rs: Int): Pair<Int, Int> {
        val maxCs = (layout.columns - col).coerceAtLeast(1)
        val maxRs = (layout.rows - row).coerceAtLeast(1)
        var c = cs.coerceIn(1, maxCs)
        var r = rs.coerceIn(1, maxRs)
        while (c >= 1 && r >= 1) {
            if (!layout.wouldOverlap("__new__", col, row, c, r)) return c to r
            if (c == 1 && r == 1) break
            if (c >= r) c -= 1 else r -= 1
        }
        return 1 to 1
    }

    // ── Drag to move ──────────────────────────────────────────────────────────

    fun moveButton(buttonId: String, newCol: Int, newRow: Int) {
        mutateLayoutContaining(buttonId) { layout ->
            val button = layout.buttons.find { it.id == buttonId }
                ?: return@mutateLayoutContaining null
            val col = newCol.coerceIn(0, layout.columns - button.colSpan)
            val row = newRow.coerceIn(0, layout.rows - button.rowSpan)
            if (layout.wouldOverlap(buttonId, col, row, button.colSpan, button.rowSpan)) {
                return@mutateLayoutContaining null
            }
            layout.copy(
                buttons = layout.buttons.map {
                    if (it.id == buttonId) it.copy(col = col, row = row) else it
                }
            )
        }
    }

    // ── Resize ────────────────────────────────────────────────────────────────

    /** Resize from the bottom-right corner: origin (col,row) stays put. */
    fun resizeButton(buttonId: String, newColSpan: Int, newRowSpan: Int) {
        mutateLayoutContaining(buttonId) { layout ->
            val button = layout.buttons.find { it.id == buttonId }
                ?: return@mutateLayoutContaining null
            resizeMutation(layout, button, button.col, button.row, newColSpan, newRowSpan)
        }
    }

    /** Resize from any corner: origin (col,row) may shift in addition to the spans
     *  changing. Used by the four-corner resize handles. */
    fun resizeButton(buttonId: String, newCol: Int, newRow: Int, newColSpan: Int, newRowSpan: Int) {
        mutateLayoutContaining(buttonId) { layout ->
            val button = layout.buttons.find { it.id == buttonId }
                ?: return@mutateLayoutContaining null
            resizeMutation(layout, button, newCol, newRow, newColSpan, newRowSpan)
        }
    }

    private fun resizeMutation(
        layout: GridLayout,
        button: GridButton,
        newCol: Int,
        newRow: Int,
        newColSpan: Int,
        newRowSpan: Int,
    ): GridLayout? {
        val col = newCol.coerceIn(0, layout.columns - 1)
        val row = newRow.coerceIn(0, layout.rows - 1)
        val colSpan = newColSpan.coerceIn(1, layout.columns - col)
        val rowSpan = newRowSpan.coerceIn(1, layout.rows - row)
        if (layout.wouldOverlap(button.id, col, row, colSpan, rowSpan)) {
            emitError("Cannot resize: overlaps another button")
            return null
        }
        return layout.copy(
            buttons = layout.buttons.map {
                if (it.id == button.id) {
                    it.copy(col = col, row = row, colSpan = colSpan, rowSpan = rowSpan)
                } else it
            }
        )
    }

    // ── Tab actions ───────────────────────────────────────────────────────────

    fun reorderTabs(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val current = keyboardController.layouts.value
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val mutable = current.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        val reordered = mutable.toImmutableList()
        keyboardController.replaceLayouts(reordered)

        // Keep selection on the moved tab if it was selected; otherwise re-resolve by id.
        val previouslySelectedId = current.getOrNull(keyboardController.selectedIndex.value)?.id
        if (previouslySelectedId != null) {
            val newIdx = reordered.indexOfFirst { it.id == previouslySelectedId }
            if (newIdx >= 0) keyboardController.setSelectedIndex(newIdx)
        }

        val profileId = activeProfile.value?.id ?: return
        val idToPosition = reordered.mapIndexed { idx, layout -> layout.id to idx }.toMap()
        viewModelScope.launch { layoutRepository.reorder(profileId, idToPosition) }
    }

    /**
     * Instant-commit pathway used by [ConfigureKeyboardScreen] for non-dimensional edits
     * (name, color slots). Persists [updated] directly. Dimensional changes (columns/rows)
     * must go through [tryResizeLayout] so an overflow check has a chance to fire.
     */
    fun updateLayoutInstant(updated: GridLayout) {
        val profileId = activeProfile.value?.id ?: return
        persistLayoutFields(updated, profileId)
    }

    /**
     * Try to apply a new grid size in-place. Returns null after successful persist, or the
     * labels of the buttons that wouldn't fit when the requested size is smaller than the
     * occupied extent. The caller surfaces those labels and may follow up with
     * [applyResizeWithAutoFit] if the user opts to drop the offending buttons.
     */
    fun tryResizeLayout(layoutId: Long, columns: Int, rows: Int): List<String>? {
        val profileId = activeProfile.value?.id ?: return null
        val layout = keyboardController.layouts.value.find { it.id == layoutId } ?: return null
        val offending = layout.buttonsExceeding(columns, rows)
        if (offending.isNotEmpty()) {
            return offending.map { it.label.ifBlank { "(unnamed)" } }
        }
        val (clamped, defaultsShrunk) = clampDefaultButtonSize(
            layout.copy(columns = columns, rows = rows),
            columns,
            rows,
        )
        persistLayoutFields(clamped, profileId)
        if (defaultsShrunk) {
            emitToast("Default button size adjusted to fit new Keyboard dimensions")
        }
        return null
    }

    /**
     * Accept a [tryResizeLayout] conflict: drop the buttons that no longer fit and apply
     * the requested dimensions. Emits a toast with the drop count.
     */
    fun applyResizeWithAutoFit(layoutId: Long, columns: Int, rows: Int) {
        val profileId = activeProfile.value?.id ?: return
        val layout = keyboardController.layouts.value.find { it.id == layoutId } ?: return
        val resized = autoFitButtons(layout.buttons, columns, rows)
        val dropped = layout.buttons.size - resized.size
        val (clamped, defaultsShrunk) = clampDefaultButtonSize(
            layout.copy(columns = columns, rows = rows, buttons = resized),
            columns,
            rows,
        )
        persistLayoutFields(clamped, profileId)
        if (dropped > 0) {
            emitToast("$dropped ${if (dropped == 1) "button" else "buttons"} removed")
        }
        if (defaultsShrunk) {
            emitToast("Default button size adjusted to fit new Keyboard dimensions")
        }
    }

    /**
     * Returns [layout] with its default-button width/height clamped to fit within
     * [columns] × [rows], plus a flag indicating whether a clamp actually happened.
     * Caller surfaces a toast when the flag is true.
     */
    private fun clampDefaultButtonSize(
        layout: GridLayout,
        columns: Int,
        rows: Int,
    ): Pair<GridLayout, Boolean> {
        val newCs = layout.defaultButtonColSpan.coerceIn(1, columns.coerceAtLeast(1))
        val newRs = layout.defaultButtonRowSpan.coerceIn(1, rows.coerceAtLeast(1))
        val changed = newCs != layout.defaultButtonColSpan || newRs != layout.defaultButtonRowSpan
        return layout.copy(
            defaultButtonColSpan = newCs,
            defaultButtonRowSpan = newRs,
        ) to changed
    }

    internal fun autoFitButtons(buttons: List<GridButton>, cols: Int, rows: Int): List<GridButton> {
        if (cols < 1 || rows < 1) return emptyList()
        val placed = mutableListOf<GridButton>()
        for (b in buttons) {
            val cs = b.colSpan.coerceIn(1, cols)
            val rs = b.rowSpan.coerceIn(1, rows)
            val newCol = b.col.coerceIn(0, cols - cs)
            val newRow = b.row.coerceIn(0, rows - rs)
            val collides = placed.any { p ->
                val bEndC = newCol + cs
                val bEndR = newRow + rs
                val pEndC = p.col + p.colSpan
                val pEndR = p.row + p.rowSpan
                !(bEndC <= p.col || pEndC <= newCol || bEndR <= p.row || pEndR <= newRow)
            }
            if (collides) continue
            placed.add(b.copy(col = newCol, row = newRow, colSpan = cs, rowSpan = rs))
        }
        return placed
    }

    fun resetKeyboard(layoutId: Long) {
        val profileId = activeProfile.value?.id ?: return
        val current = keyboardController.layouts.value.find { it.id == layoutId } ?: return
        val previousName = current.name
        viewModelScope.launch {
            val row = layoutRepository.getById(layoutId) ?: return@launch
            val snapshot: LayoutSnapshot = row.parseOriginalSnapshot() ?: run {
                emitToast("No original config to revert to")
                return@launch
            }
            val reverted = snapshot.toGridLayout(layoutId)
            // Optimistic update.
            keyboardController.replaceLayoutById(reverted)
            layoutRepository.saveLayout(
                reverted.toKeyLayout(
                    profileId = profileId,
                    position = row.position,
                    originalSnapshotJson = row.originalSnapshotJson
                )
            )
            emitToast("\"$previousName\" reset to \"${snapshot.name}\"")
        }
    }

    fun duplicateKeyboard(layoutId: Long) {
        val profileId = activeProfile.value?.id ?: return
        val layoutsNow = keyboardController.layouts.value
        val sourceIdx = layoutsNow.indexOfFirst { it.id == layoutId }
        if (sourceIdx < 0) return
        val source = layoutsNow[sourceIdx]
        val newName = nextCopyName(source.name, layoutsNow.map { it.name }.toSet())
        // Fresh UUIDs so the copy's buttons don't collide with the source's — both the
        // live draft and the reset-to-original snapshot must reference the new ids.
        val draftLayout = source.copy(name = newName).withFreshButtonIds()
        val newSnapshotJson = draftLayout.toSnapshot().toJson()

        viewModelScope.launch {
            val current = layoutRepository.getLayoutsByProfileOnce(profileId)
            val newPosition = current.firstOrNull { it.id == layoutId }?.let { it.position + 1 }
                ?: current.size

            // Shift positions of siblings at or beyond newPosition up by one.
            val shifts = current
                .filter { it.position >= newPosition }
                .associate { it.id to it.position + 1 }
            if (shifts.isNotEmpty()) layoutRepository.reorder(profileId, shifts)

            val draft = draftLayout.toKeyLayout(
                profileId = profileId,
                position = newPosition,
                originalSnapshotJson = newSnapshotJson
            ).copy(id = 0L)
            layoutRepository.saveLayout(draft)

            // Resolve the newly inserted row's id and select it.
            val refreshed = layoutRepository.getLayoutsByProfileOnce(profileId)
            val newIdx = refreshed.indexOfFirst { it.position == newPosition && it.name == newName }
            if (newIdx >= 0) keyboardController.setSelectedIndex(newIdx)
            emitToast("\"$newName\" copied")
        }
    }

    internal fun nextCopyName(base: String, existing: Set<String>): String {
        val first = "$base Copy"
        if (first !in existing) return first
        var i = 2
        while ("$base Copy $i" in existing) i++
        return "$base Copy $i"
    }

    fun removeKeyboard(layoutId: Long) {
        val profile = activeProfile.value ?: return
        val current = keyboardController.layouts.value
        val idx = current.indexOfFirst { it.id == layoutId }
        if (idx < 0) return
        val name = current[idx].name

        // Optimistic UI.
        val newList = current.toPersistentList().removeAt(idx)
        keyboardController.replaceLayouts(newList)
        if (keyboardController.selectedIndex.value >= newList.size) {
            keyboardController.setSelectedIndex((newList.size - 1).coerceAtLeast(0))
        }

        viewModelScope.launch {
            layoutRepository.deleteById(layoutId)
            val refreshed = layoutRepository.getLayoutsByProfileOnce(profile.id)
            val compacted = refreshed
                .mapIndexed { i, row -> row.id to i }
                .filter { (id, pos) -> refreshed.first { it.id == id }.position != pos }
                .toMap()
            if (compacted.isNotEmpty()) layoutRepository.reorder(profile.id, compacted)
            emitToast("\"$name\" removed from \"${profile.name}\" profile")
        }
    }

    fun saveAsNewTemplate(layoutId: Long, templateName: String) {
        val layout = keyboardController.layouts.value.find { it.id == layoutId } ?: return
        val keyboardName = layout.name
        viewModelScope.launch {
            val existing = keyboardTemplateRepository.findByName(templateName)
            if (existing != null) {
                _tabUiEvents.emit(
                    TabUiEvent.TemplateNameConflict(
                        layoutId = layoutId,
                        templateName = templateName,
                        existing = existing
                    )
                )
                return@launch
            }
            keyboardTemplateRepository.insertNew(layout, templateName)
            emitToast("\"$keyboardName\" keyboard template saved")
        }
    }

    fun updateExistingTemplate(layoutId: Long, ref: TemplateRef.User) {
        val layout = keyboardController.layouts.value.find { it.id == layoutId } ?: return
        val keyboardName = layout.name
        viewModelScope.launch {
            keyboardTemplateRepository.updateExisting(ref.id, layout)
            emitToast("\"$keyboardName\" keyboard template updated")
        }
    }

    fun addBlankKeyboard() {
        val profileId = activeProfile.value?.id ?: return
        val name = nextNumberedName("New Keyboard", keyboardController.layouts.value.map { it.name }.toSet())
        val draft = GridLayout(name = name, columns = 6, rows = 4, buttons = emptyList())
        appendNewLayout(draft, profileId)
    }

    fun addKeyboardFromTemplate(template: TemplateRef) {
        val profileId = activeProfile.value?.id ?: return
        val existing = keyboardController.layouts.value.map { it.name }.toSet()
        val name = if (template.name in existing)
            nextNumberedName(template.name, existing)
        else template.name
        // Built-in templates share GridButton instances across every instantiation, and
        // user-template JSON carries the ids the template was saved with — either way,
        // we need fresh UUIDs so this keyboard's buttons are distinguishable from any
        // other keyboard derived from the same template.
        val draft = GridLayout(
            name = name,
            columns = template.columns,
            rows = template.rows,
            buttons = template.buttons,
            fillEnabled = template.fillEnabled,
            fillColorArgb = template.fillColorArgb,
            fillIsAuto = template.fillIsAuto,
            outlineEnabled = template.outlineEnabled,
            outlineColorArgb = template.outlineColorArgb,
            outlineIsAuto = template.outlineIsAuto,
            bevelEnabled = template.bevelEnabled,
            bevelColorArgb = template.bevelColorArgb,
            bevelIsAuto = template.bevelIsAuto,
            shadowEnabled = template.shadowEnabled,
            shadowColorArgb = template.shadowColorArgb,
            shadowIsAuto = template.shadowIsAuto,
            defaultButtonColSpan = template.defaultButtonColSpan,
            defaultButtonRowSpan = template.defaultButtonRowSpan,
            defaultButtonFillEnabled = template.defaultButtonFillEnabled,
            defaultButtonFillColorArgb = template.defaultButtonFillColorArgb,
            defaultButtonFillIsAuto = template.defaultButtonFillIsAuto,
            defaultButtonOutlineEnabled = template.defaultButtonOutlineEnabled,
            defaultButtonOutlineColorArgb = template.defaultButtonOutlineColorArgb,
            defaultButtonOutlineIsAuto = template.defaultButtonOutlineIsAuto,
            defaultButtonBevelEnabled = template.defaultButtonBevelEnabled,
            defaultButtonBevelColorArgb = template.defaultButtonBevelColorArgb,
            defaultButtonBevelIsAuto = template.defaultButtonBevelIsAuto,
            defaultButtonShadowEnabled = template.defaultButtonShadowEnabled,
            defaultButtonShadowColorArgb = template.defaultButtonShadowColorArgb,
            defaultButtonShadowIsAuto = template.defaultButtonShadowIsAuto,
            defaultButtonAnimationEnabled = template.defaultButtonAnimationEnabled,
            defaultButtonAnimationColorArgb = template.defaultButtonAnimationColorArgb,
            defaultButtonAnimationIsAuto = template.defaultButtonAnimationIsAuto,
            defaultButtonAnimationMotionEnabled = template.defaultButtonAnimationMotionEnabled,
            defaultButtonRegions = template.defaultButtonRegions,
        ).withFreshButtonIds()
        appendNewLayout(draft, profileId)
    }

    fun addKeyboardFromProfile(sourceLayoutId: Long) {
        val profileId = activeProfile.value?.id ?: return
        viewModelScope.launch {
            val sourceRow = layoutRepository.getById(sourceLayoutId) ?: return@launch
            val sourceGrid = sourceRow.toGridLayout()
            val existing = keyboardController.layouts.value.map { it.name }.toSet()
            val name = if (sourceGrid.name in existing)
                nextNumberedName(sourceGrid.name, existing)
            else sourceGrid.name
            // Fresh UUIDs so this keyboard is editable independently of the source
            // (whose buttons may also exist in the active profile via a prior copy).
            val draft = sourceGrid.copy(name = name, id = 0L).withFreshButtonIds()
            appendNewLayoutSuspending(draft, profileId)
        }
    }

    suspend fun layoutsForProfile(profileId: Long): List<GridLayout> =
        layoutRepository.getLayoutsByProfileOnce(profileId).map { it.toGridLayout() }

    internal fun nextNumberedName(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        var i = 2
        while ("$base $i" in existing) i++
        return "$base $i"
    }

    private fun appendNewLayout(draft: GridLayout, profileId: Long) {
        viewModelScope.launch { appendNewLayoutSuspending(draft, profileId) }
    }

    private suspend fun appendNewLayoutSuspending(draft: GridLayout, profileId: Long) {
        val current = layoutRepository.getLayoutsByProfileOnce(profileId)
        val newPosition = current.size
        val snapshotJson = draft.toSnapshot().toJson()
        val newRow = draft.copy(id = 0L).toKeyLayout(profileId, newPosition, snapshotJson)
        layoutRepository.saveLayout(newRow)
        val refreshed = layoutRepository.getLayoutsByProfileOnce(profileId)
        val newIdx = refreshed.indexOfFirst { it.position == newPosition && it.name == draft.name }
        if (newIdx >= 0) keyboardController.setSelectedIndex(newIdx)
        // Committing to add a new keyboard ends the edit context. Cancel/dismiss paths
        // never reach this funnel, so they correctly leave edit mode untouched.
        exitEditMode()
        emitToast("\"${draft.name}\" added")
    }

    fun emitToast(message: String) {
        _toastMessage.tryEmit(message)
    }

    private fun persistLayoutFields(updated: GridLayout, profileId: Long) {
        // Optimistic in-memory update.
        keyboardController.replaceLayoutById(updated)
        val idx = keyboardController.layouts.value.indexOfFirst { it.id == updated.id }
        viewModelScope.launch {
            val existing = layoutRepository.getById(updated.id)
            layoutRepository.saveLayout(
                updated.toKeyLayout(
                    profileId = profileId,
                    position = existing?.position ?: idx.coerceAtLeast(0),
                    originalSnapshotJson = existing?.originalSnapshotJson
                )
            )
        }
    }

    // ── Error display ─────────────────────────────────────────────────────────

    private fun emitError(message: String) {
        _toastMessage.tryEmit(message)
    }

    /**
     * Drawer affordance for toggling the run-mode keyboard overlay (mirrors the QS tile).
     * Both call the same [KeyboardOverlayPresenter] so there is exactly one
     * orchestration point for "show / hide the overlay" — easy to add new triggers
     * later (FGS notification action, physical-button binding, …) without drifting.
     */
    fun toggleKeyboardOverlay() {
        keyboardOverlayPresenter.toggle()
    }

    /**
     * Debug-only — toggles the motion-capture probe (focused TYPE_APPLICATION_OVERLAY)
     * used to characterize focus side effects before committing to a Phase 6 design.
     * Remove once that decision is locked.
     */
    val motionProbeActive: StateFlow<Boolean> = motionProbeAppOverlay.active

    fun toggleMotionProbe() {
        motionProbeAppOverlay.toggle()
    }
}
