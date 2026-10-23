// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.state.Command.COMPRESS
import io.github.sakkijarvenpolkka.explorer.state.Command.COPY
import io.github.sakkijarvenpolkka.explorer.state.Command.CUT
import io.github.sakkijarvenpolkka.explorer.state.Command.DELETE
import io.github.sakkijarvenpolkka.explorer.state.Command.DELETE_PERMANENT
import io.github.sakkijarvenpolkka.explorer.state.Command.EMPTY_BIN
import io.github.sakkijarvenpolkka.explorer.state.Command.EXTRACT
import io.github.sakkijarvenpolkka.explorer.state.Command.INVERT
import io.github.sakkijarvenpolkka.explorer.state.Command.NEW_FOLDER
import io.github.sakkijarvenpolkka.explorer.state.Command.NEW_TEXT
import io.github.sakkijarvenpolkka.explorer.state.Command.OPEN
import io.github.sakkijarvenpolkka.explorer.state.Command.OPEN_WITH
import io.github.sakkijarvenpolkka.explorer.state.Command.OPTIONS
import io.github.sakkijarvenpolkka.explorer.state.Command.PASTE
import io.github.sakkijarvenpolkka.explorer.state.Command.PIN
import io.github.sakkijarvenpolkka.explorer.state.Command.PROPERTIES
import io.github.sakkijarvenpolkka.explorer.state.Command.REFRESH
import io.github.sakkijarvenpolkka.explorer.state.Command.RENAME
import io.github.sakkijarvenpolkka.explorer.state.Command.RESTORE
import io.github.sakkijarvenpolkka.explorer.state.Command.SELECT_ALL
import io.github.sakkijarvenpolkka.explorer.state.Command.SELECT_NONE
import io.github.sakkijarvenpolkka.explorer.state.Command.SHARE
import io.github.sakkijarvenpolkka.explorer.state.Command.UNPIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class CommandsTest {

    private val dir = Paths.get("/storage/emulated/0/Download")
    private val clipboard = Clipboard(listOf(Paths.get("/storage/emulated/0/x.txt")), ClipMode.COPY)
    private val mutating = setOf(
        NEW_FOLDER, NEW_TEXT, CUT, PASTE, RENAME, DELETE, DELETE_PERMANENT, COMPRESS, EXTRACT, RESTORE, EMPTY_BIN,
    )

    private fun file(name: String) = FileEntry(dir.resolve(name), name, EntryKind.FILE, 10, 0, null, false, null)

    private fun folder(name: String) = FileEntry(dir.resolve(name), name, EntryKind.DIR, 0, 0, null, false, null)

    private fun commands(
        kind: LocationKind = LocationKind.FOLDER,
        selection: List<FileEntry> = emptyList(),
        clipboard: Clipboard? = null,
        restricted: Boolean = false,
        volumeWritable: Boolean = true,
        pinned: Boolean = false,
        binEmpty: Boolean = true,
        hasEntries: Boolean = true,
    ): Set<Command> = commandsFor(
        CommandContext(kind, selection, clipboard, restricted, volumeWritable, pinned, binEmpty, hasEntries),
    )

    private fun assertHas(commands: Set<Command>, vararg expected: Command) {
        val missing = expected.filterNot { it in commands }
        assertTrue("missing $missing in $commands", missing.isEmpty())
    }

    private fun assertLacks(commands: Set<Command>, vararg unexpected: Command) {
        val present = unexpected.filter { it in commands }
        assertTrue("unexpected $present in $commands", present.isEmpty())
    }

    @Test
    fun folderWithoutSelectionOffersNewAndFolderCommands() {
        val none = commands()
        assertHas(none, NEW_FOLDER, NEW_TEXT, SELECT_ALL, INVERT, REFRESH, PROPERTIES, OPTIONS)
        assertLacks(none, CUT, COPY, DELETE, DELETE_PERMANENT, RENAME, OPEN, OPEN_WITH, SHARE, SELECT_NONE, PASTE)

        val empty = commands(hasEntries = false)
        assertHas(empty, NEW_FOLDER, NEW_TEXT, REFRESH, PROPERTIES)
        assertLacks(empty, SELECT_ALL, INVERT)
    }

    @Test
    fun oneFileSelectedOffersFileCommands() {
        val one = commands(selection = listOf(file("report.pdf")))
        assertHas(
            one, OPEN, OPEN_WITH, CUT, COPY, RENAME, SHARE, DELETE, DELETE_PERMANENT, COMPRESS, PROPERTIES, SELECT_NONE,
        )
        assertLacks(one, EXTRACT, PIN, UNPIN, RESTORE, EMPTY_BIN)
    }

    @Test
    fun twoSelectedCannotBeRenamedOrOpenedWith() {
        val two = commands(selection = listOf(file("a.txt"), file("b.txt")))
        assertLacks(two, RENAME, OPEN_WITH)
        assertHas(two, OPEN, CUT, COPY, SHARE, DELETE, DELETE_PERMANENT, COMPRESS, PROPERTIES)
    }

    @Test
    fun foldersCanBePinnedButNotShared() {
        val folders = listOf(folder("Music"), folder("Movies"))
        val unpinned = commands(selection = folders)
        assertHas(unpinned, PIN, OPEN, CUT, COPY, DELETE)
        assertLacks(unpinned, SHARE, UNPIN, OPEN_WITH)
        val pinned = commands(selection = folders, pinned = true)
        assertHas(pinned, UNPIN)
        assertLacks(pinned, PIN)

        val single = commands(selection = listOf(folder("Music")))
        assertHas(single, RENAME, PIN)
        assertLacks(single, OPEN_WITH, SHARE, EXTRACT)

        val mixed = commands(selection = listOf(folder("Music"), file("a.txt")))
        assertLacks(mixed, SHARE, PIN, UNPIN)

        assertHas(commands(), PIN)
        assertHas(commands(pinned = true), UNPIN)
        assertLacks(commands(pinned = true), PIN)
    }

    @Test
    fun oneZipCanBeExtracted() {
        assertHas(commands(selection = listOf(file("photos.zip"))), EXTRACT)
        assertHas(commands(selection = listOf(file("PHOTOS.ZIP"))), EXTRACT)
        assertLacks(commands(selection = listOf(file("a.zip"), file("b.zip"))), EXTRACT)
        assertLacks(commands(selection = listOf(folder("old.zip"))), EXTRACT)
        assertLacks(commands(selection = listOf(file("zip.txt"))), EXTRACT)
    }

    @Test
    fun pasteNeedsAClipboardAndAWritableFolder() {
        assertHas(commands(clipboard = clipboard), PASTE)
        assertHas(commands(clipboard = clipboard, selection = listOf(file("a.txt"))), PASTE)
        assertLacks(commands(), PASTE)
        assertLacks(commands(clipboard = clipboard, volumeWritable = false), PASTE)
        assertLacks(commands(clipboard = Clipboard(emptyList(), ClipMode.CUT)), PASTE)
    }

    @Test
    fun restrictedFolderCannotChangeItems() {
        val restricted = commands(restricted = true, clipboard = clipboard, selection = listOf(file("a.zip")))
        assertLacks(restricted, NEW_FOLDER, NEW_TEXT, PASTE, RENAME, COMPRESS, EXTRACT, CUT, DELETE, DELETE_PERMANENT)
        assertHas(restricted, OPEN, COPY, PROPERTIES, REFRESH)
    }

    @Test
    fun recycleBinOffersRestoreAndPermanentDeleteOnly() {
        val selection = listOf(file("a.txt"), folder("b"))
        val selected = commands(LocationKind.BIN, selection, clipboard, binEmpty = false)
        assertHas(selected, RESTORE, DELETE_PERMANENT, EMPTY_BIN, SELECT_ALL, SELECT_NONE, INVERT, REFRESH)
        assertLacks(
            selected, NEW_FOLDER, NEW_TEXT, PASTE, CUT, COPY, DELETE, RENAME, OPEN, OPEN_WITH, SHARE, COMPRESS,
            EXTRACT, PIN, UNPIN, PROPERTIES,
        )

        val none = commands(LocationKind.BIN, binEmpty = false)
        assertHas(none, EMPTY_BIN)
        assertLacks(none, RESTORE, DELETE_PERMANENT, SELECT_NONE)

        assertLacks(commands(LocationKind.BIN, binEmpty = true, hasEntries = false), EMPTY_BIN, SELECT_ALL)
    }

    @Test
    fun searchResultsCannotPasteOrCreate() {
        val selected = commands(LocationKind.SEARCH, listOf(file("report.pdf")), clipboard)
        assertLacks(selected, PASTE, NEW_FOLDER, NEW_TEXT)
        assertHas(selected, OPEN, OPEN_WITH, CUT, COPY, RENAME, SHARE, DELETE, COMPRESS, PROPERTIES, REFRESH)

        val none = commands(LocationKind.SEARCH, clipboard = clipboard)
        assertLacks(none, PASTE, NEW_FOLDER, NEW_TEXT, PROPERTIES, PIN, UNPIN)
        assertHas(none, SELECT_ALL, REFRESH)
    }

    @Test
    fun homeOffersRefreshAndOptionsOnly() {
        assertEquals(setOf(REFRESH, OPTIONS), commands(LocationKind.HOME, clipboard = clipboard))
        assertEquals(
            setOf(REFRESH, OPTIONS, OPEN),
            commands(LocationKind.HOME, listOf(folder("Music"), file("a.zip")), clipboard, binEmpty = false),
        )
    }

    @Test
    fun optionsIsAlwaysOffered() {
        val selections = listOf(emptyList(), listOf(file("a.zip")), listOf(folder("b"), file("c")))
        for (kind in LocationKind.entries) {
            for (selection in selections) {
                for (flag in listOf(false, true)) {
                    val offered = commands(
                        kind, selection, clipboard.takeIf { flag }, restricted = flag, volumeWritable = !flag,
                        pinned = flag, binEmpty = flag, hasEntries = !flag,
                    )
                    assertHas(offered, OPTIONS)
                }
            }
        }
    }

    @Test
    fun readOnlyVolumeOffersNoChanges() {
        for (kind in LocationKind.entries) {
            for (selection in listOf(emptyList(), listOf(file("a.zip")))) {
                val offered = commands(kind, selection, clipboard, volumeWritable = false, binEmpty = false)
                assertEquals("$kind $selection", emptySet<Command>(), offered intersect mutating)
            }
        }
        assertHas(
            commands(selection = listOf(file("a.zip")), clipboard = clipboard, volumeWritable = false),
            OPEN, OPEN_WITH, COPY, SHARE, PROPERTIES, SELECT_ALL, SELECT_NONE, REFRESH, OPTIONS,
        )
    }
}
