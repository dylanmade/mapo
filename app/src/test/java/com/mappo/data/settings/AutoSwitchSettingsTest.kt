package com.mappo.data.settings

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
        context.getSharedPreferences("mappo_settings", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        settings = AutoSwitchSettings(context)
    }

    /**
     * Construct settings with the launcher seed already marked complete + ignored set
     * cleared, so add/remove tests can assert against small focused sets without the
     * 20-entry default seed muddying the comparison.
     */
    private fun freshUnseededSettings(): AutoSwitchSettings {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("mappo_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean("blocklist_seeded_v1", true)
            .commit()
        return AutoSwitchSettings(context)
    }

    @Test
    fun defaults_autoSwitchAndAutoCreateOn_blocklistSeededWithLaunchers() {
        assertTrue(settings.autoSwitchEnabled.value)
        assertTrue(settings.autoCreateProfilesEnabled.value)
        // Seed includes AOSP launcher3 and Pixel Launcher; spot-check both.
        assertTrue(
            "com.android.launcher3 should be seeded into the blocklist",
            "com.android.launcher3" in settings.ignoredPackages.value,
        )
        assertTrue(
            "Pixel Launcher should be seeded into the blocklist",
            "com.google.android.apps.nexuslauncher" in settings.ignoredPackages.value,
        )
    }

    @Test
    fun seedRunsOnce_userRemovedLauncher_isNotResurrected() {
        // Remove a seeded entry through normal API.
        settings.removeIgnoredPackage("com.android.launcher3")
        assertFalse("com.android.launcher3" in settings.ignoredPackages.value)

        // A fresh AutoSwitchSettings instance reading the same prefs must NOT re-add it
        // (seed flag is sticky once set on first launch).
        val reread = AutoSwitchSettings(ApplicationProvider.getApplicationContext())
        assertFalse(
            "removed launcher should not be re-seeded by a later instance",
            "com.android.launcher3" in reread.ignoredPackages.value,
        )
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
        val s = freshUnseededSettings()
        s.addIgnoredPackage("com.example.foo")
        assertEquals(setOf("com.example.foo"), s.ignoredPackages.value)

        s.addIgnoredPackage("com.example.bar")
        assertEquals(
            setOf("com.example.foo", "com.example.bar"),
            s.ignoredPackages.value,
        )

        val reread = AutoSwitchSettings(ApplicationProvider.getApplicationContext())
        assertEquals(
            setOf("com.example.foo", "com.example.bar"),
            reread.ignoredPackages.value,
        )
    }

    @Test
    fun addIgnoredPackage_isIdempotent() {
        val s = freshUnseededSettings()
        s.addIgnoredPackage("com.example.foo")
        s.addIgnoredPackage("com.example.foo")
        assertEquals(setOf("com.example.foo"), s.ignoredPackages.value)
    }

    @Test
    fun removeIgnoredPackage_removesAndPersists() {
        val s = freshUnseededSettings()
        s.addIgnoredPackage("com.example.foo")
        s.addIgnoredPackage("com.example.bar")

        s.removeIgnoredPackage("com.example.foo")
        assertEquals(setOf("com.example.bar"), s.ignoredPackages.value)
    }

    @Test
    fun removeIgnoredPackage_missingPackage_isNoOp() {
        val s = freshUnseededSettings()
        s.addIgnoredPackage("com.example.foo")
        s.removeIgnoredPackage("com.example.never.added")
        assertEquals(setOf("com.example.foo"), s.ignoredPackages.value)
    }
}
