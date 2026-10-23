// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app process and its single activity come up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppStartTest {

    @Test
    fun activityStarts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
