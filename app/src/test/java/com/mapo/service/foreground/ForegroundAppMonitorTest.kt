package com.mapo.service.foreground

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundAppMonitorTest {

    @Test
    fun initialValue_isNull() {
        val monitor = ForegroundAppMonitor()
        assertNull(monitor.currentPackage.value)
    }

    @Test
    fun reportForegroundPackage_updatesFlow() = runTest {
        val monitor = ForegroundAppMonitor()
        monitor.reportForegroundPackage("com.example.foo")
        assertEquals("com.example.foo", monitor.currentPackage.value)
    }

    @Test
    fun blankOrNull_isIgnored() {
        val monitor = ForegroundAppMonitor()
        monitor.reportForegroundPackage(null)
        monitor.reportForegroundPackage("")
        monitor.reportForegroundPackage("   ")
        assertNull(monitor.currentPackage.value)
    }

    @Test
    fun repeatedReports_dedupedAtStateFlow() = runTest {
        val monitor = ForegroundAppMonitor()

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
