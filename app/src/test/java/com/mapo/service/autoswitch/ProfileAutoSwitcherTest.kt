package com.mapo.service.autoswitch

import app.cash.turbine.test
import com.mapo.data.model.Profile
import com.mapo.data.repository.AppProfileBindingRepository
import com.mapo.data.repository.ProfileRepository
import com.mapo.data.settings.AutoSwitchSettings
import com.mapo.service.foreground.ForegroundAppFilter
import com.mapo.service.foreground.ForegroundAppMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives [ProfileAutoSwitcher.handleForegroundChange] directly with mocked
 * collaborators. The dispatcher-bound start() collector isn't exercised here —
 * its only logic is `filterNotNull().distinctUntilChanged().collect(::handleForegroundChange)`,
 * which is trivial. Branch coverage of handleForegroundChange is the goal.
 */
class ProfileAutoSwitcherTest {

    private val foregroundAppMonitor = ForegroundAppMonitor()
    private lateinit var bindingRepo: AppProfileBindingRepository
    private lateinit var profileRepo: ProfileRepository
    private lateinit var settings: AutoSwitchSettings
    private lateinit var filter: ForegroundAppFilter
    private lateinit var subject: ProfileAutoSwitcher

    private val autoSwitchEnabled = MutableStateFlow(true)
    private val autoCreateEnabled = MutableStateFlow(false)
    private val ignoredPackages = MutableStateFlow<Set<String>>(emptySet())
    private val activeProfile = MutableStateFlow<Profile?>(
        Profile(id = 1L, name = "Default", isDefault = true),
    )

    @Before
    fun setUp() {
        bindingRepo = mockk(relaxed = true)
        profileRepo = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        filter = mockk(relaxed = true)

        every { settings.autoSwitchEnabled } returns autoSwitchEnabled
        every { settings.autoCreateProfilesEnabled } returns autoCreateEnabled
        every { settings.ignoredPackages } returns ignoredPackages
        every { profileRepo.activeProfile } returns activeProfile
        every { filter.isInteresting(any()) } returns true
        every { filter.appLabel(any()) } answers { firstArg<String>().substringAfterLast('.') }

        coEvery { bindingRepo.getForPackageOnce(any(), any()) } returns null

        subject = ProfileAutoSwitcher(
            foregroundAppMonitor = foregroundAppMonitor,
            bindingRepo = bindingRepo,
            profileRepo = profileRepo,
            settings = settings,
            filter = filter,
            scope = TestScope(),
        )
    }

    @Test
    fun emitsNothing_whenAutoSwitchDisabled() = runTest {
        autoSwitchEnabled.value = false

        subject.events.test {
            subject.handleForegroundChange("com.example.game")
            expectNoEvents()
        }
    }

    @Test
    fun emitsNothing_whenFilterRejectsPackage() = runTest {
        every { filter.isInteresting("com.android.systemui") } returns false

        subject.events.test {
            subject.handleForegroundChange("com.android.systemui")
            expectNoEvents()
        }
    }

    @Test
    fun emitsNothing_whenPackageIgnored_andNoBinding() = runTest {
        ignoredPackages.value = setOf("com.example.ignored")

        subject.events.test {
            subject.handleForegroundChange("com.example.ignored")
            expectNoEvents()
        }
    }

    @Test
    fun emitsNothing_whenBindingMatchesActiveProfile() = runTest {
        coEvery { bindingRepo.getForPackageOnce("com.example.game", any()) } returns
            com.mapo.data.model.AppProfileBinding(
                packageName = "com.example.game",
                profileId = 1L,
            )

        subject.events.test {
            subject.handleForegroundChange("com.example.game")
            expectNoEvents()
        }
    }

