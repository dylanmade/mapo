package com.mappo.service.keyboard

import com.mappo.data.model.GridButton
import com.mappo.data.model.GridLayout
import com.mappo.data.model.KeyLayout
import com.mappo.data.model.Profile
import com.mappo.data.model.RemapTarget
import com.mappo.data.model.TrackpadGesture
import com.mappo.data.model.toKeyLayout
import com.mappo.data.repository.LayoutRepository
import com.mappo.data.repository.ProfileRepository
import com.mappo.service.input.InputDispatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Brick 2 (single-screen refactor) tests. Validates the runtime state surface
 * extracted from [com.mappo.ui.viewmodel.MainViewModel]:
 *  - Layouts auto-load from `LayoutRepository` per active profile.
 *  - State mutators (`setSelectedIndex`, `replaceLayouts`, `replaceLayoutById`,
 *    `toggleRemap`) update the corresponding flows.
 *  - `displayLayout` (FC1 seam) reflects `(selectedIndex, layouts)` and is nullable.
 *  - `tabs` (FC1 seam) projects `layouts` → [KeyboardTab].
 *  - Run-mode dispatch routes through [InputDispatcher] when ready and emits
 *    error messages when not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardControllerTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var inputDispatcher: InputDispatcher
    private lateinit var layoutRepo: LayoutRepository
    private lateinit var profileRepo: ProfileRepository

    private val activeProfile = MutableStateFlow<Profile?>(null)
    private val allLayouts = MutableStateFlow<List<KeyLayout>>(emptyList())

    private lateinit var subject: KeyboardController

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        inputDispatcher = mockk(relaxed = true)
        layoutRepo = mockk(relaxed = true)
        profileRepo = mockk(relaxed = true)
        every { profileRepo.activeProfile } returns activeProfile
        every { layoutRepo.getLayoutsByProfile(any()) } returns allLayouts
        coEvery { layoutRepo.seedDefaultsIfEmpty(any()) } returns Unit
        every { inputDispatcher.isReady } returns true

        subject = KeyboardController(
            inputDispatcher = inputDispatcher,
            layoutRepository = layoutRepo,
            profileRepository = profileRepo,
            ioDispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── State load + projection ───────────────────────────────────────────────

    @Test
    fun layouts_loadsFromRepo_whenActiveProfileEmits() = runTest(testDispatcher) {
        seed(listOf(layoutOf(id = 10L, name = "Alpha"), layoutOf(id = 20L, name = "Beta")))

        assertEquals(2, subject.layouts.value.size)
        assertEquals("Alpha", subject.layouts.value[0].name)
        assertEquals("Beta", subject.layouts.value[1].name)
    }

    @Test
    fun layouts_emptyByDefault_beforeAnyProfile() = runTest(testDispatcher) {
        advanceUntilIdle()
        assertTrue(subject.layouts.value.isEmpty())
    }

    @Test
    fun selectedIndex_clampsDownWhenLayoutListShrinks() = runTest(testDispatcher) {
        seed(listOf(layoutOf(10L), layoutOf(20L), layoutOf(30L)))
        subject.setSelectedIndex(2)

        // Shrink the list to 1 entry — controller should clamp to size-1.
        seed(listOf(layoutOf(10L)))

        assertEquals(0, subject.selectedIndex.value)
    }

    @Test
    fun displayLayout_isNullable_whenNoLayoutsLoaded() = runTest(testDispatcher) {
        advanceUntilIdle()
        // FC1 seam: opaque nullable grid, no DefaultLayouts fallback at the controller surface.
        assertNull(subject.displayLayout.value)
    }

    @Test
    fun displayLayout_resolvesViaSelectedIndex() = runTest(testDispatcher) {
        seed(listOf(layoutOf(10L, "First"), layoutOf(20L, "Second")))
        subject.setSelectedIndex(1)
        advanceUntilIdle()

        assertEquals("Second", subject.displayLayout.value?.name)
    }

    @Test
    fun displayLayout_fallsBackToFirst_whenSelectedIndexOutOfRange() = runTest(testDispatcher) {
        seed(listOf(layoutOf(10L, "Only")))
        subject.setSelectedIndex(99)
        advanceUntilIdle()

        // Out-of-range index falls back to layouts.firstOrNull() — preserves the
        // pre-refactor MainViewModel behavior so the activity surface never NPEs.
        assertEquals("Only", subject.displayLayout.value?.name)
    }

    @Test
    fun tabs_projectsLayoutsToOpaqueDescriptors() = runTest(testDispatcher) {
        seed(listOf(layoutOf(10L, "A"), layoutOf(20L, "B")))
        advanceUntilIdle()

        val tabs = subject.tabs.value
        assertEquals(listOf(KeyboardTab(10L, "A"), KeyboardTab(20L, "B")), tabs)
    }

    // ── State mutators ────────────────────────────────────────────────────────

    @Test
    fun setSelectedIndex_updatesFlow() = runTest(testDispatcher) {
        subject.setSelectedIndex(3)
        assertEquals(3, subject.selectedIndex.value)
    }

    @Test
    fun replaceLayouts_setsLayoutsFlow() = runTest(testDispatcher) {
        val newLayouts = persistentListOfLayouts(layoutOf(99L, "Replaced"))
        subject.replaceLayouts(newLayouts)

        assertEquals(1, subject.layouts.value.size)
        assertEquals("Replaced", subject.layouts.value[0].name)
    }

    @Test
    fun replaceLayoutById_updatesMatchingEntryOnly() = runTest(testDispatcher) {
        seed(listOf(layoutOf(10L, "A"), layoutOf(20L, "B"), layoutOf(30L, "C")))

        subject.replaceLayoutById(GridLayout(id = 20L, name = "B-edited", columns = 3, rows = 2, buttons = emptyList()))

        assertEquals(listOf("A", "B-edited", "C"), subject.layouts.value.map { it.name })
    }

    @Test
    fun replaceLayoutById_noOps_whenIdNotFound() = runTest(testDispatcher) {
        seed(listOf(layoutOf(10L, "A")))
        val before = subject.layouts.value

        subject.replaceLayoutById(GridLayout(id = 999L, name = "Ghost", columns = 1, rows = 1, buttons = emptyList()))

        assertEquals(before, subject.layouts.value)
    }

    @Test
    fun toggleRemap_flipsFlagAndPushesToDispatcher() = runTest(testDispatcher) {
        assertEquals(false, subject.remapEnabled.value)

        subject.toggleRemap()
        assertEquals(true, subject.remapEnabled.value)
        verify { inputDispatcher.setRemapEnabled(true) }

        subject.toggleRemap()
        assertEquals(false, subject.remapEnabled.value)
        verify { inputDispatcher.setRemapEnabled(false) }
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    @Test
    fun onButtonTap_keyboardTarget_injectsKey() = runTest(testDispatcher) {
        val button = sampleButtonWithTap(RemapTarget.Keyboard("ENTER"))
        subject.onButtonTap(button)
        verify { inputDispatcher.injectKey("ENTER") }
    }

    @Test
    fun onButtonTap_unboundTarget_isNoOp() = runTest(testDispatcher) {
        val button = sampleButtonWithTap(RemapTarget.Unbound)
        subject.onButtonTap(button)
        verify(exactly = 0) { inputDispatcher.injectKey(any()) }
        verify(exactly = 0) { inputDispatcher.dispatchTargetAsClick(any()) }
    }

    @Test
    fun onButtonTap_mouseTarget_dispatchesAsClick() = runTest(testDispatcher) {
        val target = RemapTarget.Mouse("LEFT_CLICK")
        val button = sampleButtonWithTap(target)
        subject.onButtonTap(button)
        verify { inputDispatcher.dispatchTargetAsClick(target) }
    }

    @Test
    fun onButtonTap_emitsError_whenDispatcherNotReady() = runTest(testDispatcher) {
        every { inputDispatcher.isReady } returns false
        val button = sampleButtonWithTap(RemapTarget.Keyboard("ENTER"))

        val emitted = mutableListOf<String>()
        val job = launch { subject.errorMessages.collect { emitted.add(it) } }
        advanceUntilIdle()

        subject.onButtonTap(button)
        advanceUntilIdle()

        assertTrue(
            "Expected an 'Accessibility service not running' message; got $emitted",
            emitted.any { "Accessibility" in it },
        )
        verify(exactly = 0) { inputDispatcher.injectKey(any()) }
        job.cancel()
    }

    @Test
    fun onDragStart_callsDispatcher_whenReady() = runTest(testDispatcher) {
        subject.onDragStart()
        verify { inputDispatcher.startMouseDrag() }
    }

    @Test
    fun onDragStart_emitsError_whenNotReady() = runTest(testDispatcher) {
        every { inputDispatcher.isReady } returns false

        val emitted = mutableListOf<String>()
        val job = launch { subject.errorMessages.collect { emitted.add(it) } }
        advanceUntilIdle()

        subject.onDragStart()
        advanceUntilIdle()

        assertTrue(emitted.any { "Accessibility" in it })
        verify(exactly = 0) { inputDispatcher.startMouseDrag() }
        job.cancel()
    }

    @Test
    fun onMouseMove_alwaysDelegates() = runTest(testDispatcher) {
        // No isReady gate — onMouseMove is paired with a prior onDragStart that
        // already gated; the per-event check would cost overhead on every move.
        subject.onMouseMove(2.0f, -3.0f)
        verify { inputDispatcher.injectMouseMove(2.0f, -3.0f) }
    }

    @Test
    fun onDragEnd_alwaysDelegates() = runTest(testDispatcher) {
        subject.onDragEnd()
        verify { inputDispatcher.endMouseDrag() }
    }

    @Test
    fun onTrackpadGesture_dispatchesGestureTarget() = runTest(testDispatcher) {
        // Pre-seed a button whose `onTap` target is what gestureTarget() resolves to
        // for the gesture under test (TrackpadGesture defaults map to onTap for the
        // tap gesture, so a tap-target on a non-trackpad button is fine for the
        // verify side — we just need a target the dispatcher receives.)
        val target = RemapTarget.Mouse("LEFT_CLICK")
        val button = sampleButtonWithTap(target)
        subject.onTrackpadGesture(button, TrackpadGesture.TAP)
        verify { inputDispatcher.dispatchTargetAsClick(any()) }
    }

    @Test
    fun toggleRemap_initialState_isFalse() {
        assertEquals(false, subject.remapEnabled.value)
    }

    @Test
    fun activeProfileId_reflectsProfileRepoFlow() = runTest(testDispatcher) {
        activeProfile.value = null
        advanceUntilIdle()
        assertNull(subject.activeProfileId.value)

        activeProfile.value = Profile(id = 42L, name = "Test")
        advanceUntilIdle()
        assertEquals(42L, subject.activeProfileId.value)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Seed the controller's repo-backed layouts collector with [layouts] under profile id=1. */
    private fun seed(layouts: List<GridLayout>) {
        activeProfile.value = Profile(id = 1L, name = "Test")
        allLayouts.value = layouts.mapIndexed { i, gl -> gl.toKeyLayout(profileId = 1L, position = i) }
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull("seed() failed to publish layouts", subject.layouts.value)
    }

    private fun layoutOf(id: Long, name: String = "Layout-$id"): GridLayout =
        GridLayout(id = id, name = name, columns = 3, rows = 2, buttons = emptyList())

    private fun persistentListOfLayouts(vararg layouts: GridLayout): kotlinx.collections.immutable.ImmutableList<GridLayout> =
        kotlinx.collections.immutable.persistentListOf(*layouts)

    private fun sampleButtonWithTap(target: RemapTarget): GridButton =
        GridButton(id = "btn", label = "B", col = 0, row = 0, onTap = target.encode())
}
