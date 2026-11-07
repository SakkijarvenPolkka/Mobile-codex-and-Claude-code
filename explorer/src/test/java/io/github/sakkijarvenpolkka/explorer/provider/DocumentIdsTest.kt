// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.provider

import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.VolumeRoot
import io.github.sakkijarvenpolkka.explorer.fs.VolumeTraits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class DocumentIdsTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A place out of [folder], for links to lead to: not the system's temporary folder itself. */
    @get:Rule
    val elsewhere = TemporaryFolder()

    private lateinit var temp: Path
    private lateinit var primary: Path
    private lateinit var card: Path

    /** The volumes mounted now: [ids] asks for them at each call. */
    private var mounted = emptyList<VolumeRoot>()

    private val ids = DocumentIds(NioFsBackend) { mounted }

    @Before
    fun setUp() {
        temp = folder.root.toPath()
        primary = folder.newFolder("primary").toPath()
        card = folder.newFolder(CARD).toPath()
        mounted = listOf(
            VolumeRoot("primary", primary, VolumeTraits(), removable = false),
            VolumeRoot(CARD, card, VolumeTraits(fatNames = true), removable = true),
        )
    }

    /** A file at [rel] under [root], made with the folders on its way. */
    private fun file(root: Path, rel: String): Path {
        val path = root.resolve(rel)
        Files.createDirectories(path.parent)
        Files.write(path, rel.toByteArray())
        return path
    }

    private fun link(at: Path, target: Path): Path = Files.createSymbolicLink(at, target)

    private fun assertRefused(id: String) {
        assertThrows(id, FileNotFoundException::class.java) { ids.resolve(id) }
    }

    @Test
    fun roundTripsFilesAndFolders() {
        val file = file(primary, "a/b.txt")
        assertEquals("primary:a/b.txt", ids.idOf(file))
        assertEquals(file, ids.resolve("primary:a/b.txt"))
        assertEquals("primary:a", ids.idOf(file.parent))
        assertEquals(file.parent, ids.resolve("primary:a"))

        val photo = file(card, "DCIM/Camera/c.jpg")
        assertEquals("$CARD:DCIM/Camera/c.jpg", ids.idOf(photo))
        assertEquals(photo, ids.resolve("$CARD:DCIM/Camera/c.jpg"))
        assertEquals("$CARD:DCIM", ids.idOf(card.resolve("DCIM")))
        assertEquals(card.resolve("DCIM"), ids.resolve("$CARD:DCIM"))
    }

    @Test
    fun volumeRootHasEmptyRel() {
        assertEquals("primary:", ids.idOf(primary))
        assertEquals(primary, ids.resolve("primary:"))
        assertEquals("$CARD:", ids.idOf(card))
        assertEquals(card, ids.resolve("$CARD:"))
    }

    @Test
    fun colonAndUnicodeNamesRoundTrip() {
        // An id is split at its first colon only and is not decoded: the rest is the names as they are.
        for (rel in listOf("a:b.txt", "한글 #1 %.txt", "100%25 ?.txt", "폴더 :1/a:b:c", "$CARD:x.txt")) {
            val path = file(primary, rel)
            assertEquals(rel, "primary:$rel", ids.idOf(path))
            assertEquals(rel, path, ids.resolve("primary:$rel"))
        }
    }

    @Test
    fun refusesTraversalAndJunk() {
        file(primary, "a/b")
        // What the junk would name, read loosely, is there: only the form of the id refuses it.
        file(temp, "x")
        val junk = listOf(
            "primary:../x", "primary:a/./b", "primary:a//b", "primary:/a", "primary:a\u0000", "nope", "",
            "primary:.", "primary:..", "primary:a/", "primary:a/b/..", "primary:a/../a/b", "primary:a\u0000/b",
            ROOT_DOC_ID,
        )
        for (id in junk) assertRefused(id)
        assertEquals(primary.resolve("a/b"), ids.resolve("primary:a/b"))
    }

    @Test
    fun refusesUnmountedVolume() {
        assertRefused("9999-0000:x")
        assertRefused(":x")

        // A volume that is no longer mounted: its ids are refused from then on, and its files have none.
        val photo = file(card, "x")
        assertEquals(photo, ids.resolve("$CARD:x"))
        mounted = mounted.filter { it.uuid != CARD }
        assertRefused("$CARD:x")
        assertRefused("$CARD:")
        assertNull(ids.idOf(photo))
    }

    @Test
    fun refusesSymlinkOutOfVolume() {
        val outside = file(temp, "outside/secret.txt").parent
        val farAway = file(elsewhere.root.toPath(), "far.txt").parent
        val target = file(primary, "target/f.txt").parent
        val photo = file(card, "c.jpg")
        link(primary.resolve("toCard"), card)
        link(primary.resolve("toPhoto"), photo)
        link(primary.resolve("toOutside"), outside)
        link(primary.resolve("toElsewhere"), farAway)
        link(primary.resolve("up"), Paths.get("../outside"))
        link(primary.resolve("chain"), primary.resolve("toOutside"))
        link(primary.resolve("dangling"), primary.resolve("nothing"))
        link(card.resolve("toPrimary"), primary)
        val out = listOf(
            "primary:toCard", "primary:toCard/c.jpg", "primary:toPhoto", "primary:toOutside",
            "primary:toOutside/secret.txt", "primary:toElsewhere", "primary:toElsewhere/far.txt", "primary:up",
            "primary:up/secret.txt", "primary:chain", "primary:dangling", "$CARD:toPrimary",
            "$CARD:toPrimary/target/f.txt",
        )
        for (id in out) assertRefused(id)

        // A link to a folder or a file on the same volume, relative or not, is followed.
        link(primary.resolve("in"), target)
        link(primary.resolve("inRelative"), Paths.get("target/f.txt"))
        assertEquals(primary.resolve("in"), ids.resolve("primary:in"))
        assertEquals(primary.resolve("in/f.txt"), ids.resolve("primary:in/f.txt"))
        assertEquals(primary.resolve("inRelative"), ids.resolve("primary:inRelative"))
        assertEquals("primary:in/f.txt", ids.idOf(primary.resolve("in/f.txt")))
    }

    @Test
    fun refusesRestrictedEvenObb() {
        // Other apps' data is never handed to a third app, whatever the app may do itself: obb included.
        for (rel in listOf("Android/data/x", "Android/obb/x", "android/DATA/y")) file(primary, rel)
        file(card, "Android/obb/z")
        val restricted = listOf(
            "primary:Android/data", "primary:Android/data/x", "primary:Android/obb", "primary:Android/obb/x",
            "primary:android/DATA/y", "$CARD:Android/obb/z",
        )
        for (id in restricted) assertRefused(id)
        // Nor through a link.
        link(primary.resolve("toData"), primary.resolve("Android/data"))
        assertRefused("primary:toData")
        assertRefused("primary:toData/x")

        // Android itself and its other folders are documents.
        val media = file(primary, "Android/media/m.txt")
        assertEquals(primary.resolve("Android"), ids.resolve("primary:Android"))
        assertEquals(media, ids.resolve("primary:Android/media/m.txt"))
    }

    @Test
    fun refusesInternalSegments() {
        for (rel in listOf(".Recycle.Bin/x", ".recycle.bin/y", "a/.b.op.xpart", "a/.c.op.xpart/d", "a/e.XPART")) {
            file(primary, rel)
        }
        file(card, ".Recycle.Bin/1/photo.jpg")
        val internal = listOf(
            "primary:.Recycle.Bin", "primary:.Recycle.Bin/x", "primary:.recycle.bin/y", "primary:a/.b.op.xpart",
            "primary:a/.c.op.xpart", "primary:a/.c.op.xpart/d", "primary:a/e.XPART", "$CARD:.Recycle.Bin/1/photo.jpg",
        )
        for (id in internal) assertRefused(id)
        // Nor through a link.
        link(primary.resolve("toBin"), primary.resolve(".Recycle.Bin"))
        assertRefused("primary:toBin")
        assertRefused("primary:toBin/x")

        // Other hidden names are documents.
        val hidden = file(primary, ".hidden/.notes.txt")
        assertEquals(hidden, ids.resolve("primary:.hidden/.notes.txt"))
        assertEquals("primary:.hidden/.notes.txt", ids.idOf(hidden))
    }

    @Test
    fun idOfReturnsNullForUnexposable() {
        file(primary, "a/b")
        file(temp, "outside/x")
        for (rel in listOf("Android/data/x", "Android/obb/x", ".Recycle.Bin/x", "a/.b.op.xpart")) file(primary, rel)
        link(primary.resolve("toOutside"), temp.resolve("outside"))
        link(primary.resolve("toCard"), card)
        link(primary.resolve("toBin"), primary.resolve(".Recycle.Bin"))
        val unexposable = listOf(
            // Not a plain path under a volume's root.
            primary.resolve("../outside/x"), primary.resolve("a/./b"), primary.resolve("a/b/.."),
            // On no volume.
            temp.resolve("outside/x"), temp, Paths.get("/"), Paths.get("a/b"),
            // Out of its volume through a link.
            primary.resolve("toOutside"), primary.resolve("toOutside/x"), primary.resolve("toCard"),
            // Withheld, and the app's own.
            primary.resolve("Android/data"), primary.resolve("Android/data/x"), primary.resolve("Android/obb/x"),
            primary.resolve(".Recycle.Bin"), primary.resolve(".Recycle.Bin/x"), primary.resolve("a/.b.op.xpart"),
            primary.resolve("toBin/x"),
            // Not there.
            primary.resolve("missing.txt"),
        )
        for (path in unexposable) assertNull(path.toString(), ids.idOf(path))
    }

    @Test
    fun volumeOfIsTheLongestRoot() {
        val usb = Files.createDirectories(primary.resolve("usb"))
        mounted = mounted + VolumeRoot("usb", usb, VolumeTraits(fatNames = true), removable = true)
        assertEquals("primary", ids.volumeOf(primary)?.uuid)
        assertEquals("primary", ids.volumeOf(primary.resolve("a/b"))?.uuid)
        assertEquals("usb", ids.volumeOf(usb)?.uuid)
        assertEquals("usb", ids.volumeOf(usb.resolve("x"))?.uuid)
        assertEquals(CARD, ids.volumeOf(card.resolve("x"))?.uuid)
        // By whole names: a folder whose name only starts like a root is on no volume.
        assertNull(ids.volumeOf(temp.resolve("primaryX")))
        assertNull(ids.volumeOf(temp))

        // An id names the volume a path is on.
        val onUsb = file(usb, "x")
        assertEquals("usb:x", ids.idOf(onUsb))
        assertEquals(onUsb, ids.resolve("usb:x"))
    }

    private companion object {
        const val CARD = "1A2B-3C4D"
    }
}
