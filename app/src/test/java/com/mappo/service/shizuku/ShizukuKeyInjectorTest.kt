@file:OptIn(ExperimentalCoroutinesApi::class)

package com.mappo.service.shizuku

import android.os.RemoteException
import com.mappo.shizuku.IMappoInputService
import com.mappo.shizuku.InjectKeyRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Brick E gate coverage. The three plan cases — Shizuku-ready → uses Shizuku
 * path, RemoteException → falls back, Shizuku-not-ready → uses reflection — are
 * asserted via [ShizukuKeyInjector.tryInject]'s return value (true = Shizuku
 * route taken / don't fall back; false = caller runs the reflection fallback).
 *
 * Robolectric is required because the chained mocked StateFlows surface
 * `rikka.shizuku.Shizuku.*` types transitively through [ShizukuConnection]'s
 * declared properties.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShizukuKeyInjectorTest {

    private fun makeInjector(
        ready: Boolean = true,
        modeActive: Boolean = true,
        service: IMappoInputService? = mockk(relaxed = true),
    ): Pair<ShizukuKeyInjector, IMappoInputService?> {
        val connection = mockk<ShizukuConnection>(relaxed = true)
        val readyFlow: StateFlow<Boolean> = MutableStateFlow(ready)
        val serviceFlow: StateFlow<IMappoInputService?> = MutableStateFlow(service)
        every { connection.isReadyFlow } returns readyFlow
        every { connection.service } returns serviceFlow

        val coordinator = mockk<ShizukuMotionCoordinator>(relaxed = true)
        val modeFlow: StateFlow<Boolean> = MutableStateFlow(modeActive)
        every { coordinator.shizukuModeActive } returns modeFlow

        return ShizukuKeyInjector(connection, coordinator) to service
    }

    @Test
    fun tryInject_returnsFalse_whenShizukuNotReady() {
        // Shizuku not granted / not installed → caller falls back to reflection.
        val (injector, service) = makeInjector(ready = false)
        val taken = injector.tryInject(keyCode = 4, action = 0, displayId = 0, eventTime = 0L)
        assertFalse(taken)
        verify(exactly = 0) { service?.injectKeyEvent(any()) }
    }

    @Test
    fun tryInject_returnsFalse_whenModeNotActive() {
        // Shizuku ready but no analog mode active for the foreground app → fall
        // back to reflection. Keeps the no-analog-mode case bit-identical to
        // the pre-pivot inject behavior.
        val (injector, service) = makeInjector(ready = true, modeActive = false)
        val taken = injector.tryInject(keyCode = 4, action = 0, displayId = 0, eventTime = 0L)
        assertFalse(taken)
        verify(exactly = 0) { service?.injectKeyEvent(any()) }
    }

    @Test
    fun tryInject_returnsFalse_whenServiceBinderNull() {
        // State machine says Granted but the binder hasn't reconnected yet —
        // shouldn't crash; caller falls back to reflection.
        val (injector, _) = makeInjector(service = null)
        val taken = injector.tryInject(keyCode = 4, action = 0, displayId = 0, eventTime = 0L)
        assertFalse(taken)
    }

    @Test
    fun tryInject_returnsTrue_andCallsService_whenGated() {
        val (injector, service) = makeInjector()
        val taken = injector.tryInject(keyCode = 4, action = 0, displayId = 0, eventTime = 0L)
        assertTrue(taken)
        verify(exactly = 1) { service!!.injectKeyEvent(any()) }
    }

    @Test
    fun tryInject_passesFieldsThroughToInjectKeyRequest() {
        val service = mockk<IMappoInputService>(relaxed = true)
        val (injector, _) = makeInjector(service = service)
        val captured = slot<InjectKeyRequest>()
        every { service.injectKeyEvent(capture(captured)) } returns true

        injector.tryInject(
            keyCode = 111,
            action = 1,
            displayId = 4, // AYN Thor bottom screen — load-bearing for that route
            eventTime = 1_234_567L,
        )

        assertEquals(111, captured.captured.keyCode)
        assertEquals(1, captured.captured.action)
        assertEquals(4, captured.captured.displayId)
        assertEquals(1_234_567L, captured.captured.eventTime)
    }

    @Test
    fun tryInject_returnsFalse_onRemoteException() {
        // Shizuku service died mid-call. Caller's reflection fallback runs so
        // the digital remap surface survives a Shizuku flap mid-game.
        val service = mockk<IMappoInputService>(relaxed = true)
        every { service.injectKeyEvent(any()) } throws RemoteException("simulated death")
        val (injector, _) = makeInjector(service = service)
        val taken = injector.tryInject(keyCode = 4, action = 0, displayId = 0, eventTime = 0L)
        assertFalse(taken)
    }

    @Test
    fun tryInject_returnsTrue_evenWhenServiceReturnsFalse() {
        // Service-side success boolean is informational only: the path was
        // taken, the reflection fallback in :app would hit the SAME focus
        // problem, so the caller must NOT fall back.
        val service = mockk<IMappoInputService>(relaxed = true)
        every { service.injectKeyEvent(any()) } returns false
        val (injector, _) = makeInjector(service = service)
        val taken = injector.tryInject(keyCode = 4, action = 0, displayId = 0, eventTime = 0L)
        assertTrue(taken)
    }
}
