// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.provider

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.PickerChanges
import io.github.sakkijarvenpolkka.explorer.data.Favorite
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.fs.BinItem
import io.github.sakkijarvenpolkka.explorer.fs.DELETED_AT
import io.github.sakkijarvenpolkka.explorer.fs.FaultyFs
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.HiddenNames
import io.github.sakkijarvenpolkka.explorer.fs.NameLabels
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpenSaves
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.RecycleBin
import io.github.sakkijarvenpolkka.explorer.fs.TempJournal
import io.github.sakkijarvenpolkka.explorer.fs.VolumeLookup
import io.github.sakkijarvenpolkka.explorer.fs.VolumeRoot
import io.github.sakkijarvenpolkka.explorer.fs.VolumeTraits
import io.github.sakkijarvenpolkka.explorer.fs.write
import io.github.sakkijarvenpolkka.explorer.platform.MediaScan
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.ThumbKind
import io.github.sakkijarvenpolkka.explorer.platform.ThumbnailPort
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.platform.VolumeState
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What other apps change through the system file picker: saves, new items, renames and deletes (pick spec §3.5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderWriteTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var primary: Path
    private lateinit var card: Path

    /** The volumes there are now: the provider asks at each call. */
    private var volumes = emptyList<Volume>()

    private val fs = FaultyFs()
    private val journal = EventJournal()

    /** What the media store was asked to scan, in order. */
    private val scanned = CopyOnWriteArrayList<Path>()

    /** The bytes free for apps on every drive, as the provider reads them; null: no telling (the reading fails). */
    @Volatile
    private var room: Long? = Long.MAX_VALUE

    /** The saves other apps have in flight, as the app keeps them. */
    private var saves = OpenSaves()

    /** The app's settings: its favorites. */
    private val settings = SettingsStore(null)

    /** The operation the app is running, if any. */
    @Volatile
    private var running: OperationSpec? = null

    /** The threads other apps' calls come on, one each (binder threads). */
    private val calls = Executors.newCachedThreadPool()

    private lateinit var bin: RecycleBin
    private lateinit var picker: PickerChanges
    private lateinit var provider: ExplorerDocumentsProvider

    /** A journal that keeps every call, in order, from any thread. */
    private class EventJournal : TempJournal {
        val events = CopyOnWriteArrayList<String>()

        override fun add(temp: Path) {
            events += "add ${temp.fileName}"
        }

        override fun remove(temp: Path) {
            events += "remove ${temp.fileName}"
        }
    }

    /**
     * A context that keeps the URI grants of the one app that calls, as the system keeps them: what the app
     * [held] of each URI, granted and checked as the caller's; a revoke takes a URI and those under it from all.
     */
    private class Grants(base: Context) : ContextWrapper(base) {
        val held = mutableMapOf<Uri, Int>()

        override fun grantUriPermission(toPackage: String, uri: Uri, modeFlags: Int) {
            // One to neither read nor write grants nothing.
            if (modeFlags and (READ or WRITE) != 0) held[uri] = (held[uri] ?: 0) or modeFlags
        }

        override fun revokeUriPermission(uri: Uri, modeFlags: Int) {
            val segments = uri.pathSegments
            held.keys.removeIf { it.authority == uri.authority && it.pathSegments.take(segments.size) == segments }
        }

        override fun checkCallingOrSelfUriPermission(uri: Uri, modeFlags: Int): Int =
            if (((held[uri] ?: 0) and modeFlags) == modeFlags) PERMISSION_GRANTED else PERMISSION_DENIED
    }

    @Before
    fun setUp() {
        val temp = folder.root.toPath().toRealPath()
        primary = Files.createDirectory(temp.resolve("primary"))
        card = Files.createDirectory(temp.resolve("card"))
        volumes = listOf(primaryVolume(), cardVolume())
        provider = Robolectric.buildContentProvider(ExplorerDocumentsProvider::class.java).create(providerInfo()).get()
        ProviderDeps.override = deps()
        picker = PickerChanges(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        ProviderDeps.override = null
        calls.shutdownNow()
    }

    /** What a documents provider's entry in the manifest says, or it refuses to start. */
    private fun providerInfo() = ProviderInfo().apply {
        authority = PickerChanges.AUTHORITY
        exported = true
        grantUriPermissions = true
        readPermission = Manifest.permission.MANAGE_DOCUMENTS
        writePermission = Manifest.permission.MANAGE_DOCUMENTS
    }

    /** Starts another provider in [context], as the system starts one: other apps' calls go to it then. */
    private fun startIn(context: Context): ExplorerDocumentsProvider = ExplorerDocumentsProvider().also {
        it.attachInfo(context, providerInfo())
        ShadowContentResolver.registerProviderInternal(PickerChanges.AUTHORITY, it)
    }

    private fun primaryVolume() = Volume(
        "primary", "Internal shared storage", primary, VolumeState.MOUNTED,
        isPrimary = true, isRemovable = false, fuseBacked = true, traits = VolumeTraits(),
    )

    /** An SD card served as it is mounted, not through FUSE: the media store does not index it. */
    private fun cardVolume(state: VolumeState = VolumeState.MOUNTED) = Volume(
        CARD, "SD", card, state,
        isPrimary = false, isRemovable = true, fuseBacked = false, traits = VolumeTraits(fatNames = true),
    )

    private fun deps(): ProviderDeps {
        val lookup = VolumeLookup { path ->
            volumes.mapNotNull { v -> v.root?.let { VolumeRoot(v.uuid, it, v.traits, v.isRemovable) } }
                .filter { path.startsWith(it.root) }
                .maxByOrNull { it.root.nameCount }
        }
        bin = RecycleBin(fs, lookup, clock = { DELETED_AT }, ids = { "item1" })
        val labels = NameLabels(newFolder = "New folder", newTextDocument = "New Text Document", copySuffix = " - Copy")
        val fileOps = FileOps(fs, lookup, labels, journal, ids = { "r1" }, bin = bin)
        val mediaScan = object : MediaScan {
            override fun scan(paths: List<Path>) {
                scanned += paths
            }
        }
        val thumbnails = object : ThumbnailPort {
            override fun load(file: Path, kind: ThumbKind, sizePx: Int, signal: CancellationSignal): Bitmap? = null
        }
        // The file system as the provider reads it: the room there is is what the test says.
        val provided = object : FsBackend by fs {
            override fun availableBytes(path: Path): Long = room ?: throw IOException("statvfs: Input/output error")
        }
        return ProviderDeps(
            provided, { volumes }, settings, fileOps, bin, journal, mediaScan, thumbnails, MimeResolver(),
            granted = { true }, internalLabel = { "내부 저장소" }, io = Dispatchers.IO, ids = { "id" },
            openSaves = saves, busy = { running?.uses(it) == true },
        )
    }

    /**
     * Waits until the provider has heard of every file closed so far, and has done what follows: put what was saved in
     * place, or dropped it.
     */
    private fun closed() {
        shadowOf(provider.closes.looper).idle()
        shadowOf(provider.saves.looper).idle()
    }

    /**
     * Makes [call] as another app's next call comes, on a thread of its own, and gives it a moment: one that waits for
     * nothing is done by then.
     */
    private fun <T> calling(call: () -> T): Future<T> {
        val future = calls.submit(call)
        try {
            future.get(CALL_MOMENT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Still waiting, for a save to settle.
        } catch (e: ExecutionException) {
            // It failed: the future tells.
        }
        return future
    }

    /** Writes [text] into what the document [id] opens as in [mode], and closes it. */
    private fun writeThrough(id: String, mode: String, text: String) {
        ParcelFileDescriptor.AutoCloseOutputStream(provider.openDocument(id, mode, null)).use {
            it.write(text.toByteArray())
        }
    }

    private fun read(path: Path): String = String(Files.readAllBytes(path))

    private fun names(dir: Path): List<String> = NioFsBackend.list(dir).map { it.fileName.toString() }.sorted()

    private fun childrenOf(id: String): Uri = PickerChanges.childrenOf(id)

    @Test
    fun writeTruncateGoesThroughTempAndCommits() {
        val a = write(primary, "docs/a.txt", "old")
        val temp = primary.resolve("docs/.a.txt.id.xpart")

        ParcelFileDescriptor.AutoCloseOutputStream(provider.openDocument("primary:docs/a.txt", "wt", null)).use {
            it.write("new".toByteArray())
            // Into a hidden file next to it: until the other app is done, the file is as it was.
            assertEquals("new", read(temp))
            assertEquals("old", read(a))
        }
        closed()

        assertEquals("new", read(a))
        assertEquals(listOf("a.txt"), names(primary.resolve("docs")))
        assertEquals(listOf("add .a.txt.id.xpart", "remove .a.txt.id.xpart"), journal.events.toList())
        // The picker reads the folder again, and the media store the file.
        assertEquals(listOf(childrenOf("primary:docs")), picker.take())
        assertEquals(listOf(a), scanned.toList())

        // "w" is the same; on a volume the media store does not index, nothing is scanned.
        val b = write(card, "b.txt", "old")
        writeThrough("$CARD:b.txt", "w", "new")
        closed()
        assertEquals("new", read(b))
        assertEquals(listOf("b.txt"), names(card))
        assertEquals(listOf(childrenOf("$CARD:")), picker.take())
        assertEquals(listOf(a), scanned.toList())
    }

    @Test
    fun writeWhoseCommitFailsKeepsOriginal() {
        val a = write(primary, "a.txt", "old")
        // All was written, but it cannot take the file's place (its volume went read-only, say).
        fs.refuse(FaultyFs.Op.ATOMIC_MOVE, ".a.txt.id.xpart", "Read-only file system")

        writeThrough("primary:a.txt", "wt", "new")
        closed()

        assertEquals("old", read(a))
        assertEquals(listOf("a.txt"), names(primary))
        assertEquals(listOf("add .a.txt.id.xpart", "remove .a.txt.id.xpart"), journal.events.toList())
        // Nothing changed.
        assertEquals(emptyList<Uri>(), picker.take())
        assertEquals(emptyList<Path>(), scanned.toList())
    }

    @Test
    fun renameRightAfterSaveRenamesTheSavedFile() {
        write(primary, "a.txt", "old")
        // The provider hears of the close later, on a thread of its own, as the system tells it.
        shadowOf(provider.closes.looper).pause()
        writeThrough("primary:a.txt", "wt", "new")

        // The other app renames it at once, as an app saving under a temporary name does: what is renamed is the file
        // saved, not the one it replaces.
        val renamed = calling { provider.renameDocument("primary:a.txt", "b.txt") }
        shadowOf(provider.closes.looper).unPause()
        closed()

        assertEquals("primary:b.txt", renamed.get(10, TimeUnit.SECONDS))
        assertEquals("new", read(primary.resolve("b.txt")))
        assertEquals(listOf("b.txt"), names(primary))
    }

    @Test
    fun appendRightAfterSaveAppendsToTheSavedFile() {
        val log = write(primary, "log.txt", "")
        shadowOf(provider.closes.looper).pause()
        writeThrough("primary:log.txt", "w", "head ")

        // The other app appends at once: to the file saved.
        val appended = calling { writeThrough("primary:log.txt", "wa", "tail") }
        shadowOf(provider.closes.looper).unPause()
        closed()
        appended.get(10, TimeUnit.SECONDS)
        closed()

        assertEquals("head tail", read(log))
        assertEquals(listOf("log.txt"), names(primary))
    }

    @Test
    fun readRightAfterSaveReadsTheSavedFile() {
        write(primary, "a.txt", "old")
        shadowOf(provider.closes.looper).pause()
        writeThrough("primary:a.txt", "wt", "newer")

        // The other app asks what it saved, and reads it back, at once: it finds what it saved.
        val readBack = calling {
            val size = provider.queryDocument("primary:a.txt", null).use { row ->
                row.moveToFirst()
                row.getLong(row.getColumnIndexOrThrow(Document.COLUMN_SIZE))
            }
            val opened = provider.openDocument("primary:a.txt", "r", null)
            size to ParcelFileDescriptor.AutoCloseInputStream(opened).use { String(it.readBytes()) }
        }
        shadowOf(provider.closes.looper).unPause()
        closed()

        assertEquals(5L to "newer", readBack.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun folderOfAnOpenSaveIsNotRenamedOrDeleted() {
        // A call waits for a save still being written only a moment here.
        saves = OpenSaves(closeWaitMs = 50)
        ProviderDeps.override = deps()
        val a = write(primary, "docs/a.txt", "old")

        ParcelFileDescriptor.AutoCloseOutputStream(provider.openDocument("primary:docs/a.txt", "wt", null)).use {
            it.write("new".toByteArray())
            // While the other app writes, neither the file nor a folder holding it is renamed or deleted: the save
            // would land nowhere.
            assertThrows(FileNotFoundException::class.java) { provider.renameDocument("primary:docs", "papers") }
            assertThrows(FileNotFoundException::class.java) { provider.renameDocument("primary:docs/a.txt", "b.txt") }
            assertThrows(FileNotFoundException::class.java) { provider.deleteDocument("primary:docs") }
        }
        closed()

        assertEquals("new", read(a))
        assertEquals(listOf("docs"), names(primary))
        assertEquals(listOf("a.txt"), names(primary.resolve("docs")))
        assertEquals(emptyList<BinItem>(), bin.list(listOf(primary)))
        // Only the save changed the folder.
        assertEquals(listOf(childrenOf("primary:docs")), picker.take())
    }

    @Test
    fun whatTheRunningOperationUsesIsNotRenamedOrDeleted() {
        write(primary, "docs/a.txt", "a")
        // The app is copying into docs: that folder, what is in it and the folders holding it are in use.
        running = OperationSpec("op", OpKind.COPY, listOf(card.resolve("x.txt").toString()), primary.resolve("docs").toString())
        val refused = listOf<() -> Any?>(
            { provider.renameDocument("primary:docs", "x") },
            { provider.renameDocument("primary:docs/a.txt", "b.txt") },
            { provider.deleteDocument("primary:docs") },
            { provider.deleteDocument("primary:docs/a.txt") },
        )

        for (call in refused) assertThrows(FileNotFoundException::class.java) { call() }

        assertEquals(listOf("docs"), names(primary))
        assertEquals(listOf("a.txt"), names(primary.resolve("docs")))
        assertEquals(emptyList<BinItem>(), bin.list(listOf(primary)))
        assertEquals(emptyList<Uri>(), picker.take())
        // Once it is over, they are done as asked.
        running = null
        assertEquals("primary:x", provider.renameDocument("primary:docs", "x"))
    }

    @Test
    fun writeClosedWithErrorKeepsOriginal() {
        val a = write(primary, "docs/a.txt", "old")
        val pfd = provider.openDocument("primary:docs/a.txt", "w", null)
        FileOutputStream(pfd.fileDescriptor).write("new".toByteArray())

        // The other app gives up (closeWithError) or dies. Robolectric only ever closes without an error, so the
        // provider is told as the system would tell it, and the close that follows tells it nothing more.
        val shadow = Shadow.extract<Any>(pfd)
        val listener = ReflectionHelpers.getField<ParcelFileDescriptor.OnCloseListener>(shadow, "onCloseListener")
        listener.onClose(IOException("gave up"))
        ReflectionHelpers.setField(shadow, "onCloseListener", null)
        pfd.close()
        closed()

        assertEquals("old", read(a))
        assertEquals(listOf("a.txt"), names(primary.resolve("docs")))
        assertEquals(listOf("add .a.txt.id.xpart", "remove .a.txt.id.xpart"), journal.events.toList())
        // Nothing changed.
        assertEquals(emptyList<Uri>(), picker.take())
        assertEquals(emptyList<Path>(), scanned.toList())
    }

    @Test
    fun saveThatRanOutOfRoomKeepsOriginal() {
        val a = write(primary, "a.txt", "old content")

        // The drive fills up as the other app writes: its writing fails, yet it closes the file as it always does
        // (use, try-with-resources), as if it had finished. Or how full the drive is cannot be read then.
        for (free in listOf(0L, null)) {
            room = Long.MAX_VALUE
            ParcelFileDescriptor.AutoCloseOutputStream(provider.openDocument("primary:a.txt", "wt", null)).use {
                it.write("ne".toByteArray())
                room = free
            }
            closed()
        }

        // On a drive that full, a cut save cannot be told from one that finished: the file is kept as it was.
        assertEquals("old content", read(a))
        assertEquals(listOf("a.txt"), names(primary))
        val oneSave = listOf("add .a.txt.id.xpart", "remove .a.txt.id.xpart")
        assertEquals(oneSave + oneSave, journal.events.toList())
        assertEquals(emptyList<Uri>(), picker.take())
        assertEquals(emptyList<Path>(), scanned.toList())
    }

    @Test
    fun saveWithoutRoomIsRefused() {
        val a = write(primary, "a.txt", "old content")

        // Too little room to tell a cut save from a finished one, or no telling how much there is: refused at once.
        for (free in listOf(5L, null)) {
            room = free
            for (mode in listOf("w", "wt")) {
                assertThrows("$mode $free", FileNotFoundException::class.java) {
                    provider.openDocument("primary:a.txt", mode, null)
                }
            }
        }

        assertEquals("old content", read(a))
        assertEquals(listOf("a.txt"), names(primary))
        assertEquals(emptyList<String>(), journal.events.toList())
        // Written in the file itself, nothing needs room for a second copy: those modes open as before.
        room = 5
        provider.openDocument("primary:a.txt", "wa", null).close()
        closed()
        assertEquals("old content", read(a))
    }

    @Test
    fun appendWritesInPlace() {
        val log = write(primary, "log.txt", "one ")

        writeThrough("primary:log.txt", "wa", "two")
        closed()

        // Into the file itself: what is there stays, so no temporary file can stand for it.
        assertEquals("one two", read(log))
        assertEquals(listOf("log.txt"), names(primary))
        assertEquals(emptyList<String>(), journal.events.toList())
        assertEquals(listOf(childrenOf("primary:")), picker.take())
        assertEquals(listOf(log), scanned.toList())
    }

    @Test
    fun readWriteModesWriteTheFileItself() {
        val a = write(primary, "a.txt", "abcdef")

        // "rw" keeps what is not written over.
        provider.openDocument("primary:a.txt", "rw", null).use {
            FileOutputStream(it.fileDescriptor).write("XY".toByteArray())
        }
        closed()
        assertEquals("XYcdef", read(a))
        // "rwt" starts it empty.
        writeThrough("primary:a.txt", "rwt", "new")
        closed()
        assertEquals("new", read(a))

        assertEquals(listOf("a.txt"), names(primary))
        assertEquals(emptyList<String>(), journal.events.toList())
        assertEquals(List(2) { childrenOf("primary:") }, picker.take())
        assertEquals(listOf(a, a), scanned.toList())
    }

    @Test
    fun writeRefusedOnFoldersAndWhatIsNoFile() {
        Files.createDirectories(primary.resolve("dir"))
        write(primary, ".Recycle.Bin/1/x.txt")
        val a = write(primary, "a.txt", "old")
        val ids = listOf("primary:dir", "primary:", ROOT_DOC_ID, "primary:missing.txt", "primary:.Recycle.Bin/1/x.txt")

        for (mode in listOf("w", "wt", "wa", "rw", "rwt")) {
            for (id in ids) {
                assertThrows("$mode $id", FileNotFoundException::class.java) { provider.openDocument(id, mode, null) }
            }
        }
        // Nor a mode no one knows.
        assertThrows(FileNotFoundException::class.java) { provider.openDocument("primary:a.txt", "x", null) }

        assertEquals("old", read(a))
        assertEquals(listOf(".Recycle.Bin", "a.txt", "dir"), names(primary))
        assertEquals(emptyList<String>(), journal.events.toList())
    }

    @Test
    fun readOnlyVolumeTakesNoChanges() {
        val b = write(card, "docs/b.txt", "old")
        volumes = listOf(primaryVolume(), cardVolume(VolumeState.MOUNTED_READ_ONLY))

        for (mode in listOf("w", "wt", "wa", "rw", "rwt")) {
            assertThrows(mode, FileNotFoundException::class.java) {
                provider.openDocument("$CARD:docs/b.txt", mode, null)
            }
        }
        assertThrows(FileNotFoundException::class.java) { provider.renameDocument("$CARD:docs/b.txt", "c.txt") }
        assertThrows(FileNotFoundException::class.java) { provider.deleteDocument("$CARD:docs/b.txt") }

        assertEquals("old", read(b))
        assertEquals(listOf("b.txt"), names(card.resolve("docs")))
        // It is still read.
        val opened = provider.openDocument("$CARD:docs/b.txt", "r", null)
        assertEquals("old", ParcelFileDescriptor.AutoCloseInputStream(opened).use { String(it.readBytes()) })
    }

    @Test
    fun createFileAddsExtensionAndKeepsBoth() {
        val docs = Files.createDirectories(primary.resolve("docs"))

        assertEquals("primary:docs/memo.txt", provider.createDocument("primary:docs", "text/plain", "memo"))
        assertEquals("primary:docs/memo (2).txt", provider.createDocument("primary:docs", "text/plain", "memo"))
        // A name with an extension keeps its own, and a type no extension stands for adds none.
        assertEquals("primary:docs/notes.md", provider.createDocument("primary:docs", "text/plain", "notes.md"))
        assertEquals("primary:docs/data", provider.createDocument("primary:docs", "application/octet-stream", "data"))

        assertEquals(listOf("data", "memo (2).txt", "memo.txt", "notes.md"), names(docs))
        assertEquals(0L, Files.size(docs.resolve("memo.txt")))
        // Each new file is in the folder the picker reads again, and in the media store.
        assertEquals(List(4) { childrenOf("primary:docs") }, picker.take())
        assertEquals(listOf("memo.txt", "memo (2).txt", "notes.md", "data").map(docs::resolve), scanned.toList())
    }

    @Test
    fun createExistingNameKeepsBoth() {
        val a = write(primary, "a.pdf", "mine")

        assertEquals("primary:a (2).pdf", provider.createDocument("primary:", "application/pdf", "a.pdf"))

        // What was there is as it was; the new file is empty.
        assertEquals("mine", read(a))
        assertEquals(0L, Files.size(primary.resolve("a (2).pdf")))
        // As shared storage does, a volume takes a name in another case for the same.
        fs.caseInsensitive = true
        assertEquals("primary:A (3).PDF", provider.createDocument("primary:", "application/pdf", "A.PDF"))
        assertEquals(listOf("A (3).PDF", "a (2).pdf", "a.pdf"), names(primary))
        assertEquals("mine", read(a))
    }

    @Test
    fun createFolder() {
        assertEquals("primary:Work", provider.createDocument("primary:", Document.MIME_TYPE_DIR, "Work"))
        assertTrue(Files.isDirectory(primary.resolve("Work")))
        // A name taken is numbered as a whole: a folder's has no extension.
        assertEquals("primary:Work (2)", provider.createDocument("primary:", Document.MIME_TYPE_DIR, "Work"))
        assertEquals("primary:Work/v1.2", provider.createDocument("primary:Work", Document.MIME_TYPE_DIR, "v1.2"))
        assertEquals("primary:Work/v1.2 (2)", provider.createDocument("primary:Work", Document.MIME_TYPE_DIR, "v1.2"))

        assertEquals(listOf("Work", "Work (2)"), names(primary))
        assertEquals(listOf("v1.2", "v1.2 (2)"), names(primary.resolve("Work")))
        val changes = List(2) { childrenOf("primary:") } + List(2) { childrenOf("primary:Work") }
        assertEquals(changes, picker.take())
        // The media store scans files.
        assertEquals(emptyList<Path>(), scanned.toList())
    }

    @Test
    fun createRefusesBadAndReservedNames() {
        for (name in listOf("a/b", "x.xpart", ".Recycle.Bin")) {
            for (mime in listOf("text/plain", Document.MIME_TYPE_DIR)) {
                assertThrows("$name $mime", FileNotFoundException::class.java) {
                    provider.createDocument("primary:", mime, name)
                }
            }
        }
        // By the rules of the volume it goes on: an SD card takes no `?`.
        assertThrows(FileNotFoundException::class.java) { provider.createDocument("$CARD:", "text/plain", "a?b") }
        assertEquals("primary:a?b.txt", provider.createDocument("primary:", "text/plain", "a?b"))

        assertEquals(listOf("a?b.txt"), names(primary))
        assertEquals(emptyList<String>(), names(card))
    }

    @Test
    fun createAndRenameLeaveNothingWhereAndroidWithholds() {
        val media = Files.createDirectories(primary.resolve("Android/media"))

        // What Android withholds from file managers is neither made nor renamed to through the picker.
        assertThrows(FileNotFoundException::class.java) {
            provider.createDocument("primary:Android", Document.MIME_TYPE_DIR, "data")
        }
        assertThrows(FileNotFoundException::class.java) {
            provider.createDocument("primary:Android", "application/octet-stream", "obb")
        }
        assertThrows(FileNotFoundException::class.java) { provider.renameDocument("primary:Android/media", "data") }

        assertEquals(listOf("media"), names(primary.resolve("Android")))
        assertTrue(Files.isDirectory(media))
        assertEquals(emptyList<Uri>(), picker.take())
    }

    @Test
    fun createOnReadOnlyVolumeRefused() {
        Files.createDirectories(card.resolve("dir"))
        volumes = listOf(primaryVolume(), cardVolume(VolumeState.MOUNTED_READ_ONLY))

        for (parent in listOf("$CARD:", "$CARD:dir")) {
            for (mime in listOf("text/plain", Document.MIME_TYPE_DIR)) {
                assertThrows("$parent $mime", FileNotFoundException::class.java) {
                    provider.createDocument(parent, mime, "new")
                }
            }
        }

        assertEquals(listOf("dir"), names(card))
        assertEquals(emptyList<String>(), names(card.resolve("dir")))
        assertEquals(emptyList<Uri>(), picker.take())
    }

    @Test
    fun createRefusedOutsideRealFolders() {
        write(primary, "a.txt")

        // The folder of the favorites and the drives is on no volume; a file holds nothing; the rest is not there.
        for (parent in listOf(ROOT_DOC_ID, "primary:a.txt", "primary:missing", "9999-0000:")) {
            assertThrows(parent, FileNotFoundException::class.java) {
                provider.createDocument(parent, Document.MIME_TYPE_DIR, "new")
            }
        }

        assertEquals(listOf("a.txt"), names(primary))
    }

    @Test
    fun renameReturnsNewIdAndCaseOnlyWorks() {
        // As on shared storage, where a name in another case is the same name.
        fs.caseInsensitive = true
        val docs = primary.resolve("docs")
        write(docs, "a.txt", "a")
        write(docs, "b.txt", "b")

        assertEquals("primary:docs/A.txt", provider.renameDocument("primary:docs/a.txt", "A.txt"))
        assertEquals(listOf("A.txt", "b.txt"), names(docs))
        assertEquals("primary:docs/c.txt", provider.renameDocument("primary:docs/b.txt", "c.txt"))
        // The same name: the document keeps its id.
        assertNull(provider.renameDocument("primary:docs/c.txt", "c.txt"))
        // A name another item has, in any case, is refused, and both stay.
        assertThrows(FileNotFoundException::class.java) { provider.renameDocument("primary:docs/c.txt", "a.TXT") }

        assertEquals(listOf("A.txt", "c.txt"), names(docs))
        assertEquals("a", read(docs.resolve("A.txt")))
        assertEquals(List(2) { childrenOf("primary:docs") }, picker.take())
    }

    @Test
    fun renameVolumeRootRefused() {
        val a = write(primary, "a.txt")

        for (id in listOf("primary:", "$CARD:", ROOT_DOC_ID)) {
            assertThrows(id, FileNotFoundException::class.java) { provider.renameDocument(id, "x") }
        }

        assertEquals(listOf("card", "primary"), names(primary.parent))
        assertTrue(Files.exists(a))
    }

    @Test
    fun renameCarriesTheCallersGrantToTheNewId() {
        write(primary, "docs/a.txt", "a")
        val app = ApplicationProvider.getApplicationContext<Context>()
        val grants = Grants(app)
        startIn(grants)
        // What ACTION_OPEN_DOCUMENT gave the app: this file alone, no folder of it.
        val old = DocumentsContract.buildDocumentUri(PickerChanges.AUTHORITY, "primary:docs/a.txt")
        grants.grantUriPermission("com.example.editor", old, OPENED)

        // The app renames it through the system, which hands it the new id with what it held of the old one.
        val renamed = DocumentsContract.renameDocument(app.contentResolver, old, "b.txt")

        assertEquals(DocumentsContract.buildDocumentUri(PickerChanges.AUTHORITY, "primary:docs/b.txt"), renamed)
        // It can open the file, and keep it, still; the old id is no one's.
        assertEquals(mapOf(renamed to OPENED), grants.held)
    }

    @Test
    fun deleteGoesToRecycleBin() {
        val a = write(primary, "docs/a.txt", "a")

        provider.deleteDocument("primary:docs/a.txt")

        assertFalse(Files.exists(a))
        // In the app's Recycle Bin, which knows where it came from: it can be restored there.
        val item = bin.list(listOf(primary)).single()
        assertEquals(a, bin.originalLocation(item))
        assertEquals("a", read(item.payload))
        assertEquals(listOf(childrenOf("primary:docs")), picker.take())
    }

    @Test
    fun deleteFallsBackToPermanentWhenBinUnavailable() {
        write(primary, "docs/sub/b.txt")
        // No bin can be made here; no one can be asked, so what was asked for is done.
        fs.refuse(FaultyFs.Op.CREATE_DIRECTORY, HiddenNames.RECYCLE_BIN, "Permission denied")

        provider.deleteDocument("primary:docs")

        assertEquals(emptyList<String>(), names(primary))
        assertEquals(emptyList<BinItem>(), bin.list(listOf(primary)))
        assertEquals(listOf(childrenOf("primary:")), picker.take())
    }

    @Test
    fun deleteThatFailsPartwayIsRefused() {
        write(primary, "docs/a.txt")
        val b = write(primary, "docs/b.txt")
        fs.refuse(FaultyFs.Op.CREATE_DIRECTORY, HiddenNames.RECYCLE_BIN, "Permission denied")
        fs.refuse(FaultyFs.Op.DELETE, "b.txt", "Permission denied")

        assertThrows(FileNotFoundException::class.java) { provider.deleteDocument("primary:docs") }

        // What could go went, and the folder that holds the rest stays: the picker reads its folder again.
        assertEquals(listOf("b.txt"), names(primary.resolve("docs")))
        assertEquals("docs/b.txt", read(b))
        assertEquals(listOf(childrenOf("primary:")), picker.take())
    }

    @Test
    fun renamingOrDeletingAFavoriteTellsTheTopFolder() {
        write(card, "docs/sub/a.txt")
        write(card, "plain/b.txt")
        settings.setFavorites(listOf(Favorite(CARD, "docs", "Docs")))

        // The top folder lists the favorites: renamed, one is not there any more.
        assertEquals("$CARD:papers", provider.renameDocument("$CARD:docs", "papers"))
        assertEquals(setOf(childrenOf("$CARD:"), childrenOf(ROOT_DOC_ID)), picker.take().toSet())
        // Nor is one that was in a folder deleted.
        settings.setFavorites(listOf(Favorite(CARD, "papers/sub", "Sub")))
        provider.deleteDocument("$CARD:papers")
        assertEquals(setOf(childrenOf("$CARD:"), childrenOf(ROOT_DOC_ID)), picker.take().toSet())
        // A folder that holds none is only read again where it is.
        provider.renameDocument("$CARD:plain", "plainer")
        assertEquals(listOf(childrenOf("$CARD:")), picker.take())
    }

    @Test
    fun deleteVolumeRootRefused() {
        val a = write(primary, "a.txt")

        for (id in listOf("primary:", "$CARD:", ROOT_DOC_ID)) {
            assertThrows(id, FileNotFoundException::class.java) { provider.deleteDocument(id) }
        }

        assertTrue(Files.exists(a))
        assertEquals(listOf("a.txt"), names(primary))
        assertEquals(emptyList<BinItem>(), bin.list(listOf(primary)))
    }

    private companion object {
        const val CARD = "1A2B-3C4D"

        /** How long a call that waits for nothing may take, at most, in milliseconds. */
        const val CALL_MOMENT_MS = 300L

        const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
        const val WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /** What `ACTION_OPEN_DOCUMENT` grants the app that asked of the file it chose: to read, write and keep it. */
        const val OPENED = READ or WRITE or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    }
}
