package com.mapo.data.settings

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SharedPreferences-backed settings; tested via Robolectric so we get a real
 * (in-memory) prefs implementation rather than mocking it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutoSwitchSettingsTest {

    private lateinit var settings: AutoSwitchSettings

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Wipe any leftover prefs so each test starts clean.
        context.getSharedPreferences("mapo_settings", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        settings = AutoSwitchSettings(context)
    }

    @Test
    fun defaults_autoSwitchAndAutoCreateOn_ignoredEmpty() {
        assertTrue(settings.autoSwitchEnabled.value)
        assertTrue(settings.autoCreateProfilesEnabled.value)
        assertEquals(emptySet<String>(), settings.ignoredPackages.value)
    }

    @Test
    fun setAutoSwitchEnabled_persistsAndUpdatesFlow() {
        settings.setAutoSwitchEnabled(false)
        assertFalse(settings.autoSwitchEnabled.value)

        // New instance reading the same prefs should see the persisted value.
        val reread = AutoSwitchSettings(ApplicationProvider.getApplicationContext())
        assertFalse(reread.autoSwitchEnabled.value)
    }

    @Test
    fun setAutoCreateProfilesEnabled_persistsAndUpdatesFlow() {
        settings.setAutoCreateProfilesEnabled(false)
        assertFalse(settings.autoCreateProfilesEnabled.value)

        val reread = AutoSwitchSettings(ApplicationProvider.getApplicationContext())
        assertFalse(reread.autoCreateProfilesEnabled.value)
    }

    @Test
    fun addIgnoredPackage_addsToFlowAndPersists() {
        settings.addIgnoredPackage("com.example.foo")
        assertEquals(setOf("com.example.foo"), settings.ignoredPackages.value)

        settings.addIgnoredPackage("com.example.bar")
        assertEquals(
            setOf("com.example.foo", "com.example.bar"),
            settings.ignoredPackages.value,
        )

        val reread = AutoSwitchSettings(ApplicationProvider.getApplicationContext())
        assertEquals(
            setOf("com.example.foo", "com.example.bar"),
            reread.ignoredPackages.value,
        )
    }

    @Test
    fun addIgnoredPackage_isIdempotent() {
        settings.addIgnoredPackage("com.example.foo")
        settings.addIgnoredPackage("com.example.foo")
        assertEquals(setOf("com.example.foo"), settings.ignoredPackages.value)
    }

    @Test
    fun removeIgnoredPackage_removesAndPersists() {
        settings.addIgnoredPackage("com.example.foo")
        settings.addIgnoredPackage("com.example.bar")

        settings.removeIgnoredPackage("com.example.foo")
        assertEquals(setOf("com.example.bar"), settings.ignoredPackages.value)
    }

    @Test
    fun removeIgnoredPackage_missingPackage_isNoOp() {
        settings.addIgnoredPackage("com.example.foo")
        settings.removeIgnoredPackage("com.example.never.added")
        assertEquals(setOf("com.example.foo"), settings.ignoredPackages.value)
    }
}
