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
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.fs.BinItem
import io.github.sakkijarvenpolkka.explorer.fs.DELETED_AT
import io.github.sakkijarvenpolkka.explorer.fs.FaultyFs
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.HiddenNames
import io.github.sakkijarvenpolkka.explorer.fs.NameLabels
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
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
import org.robolectric.shadows.ShadowContentResolver
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

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
        return ProviderDeps(
            fs, { volumes }, SettingsStore(null), fileOps, bin, journal, mediaScan, thumbnails, MimeResolver(),
            granted = { true }, internalLabel = { "내부 저장소" }, io = Dispatchers.IO, ids = { "id" },
        )
    }

    /** Waits until the provider has heard of every file closed so far, and has done what follows. */
    private fun closed() = shadowOf(provider.closes.looper).idle()

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

        const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
        const val WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /** What `ACTION_OPEN_DOCUMENT` grants the app that asked of the file it chose: to read, write and keep it. */
        const val OPENED = READ or WRITE or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    }
}