    @Test
    fun emitsSwitched_whenBindingPointsToDifferentProfile() = runTest {
        val gameProfile = Profile(id = 7L, name = "Racing", isDefault = false)
        coEvery { bindingRepo.getForPackageOnce("com.example.game", any()) } returns
            com.mapo.data.model.AppProfileBinding(
                packageName = "com.example.game",
                profileId = 7L,
            )
        coEvery { profileRepo.setActiveProfileById(7L) } returns gameProfile

        subject.events.test {
            subject.handleForegroundChange("com.example.game")
            val event = awaitItem()
            assertEquals(
                ProfileAutoSwitcher.UiEvent.Switched(
                    pkg = "com.example.game",
                    appLabel = "game",
                    profileName = "Racing",
                ),
                event,
            )
        }
    }

    @Test
    fun emitsNothing_whenBindingReferencesMissingProfile() = runTest {
        coEvery { bindingRepo.getForPackageOnce("com.example.game", any()) } returns
            com.mapo.data.model.AppProfileBinding(
                packageName = "com.example.game",
                profileId = 999L,
            )
        coEvery { profileRepo.setActiveProfileById(999L) } returns null

        subject.events.test {
            subject.handleForegroundChange("com.example.game")
            expectNoEvents()
        }
    }

    @Test
    fun autoCreatesProfile_whenAutoCreateEnabled_andNoBinding() = runTest {
        autoCreateEnabled.value = true
        val newId = 42L
        coEvery { profileRepo.addProfile("game") } returns newId
        coEvery { profileRepo.setActiveProfileById(newId) } returns
            Profile(id = newId, name = "game", isDefault = false)

        subject.events.test {
            subject.handleForegroundChange("com.example.game")
            assertEquals(
                ProfileAutoSwitcher.UiEvent.Switched(
                    pkg = "com.example.game",
                    appLabel = "game",
                    profileName = "game",
                ),
                awaitItem(),
            )
        }
        coVerify { bindingRepo.bind(packageName = "com.example.game", profileId = newId) }
    }

    @Test
    fun emitsPromptCreate_whenNoBinding_andAutoCreateOff() = runTest {
        autoCreateEnabled.value = false

        subject.events.test {
            subject.handleForegroundChange("com.example.game")
            assertEquals(
                ProfileAutoSwitcher.UiEvent.PromptCreate(
                    pkg = "com.example.game",
                    appLabel = "game",
                ),
                awaitItem(),
            )
        }
    }

    @Test
    fun throttlesSecondPromptForSamePackage() = runTest {
        autoCreateEnabled.value = false

        subject.events.test {
            subject.handleForegroundChange("com.example.game")
            awaitItem() // first PromptCreate
            subject.handleForegroundChange("com.example.game")
            expectNoEvents() // second within 60s throttled
        }
    }

    @Test
    fun doesNotThrottle_differentPackages() = runTest {
        autoCreateEnabled.value = false

        subject.events.test {
            subject.handleForegroundChange("com.example.alpha")
            assertTrue(awaitItem() is ProfileAutoSwitcher.UiEvent.PromptCreate)
            subject.handleForegroundChange("com.example.beta")
            assertTrue(awaitItem() is ProfileAutoSwitcher.UiEvent.PromptCreate)
        }
    }

    @Test
    fun createProfileAndBind_addsProfileAndBindsAndSwitches() = runTest {
        val newId = 99L
        coEvery { profileRepo.addProfile("Cool App") } returns newId
        val pkgSlot = slot<String>()
        val idSlot = slot<Long>()
        coEvery {
            bindingRepo.bind(packageName = capture(pkgSlot), profileId = capture(idSlot))
        } returns Unit
        coEvery { profileRepo.setActiveProfileById(newId) } returns
            Profile(id = newId, name = "Cool App", isDefault = false)

        subject.createProfileAndBind(pkg = "com.example.cool", appLabel = "Cool App")

        coVerify { profileRepo.addProfile("Cool App") }
        assertEquals("com.example.cool", pkgSlot.captured)
        assertEquals(newId, idSlot.captured)
        coVerify { profileRepo.setActiveProfileById(newId) }
    }

    @Test
    fun ignorePackage_delegatesToSettings() {
        subject.ignorePackage("com.example.ignored")
        io.mockk.verify { settings.addIgnoredPackage("com.example.ignored") }
    }
}
