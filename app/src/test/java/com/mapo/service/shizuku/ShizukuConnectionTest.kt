package com.mapo.service.shizuku

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.Shizuku

/**
 * Robolectric is required because [ShizukuConnection]'s listeners are typed against
 * `rikka.shizuku.Shizuku.OnBinderReceivedListener` etc. — declared in the Shizuku-API
 * jar. The jar requires real classloading even though we never hit live Shizuku in
 * tests (the facade is mocked, so Shizuku's static methods are never invoked).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShizukuConnectionTest {

    // Unconfined dispatcher so the `stateIn`-derived [ShizukuConnection.isReadyFlow]
    // collector runs inline on each `state` emission. The `while (true) { delay(2_000) }`
    // poll loop in the connection's init block stays suspended at the first `delay`
    // because we don't advance virtual time.
    private val testScope: TestScope = TestScope(UnconfinedTestDispatcher())

    @After
    fun teardown() {
        (testScope as CoroutineScope).cancel()
    }

    private fun fakeFacade(
        installed: Boolean = false,
        binderAlive: Boolean = false,
        granted: Boolean = false,
    ): ShizukuFacade {
        val facade = mockk<ShizukuFacade>(relaxed = true)
        every { facade.isShizukuPackageInstalled() } returns installed
        every { facade.isBinderAlive() } returns binderAlive
        every { facade.isPermissionGranted() } returns granted
        return facade
    }

    @Test
    fun initialState_notInstalled_whenShizukuMissing() {
        val facade = fakeFacade(installed = false)
        val connection = ShizukuConnection(facade, testScope)
        assertEquals(ShizukuState.NotInstalled, connection.state.value)
        assertFalse(connection.isReadyFlow.value)
    }

    @Test
    fun initialState_installedNotRunning_whenBinderDead() {
        val facade = fakeFacade(installed = true, binderAlive = false)
        val connection = ShizukuConnection(facade, testScope)
        assertEquals(ShizukuState.InstalledNotRunning, connection.state.value)
    }

    @Test
    fun initialState_runningNotGranted_whenBinderAliveButNoPermission() {
        val facade = fakeFacade(installed = true, binderAlive = true, granted = false)
        val connection = ShizukuConnection(facade, testScope)
        assertEquals(ShizukuState.RunningNotGranted, connection.state.value)
    }

    @Test
    fun initialState_granted_whenAllChecksPass() {
        val facade = fakeFacade(installed = true, binderAlive = true, granted = true)
        val connection = ShizukuConnection(facade, testScope)
        assertEquals(ShizukuState.Granted, connection.state.value)
    }

    @Test
    fun refresh_picksUpFacadeStateChange_notInstalledToGranted() {
        val facade = fakeFacade(installed = false)
        val connection = ShizukuConnection(facade, testScope)
        assertEquals(ShizukuState.NotInstalled, connection.state.value)

        // User installs + starts Shizuku + grants permission. Facade flips its returns.
        every { facade.isShizukuPackageInstalled() } returns true
        every { facade.isBinderAlive() } returns true
        every { facade.isPermissionGranted() } returns true

        connection.refresh()
        assertEquals(ShizukuState.Granted, connection.state.value)
    }

    @Test
    fun listenersAreRegisteredOnConstruction() {
        // Reconnect-loop contract: even if `refresh()` is never called explicitly, the
        // listeners must be live so Shizuku binder lifecycle events flip our state.
        // We verify the facade was asked to register all three.
        val facade = fakeFacade()
        ShizukuConnection(facade, testScope)
        verify(exactly = 1) { facade.addBinderReceivedListener(any<Shizuku.OnBinderReceivedListener>()) }
        verify(exactly = 1) { facade.addBinderDeadListener(any<Shizuku.OnBinderDeadListener>()) }
        verify(exactly = 1) { facade.addPermissionResultListener(any<Shizuku.OnRequestPermissionResultListener>()) }
    }

    @Test
    fun isReadyFlow_tracksGrantedState() {
        val facade = fakeFacade(installed = true, binderAlive = true, granted = true)
        val connection = ShizukuConnection(facade, testScope)
        assertTrue(connection.isReadyFlow.value)

        // User revokes via Shizuku Manager.
        every { facade.isPermissionGranted() } returns false
        connection.refresh()
        assertFalse(connection.isReadyFlow.value)
    }

    @Test
    fun requestPermission_delegatesToFacade() {
        val facade = fakeFacade()
        val connection = ShizukuConnection(facade, testScope)
        connection.requestPermission(42)
        verify(exactly = 1) { facade.requestPermission(42) }
    }

    @Test
    fun requestPermission_defaultRequestCode_isStable() {
        // Stability matters: Shizuku's permission listener fires with the SAME
        // request code we passed; if the constant moves, we silently lose track.
        assertEquals(17_031, ShizukuConnection.DEFAULT_REQUEST_CODE)
    }

    // ── Brick B: UserService bind/unbind ─────────────────────────────────────

    @Test
    fun service_isNullInitially_whenNotGranted() {
        val facade = fakeFacade()
        val connection = ShizukuConnection(facade, testScope)
        // No bind happens unless the state machine reaches Granted.
        assertNull(connection.service.value)
    }

    @Test
    fun bindUserService_calledOnceWhenStateBecomesGranted() {
        val facade = fakeFacade()
        // userServiceArgsForMapoInput is non-relaxed-friendly (it returns a
        // Shizuku.UserServiceArgs which is final / hard to construct). For test
        // purposes we just need the facade to return a non-null sentinel so the
        // bind path actually invokes facade.bindUserService.
        val sentinelArgs = mockk<Shizuku.UserServiceArgs>(relaxed = true)
        every { facade.userServiceArgsForMapoInput() } returns sentinelArgs

        val connection = ShizukuConnection(facade, testScope)
        // Move state to Granted; the state.collect launched in init kicks bindService().
        every { facade.isShizukuPackageInstalled() } returns true
        every { facade.isBinderAlive() } returns true
        every { facade.isPermissionGranted() } returns true
        connection.refresh()

        verify(atLeast = 1) { facade.bindUserService(sentinelArgs, any()) }
    }

    @Test
    fun unbindUserService_calledWhenStateLeavesGranted() {
        val facade = fakeFacade(installed = true, binderAlive = true, granted = true)
        val sentinelArgs = mockk<Shizuku.UserServiceArgs>(relaxed = true)
        every { facade.userServiceArgsForMapoInput() } returns sentinelArgs

        val connection = ShizukuConnection(facade, testScope)
        // We started Granted, so bind happened during init.
        verify(atLeast = 1) { facade.bindUserService(sentinelArgs, any()) }

        // User revokes permission via Shizuku Manager.
        every { facade.isPermissionGranted() } returns false
        connection.refresh()

        verify(atLeast = 1) { facade.unbindUserService(sentinelArgs, any(), remove = true) }
    }

    @Test
    fun bindUserService_skipped_whenArgsBuilderThrows() {
        // Defensive path: if facade.userServiceArgsForMapoInput() can't build
        // args (e.g. Shizuku class somehow unloadable at runtime), we must NOT
        // call bind with corrupted args. Connection's `runCatching` should
        // swallow the exception and the bind path should no-op.
        val facade = fakeFacade(installed = true, binderAlive = true, granted = true)
        every { facade.userServiceArgsForMapoInput() } throws
            RuntimeException("simulated Shizuku-class unloadable")

        val connection = ShizukuConnection(facade, testScope)
        verify(exactly = 0) { facade.bindUserService(any(), any()) }
        assertNull(connection.service.value)
    }
}
