package com.mapo.ui.viewmodel

import com.mapo.data.model.AppProfileBinding
import com.mapo.data.model.KeyLayout
import com.mapo.data.model.Profile
import com.mapo.data.model.TemplateRef
import com.mapo.data.model.steam.ControllerConfig
import com.mapo.data.repository.AppProfileBindingRepository
import com.mapo.data.repository.ControllerConfigRepository
import com.mapo.data.repository.InstalledAppsRepository
import com.mapo.data.repository.KeyboardTemplateRepository
import com.mapo.data.repository.LayoutRepository
import com.mapo.data.repository.ProfileRepository
import com.mapo.data.settings.AnalogModePreferences
import com.mapo.data.settings.AutoSwitchSettings
import com.mapo.service.autoswitch.ProfileAutoSwitcher
import com.mapo.service.foreground.ForegroundAppFilter
import com.mapo.service.input.InputDispatcher
import com.mapo.service.input.MotionProbeAppOverlay
import com.mapo.service.keyboard.KeyboardController
import com.mapo.service.overlay.keyboard.KeyboardOverlayPresenter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Brick 2 coverage: `bindAppsToProfile` (multi-package atomic bind) and
 * `loadInstalledApps` (one-shot PackageManager pass into the picker sheet's
 * state flow). The repo and PM are mocked; this test only checks that the
 * VM glue forwards correctly and skips no-op inputs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelMultiBindTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var layoutRepo: LayoutRepository
    private lateinit var profileRepo: ProfileRepository
    private lateinit var controllerConfigRepo: ControllerConfigRepository
    private lateinit var bindingRepo: AppProfileBindingRepository
    private lateinit var installedAppsRepo: InstalledAppsRepository
    private lateinit var settings: AutoSwitchSettings
    private lateinit var analogModePrefs: AnalogModePreferences
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
        analogModePrefs = mockk(relaxed = true)
        autoSwitcher = mockk(relaxed = true)
        filter = mockk(relaxed = true)
        templateRepo = mockk(relaxed = true)
        inputDispatcher = mockk(relaxed = true)
        keyboardOverlayPresenter = mockk(relaxed = true)
        motionProbeAppOverlay = mockk(relaxed = true)
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
        every { controllerConfigRepo.observeActiveConfig(any()) } returns
            MutableStateFlow<ControllerConfig?>(null)

        subject = MainViewModel(
            layoutRepository = layoutRepo,
            profileRepository = profileRepo,
            controllerConfigRepository = controllerConfigRepo,
            appProfileBindingRepository = bindingRepo,
            installedAppsRepository = installedAppsRepo,
            autoSwitchSettings = settings,
            analogModePreferences = analogModePrefs,
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

    @Test
    fun bindAppsToProfile_forwardsPackagesToRepository() = runTest(testDispatcher) {
        val packages = setOf("com.example.game", "com.example.launcher", "org.foo.bar")
        subject.bindAppsToProfile(profileId = 42L, packages = packages)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            bindingRepo.bindMany(profileId = 42L, packageNames = packages)
        }
    }

    @Test
    fun bindAppsToProfile_emptySet_isNoop() = runTest(testDispatcher) {
        subject.bindAppsToProfile(profileId = 42L, packages = emptySet())
        advanceUntilIdle()

        // No repo call — empty set is a UX safety net (button is disabled
        // upstream, but the VM shouldn't trust the UI).
        coVerify(exactly = 0) { bindingRepo.bindMany(any(), any()) }
    }

    @Test
    fun loadInstalledApps_populatesStateFlowFromRepository() = runTest(testDispatcher) {
        val apps = listOf(
            InstalledAppsRepository.InstalledApp("com.a", "Alpha"),
            InstalledAppsRepository.InstalledApp("com.b", "Beta"),
        )
        coEvery { installedAppsRepo.launchableApps() } returns apps

        assertTrue(
            "installedApps starts empty before load",
            subject.installedApps.value.isEmpty(),
        )

        subject.loadInstalledApps()
        advanceUntilIdle()

        assertEquals(apps, subject.installedApps.value)
    }
}
