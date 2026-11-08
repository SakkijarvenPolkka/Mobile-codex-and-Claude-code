// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer

import android.Manifest
import android.app.AppOpsManager
import android.os.Looper
import android.os.Process
import android.os.storage.StorageManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.data.VolumeLocationCodec
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.TabsReducer
import io.github.sakkijarvenpolkka.explorer.ui.Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.StorageVolumeBuilder
import java.nio.file.Files

/** The app process and its single activity come up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppStartTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val rule = createEmptyComposeRule()

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
    fun comingBackTellsTheSystemPickerOfItsRoot() {
        val picker = PickerChanges(ApplicationProvider.getApplicationContext())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(listOf(PickerChanges.ROOTS), picker.take())
            // The access may have changed while the app was away, and the picker lists the app only with it.
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(listOf(PickerChanges.ROOTS), picker.take())
        }
    }

    @Test
    fun recreatedActivityReadsTheFolderAgain() {
        val app = ApplicationProvider.getApplicationContext<ExplorerApp>()
        val root = folder.newFolder("primary").toPath().toRealPath()
        val primary = StorageVolumeBuilder(
            "emulated;0", root.toFile(), "Internal shared storage", Process.myUserHandle(), "mounted",
        ).setIsPrimary(true).setIsEmulated(true).build()
        shadowOf(app.getSystemService(StorageManager::class.java)).addStorageVolume(primary)
        app.volumes.refresh()
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(
            "android:manage_external_storage", app.applicationInfo.uid, app.packageName, AppOpsManager.MODE_ALLOWED,
        )
        Files.createFile(root.resolve("a.jpg"))
        // The app was last left in that folder.
        val codec = VolumeLocationCodec { app.volumes.volumes.value }
        app.settings.saveSession(TabsReducer.initial(Location.Folder(root)), codec)
        fun shown(name: String) = rule.onAllNodesWithTag(Tags.row(name)).fetchSemanticsNodes().isNotEmpty()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            rule.waitUntil(10_000) { shown("a.jpg") }
            // Made meanwhile, as by another app; then the activity is made again (the dark mode changed while it was
            // away, say). Its view model is the one it had, read before: the new activity's first resume reads it
            // again. (Recreated from RESUMED: Robolectric brings a stopped activity back to RESUMED before it makes
            // the new one, which would read it there.)
            Files.createFile(root.resolve("b.jpg"))
            scenario.recreate()
            rule.waitUntil(10_000) { shown("b.jpg") }
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
