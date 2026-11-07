// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.platform

import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PermissionGateTest {

    @Test
    fun settingsIntentsOrder() {
        val pkg = "io.github.sakkijarvenpolkka.explorer"
        val intents = PermissionGate.settingsIntents(pkg)
        assertEquals(
            listOf(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            intents.map { it.action },
        )
        assertEquals("package:$pkg", intents[0].data?.toString())
        assertNull(intents[1].data)
        assertEquals("package:$pkg", intents[2].data?.toString())
    }

    @Test
    fun settingsOpenTheFirstScreenTheDeviceHas() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val screens = PermissionGate.settingsIntents(activity.packageName)
        fun opened(): String? {
            activity.openAllFilesAccessSettings()
            return shadowOf(activity).nextStartedActivity?.action
        }
        assertEquals(screens[0].action, opened())

        // A device that has only the app's details: the screens before them are not found.
        shadowOf(activity.application).checkActivities(true)
        val packages = shadowOf(activity.packageManager)
        val details = ComponentName("com.android.settings", "com.android.settings.AppDetails")
        packages.addActivityIfNotPresent(details)
        val filter = IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("package")
        }
        packages.addIntentFilterForActivity(details, filter)
        assertEquals(screens[2].action, opened())
        assertNull(shadowOf(activity).nextStartedActivity)

        // One that has none of them: nothing opens, and nothing fails.
        packages.removeActivity(details)
        assertNull(opened())
    }

    @Test
    fun grantedFollowsAllFilesAccess() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val appOps = shadowOf(context.getSystemService(AppOpsManager::class.java))
        val uid = context.applicationInfo.uid
        ShadowEnvironment.addExternalDir("emulated/0")
        appOps.setMode(MANAGE_EXTERNAL_STORAGE_OP, uid, context.packageName, AppOpsManager.MODE_ERRORED)
        assertFalse(PermissionGate.granted())
        appOps.setMode(MANAGE_EXTERNAL_STORAGE_OP, uid, context.packageName, AppOpsManager.MODE_ALLOWED)
        assertTrue(PermissionGate.granted())
    }

    private companion object {
        const val MANAGE_EXTERNAL_STORAGE_OP = "android:manage_external_storage"
    }
}
