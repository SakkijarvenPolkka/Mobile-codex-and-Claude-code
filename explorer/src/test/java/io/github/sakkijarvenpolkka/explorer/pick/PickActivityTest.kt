// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.pick

import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.ExplorerApp
import io.github.sakkijarvenpolkka.explorer.data.VolumeLocationCodec
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.OpenWith
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.TabAction
import io.github.sakkijarvenpolkka.explorer.state.TabsReducer
import io.github.sakkijarvenpolkka.explorer.ui.Tags
import io.github.sakkijarvenpolkka.explorer.ui.fluent.TabStripTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
import org.xmlpull.v1.XmlPullParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Another app's request for files (`ACTION_GET_CONTENT`, pick spec §1.1, §2) as the activity answers it, on a
 * temporary primary volume. Robolectric has no switch for all files access: it follows the app op, as on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PickActivityTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val rule = createEmptyComposeRule()

    private val app: ExplorerApp get() = ApplicationProvider.getApplicationContext()
    private val openWith by lazy { OpenWith(app, MimeResolver()) }
    private lateinit var root: Path
    private lateinit var codec: VolumeLocationCodec

    @Before
    fun setUp() {
        root = folder.newFolder("primary").toPath().toRealPath()
        val primary = StorageVolumeBuilder(
            "emulated;0", root.toFile(), "Internal shared storage", Process.myUserHandle(), "mounted",
        )
            .setIsPrimary(true)
            .setIsEmulated(true)
            .build()
        shadowOf(app.getSystemService(StorageManager::class.java)).addStorageVolume(primary)
        app.volumes.refresh()
        codec = VolumeLocationCodec { app.volumes.volumes.value }
        ShadowEnvironment.addExternalDir("emulated/0")
        allowAllFilesAccess(true)
    }

    private fun allowAllFilesAccess(allowed: Boolean) {
        val mode = if (allowed) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_ERRORED
        shadowOf(app.getSystemService(AppOpsManager::class.java))
            .setMode(MANAGE_EXTERNAL_STORAGE_OP, app.applicationInfo.uid, app.packageName, mode)
    }

    /** An app's request for a file of [type] (several, if [multiple]; of [types], if given), made to the activity. */
    private fun getContent(type: String, multiple: Boolean = false, types: Array<String>? = null): Intent =
        Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(type)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            .apply { if (types != null) putExtra(Intent.EXTRA_MIME_TYPES, types) }
            .setClass(app, PickActivity::class.java)

    /** [folder] as the folder files were last picked from, where picking starts. */
    private fun startIn(folder: Path) {
        app.settings.pickLast = codec.encode(Location.Folder(folder))
    }

    private fun file(rel: String): Path = Files.createFile(root.resolve(rel))

    private fun <A : Activity> activityOf(scenario: ActivityScenario<A>): A {
        lateinit var activity: A
        scenario.onActivity { activity = it }
        return activity
    }

    /**
     * [activity] gives out the files of the temporary volume as those of the primary volume at the same place: the
     * FileProvider serves only the storage volumes.
     */
    private fun asOnTheDevice(activity: PickActivity) {
        activity.uriFor = { path -> openWith.uriFor(STORAGE.resolve(root.relativize(path).toString())) }
    }

    private fun shown(tag: String): Boolean = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** Waits for [condition], running the main thread's work meanwhile (folders are read on another thread). */
    private fun waitFor(condition: () -> Boolean) = rule.waitUntil(10_000) {
        rule.waitForIdle()
        condition()
    }

    /** Taps the element tagged [tag], once it shows. */
    private fun tap(tag: String) {
        waitFor { shown(tag) }
        rule.onNodeWithTag(tag).performClick()
    }

    @Test
    fun intentFilterMatchesGetContent() {
        fun pickers(intent: Intent, flags: Int = 0) =
            app.packageManager.queryIntentActivities(intent, flags).map { it.activityInfo.name }
        val picker = PickActivity::class.java.name
        val openable = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)

        assertTrue(picker in pickers(Intent(openable).setType("image/*")))
        assertTrue(picker in pickers(Intent(openable).setType("*/*")))
        // A request that is not marked openable, as the system starts an activity for it (the default category).
        val plain = Intent(Intent.ACTION_GET_CONTENT).setType("application/pdf")
        assertTrue(picker in pickers(plain, PackageManager.MATCH_DEFAULT_ONLY))
    }

    @Test
    fun declaredToOpenOverTheAppThatAsks() {
        // As the manifest is built, the one the tests run with.
        val manifest = app.assets.openXmlResourceParser("AndroidManifest.xml")
        while (manifest.next() != XmlPullParser.END_DOCUMENT) {
            val picker = manifest.eventType == XmlPullParser.START_TAG && manifest.name == "activity" &&
                manifest.getAttributeValue(ANDROID, "name") == PickActivity::class.java.name
            if (!picker) continue
            assertTrue(manifest.getAttributeBooleanValue(ANDROID, "exported", false))
            // In the task of the app that asked, never in the app's own, and not listed in the recent apps.
            assertEquals("", manifest.getAttributeValue(ANDROID, "taskAffinity"))
            assertTrue(manifest.getAttributeBooleanValue(ANDROID, "excludeFromRecents", false))
            assertNull(manifest.getAttributeValue(ANDROID, "launchMode"))
            // The window makes room for the keyboard, as the app's does.
            @Suppress("DEPRECATION")
            val adjustResize = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            assertEquals(adjustResize, manifest.getAttributeIntValue(ANDROID, "windowSoftInputMode", 0))
            // Recreated on rotation and resizing.
            assertNull(manifest.getAttributeValue(ANDROID, "configChanges"))
            return
        }
        fail("No PickActivity in the manifest")
    }

    @Test
    fun pickingReturnsOk() {
        val dcim = Files.createDirectory(root.resolve("DCIM"))
        file("DCIM/a.jpg")
        file("DCIM/doc.pdf")
        startIn(root)
        ActivityScenario.launchActivityForResult<PickActivity>(getContent("image/*")).use { scenario ->
            val activity = activityOf(scenario)
            asOnTheDevice(activity)
            tap(Tags.row("DCIM"))
            tap(Tags.row("a.jpg"))
            waitFor { activity.isFinishing }

            val result = scenario.result
            assertEquals(Activity.RESULT_OK, result.resultCode)
            val data = checkNotNull(result.resultData?.data)
            assertEquals("content", data.scheme)
            assertEquals("io.github.sakkijarvenpolkka.explorer.files", data.authority)
            assertEquals(openWith.uriFor(STORAGE.resolve("DCIM/a.jpg")), data)
            assertEquals("image/jpeg", result.resultData.type)
            assertTrue(result.resultData.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
        // Picking starts in its folder next time, and it is a recent file.
        assertEquals(codec.encode(Location.Folder(dcim)), app.settings.pickLast)
        assertEquals(listOf("DCIM/a.jpg"), app.settings.recents.value.map { it.rel })
    }

    @Test
    fun pickingSeveralOfTheTypesAskedReturnsThemAll() {
        file("a.jpg")
        file("b.png")
        file("doc.pdf")
        startIn(root)
        val request = getContent("*/*", multiple = true, types = arrayOf("image/jpeg", "application/pdf"))
        ActivityScenario.launchActivityForResult<PickActivity>(request).use { scenario ->
            val activity = activityOf(scenario)
            asOnTheDevice(activity)
            waitFor { shown(Tags.row("b.png")) }
            // Of a type not asked for.
            rule.onNodeWithTag(Tags.row("b.png")).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
            tap(Tags.row("doc.pdf"))
            tap(Tags.row("a.jpg"))
            tap(Tags.PICK_OPEN)
            waitFor { activity.isFinishing }

            val result = scenario.result
            assertEquals(Activity.RESULT_OK, result.resultCode)
            val uris = listOf("a.jpg", "doc.pdf").map { openWith.uriFor(STORAGE.resolve(it)) }
            val clip = checkNotNull(result.resultData.clipData)
            assertEquals(uris, (0 until clip.itemCount).map { clip.getItemAt(it).uri })
            assertEquals(uris.first(), result.resultData.data)
            assertEquals("*/*", result.resultData.type)
        }
    }

    @Test
    fun cancelReturnsCanceled() {
        startIn(root)
        ActivityScenario.launchActivityForResult<PickActivity>(getContent("image/*")).use { scenario ->
            val activity = activityOf(scenario)
            tap(Tags.PICK_CANCEL)
            waitFor { activity.isFinishing }
            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        }

        // Back goes back first; with nothing left to go back to, it cancels.
        Files.createDirectory(root.resolve("DCIM"))
        ActivityScenario.launchActivityForResult<PickActivity>(getContent("image/*")).use { scenario ->
            val activity = activityOf(scenario)
            tap(Tags.row("DCIM"))
            waitFor { !shown(Tags.row("DCIM")) }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor { shown(Tags.row("DCIM")) }
            assertFalse(activity.isFinishing)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor { activity.isFinishing }
            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        }
    }

    @Test
    fun withoutPermissionShowsPermissionScreen() {
        allowAllFilesAccess(false)
        file("a.jpg")
        startIn(root)
        ActivityScenario.launchActivityForResult<PickActivity>(getContent("image/*")).use { scenario ->
            rule.onNodeWithTag(Tags.PERMISSION_SCREEN).assertIsDisplayed()
            rule.onNodeWithTag(Tags.SHELL).assertDoesNotExist()
            rule.onNodeWithTag(Tags.PERMISSION_BUTTON).performClick()
            scenario.onActivity { activity ->
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, started.action)
                assertEquals("package:${app.packageName}", started.dataString)
            }

            // Granted in the settings: coming back, picking starts.
            allowAllFilesAccess(true)
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor { shown(Tags.row("a.jpg")) }
            rule.onNodeWithTag(Tags.PICK_TITLE_BAR).assertIsDisplayed()
            rule.onNodeWithTag(Tags.PERMISSION_SCREEN).assertDoesNotExist()
        }
    }

    @Test
    fun doesNotTouchAppSession() {
        Files.createDirectory(root.resolve("DCIM"))
        file("DCIM/a.jpg")
        // The app's own tabs: two.
        val tabs = TabsReducer.reduce(TabsReducer.initial(Location.Folder(root)), TabAction.NewTab(Location.Home))
        app.settings.saveSession(tabs, codec)
        val saved = checkNotNull(app.settings.loadSession(codec))
        startIn(root)
        ActivityScenario.launchActivityForResult<PickActivity>(getContent("*/*")).use { scenario ->
            val activity = activityOf(scenario)
            waitFor { shown(Tags.PICK_TITLE_BAR) }
            rule.onNodeWithTag(TabStripTags.STRIP).assertDoesNotExist()
            tap(Tags.row("DCIM"))
            waitFor { shown(Tags.row("a.jpg")) }
            tap(Tags.PICK_CANCEL)
            waitFor { activity.isFinishing }
        }
        assertEquals(saved, app.settings.loadSession(codec))
    }

    private companion object {
        const val MANAGE_EXTERNAL_STORAGE_OP = "android:manage_external_storage"
        const val ANDROID = "http://schemas.android.com/apk/res/android"

        /** Where the primary volume is on a device. */
        val STORAGE: Path = Paths.get("/storage/emulated/0")
    }
}
