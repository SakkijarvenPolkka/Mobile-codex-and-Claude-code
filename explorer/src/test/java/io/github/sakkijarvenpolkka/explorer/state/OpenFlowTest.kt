// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.state

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.Recent
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.OpenResult
import io.github.sakkijarvenpolkka.explorer.platform.OpenWith
import io.github.sakkijarvenpolkka.explorer.util.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/** Opening files in other apps, with the real [OpenWith] (its FileProvider serves `/storage` and `/mnt/media_rw`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenFlowTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = SettingsStore(null)
    private lateinit var root: Path
    private lateinit var vm: ExplorerViewModel

    @Before
    fun setUp() {
        root = folder.newFolder("primary").toPath().toRealPath()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
    }

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
    }

    /** A view model on [root] as the primary volume and an SD card at [SD]; folders are read on the main thread. */
    private fun start(fs: FsBackend = NioFsBackend, io: CoroutineDispatcher = Dispatchers.Unconfined) {
        val volumes = FakeVolumes(FakeVolumes.primary(root), FakeVolumes.removable("1A2B-3C4D", "SD card", SD))
        settings.startAt(root, volumes)
        val mime = MimeResolver()
        val deps = ExplorerDeps(fs, settings, volumes, volumes, mime, io, openWith = OpenWith(app, mime))
        vm = ExplorerViewModel(app, deps)
        settle()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    /** Runs what is queued on [io], then on the main thread, a few rounds over: each hands work to the other. */
    private fun drain(io: TestDispatcher) {
        repeat(5) {
            io.scheduler.advanceUntilIdle()
            settle()
        }
    }

    private fun file(rel: String): Path = Files.createFile(root.resolve(rel))

    private val ui get() = vm.ui.value

    private fun entry(name: String): FileEntry = ui.rows.first { it.entry.name == name }.entry

    private fun names(): List<String> = ui.rows.map { it.entry.name }

    /** [file], which the FileProvider serves (it is on the SD card), as there to the backend; the rest as it is. */
    private fun backendWith(file: Path): FsBackend = object : FsBackend by NioFsBackend {
        override fun exists(path: Path): Boolean = path == file || NioFsBackend.exists(path)

        override fun attributes(path: Path): BasicFileAttributes =
            NioFsBackend.attributes(if (path == file) root else path)
    }

    private fun photo(path: Path) =
        FileEntry(path, "photo.jpg", EntryKind.FILE, 1, 0, "image/jpeg", hidden = false, fileKey = null)

    @Test
    fun openingVanishedFileShowsNotFoundAndRefreshes() {
        file("a.txt")
        file("b.txt")
        start()
        val a = entry("a.txt")
        Files.delete(a.path)

        vm.onTap(a)
        settle()
        assertEquals(UiText.res(R.string.error_not_found), ui.message?.text)
        assertEquals(listOf("b.txt"), names())
        assertNull(ui.launch)
    }

    @Test
    fun vanishedFileMessageIsDroppedOnceTheTabHasMoved() {
        file("a.txt")
        Files.createDirectory(root.resolve("sub"))
        val io = StandardTestDispatcher()
        start(io = io)
        drain(io)
        val a = entry("a.txt")
        Files.delete(a.path)

        vm.onTap(a)
        vm.navigate(Location.Folder(root.resolve("sub")))
        drain(io)
        assertEquals(Location.Folder(root.resolve("sub")), ui.tabs.active.current.location)
        assertNull(ui.message)
    }

    @Test
    fun apkTapWithoutPermissionShowsInstallBar() {
        file("app.apk")
        start()
        vm.onTap(entry("app.apk"))
        settle()
        val message = ui.message!!
        assertEquals(UiText.res(R.string.install_permission), message.text)
        assertNull(ui.launch)

        val (label, allow) = message.action!!
        assertEquals(UiText.res(R.string.permission_button), label)
        allow()
        settle()
        val settings = ui.launch!!
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.action)
        assertEquals(Uri.parse("package:${app.packageName}"), settings.data)
        assertNull(ui.message)
    }

    @Test
    fun tapFileStartsViewIntentAndReportsNoApp() {
        // A file on an SD card: the FileProvider serves it, and the backend says it is there.
        val photo = SD.resolve("DCIM/photo.jpg")
        start(backendWith(photo))
        vm.open(photo(photo))
        settle()
        val view = ui.launch!!
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("image/jpeg", view.type)
        assertEquals("/storage/1A2B-3C4D/DCIM/photo.jpg", view.data?.path)
        assertNull(ui.message)

        vm.launched(view, OpenResult.NoApp)
        settle()
        assertNull(ui.launch)
        assertEquals(UiText.res(R.string.open_no_app), ui.message?.text)
        // No app opened it: it is no recent file.
        assertEquals(emptyList<Recent>(), settings.recents.value)
    }

    @Test
    fun fileAnotherAppOpenedIsRecent() {
        val photo = SD.resolve("DCIM/photo.jpg")
        start(backendWith(photo))
        vm.open(photo(photo))
        settle()
        // Asked for, not yet started.
        assertEquals(emptyList<Recent>(), settings.recents.value)

        vm.launched(ui.launch!!, OpenResult.Started)
        settle()
        assertEquals(listOf("DCIM/photo.jpg"), settings.recents.value.map { it.rel })
    }

    @Test
    fun unreadableFileIsNotReportedGone() {
        // The backend can tell it is there, but may not read it.
        val photo = SD.resolve("DCIM/photo.jpg")
        start(object : FsBackend by NioFsBackend {
            override fun exists(path: Path): Boolean = path != photo && NioFsBackend.exists(path)

            override fun attributes(path: Path): BasicFileAttributes =
                if (path == photo) throw AccessDeniedException(path.toString()) else NioFsBackend.attributes(path)
        })
        vm.open(photo(photo))
        settle()
        assertNull(ui.launch)
        assertEquals(UiText.res(R.string.error_access_denied), ui.message?.text)
    }

    @Test
    fun fileOutsideTheSharedFoldersCannotBeOpened() {
        // The temporary volume is neither under /storage nor under /mnt/media_rw.
        file("a.txt")
        start()
        vm.onTap(entry("a.txt"))
        settle()
        assertEquals(UiText.res(R.string.open_unsupported), ui.message?.text)
        assertNull(ui.launch)
    }

    @Test
    fun obbIsRestrictedOnlyWhileInstallsAreNotAllowed() {
        Files.createDirectories(root.resolve("Android/obb/com.example"))
        start()
        vm.navigate(Location.Folder(root.resolve("Android/obb")))
        settle()
        assertTrue(ui.restricted)

        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        vm.onResume()
        settle()
        assertFalse(ui.restricted)
        assertTrue(Command.NEW_FOLDER in ui.commands)
    }

    private companion object {
        val SD: Path = Paths.get("/storage/1A2B-3C4D")
    }
}
