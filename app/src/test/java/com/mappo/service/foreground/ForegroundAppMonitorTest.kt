package com.mappo.service.foreground

import android.content.Context
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundAppMonitorTest {

    private fun newMonitor(ownPackage: String = "com.mappo"): ForegroundAppMonitor {
        val context = mockk<Context>()
        every { context.packageName } returns ownPackage
        return ForegroundAppMonitor(context)
    }

    @Test
    fun initialValue_isNull() {
        val monitor = newMonitor()
        assertNull(monitor.currentPackage.value)
    }

    @Test
    fun reportForegroundPackage_updatesFlow() = runTest {
        val monitor = newMonitor()
        monitor.reportForegroundPackage("com.example.foo")
        assertEquals("com.example.foo", monitor.currentPackage.value)
    }

    @Test
    fun blankOrNull_isIgnored() {
        val monitor = newMonitor()
        monitor.reportForegroundPackage(null)
        monitor.reportForegroundPackage("")
        monitor.reportForegroundPackage("   ")
        assertNull(monitor.currentPackage.value)
    }

    @Test
    fun ownPackage_isIgnored() {
        val monitor = newMonitor(ownPackage = "com.mappo")

        monitor.reportForegroundPackage("com.example.foo")
        assertEquals("com.example.foo", monitor.currentPackage.value)

        // Mappo coming to foreground (e.g. user reopens it on the bottom screen) must
        // not overwrite the cached game pkg — that cache feeds the resume-time
        // auto-switch re-evaluation.
        monitor.reportForegroundPackage("com.mappo")
        assertEquals("com.example.foo", monitor.currentPackage.value)
    }

    @Test
    fun repeatedReports_dedupedAtStateFlow() = runTest {
        val monitor = newMonitor()

        monitor.currentPackage.test {
            assertNull(awaitItem()) // initial null
            monitor.reportForegroundPackage("com.example.foo")
            assertEquals("com.example.foo", awaitItem())

            // Reporting the same package again should not produce a new emission.
            monitor.reportForegroundPackage("com.example.foo")
            expectNoEvents()

            monitor.reportForegroundPackage("com.example.bar")
            assertEquals("com.example.bar", awaitItem())
        }
    }
}
