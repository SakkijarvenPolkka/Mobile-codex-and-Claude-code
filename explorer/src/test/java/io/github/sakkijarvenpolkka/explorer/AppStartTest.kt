// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer

import android.Manifest
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

/** The app process and its single activity come up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppStartTest {

    /** A device always has the primary external storage directory, which the all files access check reads. */
    @Before
    fun setUp() {
        ShadowEnvironment.addExternalDir("emulated/0")
    }

    @Test
    fun activityStarts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun firstOperationAsksForNotificationsOnce() {
        val app = ApplicationProvider.getApplicationContext<ExplorerApp>()
        fun submit(id: String) {
            app.runner.submit(OperationSpec(id, OpKind.COPY, listOf("/nowhere/a.txt"), "/nowhere"))
            shadowOf(Looper.getMainLooper()).idle()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                // Not before there is an operation to tell of.
                assertNull(shadowOf(activity).lastRequestedPermission)
                submit("n1")
                val asked = shadowOf(activity).lastRequestedPermission?.requestedPermissions?.toList()
                assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), asked)
            }
        }
        // Denied (nothing granted it): the next activity does not ask again.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                submit("n2")
                assertNull(shadowOf(activity).lastRequestedPermission)
            }
        }
    }
}
