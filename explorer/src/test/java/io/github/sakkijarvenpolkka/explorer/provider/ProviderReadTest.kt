// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.provider

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.Favorite
import io.github.sakkijarvenpolkka.explorer.data.Recent
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.NameLabels
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.NoJournal
import io.github.sakkijarvenpolkka.explorer.fs.RecycleBin
import io.github.sakkijarvenpolkka.explorer.fs.VolumeLookup
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
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** What the system file picker reads through the app's documents provider (pick spec §3.1–§3.4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderReadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var primary: Path
    private lateinit var card: Path

    /** The volumes there are now: the provider asks at each call. */
    private var volumes = emptyList<Volume>()

    /** Whether the app has all files access now. */
    private var granted = true

    private val settings = SettingsStore(null)
    private val port = FakePort()
    private lateinit var provider: ExplorerDocumentsProvider

    /** Makes a thumbnail of what it is asked for while it [makes] them, and records each ask: the file and the size. */
    private class FakePort : ThumbnailPort {
        val asked = CopyOnWriteArrayList<Pair<Path, Int>>()

        @Volatile
        var makes = true

        override fun load(file: Path, kind: ThumbKind, sizePx: Int, signal: CancellationSignal): Bitmap? {
            asked += file to sizePx
            return if (makes) Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888) else null
        }
    }

    @Before
    fun setUp() {
        val temp = folder.root.toPath().toRealPath()
        primary = Files.createDirectory(temp.resolve("primary"))
        card = Files.createDirectory(temp.resolve("card"))
        volumes = listOf(primaryVolume(), cardVolume())
        val info = ProviderInfo().apply {
            authority = AUTHORITY
            // What a documents provider's entry in the manifest says, or it refuses to start.
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        }
        provider = Robolectric.buildContentProvider(ExplorerDocumentsProvider::class.java).create(info).get()
        // Only now, as on a device, where the provider is created before the app: it finds them on its first call.
        ProviderDeps.override = deps()
    }

    @After
    fun tearDown() {
        ProviderDeps.override = null
    }

    private fun primaryVolume() = Volume(
        "primary", "Internal shared storage", primary, VolumeState.MOUNTED,
        isPrimary = true, isRemovable = false, fuseBacked = true, traits = VolumeTraits(),
    )

    private fun cardVolume(state: VolumeState = VolumeState.MOUNTED, root: Path? = card) = Volume(
        CARD, "SD", root, state,
        isPrimary = false, isRemovable = true, fuseBacked = true, traits = VolumeTraits(fatNames = true),
    )

    private fun deps(): ProviderDeps {
        // What only writing uses.
        val lookup = VolumeLookup { null }
        val bin = RecycleBin(NioFsBackend, lookup, clock = { 0L }, ids = { "id" })
        val labels = NameLabels(newFolder = "New folder", newTextDocument = "New Text Document", copySuffix = " - Copy")
        val fileOps = FileOps(NioFsBackend, lookup, labels, NoJournal, ids = { "id" }, bin = bin)
        val mediaScan = object : MediaScan {
            override fun scan(paths: List<Path>) = Unit
        }
        return ProviderDeps(
            NioFsBackend, { volumes }, settings, fileOps, bin, NoJournal, mediaScan, port, MimeResolver(),
            granted = { granted }, internalLabel = INTERNAL, io = Dispatchers.IO, ids = { "id" },
        )
    }

    /** The rows of [cursor], which is closed then: each a map from column to value (null, a Long or a String). */
    private fun rowsOf(cursor: Cursor): List<Map<String, Any?>> = cursor.use {
        val rows = ArrayList<Map<String, Any?>>()
        while (it.moveToNext()) {
            rows += it.columnNames.withIndex().associate { (i, column) ->
                column to when (it.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> it.getLong(i)
                    else -> it.getString(i)
                }
            }
        }
        rows
    }

    private fun document(id: String): Map<String, Any?> = rowsOf(provider.queryDocument(id, null)).single()

    private fun children(id: String): List<Map<String, Any?>> =
        rowsOf(provider.queryChildDocuments(id, null, null as String?))

    private fun search(query: String): List<Any?> =
        rowsOf(provider.querySearchDocuments(ROOT_ID, query, null)).column(ID)

    private fun List<Map<String, Any?>>.column(name: String): List<Any?> = map { it[name] }

    private fun Map<String, Any?>.flags(): Int = (getValue(Document.COLUMN_FLAGS) as Long).toInt()

    /** Makes a pipe, a special file, at [path], where the system has `mkfifo`. */
    private fun mkfifo(path: Path) {
        try {
            ProcessBuilder("mkfifo", path.toString()).start().waitFor()
        } catch (e: IOException) {
            // No pipe, then.
        }
    }

    /**
     * The JPEG [afd] holds, read to its end marker: Robolectric's pipe is a file, which reads as ended where the
     * writer, on its own thread, has not written yet.
     */
    private fun jpegOf(afd: AssetFileDescriptor): ByteArray = afd.createInputStream().use { input ->
        val read = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!read.toByteArray().endsWithJpegEnd()) {
            val count = input.read(buffer)
            if (count > 0) {
                read.write(buffer, 0, count)
            } else {
                check(System.nanoTime() < deadline) { "No end of a JPEG after ${read.size()} bytes" }
                Thread.sleep(10)
            }
        }
        read.toByteArray()
    }

    private fun ByteArray.endsWithJpegEnd() = size >= 2 && this[size - 2] == 0xFF.toByte() && last() == 0xD9.toByte()

    @Test
    fun noRootsWithoutPermission() {
        granted = false
        assertEquals(emptyList<Map<String, Any?>>(), rowsOf(provider.queryRoots(null)))
        // Asked at each call: the root is back with the access.
        granted = true
        assertEquals(listOf(ROOT_ID), rowsOf(provider.queryRoots(null)).column(Root.COLUMN_ROOT_ID))
    }

    @Test
    fun declaredForTheSystemPicker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // As the system picker finds the providers it lists, which it alone may connect to.
        val found = context.packageManager.queryIntentContentProviders(Intent(DocumentsContract.PROVIDER_INTERFACE), 0)
        val info = found.map { it.providerInfo }.single { it.name == ExplorerDocumentsProvider::class.java.name }
        assertEquals(AUTHORITY, info.authority)
        assertTrue(info.exported)
        assertTrue(info.grantUriPermissions)
        assertEquals(Manifest.permission.MANAGE_DOCUMENTS, info.readPermission)
        assertEquals(Manifest.permission.MANAGE_DOCUMENTS, info.writePermission)
    }

    @Test
    fun oneRootWithFlags() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = rowsOf(provider.queryRoots(null)).single()
        assertEquals(ROOT_ID, root[Root.COLUMN_ROOT_ID])
        assertEquals(ROOT_DOC_ID, root[Root.COLUMN_DOCUMENT_ID])
        val flags = Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_RECENTS or Root.FLAG_SUPPORTS_SEARCH or
            Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY
        assertEquals(flags.toLong(), root[Root.COLUMN_FLAGS])
        assertEquals(context.getString(R.string.app_name), root[Root.COLUMN_TITLE])
        assertEquals(R.mipmap.ic_launcher.toLong(), root[Root.COLUMN_ICON])
        // The space free on the primary volume.
        assertTrue(root.toString(), (root[Root.COLUMN_AVAILABLE_BYTES] as Long) > 0)

        // Its document is the folder of the favorites and the drives, called as the root is.
        val top = document(ROOT_DOC_ID)
        assertEquals(Document.MIME_TYPE_DIR, top[MIME])
        assertEquals(context.getString(R.string.app_name), top[NAME])
    }

    @Test
    fun rootListsFavoritesThenDrives() {
        Files.createDirectories(primary.resolve("Download"))
        Files.createDirectories(primary.resolve("Documents/Work"))
        write(primary, "a.txt")
        settings.setFavorites(
            listOf(
                Favorite("primary", "Download", "Download"),
                // A volume's root: its drive is there.
                Favorite(CARD, "", "SD"),
                // Not there, not a folder, on a volume that is out.
                Favorite("primary", "gone", "gone"),
                Favorite("primary", "a.txt", "a"),
                Favorite("9999-0000", "x", "x"),
            ),
        )

        val top = children(ROOT_DOC_ID)
        assertEquals(listOf("Download", INTERNAL, "SD"), top.column(NAME))
        assertEquals(listOf("primary:Download", "primary:", "$CARD:"), top.column(ID))
        assertEquals(List(3) { Document.MIME_TYPE_DIR }, top.column(MIME))
        // A drive is called so when the picker reads it alone too, as it does going into it.
        assertEquals(INTERNAL, document("primary:")[NAME])
        assertEquals("SD", document("$CARD:")[NAME])

        // In the user's order, each by its label.
        settings.setFavorites(
            listOf(Favorite("primary", "Documents/Work", "Reports"), Favorite("primary", "Download", "Download")),
        )
        val pinned = children(ROOT_DOC_ID)
        assertEquals(listOf("Reports", "Download", INTERNAL, "SD"), pinned.column(NAME))
        assertEquals("primary:Documents/Work", pinned.first()[ID])
    }

    @Test
    fun childrenHideInternalRestrictedLinksAndSpecials() {
        write(primary, "a.txt")
        Files.createDirectories(primary.resolve("dir"))
        write(primary, ".hidden.txt")
        write(primary, ".Recycle.Bin/1/x.txt")
        write(primary, ".a.txt.op1.xpart")
        write(primary, "b.XPART")
        write(primary, "Android/data/com.example/x")
        write(primary, "Android/obb/com.example/y")
        write(primary, "Android/media/m.txt")
        Files.createSymbolicLink(primary.resolve("link.txt"), primary.resolve("a.txt"))
        Files.createSymbolicLink(primary.resolve("linkDir"), primary.resolve("dir"))
        mkfifo(primary.resolve("pipe"))

        // Hidden items are there: the picker shows them as its own setting says.
        assertEquals(setOf(".hidden.txt", "Android", "a.txt", "dir"), children("primary:").column(NAME).toSet())
        assertEquals(listOf("media"), children("primary:Android").column(NAME))
    }

    @Test
    fun childRowColumns() {
        val a = write(primary, "docs/a.txt", "hello")
        Files.setLastModifiedTime(a, FileTime.fromMillis(A_TIME))
        val sub = Files.createDirectories(primary.resolve("docs/sub"))
        Files.setLastModifiedTime(sub, FileTime.fromMillis(SUB_TIME))
        write(primary, "docs/notes.unknownext", "x")

        val rows = children("primary:docs").associateBy { it[NAME] }
        assertEquals(setOf("a.txt", "sub", "notes.unknownext"), rows.keys)
        val file = rows.getValue("a.txt")
        assertEquals("primary:docs/a.txt", file[ID])
        assertEquals("text/plain", file[MIME])
        assertEquals(5L, file[Document.COLUMN_SIZE])
        assertEquals(A_TIME, file[Document.COLUMN_LAST_MODIFIED])
        val dir = rows.getValue("sub")
        assertEquals("primary:docs/sub", dir[ID])
        assertEquals(Document.MIME_TYPE_DIR, dir[MIME])
        assertNull(dir[Document.COLUMN_SIZE])
        assertEquals(SUB_TIME, dir[Document.COLUMN_LAST_MODIFIED])
        // A type no one knows.
        assertEquals("application/octet-stream", rows.getValue("notes.unknownext")[MIME])

        // A document reads the same alone, and its type is known on its own.
        assertEquals(file, document("primary:docs/a.txt"))
        assertEquals("text/plain", provider.getDocumentType("primary:docs/a.txt"))
        assertEquals(Document.MIME_TYPE_DIR, provider.getDocumentType("primary:docs/sub"))
        assertEquals(Document.MIME_TYPE_DIR, provider.getDocumentType(ROOT_DOC_ID))
    }

    @Test
    fun downloadAndVolumeRootsBlockTree() {
        Files.createDirectories(primary.resolve("Download"))
        Files.createDirectories(primary.resolve("Documents"))
        Files.createDirectories(card.resolve("Download"))
        val folder = CREATE or DELETE or RENAME

        assertEquals(BLOCKS_TREE, document(ROOT_DOC_ID).flags())
        assertEquals(CREATE or BLOCKS_TREE, document("primary:").flags())
        assertEquals(CREATE or BLOCKS_TREE, document("$CARD:").flags())
        assertEquals(folder or BLOCKS_TREE, document("primary:Download").flags())
        assertEquals(folder, document("primary:Documents").flags())
        // In any case: on the volume, which ignores it, that is the same folder.
        Files.createDirectories(primary.resolve("download"))
        assertEquals(folder or BLOCKS_TREE, document("primary:download").flags())
        // Only the primary volume's Download.
        assertEquals(folder, document("$CARD:Download").flags())

        // As the folder of the favorites and the drives lists them, and the primary volume's root.
        settings.setFavorites(listOf(Favorite("primary", "Download", "Download")))
        val top = children(ROOT_DOC_ID).associate { it[ID] to it.flags() }
        val blocked = mapOf(
            "primary:Download" to (folder or BLOCKS_TREE),
            "primary:" to (CREATE or BLOCKS_TREE),
            "$CARD:" to (CREATE or BLOCKS_TREE),
        )
        assertEquals(blocked, top)
        assertEquals(folder or BLOCKS_TREE, children("primary:").single { it[NAME] == "Download" }.flags())
    }

    @Test
    fun readOnlyVolumeHasNoWriteFlags() {
        volumes = listOf(primaryVolume(), cardVolume(VolumeState.MOUNTED_READ_ONLY))
        write(card, "a.txt")
        write(card, "p.jpg")
        Files.createDirectories(card.resolve("dir"))
        write(primary, "b.txt")

        assertEquals(BLOCKS_TREE, document("$CARD:").flags())
        val flags = children("$CARD:").associate { it[NAME] to it.flags() }
        assertEquals(mapOf("a.txt" to 0, "p.jpg" to THUMBNAIL, "dir" to 0), flags)
        assertEquals(BLOCKS_TREE, children(ROOT_DOC_ID).single { it[ID] == "$CARD:" }.flags())
        // The primary volume takes changes.
        assertEquals(WRITE or DELETE or RENAME, document("primary:b.txt").flags())
    }

    @Test
    fun openForReadReturnsContent() {
        val a = write(primary, "docs/a.txt", "hello")
        val opened = provider.openDocument("primary:docs/a.txt", "r", null)
        assertEquals("hello", ParcelFileDescriptor.AutoCloseInputStream(opened).use { String(it.readBytes()) })

        // Nothing is written through the picker yet: every other mode is refused, and the file stays as it was.
        for (mode in listOf("w", "wt", "wa", "rw", "rwt")) {
            assertThrows(mode, FileNotFoundException::class.java) {
                provider.openDocument("primary:docs/a.txt", mode, null)
            }
        }
        assertEquals("hello", String(Files.readAllBytes(a)))
    }

    @Test
    fun openFolderIsNotFound() {
        Files.createDirectories(primary.resolve("dir"))
        // Nor a special file, whose reading would wait for a writer.
        mkfifo(primary.resolve("pipe"))
        for (id in listOf("primary:dir", "primary:", ROOT_DOC_ID, "primary:missing.txt", "primary:pipe")) {
            assertThrows(id, FileNotFoundException::class.java) { provider.openDocument(id, "r", null) }
        }
    }

    @Test
    fun thumbnailForImageOnly() {
        // The fake port does not read the files.
        for (name in listOf("p.jpg", "v.mp4", "s.mp3", "a.txt")) write(primary, name)

        val jpeg = jpegOf(provider.openDocumentThumbnail("primary:p.jpg", Point(96, 64), null))
        assertTrue(jpeg.size > 0)
        assertEquals(listOf(0xFF.toByte(), 0xD8.toByte()), jpeg.take(2))
        // As big as the larger side asked for.
        assertEquals(listOf(primary.resolve("p.jpg") to 96), port.asked.toList())
        assertTrue(jpegOf(provider.openDocumentThumbnail("primary:v.mp4", Point(64, 64), null)).isNotEmpty())

        // Of pictures and videos only, and none when the port makes none: the picker shows its own icon then.
        for (id in listOf("primary:a.txt", "primary:s.mp3", "primary:", ROOT_DOC_ID)) {
            assertThrows(id, FileNotFoundException::class.java) {
                provider.openDocumentThumbnail(id, Point(64, 64), null)
            }
        }
        assertEquals(2, port.asked.size)
        port.makes = false
        assertThrows(FileNotFoundException::class.java) {
            provider.openDocumentThumbnail("primary:p.jpg", Point(64, 64), null)
        }
    }

    @Test
    fun recentsAreExistingAppRecents() {
        write(primary, "a.txt")
        write(card, "b.txt")
        write(primary, "c.txt")
        Files.createDirectories(primary.resolve("d"))
        write(primary, ".Recycle.Bin/1/e.txt")
        val opened = listOf(
            "primary" to "a.txt", CARD to "b.txt", "primary" to "gone.txt", "primary" to "d",
            "primary" to ".Recycle.Bin/1/e.txt", "primary" to "c.txt", "9999-0000" to "x.txt",
        )
        for ((i, place) in opened.withIndex()) settings.addRecent(Recent(place.first, place.second, openedAt = i + 1L))

        // Newest first; what is gone, no file, the app's own or on a volume that is out is left out.
        val recents = rowsOf(provider.queryRecentDocuments(ROOT_ID, null))
        assertEquals(listOf("primary:c.txt", "$CARD:b.txt", "primary:a.txt"), recents.column(ID))
        // Of no other root.
        assertThrows(FileNotFoundException::class.java) { provider.queryRecentDocuments("other", null) }
        assertThrows(FileNotFoundException::class.java) { provider.querySearchDocuments("other", "a", null) }
    }

    @Test
    fun searchFindsAndCapsAt200() {
        write(primary, "docs/report.txt")
        write(card, "Report 2.txt")
        Files.createDirectories(primary.resolve("reports"))
        write(primary, "Android/media/report.ogg")
        // Never found: hidden, in the Recycle Bin, withheld, a link.
        write(primary, ".report.txt")
        write(primary, ".private/report.txt")
        write(primary, ".Recycle.Bin/1/report.txt")
        write(primary, "Android/data/com.example/report.txt")
        write(primary, "Android/obb/com.example/report.txt")
        Files.createSymbolicLink(primary.resolve("report-link.txt"), primary.resolve("docs/report.txt"))

        val found = setOf(
            "primary:docs/report.txt", "primary:reports", "primary:Android/media/report.ogg", "$CARD:Report 2.txt",
        )
        assertEquals(found, search("report").toSet())
        // A blank query finds nothing.
        assertEquals(emptyList<Any?>(), search(" "))

        // 250 match: 200 come.
        for (i in 1..250) write(primary, "many/match-$i.txt", "")
        val matches = search("match")
        assertEquals(200, matches.size)
        assertEquals(200, matches.toSet().size)
    }

    @Test
    fun isChildDocumentChecksPaths() {
        write(primary, "a/b/c.txt")
        write(primary, "ab/x.txt")
        write(card, "a/c.txt")
        assertTrue(provider.isChildDocument("primary:a", "primary:a/b/c.txt"))
        assertTrue(provider.isChildDocument("primary:a", "primary:a/b"))
        assertTrue(provider.isChildDocument("primary:", "primary:a/b/c.txt"))
        // Not a sibling, even one whose name starts the same; not the folder above; not on another volume.
        assertFalse(provider.isChildDocument("primary:a", "primary:ab/x.txt"))
        assertFalse(provider.isChildDocument("primary:a/b", "primary:a"))
        assertFalse(provider.isChildDocument("primary:a", "$CARD:a/c.txt"))
        assertFalse(provider.isChildDocument("primary:", "$CARD:a/c.txt"))
        // The folder of the favorites and the drives is no folder another app is given.
        assertFalse(provider.isChildDocument(ROOT_DOC_ID, "primary:a"))
        // Nor is what is not there in a folder, or what a link in it leads to elsewhere.
        assertFalse(provider.isChildDocument("primary:a", "primary:a/missing.txt"))
        Files.createSymbolicLink(primary.resolve("a/out"), primary.resolve("ab"))
        assertFalse(provider.isChildDocument("primary:a", "primary:a/out/x.txt"))
    }

    @Test
    fun ejectedVolumeDocumentsAreNotFound() {
        write(card, "DCIM/p.jpg")
        assertEquals("p.jpg", document("$CARD:DCIM/p.jpg")[NAME])

        // Android reports no root while the volume ejects; then it is gone from the list.
        val ejecting = listOf(primaryVolume(), cardVolume(VolumeState.EJECTING, root = null))
        for (out in listOf(ejecting, listOf(primaryVolume()))) {
            volumes = out
            val calls = listOf<() -> Any>(
                { provider.queryDocument("$CARD:DCIM/p.jpg", null) },
                { provider.openDocument("$CARD:DCIM/p.jpg", "r", null) },
                { provider.queryChildDocuments("$CARD:DCIM", null, null as String?) },
                { provider.openDocumentThumbnail("$CARD:DCIM/p.jpg", Point(64, 64), null) },
                { provider.getDocumentType("$CARD:") },
            )
            for (call in calls) assertThrows(FileNotFoundException::class.java) { call() }
            // The drives go without it; the rest is as it was.
            assertEquals(listOf("primary:"), children(ROOT_DOC_ID).column(ID))
            assertFalse(provider.isChildDocument("$CARD:", "$CARD:DCIM/p.jpg"))
            assertEquals(emptyList<Any?>(), search("p.jpg"))
        }
    }

    @Test
    fun volumeWhoseKeyHasAColonIsLeftOut() {
        // Android keys a volume without a file system UUID by the name of its folder, such as `public:8,1`, which no
        // id can name: an id's first colon ends its volume's key.
        val usb = Files.createDirectory(folder.root.toPath().toRealPath().resolve("public:8,1"))
        write(usb, "report.txt")
        volumes = volumes + Volume(
            "public:8,1", "USB", usb, VolumeState.MOUNTED,
            isPrimary = false, isRemovable = true, fuseBacked = true, traits = VolumeTraits(fatNames = true),
        )

        assertEquals(listOf("primary:", "$CARD:"), children(ROOT_DOC_ID).column(ID))
        assertEquals(emptyList<Any?>(), search("report"))
    }

    @Test
    fun childrenCursorHasNotificationUri() {
        Files.createDirectories(primary.resolve("dir"))
        for (id in listOf(ROOT_DOC_ID, "primary:", "primary:dir")) {
            provider.queryChildDocuments(id, null, null as String?).use { cursor ->
                assertEquals(id, DocumentsContract.buildChildDocumentsUri(AUTHORITY, id), cursor.notificationUri)
            }
        }
    }

    private companion object {
        const val AUTHORITY = "io.github.sakkijarvenpolkka.explorer.documents"
        const val ROOT_ID = "explorer"
        const val CARD = "1A2B-3C4D"
        const val INTERNAL = "내부 저장소"
        const val A_TIME = 1_700_000_000_000L
        const val SUB_TIME = 1_600_000_000_000L

        const val ID = Document.COLUMN_DOCUMENT_ID
        const val NAME = Document.COLUMN_DISPLAY_NAME
        const val MIME = Document.COLUMN_MIME_TYPE

        const val CREATE = Document.FLAG_DIR_SUPPORTS_CREATE
        const val DELETE = Document.FLAG_SUPPORTS_DELETE
        const val RENAME = Document.FLAG_SUPPORTS_RENAME
        const val WRITE = Document.FLAG_SUPPORTS_WRITE
        const val THUMBNAIL = Document.FLAG_SUPPORTS_THUMBNAIL
        const val BLOCKS_TREE = Document.FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE
    }
}
