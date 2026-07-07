@file:OptIn(ExperimentalCoroutinesApi::class)

package com.mappo.service.shizuku

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Brick G coverage for the show/hide decision driving
 * [ShizukuHealthNotification]. The pure helper `shouldShow(wanted, ready)`
 * is the entirety of the notification's logic — the post/cancel side is a
 * thin wrapper over `NotificationManager` which Robolectric's shadow already
 * covers for "did the channel get registered" but offers diminishing returns
 * for the visual-state assertions we'd want.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShizukuHealthNotificationTest {

    private val testScope: TestScope = TestScope(UnconfinedTestDispatcher())

    @After
    fun teardown() {
        (testScope as CoroutineScope).cancel()
    }

    private fun makeNotif(): ShizukuHealthNotification = ShizukuHealthNotification(
        context = mockk<Context>(relaxed = true),
        shizukuConnection = mockk<ShizukuConnection>(relaxed = true),
        coordinator = mockk<ShizukuMotionCoordinator>(relaxed = true),
        applicationScope = testScope,
    )

    @Test
    fun shouldShow_true_whenWantedAndShizukuNotReady() {
        // The whole point of this notification — analog binding configured
        // for the foreground app, Shizuku can't service it. Surface a fix.
        val notif = makeNotif()
        assertTrue(notif.shouldShow(wanted = true, shizukuReady = false))
    }

    @Test
    fun shouldShow_false_whenShizukuReady() {
        // Shizuku is healthy — analog modes work; nothing to nag about.
        val notif = makeNotif()
        assertFalse(notif.shouldShow(wanted = true, shizukuReady = true))
    }

    @Test
    fun shouldShow_false_whenNotWanted() {
        // No analog binding is in scope for the foreground app (digital-only
        // profile, or foreground app isn't bound). Shizuku readiness is
        // irrelevant — don't nag.
        val notif = makeNotif()
        assertFalse(notif.shouldShow(wanted = false, shizukuReady = false))
        assertFalse(notif.shouldShow(wanted = false, shizukuReady = true))
    }
}
