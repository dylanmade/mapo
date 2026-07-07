package com.mappo.service.foreground

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric covers the Context-bound parts: static exclusions, blank-package
 * rejection, and appLabel's PackageManager fallback. The launcher- and IME-
 * resolution branches need shadow setup that's not worth the depth here —
 * those code paths can be exercised in instrumented tests if/when needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ForegroundAppFilterTest {

    private lateinit var subject: ForegroundAppFilter

    @Before
    fun setUp() {
        subject = ForegroundAppFilter(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun blankPackage_isNotInteresting() {
        assertFalse(subject.isInteresting(""))
        assertFalse(subject.isInteresting("   "))
    }

    @Test
    fun staticExclusions_areNotInteresting() {
        assertFalse(subject.isInteresting("android"))
        assertFalse(subject.isInteresting("com.android.systemui"))
        assertFalse(subject.isInteresting("com.android.systemui.recents"))
    }

    @Test
    fun ownPackage_isNotInteresting() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse(subject.isInteresting(ctx.packageName))
    }

    @Test
    fun arbitraryThirdPartyPackage_isInteresting() {
        // No launcher/IME shadows configured → only the static exclusions apply.
        assertTrue(subject.isInteresting("com.example.somegame"))
    }

    @Test
    fun appLabel_unknownPackage_returnsPackageAsFallback() {
        assertEquals("com.unknown.foo", subject.appLabel("com.unknown.foo"))
    }

    @Test
    fun appLabel_installedPackage_returnsApplicationLabel() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = ctx.packageManager
        val packageInfo = PackageInfo().apply {
            packageName = "com.example.real"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.example.real"
                name = "Real App"
                nonLocalizedLabel = "Real App"
            }
        }
        shadowOf(pm).installPackage(packageInfo)

        assertEquals("Real App", subject.appLabel("com.example.real"))
    }
}
