package com.mappo.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI smoke check. Runs on JVM via Robolectric — on-device runs on the
 * AYN Thor immediately background test-launched activities (the launcher on
 * display 0 holds focus, our ComponentActivity gets RESUMED then PAUSED+STOPPED
 * within ~17ms before setContent can register a Compose hierarchy). Robolectric
 * sidesteps the device entirely and runs the composition through shadow classes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ComposeSmokeTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersText_andFindsItBySemantics() {
        composeRule.setContent {
            Text("smoke-test-marker")
        }
        composeRule.onNodeWithText("smoke-test-marker").assertIsDisplayed()
    }
}
