// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ui

import android.app.AppOpsManager
import android.content.Context
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.MainActivity
import io.github.sakkijarvenpolkka.explorer.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

/**
 * Without all files access the activity shows only the permission screen, and the shell once
 * the access is granted and the app comes back. Robolectric has no switch for
 * `isExternalStorageManager()`; it follows the app op, as on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PermissionScreenTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ShadowEnvironment.addExternalDir("emulated/0")
    }

    private fun allowAllFilesAccess(allowed: Boolean) {
        val mode = if (allowed) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_ERRORED
        shadowOf(context.getSystemService(AppOpsManager::class.java))
            .setMode(MANAGE_EXTERNAL_STORAGE_OP, context.applicationInfo.uid, context.packageName, mode)
    }

    @Test
    fun deniedShowsPermissionScreenThatOpensSettings() {
        allowAllFilesAccess(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            rule.onNodeWithText(context.getString(R.string.permission_title)).assertIsDisplayed()
            rule.onNodeWithText(context.getString(R.string.permission_body)).assertIsDisplayed()
            rule.onNodeWithTag(Tags.SHELL).assertDoesNotExist()

            rule.onNodeWithTag(Tags.PERMISSION_BUTTON).performClick()
            scenario.onActivity { activity ->
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, started.action)
                assertEquals("package:${context.packageName}", started.dataString)
            }
        }
    }

    @Test
    fun grantedOnResumeShowsShell() {
        allowAllFilesAccess(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            rule.onNodeWithTag(Tags.PERMISSION_SCREEN).assertIsDisplayed()

            allowAllFilesAccess(true)
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            rule.onNodeWithTag(Tags.SHELL).assertIsDisplayed()
            rule.onNodeWithTag(Tags.PERMISSION_SCREEN).assertDoesNotExist()
        }
    }

    private companion object {
        const val MANAGE_EXTERNAL_STORAGE_OP = "android:manage_external_storage"
    }
}
