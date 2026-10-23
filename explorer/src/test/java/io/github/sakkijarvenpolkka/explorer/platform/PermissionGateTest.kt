// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.platform

import android.app.AppOpsManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
