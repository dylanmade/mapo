package com.mappo

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Custom test runner that swaps the production [MappoApplication] (which is
 * `@HiltAndroidApp` and runs autoswitch/overlay services on create) for
 * [HiltTestApplication]. Wired via `defaultConfig.testInstrumentationRunner`.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        name: String?,
        ctx: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, ctx)
}
