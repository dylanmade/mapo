package com.mapo.ui.viewmodel

import com.mapo.data.model.AppProfileBinding
import com.mapo.data.model.GridButton
import com.mapo.data.model.GridLayout
import com.mapo.data.model.onTapTarget
import com.mapo.data.model.KeyLayout
import com.mapo.data.model.Profile
import com.mapo.data.model.RemapTarget
import com.mapo.data.model.TemplateRef
import com.mapo.data.model.toKeyLayout
import com.mapo.data.model.steam.BindingOutput
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.data.repository.AppProfileBindingRepository
import com.mapo.data.repository.ControllerConfigRepository
import com.mapo.data.repository.InstalledAppsRepository
import com.mapo.data.repository.KeyboardTemplateRepository
import com.mapo.data.repository.LayoutRepository
import com.mapo.data.repository.ProfileRepository
import com.mapo.data.settings.AutoSwitchSettings
import com.mapo.service.autoswitch.ProfileAutoSwitcher
import com.mapo.service.foreground.ForegroundAppFilter
import com.mapo.service.input.InputDispatcher
import com.mapo.service.keyboard.KeyboardController
import com.mapo.service.input.MotionProbeAppOverlay
import com.mapo.service.overlay.keyboard.KeyboardOverlayPresenter
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var layoutRepo: LayoutRepository
    private lateinit var profileRepo: ProfileRepository
    private lateinit var controllerConfigRepo: ControllerConfigRepository
    private lateinit var bindingRepo: AppProfileBindingRepository
    private lateinit var installedAppsRepo: InstalledAppsRepository
    private lateinit var settings: AutoSwitchSettings
    private lateinit var autoSwitcher: ProfileAutoSwitcher
    private lateinit var filter: ForegroundAppFilter
    private lateinit var templateRepo: KeyboardTemplateRepository
    private lateinit var inputDispatcher: InputDispatcher
    private lateinit var keyboardOverlayPresenter: KeyboardOverlayPresenter
    private lateinit var motionProbeAppOverlay: MotionProbeAppOverlay
    private lateinit var keyboardController: KeyboardController

    private val activeProfile = MutableStateFlow<Profile?>(null)
    private val allProfiles = MutableStateFlow<List<Profile>>(emptyList())
    private val allBindings = MutableStateFlow<List<AppProfileBinding>>(emptyList())
    private val allLayouts = MutableStateFlow<List<KeyLayout>>(emptyList())
    private val allTemplates = MutableStateFlow<List<TemplateRef>>(emptyList())
    private val autoSwitchEvents = MutableSharedFlow<ProfileAutoSwitcher.UiEvent>(
        replay = 0, extraBufferCapacity = 4,
    )
    private val autoSwitchEnabled = MutableStateFlow(true)
    private val autoCreateEnabled = MutableStateFlow(false)
    private val ignoredPackages = MutableStateFlow<Set<String>>(emptySet())

    private lateinit var subject: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        layoutRepo = mockk(relaxed = true)
        profileRepo = mockk(relaxed = true)
        controllerConfigRepo = mockk(relaxed = true)
        bindingRepo = mockk(relaxed = true)
        installedAppsRepo = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        autoSwitcher = mockk(relaxed = true)
        filter = mockk(relaxed = true)
        templateRepo = mockk(relaxed = true)
        inputDispatcher = mockk(relaxed = true)
        keyboardOverlayPresenter = mockk(relaxed = true)
        motionProbeAppOverlay = mockk(relaxed = true)
        // Real KeyboardController with mocked deps — the controller's StateFlows /
        // mutators are the source of truth for `layouts`, `selectedIndex`,
        // `remapEnabled`, etc., so tests need a working instance, not a relaxed mock.
        keyboardController = KeyboardController(
            inputDispatcher = inputDispatcher,
            layoutRepository = layoutRepo,
            profileRepository = profileRepo,
            ioDispatcher = testDispatcher,
        )

        every { profileRepo.activeProfile } returns activeProfile
        every { profileRepo.getAllProfiles() } returns allProfiles
        every { bindingRepo.getAll() } returns allBindings
        every { layoutRepo.getLayoutsByProfile(any()) } returns allLayouts
        every { settings.autoSwitchEnabled } returns autoSwitchEnabled
        every { settings.autoCreateProfilesEnabled } returns autoCreateEnabled
        every { settings.ignoredPackages } returns ignoredPackages
        every { autoSwitcher.events } returns autoSwitchEvents
        every { templateRepo.builtIns } returns emptyList()
        every { templateRepo.allTemplates } returns allTemplates
        every { controllerConfigRepo.observeActiveConfig(any()) } returns MutableStateFlow<ControllerConfig?>(null)

        subject = MainViewModel(
            layoutRepository = layoutRepo,
            profileRepository = profileRepo,
            controllerConfigRepository = controllerConfigRepo,
            appProfileBindingRepository = bindingRepo,
            installedAppsRepository = installedAppsRepo,
            autoSwitchSettings = settings,
            autoSwitcher = autoSwitcher,
            foregroundAppFilter = filter,
            keyboardTemplateRepository = templateRepo,
            inputDispatcher = inputDispatcher,
            keyboardOverlayPresenter = keyboardOverlayPresenter,
            motionProbeAppOverlay = motionProbeAppOverlay,
            keyboardController = keyboardController,
            ioDispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Pure helpers ──────────────────────────────────────────────────────────

    @Test
    fun nextCopyName_firstCopy_appendsCopySuffix() {
        assertEquals("Foo Copy", subject.nextCopyName("Foo", existing = setOf("Foo")))
    }

    @Test
    fun nextCopyName_subsequentCopies_areNumbered() {
        assertEquals(
            "Foo Copy 2",
            subject.nextCopyName("Foo", existing = setOf("Foo", "Foo Copy")),
        )
        assertEquals(
            "Foo Copy 4",
            subject.nextCopyName(
                "Foo",
                existing = setOf("Foo", "Foo Copy", "Foo Copy 2", "Foo Copy 3"),
            ),
        )
    }

    @Test
    fun nextNumberedName_freeBaseName_returnsBase() {
        assertEquals("New", subject.nextNumberedName("New", existing = emptySet()))
    }

    @Test
    fun nextNumberedName_collisions_returnNextNumber() {
        assertEquals("New 2", subject.nextNumberedName("New", existing = setOf("New")))
        assertEquals(
            "New 4",
            subject.nextNumberedName("New", existing = setOf("New", "New 2", "New 3")),
        )
    }

    @Test
    fun autoFitButtons_clampsButtonSpansToGrid() {
        val source = listOf(
            GridButton(label = "A", col = 0, row = 0, colSpan = 5, rowSpan = 5),
        )
        val fit = subject.autoFitButtons(source, cols = 3, rows = 3)
        assertEquals(1, fit.size)
        assertEquals(3, fit[0].colSpan)
        assertEquals(3, fit[0].rowSpan)
    }

    @Test
    fun autoFitButtons_relocatesButtonsThatFallOutsideGrid() {
        val source = listOf(
            GridButton(label = "A", col = 5, row = 0, colSpan = 1, rowSpan = 1),
        )
        val fit = subject.autoFitButtons(source, cols = 3, rows = 3)
        assertEquals(1, fit.size)
        assertEquals(2, fit[0].col) // clamped to last valid col (cols - colSpan)
    }

    @Test
    fun autoFitButtons_dropsButtonsThatCannotFitWithoutOverlap() {
        val source = listOf(
            GridButton(label = "A", col = 0, row = 0),
            GridButton(label = "B", col = 0, row = 0), // would overlap A after fitting
        )
        val fit = subject.autoFitButtons(source, cols = 3, rows = 3)
        assertEquals(1, fit.size)
        assertEquals("A", fit[0].label)
    }

    @Test
    fun autoFitButtons_emptyGrid_returnsEmpty() {
        val source = listOf(
            GridButton(label = "A", col = 0, row = 0),
        )
        assertEquals(emptyList<GridButton>(), subject.autoFitButtons(source, cols = 0, rows = 5))
        assertEquals(emptyList<GridButton>(), subject.autoFitButtons(source, cols = 5, rows = 0))
    }

    // ── Profile management ────────────────────────────────────────────────────

    @Test
    fun selectProfile_setsActiveAndResetsSelectedIndex() = runTest(testDispatcher) {
        // Set up a non-zero selectedIndex to verify it resets.
        subject.selectLayout(3)
        val profile = Profile(id = 7L, name = "Game", isDefault = false)

        subject.selectProfile(profile)

        verify { profileRepo.setActiveProfile(profile) }
        assertEquals(0, subject.selectedIndex.value)
    }

    @Test
    fun deleteProfile_whenActive_fallsBackToDefault() = runTest(testDispatcher) {
        val toDelete = Profile(id = 7L, name = "Game", isDefault = false)
        val default = Profile(id = 1L, name = "Default", isDefault = true)
        allProfiles.value = listOf(default, toDelete)
        activeProfile.value = toDelete
        advanceUntilIdle() // let init's collector populate the VM's _profiles snapshot

        subject.deleteProfile(toDelete)
        advanceUntilIdle()

        coVerify { profileRepo.deleteProfile(toDelete) }
        verify { profileRepo.setActiveProfile(default) }
        assertEquals(0, subject.selectedIndex.value)
    }

    @Test
    fun deleteProfile_whenInactive_keepsCurrentActive() = runTest(testDispatcher) {
        val toDelete = Profile(id = 7L, name = "Game", isDefault = false)
        val other = Profile(id = 9L, name = "Other", isDefault = false)
        val default = Profile(id = 1L, name = "Default", isDefault = true)
        allProfiles.value = listOf(default, toDelete, other)
        activeProfile.value = other
        advanceUntilIdle()

        subject.deleteProfile(toDelete)
        advanceUntilIdle()

        coVerify { profileRepo.deleteProfile(toDelete) }
        verify(exactly = 0) { profileRepo.setActiveProfile(default) }
    }

    @Test
    fun addProfile_delegatesToRepo() = runTest(testDispatcher) {
        subject.addProfile("New")
        advanceUntilIdle()
        coVerify { profileRepo.addProfile("New") }
    }

    @Test
    fun duplicateProfile_namesCopyOfSource() = runTest(testDispatcher) {
        val source = Profile(id = 7L, name = "Game", isDefault = false)
        subject.duplicateProfile(source)
        advanceUntilIdle()
        coVerify { profileRepo.duplicateProfile(source, "Copy of Game") }
    }

    // ── Edit mode + tab navigation ────────────────────────────────────────────

    @Test
    fun enterEditMode_setsEditingAndSelectsCorrectTab() {
        val layouts = listOf(
            sampleLayout(id = 10L, name = "Alpha"),
            sampleLayout(id = 20L, name = "Beta"),
        )
        seedLayouts(layouts)

        subject.enterEditMode(layoutId = 20L)

        assertEquals(20L, subject.editingLayoutId.value)
        assertEquals(1, subject.selectedIndex.value)
        assertNull(subject.selectedButtonId.value)
    }

    @Test
    fun enterEditMode_unknownLayoutId_isNoOp() {
        seedLayouts(listOf(sampleLayout(id = 10L)))
        subject.enterEditMode(layoutId = 999L)
        assertNull(subject.editingLayoutId.value)
    }

    @Test
    fun exitEditMode_clearsEditAndSelectedButton() {
        seedLayouts(listOf(sampleLayout(id = 10L)))
        subject.enterEditMode(10L)
        subject.selectButton("button-1")

        subject.exitEditMode()

        assertNull(subject.editingLayoutId.value)
        assertNull(subject.selectedButtonId.value)
    }

    @Test
    fun selectLayout_switchToOtherTab_exitsEditMode() {
        val layouts = listOf(
            sampleLayout(id = 10L),
            sampleLayout(id = 20L),
        )
        seedLayouts(layouts)
        subject.enterEditMode(10L)

        subject.selectLayout(1) // switching from index 0 (id 10) to index 1 (id 20)

        assertEquals(1, subject.selectedIndex.value)
        assertNull(subject.editingLayoutId.value)
    }

    @Test
    fun selectLayout_sameTab_keepsEditMode() {
        seedLayouts(listOf(sampleLayout(id = 10L)))
        subject.enterEditMode(10L)

        subject.selectLayout(0)

        assertEquals(10L, subject.editingLayoutId.value)
    }

    @Test
    fun selectButton_toggleSelection() {
        subject.selectButton("alpha")
        assertEquals("alpha", subject.selectedButtonId.value)

        subject.selectButton("alpha")
        assertNull(subject.selectedButtonId.value)

        subject.selectButton("alpha")
        subject.selectButton("beta")
        assertEquals("beta", subject.selectedButtonId.value)
    }

    @Test
    fun selectButtonOnly_forcesSelectionWithoutToggle() {
        subject.selectButtonOnly("alpha")
        subject.selectButtonOnly("alpha") // calling again should keep, not clear
        assertEquals("alpha", subject.selectedButtonId.value)
    }

    @Test
    fun openTabMenu_setsTarget_closeTabMenu_clears() {
        subject.openTabMenu(42L)
        assertEquals(42L, subject.tabContextMenuFor.value)
        subject.closeTabMenu()
        assertNull(subject.tabContextMenuFor.value)
    }

    // ── Auto-switch passthroughs ──────────────────────────────────────────────

    @Test
    fun setAutoSwitchEnabled_delegatesToSettings() {
        subject.setAutoSwitchEnabled(false)
        verify { settings.setAutoSwitchEnabled(false) }
    }

    @Test
    fun setAutoCreateProfilesEnabled_delegatesToSettings() {
        subject.setAutoCreateProfilesEnabled(true)
        verify { settings.setAutoCreateProfilesEnabled(true) }
    }

    @Test
    fun acceptCreateProfilePrompt_delegatesToAutoSwitcher() = runTest(testDispatcher) {
        subject.acceptCreateProfilePrompt(pkg = "com.example", appLabel = "Example")
        advanceUntilIdle()
        coVerify { autoSwitcher.createProfileAndBind("com.example", "Example") }
    }

    @Test
    fun ignorePackageForever_delegatesToAutoSwitcher() {
        subject.ignorePackageForever("com.example")
        verify { autoSwitcher.ignorePackage("com.example") }
    }

    @Test
    fun unignorePackage_delegatesToSettings() {
        subject.unignorePackage("com.example")
        verify { settings.removeIgnoredPackage("com.example") }
    }

    @Test
    fun reevaluateAutoSwitch_delegatesToAutoSwitcher() {
        subject.reevaluateAutoSwitch()
        verify { autoSwitcher.reevaluate() }
    }

    @Test
    fun deleteBinding_delegatesToBindingRepo() = runTest(testDispatcher) {
        subject.deleteBinding(packageName = "com.example", subId = "guest")
        advanceUntilIdle()
        coVerify { bindingRepo.unbind("com.example", "guest") }
    }

    @Test
    fun appLabels_resolvesLabelsForBindingPackages() = runTest(testDispatcher) {
        every { filter.appLabel("com.example") } returns "Example"
        every { filter.appLabel("com.foo") } returns "Foo"

        allBindings.value = listOf(
            AppProfileBinding(packageName = "com.example", subId = "", profileId = 1L),
            AppProfileBinding(packageName = "com.foo", subId = "", profileId = 1L),
        )
        advanceUntilIdle()

        assertEquals(
            mapOf("com.example" to "Example", "com.foo" to "Foo"),
            subject.appLabels.value
        )
    }

    @Test
    fun appLabels_resolvesLabelsForIgnoredPackages() = runTest(testDispatcher) {
        every { filter.appLabel("com.blocked") } returns "Blocked App"

        ignoredPackages.value = setOf("com.blocked")
        advanceUntilIdle()

        assertEquals("Blocked App", subject.appLabels.value["com.blocked"])
    }

    @Test
    fun appLabels_doesNotReResolveAlreadyCachedPackages() = runTest(testDispatcher) {
        every { filter.appLabel("com.example") } returns "Example"

        allBindings.value = listOf(
            AppProfileBinding(packageName = "com.example", subId = "", profileId = 1L)
        )
        advanceUntilIdle()
        // Same package re-emitted via the ignored-packages channel; should not call
        // the filter again because the label is cached.
        ignoredPackages.value = setOf("com.example")
        advanceUntilIdle()

        verify(exactly = 1) { filter.appLabel("com.example") }
    }

    // ── Viewing action set (Brick 4.3) ────────────────────────────────────────

    @Test
    fun setViewingActionSet_flowsThroughStateFlow() = runTest(testDispatcher) {
        assertNull(subject.viewingActionSetId.value)

        subject.setViewingActionSet(42L)
        assertEquals(42L, subject.viewingActionSetId.value)

        subject.setViewingActionSet(null)
        assertNull(subject.viewingActionSetId.value)
    }

    @Test
    fun viewingActionSetId_resetsToNull_whenActiveProfileChanges() = runTest(testDispatcher) {
        subject.setViewingActionSet(42L)
        assertEquals(42L, subject.viewingActionSetId.value)

        activeProfile.value = Profile(id = 7L, name = "Other")
        advanceUntilIdle()

        assertNull(
            "Switching the active profile should drop the editor's viewing pointer — the new controller has its own sets",
            subject.viewingActionSetId.value,
        )
    }

    @Test
    fun viewingActionSetId_resetsToNull_whenViewedSetDisappearsFromConfig() = runTest(testDispatcher) {
        val activeConfigFlow = MutableStateFlow<ControllerConfig?>(null)
        every { controllerConfigRepo.observeActiveConfig(any()) } returns activeConfigFlow
        // Rebuild subject so it picks up the new mock behavior.
        subject = MainViewModel(
            layoutRepository = layoutRepo,
            profileRepository = profileRepo,
            controllerConfigRepository = controllerConfigRepo,
            appProfileBindingRepository = bindingRepo,
            installedAppsRepository = installedAppsRepo,
            autoSwitchSettings = settings,
            autoSwitcher = autoSwitcher,
            foregroundAppFilter = filter,
            keyboardTemplateRepository = templateRepo,
            inputDispatcher = inputDispatcher,
            keyboardOverlayPresenter = keyboardOverlayPresenter,
            motionProbeAppOverlay = motionProbeAppOverlay,
            keyboardController = keyboardController,
            ioDispatcher = testDispatcher,
        )
        activeProfile.value = Profile(id = 1L, name = "P")
        // Emit a config that has set ids 1L and 2L.
        activeConfigFlow.value = miniConfig(setIds = listOf(1L, 2L))
        advanceUntilIdle()

        subject.setViewingActionSet(2L)
        assertEquals(2L, subject.viewingActionSetId.value)

        // Now emit a new config without set 2L (e.g., user deleted it).
        activeConfigFlow.value = miniConfig(setIds = listOf(1L))
        advanceUntilIdle()

        assertNull(
            "Stale viewing pointer should reset to null when the set disappears from config",
            subject.viewingActionSetId.value,
        )
    }

    /**
     * Minimal ControllerConfig with the given [setIds] — used by Brick 4.3 cleanup tests.
     * Empty preset / no inputs, since these tests don't exercise the binding graph.
     * [layersBySet] (5.3) attaches layer ids per set when supplied.
     */
    private fun miniConfig(
        setIds: List<Long>,
        layersBySet: Map<Long, List<Long>> = emptyMap(),
    ): ControllerConfig {
        val sets = setIds.map { id ->
            com.mapo.data.model.steam.ActionSetGraph(
                actionSet = com.mapo.data.model.steam.ActionSet(
                    id = id, controllerProfileId = 1L, name = "s$id", title = "S$id",
                ),
                layers = (layersBySet[id] ?: emptyList()).map { lid ->
                    com.mapo.data.model.steam.ActionLayerGraph(
                        layer = com.mapo.data.model.steam.ActionLayer(
                            id = lid, parentActionSetId = id,
                            name = "l$lid", title = "L$lid",
                        ),
                        bindingGroups = emptyList(),
                    )
                },
                preset = emptyList(),
            )
        }
        return ControllerConfig(
            controllerProfile = com.mapo.data.model.steam.ControllerProfile(
                id = 1L, profileId = 1L,
                controllerType = com.mapo.data.model.steam.ControllerType.GENERIC_ANDROID,
                name = "Default",
            ),
            actionSets = sets,
        )
    }

    // ── Action set management (Brick 4.4) ─────────────────────────────────────

    @Test
    fun addControllerActionSet_noActiveProfile_isNoOp() = runTest(testDispatcher) {
        activeProfile.value = null

        subject.addControllerActionSet(name = "menu", title = "Menu", inheritFromSetId = null)
        advanceUntilIdle()

        coVerify(exactly = 0) { controllerConfigRepo.addActionSet(any(), any(), any(), any()) }
    }

    @Test
    fun addControllerActionSet_noActiveConfig_isNoOp() = runTest(testDispatcher) {
        activeProfile.value = Profile(id = 1L, name = "P")
        // observeActiveConfig still emits null, so activeControllerConfig.value stays null.
        subject.addControllerActionSet(name = "menu", title = "Menu", inheritFromSetId = null)
        advanceUntilIdle()

        coVerify(exactly = 0) { controllerConfigRepo.addActionSet(any(), any(), any(), any()) }
    }

    @Test
    fun addControllerActionSet_delegatesToRepo_andFlipsViewingPointerToNewSet() = runTest(testDispatcher) {
        val configFlow = MutableStateFlow<ControllerConfig?>(miniConfig(setIds = listOf(1L)))
        every { controllerConfigRepo.observeActiveConfig(any()) } returns configFlow
        coEvery { controllerConfigRepo.addActionSet(any(), any(), any(), any()) } returns 42L
        subject = rebuildSubject()
        activeProfile.value = Profile(id = 1L, name = "P")
        advanceUntilIdle()

        subject.addControllerActionSet(name = "menu", title = "Menu", inheritFromSetId = null)
        advanceUntilIdle()

        coVerify { controllerConfigRepo.addActionSet(1L, "menu", "Menu", null) }
        assertEquals(42L, subject.viewingActionSetId.value)
    }

    @Test
    fun renameControllerActionSet_delegatesToRepo() = runTest(testDispatcher) {
        activeProfile.value = Profile(id = 1L, name = "P")
        subject.renameControllerActionSet(actionSetId = 7L, name = "menu", title = "Menu")
        advanceUntilIdle()
        coVerify { controllerConfigRepo.renameActionSet(7L, "menu", "Menu") }
    }

    @Test
    fun renameControllerActionSet_noActiveProfile_isNoOp() = runTest(testDispatcher) {
        activeProfile.value = null
        subject.renameControllerActionSet(actionSetId = 7L, name = "menu", title = "Menu")
        advanceUntilIdle()
        coVerify(exactly = 0) { controllerConfigRepo.renameActionSet(any(), any(), any()) }
    }

    @Test
    fun duplicateControllerActionSet_flipsViewingPointerToCopy() = runTest(testDispatcher) {
        activeProfile.value = Profile(id = 1L, name = "P")
        coEvery { controllerConfigRepo.duplicateActionSet(any(), any(), any()) } returns 99L

        subject.duplicateControllerActionSet(sourceSetId = 5L, name = "copy", title = "Copy")
        advanceUntilIdle()

        coVerify { controllerConfigRepo.duplicateActionSet(5L, "copy", "Copy") }
        assertEquals(99L, subject.viewingActionSetId.value)
    }

    @Test
    fun deleteControllerActionSet_delegatesToRepo() = runTest(testDispatcher) {
        activeProfile.value = Profile(id = 1L, name = "P")
        subject.deleteControllerActionSet(actionSetId = 5L)
        advanceUntilIdle()
        coVerify { controllerConfigRepo.deleteActionSet(5L) }
    }

    // ── Action layers — viewing pointer (Brick 5.3) ──────────────────────────

    @Test
    fun setViewingLayer_flowsThroughStateFlow() = runTest(testDispatcher) {
        assertNull(subject.viewingLayerId.value)

        subject.setViewingLayer(7L)
        assertEquals(7L, subject.viewingLayerId.value)

        subject.setViewingLayer(null)
        assertNull(subject.viewingLayerId.value)
    }

    @Test
    fun viewingLayerId_resetsToNull_whenActiveProfileChanges() = runTest(testDispatcher) {
        subject.setViewingLayer(7L)
        assertEquals(7L, subject.viewingLayerId.value)

        activeProfile.value = Profile(id = 9L, name = "Other")
        advanceUntilIdle()

        assertNull(
            "Switching the active profile should drop the editor's layer focus",
            subject.viewingLayerId.value,
        )
    }

    @Test
    fun viewingLayerId_resetsToNull_whenViewingActionSetChanges() = runTest(testDispatcher) {
        subject.setViewingLayer(7L)
        assertEquals(7L, subject.viewingLayerId.value)

        subject.setViewingActionSet(2L)
        advanceUntilIdle()

        assertNull(
            "Layers are per-set: switching set must clear the layer focus",
            subject.viewingLayerId.value,
        )
    }

    @Test
    fun viewingLayerId_resetsToNull_whenLayerDisappearsFromConfig() = runTest(testDispatcher) {
        val activeConfigFlow = MutableStateFlow<ControllerConfig?>(null)
        every { controllerConfigRepo.observeActiveConfig(any()) } returns activeConfigFlow
        subject = rebuildSubject()
        activeProfile.value = Profile(id = 1L, name = "P")
        activeConfigFlow.value = miniConfig(setIds = listOf(1L), layersBySet = mapOf(1L to listOf(10L, 11L)))
        advanceUntilIdle()

        subject.setViewingLayer(11L)
        advanceUntilIdle()
        assertEquals(11L, subject.viewingLayerId.value)

        // User deletes layer 11; new config drops it.
        activeConfigFlow.value = miniConfig(setIds = listOf(1L), layersBySet = mapOf(1L to listOf(10L)))
        advanceUntilIdle()

        assertNull(
            "Stale layer pointer should reset when the layer disappears from config",
            subject.viewingLayerId.value,
        )
    }

    @Test
    fun viewingLayerId_isNotClearedWhenAnotherSetsLayerChanges() = runTest(testDispatcher) {
        // Viewing set 1 with layer 10. Adding/removing a layer on set 2 must not
        // affect set 1's layer pointer.
        val activeConfigFlow = MutableStateFlow<ControllerConfig?>(null)
        every { controllerConfigRepo.observeActiveConfig(any()) } returns activeConfigFlow
        subject = rebuildSubject()
        activeProfile.value = Profile(id = 1L, name = "P")
        activeConfigFlow.value = miniConfig(
            setIds = listOf(1L, 2L),
            layersBySet = mapOf(1L to listOf(10L), 2L to listOf(20L)),
        )
        advanceUntilIdle()

        subject.setViewingActionSet(1L)
        advanceUntilIdle()
        subject.setViewingLayer(10L)
        advanceUntilIdle()

        // Set 2 loses its layer; we're still on set 1.
        activeConfigFlow.value = miniConfig(
            setIds = listOf(1L, 2L),
            layersBySet = mapOf(1L to listOf(10L), 2L to emptyList()),
        )
        advanceUntilIdle()

        assertEquals(
            "Set 1's layer pointer must not be affected by edits to set 2's layers",
            10L, subject.viewingLayerId.value,
        )
    }

    @Test
    fun availableLayers_reflectsViewingSet() = runTest(testDispatcher) {
        val activeConfigFlow = MutableStateFlow<ControllerConfig?>(null)
        every { controllerConfigRepo.observeActiveConfig(any()) } returns activeConfigFlow
        subject = rebuildSubject()
        activeProfile.value = Profile(id = 1L, name = "P")
        activeConfigFlow.value = miniConfig(
            setIds = listOf(1L, 2L),
            layersBySet = mapOf(1L to listOf(10L, 11L), 2L to listOf(20L)),
        )
        advanceUntilIdle()

        // Default viewing = starting set (id = 1).
        assertEquals(
            listOf(10L to "L10", 11L to "L11"),
            subject.availableLayers.value,
        )

        subject.setViewingActionSet(2L)
        advanceUntilIdle()
        assertEquals(listOf(20L to "L20"), subject.availableLayers.value)
    }

    // ── Action layer CRUD wrappers (Brick 5.3) ───────────────────────────────

    @Test
    fun addControllerActionLayer_noActiveProfile_isNoOp() = runTest(testDispatcher) {
        activeProfile.value = null

        subject.addControllerActionLayer(actionSetId = 1L, name = "scope", title = "Scope")
        advanceUntilIdle()

        coVerify(exactly = 0) { controllerConfigRepo.addLayer(any(), any(), any()) }
    }

    @Test
    fun addControllerActionLayer_delegatesToRepo_andFlipsViewingLayerToNew() = runTest(testDispatcher) {
        activeProfile.value = Profile(id = 1L, name = "P")
        coEvery { controllerConfigRepo.addLayer(any(), any(), any()) } returns 77L

        subject.addControllerActionLayer(actionSetId = 5L, name = "scope", title = "Scope")
        advanceUntilIdle()

        coVerify { controllerConfigRepo.addLayer(5L, "scope", "Scope") }
        assertEquals(77L, subject.viewingLayerId.value)
    }

    @Test
    fun renameControllerActionLayer_delegatesToRepo() = runTest(testDispatcher) {
        activeProfile.value = Profile(id = 1L, name = "P")
        subject.renameControllerActionLayer(layerId = 7L, name = "ads", title = "ADS")
        advanceUntilIdle()
        coVerify { controllerConfigRepo.renameLayer(7L, "ads", "ADS") }
    }

    @Test
    fun renameControllerActionLayer_noActiveProfile_isNoOp() = runTest(testDispatcher) {
        activeProfile.value = null
        subject.renameControllerActionLayer(layerId = 7L, name = "ads", title = "ADS")
        advanceUntilIdle()
        coVerify(exactly = 0) { controllerConfigRepo.renameLayer(any(), any(), any()) }
    }

    @Test
    fun duplicateControllerActionLayer_flipsViewingLayerToCopy() = runTest(testDispatcher) {
        activeProfile.value = Profile(id = 1L, name = "P")
        coEvery { controllerConfigRepo.duplicateLayer(any(), any(), any()) } returns 88L

        subject.duplicateControllerActionLayer(sourceLayerId = 5L, name = "copy", title = "Copy")
        advanceUntilIdle()

        coVerify { controllerConfigRepo.duplicateLayer(5L, "copy", "Copy") }
        assertEquals(88L, subject.viewingLayerId.value)
    }

    @Test
    fun deleteControllerActionLayer_delegatesToRepo() = runTest(testDispatcher) {
        activeProfile.value = Profile(id = 1L, name = "P")
        subject.deleteControllerActionLayer(layerId = 5L)
        advanceUntilIdle()
        coVerify { controllerConfigRepo.deleteLayer(5L) }
    }

    // ── Brick 5.5.b: layer override CRUD wrappers ────────────────────────────

    @Test
    fun materializeLayerOverride_noActiveProfile_returnsNull_andDoesNotCallRepo() = runTest(testDispatcher) {
        activeProfile.value = null

        val result = subject.materializeLayerOverride(
            layerId = 1L,
            inputSource = com.mapo.data.model.steam.InputSource.BUTTON_DIAMOND,
            groupInputKey = "button_a",
        )

        assertEquals(null, result)
        coVerify(exactly = 0) {
            controllerConfigRepo.materializeLayerOverride(any(), any(), any())
        }
    }

    @Test
    fun materializeLayerOverride_delegatesToRepo_andReturnsId() = runTest(testDispatcher) {
        activeProfile.value = Profile(id = 1L, name = "P")
        coEvery {
            controllerConfigRepo.materializeLayerOverride(
                layerId = 10L,
                inputSource = com.mapo.data.model.steam.InputSource.BUTTON_DIAMOND,
                groupInputKey = "button_a",
            )
        } returns 555L

        val result = subject.materializeLayerOverride(
            layerId = 10L,
            inputSource = com.mapo.data.model.steam.InputSource.BUTTON_DIAMOND,
            groupInputKey = "button_a",
        )

        assertEquals(555L, result)
        coVerify {
            controllerConfigRepo.materializeLayerOverride(
                layerId = 10L,
                inputSource = com.mapo.data.model.steam.InputSource.BUTTON_DIAMOND,
                groupInputKey = "button_a",
            )
        }
    }

    @Test
    fun clearLayerOverride_noActiveProfile_isNoOp() = runTest(testDispatcher) {
        activeProfile.value = null

        subject.clearLayerOverride(
            layerId = 1L,
            inputSource = com.mapo.data.model.steam.InputSource.BUTTON_DIAMOND,
            groupInputKey = "button_a",
        )
        advanceUntilIdle()

        coVerify(exactly = 0) {
            controllerConfigRepo.clearLayerOverride(any(), any(), any())
        }
    }

    @Test
    fun clearLayerOverride_delegatesToRepo() = runTest(testDispatcher) {
        activeProfile.value = Profile(id = 1L, name = "P")

        subject.clearLayerOverride(
            layerId = 10L,
            inputSource = com.mapo.data.model.steam.InputSource.BUTTON_DIAMOND,
            groupInputKey = "button_a",
        )
        advanceUntilIdle()

        coVerify {
            controllerConfigRepo.clearLayerOverride(
                10L,
                com.mapo.data.model.steam.InputSource.BUTTON_DIAMOND,
                "button_a",
            )
        }
    }

    /** Helper for tests that need to rebuild the subject after tweaking mock behavior. */
    private fun rebuildSubject(): MainViewModel = MainViewModel(
        layoutRepository = layoutRepo,
        profileRepository = profileRepo,
        controllerConfigRepository = controllerConfigRepo,
        appProfileBindingRepository = bindingRepo,
        installedAppsRepository = installedAppsRepo,
        autoSwitchSettings = settings,
        autoSwitcher = autoSwitcher,
        foregroundAppFilter = filter,
        keyboardTemplateRepository = templateRepo,
        inputDispatcher = inputDispatcher,
        keyboardOverlayPresenter = keyboardOverlayPresenter,
        motionProbeAppOverlay = motionProbeAppOverlay,
        keyboardController = keyboardController,
        ioDispatcher = testDispatcher,
    )

    @Test
    fun setControllerBinding_noActiveProfile_isNoOp() = runTest(testDispatcher) {
        activeProfile.value = null

        subject.setControllerBinding(activatorId = 42L, output = BindingOutput.KeyPress("ENTER"))
        advanceUntilIdle()

        coVerify(exactly = 0) { controllerConfigRepo.setBinding(any(), any()) }
    }

    @Test
    fun setControllerBinding_activeProfile_delegatesToRepository() = runTest(testDispatcher) {
        activeProfile.value = Profile(id = 5L, name = "Test")

        subject.setControllerBinding(activatorId = 42L, output = BindingOutput.KeyPress("ENTER"))
        advanceUntilIdle()

        coVerify { controllerConfigRepo.setBinding(42L, BindingOutput.KeyPress("ENTER")) }
    }

    @Test
    fun toggleRemap_flipsRemapEnabled() {
        assertFalse(subject.remapEnabled.value)
        subject.toggleRemap()
        assertTrue(subject.remapEnabled.value)
        subject.toggleRemap()
        assertFalse(subject.remapEnabled.value)
    }

    @Test
    fun toggleRemap_pushesNewValueToDispatcher() {
        subject.toggleRemap() // off → on
        verify { inputDispatcher.setRemapEnabled(true) }
        subject.toggleRemap() // on → off
        verify { inputDispatcher.setRemapEnabled(false) }
    }

    // ── Input dispatch ────────────────────────────────────────────────────────

    private fun btn(target: RemapTarget) = GridButton(
        label = "x", col = 0, row = 0,
        onTap = target.encode(),
    )

    @Test
    fun onButtonTap_serviceNotReady_emitsToastAndDoesNotDispatch() = runTest(testDispatcher) {
        every { inputDispatcher.isReady } returns false

        subject.toastMessage.test {
            subject.onButtonTap(btn(RemapTarget.Keyboard("ENTER")))
            assertEquals("Accessibility service not running", awaitItem())
        }
        verify(exactly = 0) { inputDispatcher.injectKey(any()) }
        verify(exactly = 0) { inputDispatcher.dispatchTargetAsClick(any()) }
    }

    @Test
    fun onButtonTap_keyboardTarget_routesToInjectKey() {
        every { inputDispatcher.isReady } returns true

        subject.onButtonTap(btn(RemapTarget.Keyboard("ENTER")))

        verify { inputDispatcher.injectKey("ENTER") }
        verify(exactly = 0) { inputDispatcher.dispatchTargetAsClick(any()) }
    }

    @Test
    fun onButtonTap_mouseTarget_routesToDispatchTargetAsClick() {
        every { inputDispatcher.isReady } returns true

        subject.onButtonTap(btn(RemapTarget.Mouse("MOUSE_LEFT")))

        verify { inputDispatcher.dispatchTargetAsClick(RemapTarget.Mouse("MOUSE_LEFT")) }
        verify(exactly = 0) { inputDispatcher.injectKey(any()) }
    }

    @Test
    fun onButtonTap_scrollTarget_routesToDispatchTargetAsClick() {
        every { inputDispatcher.isReady } returns true

        subject.onButtonTap(btn(RemapTarget.Mouse("SCROLL_DOWN")))

        verify { inputDispatcher.dispatchTargetAsClick(RemapTarget.Mouse("SCROLL_DOWN")) }
    }

    @Test
    fun onButtonTap_unboundTarget_isNoOp() {
        every { inputDispatcher.isReady } returns true

        subject.onButtonTap(btn(RemapTarget.Unbound))

        verify(exactly = 0) { inputDispatcher.injectKey(any()) }
        verify(exactly = 0) { inputDispatcher.dispatchTargetAsClick(any()) }
    }

    @Test
    fun onButtonDoubleTap_dispatchesOnDoubleTapTarget() {
        every { inputDispatcher.isReady } returns true
        val button = GridButton(
            label = "x", col = 0, row = 0,
            onTap = RemapTarget.Keyboard("ENTER").encode(),
            onDoubleTap = RemapTarget.Mouse("MOUSE_RIGHT").encode(),
        )

        subject.onButtonDoubleTap(button)

        verify { inputDispatcher.dispatchTargetAsClick(RemapTarget.Mouse("MOUSE_RIGHT")) }
        verify(exactly = 0) { inputDispatcher.injectKey(any()) }
    }

    @Test
    fun onButtonHold_dispatchesOnHoldTarget() {
        every { inputDispatcher.isReady } returns true
        val button = GridButton(
            label = "x", col = 0, row = 0,
            onHold = RemapTarget.Keyboard("F12").encode(),
        )

        subject.onButtonHold(button)

        verify { inputDispatcher.injectKey("F12") }
    }

    @Test
    fun onButtonHold_unboundTarget_isNoOpEvenIfServiceNotReady() = runTest(testDispatcher) {
        every { inputDispatcher.isReady } returns false
        val button = GridButton(label = "x", col = 0, row = 0)  // all targets default to Unbound

        subject.toastMessage.test {
            subject.onButtonHold(button)
            // No toast for unbound — nothing meaningful would dispatch anyway.
            expectNoEvents()
        }
        verify(exactly = 0) { inputDispatcher.injectKey(any()) }
        verify(exactly = 0) { inputDispatcher.dispatchTargetAsClick(any()) }
    }

    @Test
    fun onTrackpadGesture_serviceReady_dispatchesGestureTarget() {
        every { inputDispatcher.isReady } returns true
        val button = GridButton(
            label = "tp", col = 0, row = 0, type = "trackpad",
            onTap = RemapTarget.Mouse("MOUSE_LEFT").encode(),
        )

        subject.onTrackpadGesture(button, com.mapo.data.model.TrackpadGesture.TAP)

        verify { inputDispatcher.dispatchTargetAsClick(RemapTarget.Mouse("MOUSE_LEFT")) }
    }

    @Test
    fun onTrackpadGesture_serviceNotReady_emitsToastAndDoesNotDispatch() = runTest(testDispatcher) {
        every { inputDispatcher.isReady } returns false
        val button = GridButton(label = "tp", col = 0, row = 0, type = "trackpad")

        subject.toastMessage.test {
            subject.onTrackpadGesture(button, com.mapo.data.model.TrackpadGesture.TAP)
            assertEquals("Accessibility service not running", awaitItem())
        }
        verify(exactly = 0) { inputDispatcher.dispatchTargetAsClick(any()) }
    }

    @Test
    fun onDragStart_serviceReady_startsMouseDrag() {
        every { inputDispatcher.isReady } returns true

        subject.onDragStart()

        verify { inputDispatcher.startMouseDrag() }
    }

    @Test
    fun onDragStart_serviceNotReady_emitsToast() = runTest(testDispatcher) {
        every { inputDispatcher.isReady } returns false

        subject.toastMessage.test {
            subject.onDragStart()
            assertEquals("Accessibility service not running", awaitItem())
        }
        verify(exactly = 0) { inputDispatcher.startMouseDrag() }
    }

    @Test
    fun onMouseMove_alwaysDelegatesToDispatcher() {
        // High-frequency call site — dispatcher is silent no-op when not ready, so the
        // VM doesn't bother gating; verify it just forwards.
        subject.onMouseMove(dx = 5f, dy = -3f)
        verify { inputDispatcher.injectMouseMove(5f, -3f) }
    }

    @Test
    fun onDragEnd_alwaysDelegatesToDispatcher() {
        subject.onDragEnd()
        verify { inputDispatcher.endMouseDrag() }
    }

    // ── Button CRUD ───────────────────────────────────────────────────────────

    @Test
    fun addButton_findsFirstEmptyCell_andSelectsNewButton() = runTest(testDispatcher) {
        seedLayouts(listOf(sampleLayout(id = 10L, cols = 3, rows = 2)))
        subject.enterEditMode(10L)

        subject.addButton(GridButton(label = "X", col = 0, row = 0))
        advanceUntilIdle()

        val layout = subject.layouts.value.first()
        assertEquals(1, layout.buttons.size)
        assertEquals(0, layout.buttons[0].col)
        assertEquals(0, layout.buttons[0].row)
        assertEquals(layout.buttons[0].id, subject.selectedButtonId.value)
        coVerify { layoutRepo.saveLayout(any()) }
    }

    @Test
    fun addButton_fullGrid_emitsErrorAndDoesNotPersist() = runTest(testDispatcher) {
        val full = sampleLayout(
            id = 10L, cols = 1, rows = 1,
            buttons = listOf(GridButton(label = "A", col = 0, row = 0)),
        )
        seedLayouts(listOf(full))
        subject.enterEditMode(10L)

        // Drain initial DB load saveLayout calls (none expected, but defensive).
        advanceUntilIdle()

        subject.addButton(GridButton(label = "Y", col = 0, row = 0))
        advanceUntilIdle()

        // _layouts unchanged (still 1 button).
        assertEquals(1, subject.layouts.value.first().buttons.size)
    }

    @Test
    fun addButtonAt_validBounds_andEmptyCell_persists() = runTest(testDispatcher) {
        seedLayouts(listOf(sampleLayout(id = 10L, cols = 3, rows = 3)))
        subject.enterEditMode(10L)

        subject.addButtonAt(col = 2, row = 1, GridButton(label = "X", col = 0, row = 0))
        advanceUntilIdle()

        val placed = subject.layouts.value.first().buttons.single()
        assertEquals(2, placed.col)
        assertEquals(1, placed.row)
    }

    @Test
    fun addButtonAt_outOfBounds_isRejected() = runTest(testDispatcher) {
        seedLayouts(listOf(sampleLayout(id = 10L, cols = 3, rows = 3)))
        subject.enterEditMode(10L)

        subject.addButtonAt(col = 5, row = 0, GridButton(label = "X", col = 0, row = 0))
        advanceUntilIdle()

        assertTrue(subject.layouts.value.first().buttons.isEmpty())
    }

    @Test
    fun addButtonAt_occupiedCell_isRejected() = runTest(testDispatcher) {
        val occupied = sampleLayout(
            id = 10L, cols = 3, rows = 3,
            buttons = listOf(GridButton(label = "A", col = 1, row = 1)),
        )
        seedLayouts(listOf(occupied))
        subject.enterEditMode(10L)

        subject.addButtonAt(col = 1, row = 1, GridButton(label = "X", col = 0, row = 0))
        advanceUntilIdle()

        assertEquals(1, subject.layouts.value.first().buttons.size)
    }

    @Test
    fun duplicateButton_originalSizeFits_clonesAtSameSpan() = runTest(testDispatcher) {
        val source = GridButton(
            id = "src",
            label = "A",
            col = 0, row = 0,
            colSpan = 2, rowSpan = 1,
        )
        seedLayouts(listOf(sampleLayout(id = 10L, cols = 4, rows = 2, buttons = listOf(source))))
        subject.enterEditMode(10L)

        subject.duplicateButton("src")
        advanceUntilIdle()

        val buttons = subject.layouts.value.first().buttons
        assertEquals(2, buttons.size)
        val copy = buttons.first { it.id != "src" }
        assertEquals(2, copy.colSpan) // preserved original colSpan
        assertEquals(1, copy.rowSpan)
    }

    @Test
    fun duplicateButton_originalSizeDoesNotFit_fallsBackTo1x1() = runTest(testDispatcher) {
        // 4x2 grid: source is 3x1 at row 0 (fills cols 0-2). A blocker at (1,1)
        // splits row 1 so no 3-wide gap remains anywhere in the grid. Single
        // free cells exist (e.g. col 3 of row 0), so the 1x1 fallback succeeds.
        val source = GridButton(
            id = "src", label = "A",
            col = 0, row = 0, colSpan = 3, rowSpan = 1,
        )
        val blocker = GridButton(
            id = "blk", label = "B",
            col = 1, row = 1, colSpan = 1, rowSpan = 1,
        )
        seedLayouts(listOf(sampleLayout(id = 10L, cols = 4, rows = 2, buttons = listOf(source, blocker))))
        subject.enterEditMode(10L)

        subject.duplicateButton("src")
        advanceUntilIdle()

        val buttons = subject.layouts.value.first().buttons
        assertEquals(3, buttons.size) // source + blocker + copy
        val copy = buttons.first { it.id !in setOf("src", "blk") }
        assertEquals(1, copy.colSpan)
        assertEquals(1, copy.rowSpan)
    }

    @Test
    fun moveButton_clampsToGridAndRejectsOverlap() = runTest(testDispatcher) {
        val a = GridButton(id = "a", label = "A", col = 0, row = 0)
        val b = GridButton(id = "b", label = "B", col = 1, row = 0)
        seedLayouts(listOf(sampleLayout(id = 10L, cols = 3, rows = 3, buttons = listOf(a, b))))
        subject.enterEditMode(10L)

        // Move a to b's cell — should be rejected (no change).
        subject.moveButton("a", newCol = 1, newRow = 0)
        advanceUntilIdle()
        assertEquals(0, subject.layouts.value.first().buttons.first { it.id == "a" }.col)

        // Move a to a free cell — should succeed and persist.
        subject.moveButton("a", newCol = 2, newRow = 2)
        advanceUntilIdle()
        val moved = subject.layouts.value.first().buttons.first { it.id == "a" }
        assertEquals(2, moved.col)
        assertEquals(2, moved.row)

        // Move out-of-bounds — should clamp to last valid position.
        subject.moveButton("a", newCol = 99, newRow = 99)
        advanceUntilIdle()
        val clamped = subject.layouts.value.first().buttons.first { it.id == "a" }
        assertEquals(2, clamped.col) // grid is 3 cols, span 1 → max col = 2
        assertEquals(2, clamped.row)
    }

    @Test
    fun resizeButton_validResize_persists() = runTest(testDispatcher) {
        val a = GridButton(id = "a", label = "A", col = 0, row = 0)
        seedLayouts(listOf(sampleLayout(id = 10L, cols = 3, rows = 3, buttons = listOf(a))))
        subject.enterEditMode(10L)

        subject.resizeButton("a", newColSpan = 2, newRowSpan = 2)
        advanceUntilIdle()

        val resized = subject.layouts.value.first().buttons.single()
        assertEquals(2, resized.colSpan)
        assertEquals(2, resized.rowSpan)
    }

    @Test
    fun resizeButton_overlapsAnother_isRejected() = runTest(testDispatcher) {
        val a = GridButton(id = "a", label = "A", col = 0, row = 0)
        val b = GridButton(id = "b", label = "B", col = 1, row = 0)
        seedLayouts(listOf(sampleLayout(id = 10L, cols = 3, rows = 3, buttons = listOf(a, b))))
        subject.enterEditMode(10L)

        subject.resizeButton("a", newColSpan = 2, newRowSpan = 1)
        advanceUntilIdle()

        // a stays 1x1.
        val unchanged = subject.layouts.value.first().buttons.first { it.id == "a" }
        assertEquals(1, unchanged.colSpan)
    }

    @Test
    fun deleteButton_removesById() = runTest(testDispatcher) {
        val a = GridButton(id = "a", label = "A", col = 0, row = 0)
        val b = GridButton(id = "b", label = "B", col = 1, row = 0)
        seedLayouts(listOf(sampleLayout(id = 10L, cols = 3, rows = 3, buttons = listOf(a, b))))
        subject.enterEditMode(10L)

        subject.deleteButton("a")
        advanceUntilIdle()

        val remaining = subject.layouts.value.first().buttons
        assertEquals(1, remaining.size)
        assertEquals("b", remaining[0].id)
    }

    @Test
    fun deleteSelectedButton_removesAndClearsSelection() = runTest(testDispatcher) {
        val a = GridButton(id = "a", label = "A", col = 0, row = 0)
        seedLayouts(listOf(sampleLayout(id = 10L, cols = 3, rows = 3, buttons = listOf(a))))
        subject.enterEditMode(10L)
        subject.selectButton("a")

        subject.deleteSelectedButton()
        advanceUntilIdle()

        assertTrue(subject.layouts.value.first().buttons.isEmpty())
        assertNull(subject.selectedButtonId.value)
    }

    @Test
    fun updateSelectedButton_appliesChangesToSelectedButton() = runTest(testDispatcher) {
        val a = GridButton(id = "a", label = "A", col = 0, row = 0)
        seedLayouts(listOf(sampleLayout(id = 10L, cols = 3, rows = 3, buttons = listOf(a))))
        subject.enterEditMode(10L)
        subject.selectButton("a")

        subject.updateSelectedButton(
            a.copy(
                label = "Renamed",
                onTap = RemapTarget.Keyboard("Z").encode(),
            )
        )
        advanceUntilIdle()

        val updated = subject.layouts.value.first().buttons.single()
        assertEquals("Renamed", updated.label)
        assertEquals(RemapTarget.Keyboard("Z"), updated.onTapTarget)
    }

    @Test
    fun updateSelectedButton_noSelection_isNoOp() = runTest(testDispatcher) {
        val a = GridButton(id = "a", label = "A", col = 0, row = 0)
        seedLayouts(listOf(sampleLayout(id = 10L, cols = 3, rows = 3, buttons = listOf(a))))
        subject.enterEditMode(10L)
        // No selectButton call → _selectedButtonId is null.

        subject.updateSelectedButton(a.copy(label = "Renamed"))
        advanceUntilIdle()

        assertEquals("A", subject.layouts.value.first().buttons.single().label)
    }

    // ── Tab choreography ──────────────────────────────────────────────────────

    @Test
    fun reorderTabs_movesItem_andPersists() = runTest(testDispatcher) {
        val layouts = listOf(
            sampleLayout(id = 1L),
            sampleLayout(id = 2L),
            sampleLayout(id = 3L),
        )
        seedLayouts(layouts)

        subject.reorderTabs(fromIndex = 0, toIndex = 2)
        advanceUntilIdle()

        val ids = subject.layouts.value.map { it.id }
        assertEquals(listOf(2L, 3L, 1L), ids)
        coVerify {
            layoutRepo.reorder(profileId = 1L, idToPosition = mapOf(2L to 0, 3L to 1, 1L to 2))
        }
    }

    @Test
    fun reorderTabs_keepsSelectionOnMovedTab() = runTest(testDispatcher) {
        val layouts = listOf(sampleLayout(id = 1L), sampleLayout(id = 2L), sampleLayout(id = 3L))
        seedLayouts(layouts)
        subject.selectLayout(0) // selecting id=1

        subject.reorderTabs(fromIndex = 0, toIndex = 2)
        advanceUntilIdle()

        // id=1 is now at index 2; selection should follow.
        assertEquals(2, subject.selectedIndex.value)
    }

    @Test
    fun reorderTabs_sameIndex_isNoOp() = runTest(testDispatcher) {
        seedLayouts(listOf(sampleLayout(id = 1L), sampleLayout(id = 2L)))

        subject.reorderTabs(fromIndex = 1, toIndex = 1)
        advanceUntilIdle()

        coVerify(exactly = 0) { layoutRepo.reorder(any(), any()) }
    }

    @Test
    fun tryResizeLayout_noOverflow_persistsAndReturnsNull() = runTest(testDispatcher) {
        val layout = sampleLayout(
            id = 10L, cols = 3, rows = 2,
            buttons = listOf(GridButton(label = "A", col = 0, row = 0)),
        )
        seedLayouts(listOf(layout))

        val conflict = subject.tryResizeLayout(layoutId = 10L, columns = 4, rows = 3)
        advanceUntilIdle()

        assertEquals(null, conflict)
        val updated = subject.layouts.value.first()
        assertEquals(4, updated.columns)
        assertEquals(3, updated.rows)
    }

    @Test
    fun tryResizeLayout_buttonsExceedBounds_returnsLabelsWithoutPersisting() = runTest(testDispatcher) {
        val layout = sampleLayout(
            id = 10L, cols = 4, rows = 4,
            buttons = listOf(
                GridButton(id = "big", label = "Big", col = 2, row = 2, colSpan = 2, rowSpan = 2),
            ),
        )
        seedLayouts(listOf(layout))

        val conflict = subject.tryResizeLayout(layoutId = 10L, columns = 2, rows = 2)
        advanceUntilIdle()

        assertEquals(listOf("Big"), conflict)
        // Resize NOT applied because of the conflict.
        assertEquals(4, subject.layouts.value.first().columns)
    }

    @Test
    fun applyResizeWithAutoFit_dropsButtonsThatCannotFit() = runTest(testDispatcher) {
        val layout = sampleLayout(
            id = 10L, cols = 4, rows = 4,
            buttons = listOf(
                GridButton(id = "a", label = "A", col = 0, row = 0),
                GridButton(id = "b", label = "B", col = 0, row = 0), // already overlap-y
            ),
        )
        seedLayouts(listOf(layout))

        subject.toastMessage.test {
            subject.applyResizeWithAutoFit(layoutId = 10L, columns = 2, rows = 2)
            val toast = awaitItem()
            assertTrue("expected drop toast, got '$toast'", toast.contains("removed"))
        }
        val updated = subject.layouts.value.first()
        // a fits at (0,0); b would overlap a → dropped.
        assertEquals(1, updated.buttons.size)
        assertEquals(2, updated.columns)
        assertEquals(2, updated.rows)
    }

    @Test
    fun duplicateKeyboard_appendsCopyAtNextPosition() = runTest(testDispatcher) {
        val layout = sampleLayout(id = 10L, name = "Main")
        seedLayouts(listOf(layout))
        // The DB-side query returns the existing row; subsequent insert + re-fetch
        // must reflect the appended copy.
        val sourceKey = layout.toKeyLayout(profileId = 1L, position = 0)
        val copyKey = sourceKey.copy(id = 11L, name = "Main Copy", position = 1)
        coEvery { layoutRepo.getLayoutsByProfileOnce(1L) } returnsMany listOf(
            listOf(sourceKey),
            listOf(sourceKey, copyKey),
        )

        subject.duplicateKeyboard(layoutId = 10L)
        advanceUntilIdle()

        coVerify { layoutRepo.saveLayout(match { it.name == "Main Copy" && it.position == 1 }) }
    }

    @Test
    fun removeKeyboard_optimisticallyRemovesAndCompacts() = runTest(testDispatcher) {
        val layouts = listOf(
            sampleLayout(id = 1L),
            sampleLayout(id = 2L),
            sampleLayout(id = 3L),
        )
        seedLayouts(layouts)
        // After the optimistic delete, the repo's getLayoutsByProfileOnce should
        // reflect the post-delete state (used by compaction).
        val keyLayouts = listOf(
            sampleLayout(id = 1L).toKeyLayout(1L, position = 0),
            sampleLayout(id = 3L).toKeyLayout(1L, position = 2), // gap at position 1
        )
        coEvery { layoutRepo.getLayoutsByProfileOnce(1L) } returns keyLayouts

        subject.removeKeyboard(layoutId = 2L)
        advanceUntilIdle()

        // Optimistic UI: layouts list no longer contains id=2.
        assertEquals(listOf(1L, 3L), subject.layouts.value.map { it.id })
        // Compaction: id=3 was at position 2 (gap), should be reordered to position 1.
        coVerify { layoutRepo.reorder(profileId = 1L, idToPosition = mapOf(3L to 1)) }
    }

    @Test
    fun saveAsNewTemplate_uniqueName_inserts() = runTest(testDispatcher) {
        val layout = sampleLayout(id = 10L, name = "Custom")
        seedLayouts(listOf(layout))
        coEvery { templateRepo.findByName("MyTemplate") } returns null

        subject.saveAsNewTemplate(layoutId = 10L, templateName = "MyTemplate")
        advanceUntilIdle()

        coVerify { templateRepo.insertNew(any(), "MyTemplate") }
    }

    @Test
    fun saveAsNewTemplate_collidingName_emitsConflictAndDoesNotInsert() = runTest(testDispatcher) {
        val layout = sampleLayout(id = 10L, name = "Custom")
        seedLayouts(listOf(layout))
        val existing = TemplateRef.User(
            id = 99L, name = "MyTemplate",
            columns = 3, rows = 2, buttons = emptyList(),
        )
        coEvery { templateRepo.findByName("MyTemplate") } returns existing

        subject.tabUiEvents.test {
            subject.saveAsNewTemplate(layoutId = 10L, templateName = "MyTemplate")
            val conflict = awaitItem() as TabUiEvent.TemplateNameConflict
            assertEquals(99L, (conflict.existing as TemplateRef.User).id)
        }
        coVerify(exactly = 0) { templateRepo.insertNew(any(), any()) }
    }

    @Test
    fun updateExistingTemplate_passesLayoutAndId() = runTest(testDispatcher) {
        val layout = sampleLayout(id = 10L, name = "Custom")
        seedLayouts(listOf(layout))
        val ref = TemplateRef.User(id = 42L, name = "Old", columns = 3, rows = 2, buttons = emptyList())

        subject.updateExistingTemplate(layoutId = 10L, ref = ref)
        advanceUntilIdle()

        coVerify { templateRepo.updateExisting(42L, any()) }
    }

    @Test
    fun addBlankKeyboard_namedUniquely_andSelected() = runTest(testDispatcher) {
        seedLayouts(listOf(sampleLayout(id = 10L, name = "New Keyboard")))
        // First call returns existing; second (after insert) returns appended row.
        val existingKey = sampleLayout(id = 10L, name = "New Keyboard").toKeyLayout(1L, 0)
        val appendedKey = sampleLayout(id = 11L, name = "New Keyboard 2").toKeyLayout(1L, 1)
        coEvery { layoutRepo.getLayoutsByProfileOnce(1L) } returnsMany listOf(
            listOf(existingKey),
            listOf(existingKey, appendedKey),
        )

        subject.addBlankKeyboard()
        advanceUntilIdle()

        coVerify { layoutRepo.saveLayout(match { it.name == "New Keyboard 2" && it.position == 1 }) }
    }

    @Test
    fun addKeyboardFromTemplate_usesTemplateNameWhenUnique() = runTest(testDispatcher) {
        seedLayouts(listOf(sampleLayout(id = 10L, name = "Existing")))
        val template = TemplateRef.User(
            id = 42L, name = "FromTpl",
            columns = 2, rows = 2, buttons = emptyList(),
        )
        coEvery { layoutRepo.getLayoutsByProfileOnce(1L) } returnsMany listOf(
            listOf(sampleLayout(id = 10L, name = "Existing").toKeyLayout(1L, 0)),
            listOf(
                sampleLayout(id = 10L, name = "Existing").toKeyLayout(1L, 0),
                sampleLayout(id = 11L, name = "FromTpl").toKeyLayout(1L, 1),
            ),
        )

        subject.addKeyboardFromTemplate(template)
        advanceUntilIdle()

        coVerify { layoutRepo.saveLayout(match { it.name == "FromTpl" }) }
    }

    @Test
    fun addKeyboardFromTemplate_collidingName_picksNumberedAlternative() = runTest(testDispatcher) {
        seedLayouts(listOf(sampleLayout(id = 10L, name = "Tpl")))
        val template = TemplateRef.User(
            id = 42L, name = "Tpl",
            columns = 2, rows = 2, buttons = emptyList(),
        )
        coEvery { layoutRepo.getLayoutsByProfileOnce(1L) } returnsMany listOf(
            listOf(sampleLayout(id = 10L, name = "Tpl").toKeyLayout(1L, 0)),
            listOf(
                sampleLayout(id = 10L, name = "Tpl").toKeyLayout(1L, 0),
                sampleLayout(id = 11L, name = "Tpl 2").toKeyLayout(1L, 1),
            ),
        )

        subject.addKeyboardFromTemplate(template)
        advanceUntilIdle()

        coVerify { layoutRepo.saveLayout(match { it.name == "Tpl 2" }) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Drives the in-memory [_layouts] StateFlow indirectly by making the
     * underlying flow emit. The init collector observes activeProfile→layouts
     * and pushes into _layouts via toGridLayout(); we mimic that path by
     * setting both flows.
     */
    private fun seedLayouts(layouts: List<GridLayout>) {
        // Use the production toKeyLayout() extension so buttons round-trip via
        // the real Gson serializer; in-memory GridLayouts with pre-populated
        // buttons must be visible to button-CRUD tests after the init collector
        // converts them back via toGridLayout().
        val keyLayouts = layouts.mapIndexed { i, gl -> gl.toKeyLayout(profileId = 1L, position = i) }
        activeProfile.value = Profile(id = 1L, name = "Test")
        allLayouts.value = keyLayouts
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun sampleLayout(
        id: Long,
        name: String = "Layout-$id",
        cols: Int = 3,
        rows: Int = 2,
        buttons: List<GridButton> = emptyList(),
    ) = GridLayout(
        id = id,
        name = name,
        columns = cols,
        rows = rows,
        buttons = buttons,
    )
}
