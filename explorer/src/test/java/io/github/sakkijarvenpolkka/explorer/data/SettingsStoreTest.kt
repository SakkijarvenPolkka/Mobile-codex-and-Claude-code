// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.fs.SortKey
import io.github.sakkijarvenpolkka.explorer.fs.SortSpec
import io.github.sakkijarvenpolkka.explorer.fs.VolumeTraits
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.platform.VolumeState
import io.github.sakkijarvenpolkka.explorer.state.HistoryEntry
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.TabAction
import io.github.sakkijarvenpolkka.explorer.state.TabId
import io.github.sakkijarvenpolkka.explorer.state.TabsModel
import io.github.sakkijarvenpolkka.explorer.state.TabsReducer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Path
import java.nio.file.Paths

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences = context.getSharedPreferences("explorer_settings", Context.MODE_PRIVATE)

    private val primaryRoot: Path = Paths.get("/storage/emulated/0")
    private val sdRoot: Path = Paths.get("/storage/1A2B-3C4D")

    private fun volume(uuid: String, root: Path?, primary: Boolean = false) = Volume(
        uuid = uuid,
        label = uuid,
        root = root,
        state = if (root != null) VolumeState.MOUNTED else VolumeState.UNMOUNTED,
        isPrimary = primary,
        isRemovable = !primary,
        fuseBacked = true,
        traits = VolumeTraits(fatNames = !primary),
    )

    private val primary = volume("primary", primaryRoot, primary = true)
    private val sd = volume("1A2B-3C4D", sdRoot)
    private val codec = VolumeLocationCodec { listOf(primary, sd) }

    private fun folder(rel: String) = Location.Folder(primaryRoot.resolve(rel))

    private fun TabsModel.then(vararg actions: TabAction): TabsModel = actions.fold(this, TabsReducer::reduce)

    private fun storedSession(): PersistedSession =
        Json.decodeFromString(PersistedSession.serializer(), prefs.getString("session", null)!!)

    private fun storeSession(text: String) = prefs.edit(commit = true) { putString("session", text) }

    private fun storeSession(session: PersistedSession) =
        storeSession(Json.encodeToString(PersistedSession.serializer(), session))

    @Test
    fun sessionRoundTrips() {
        val model = TabsReducer.initial(folder("Download")).then(
            TabAction.Navigate(folder("Download/a")),
            TabAction.Navigate(folder("Download/a/b")),
            TabAction.Back,
            TabAction.NewTab(Location.Home),
            TabAction.Navigate(Location.Folder(sdRoot.resolve("Music"))),
            TabAction.Navigate(Location.RecycleBin),
            TabAction.Activate(TabId(1)),
        )
        SettingsStore(context).saveSession(model, codec)

        val restored = SettingsStore(context).loadSession(codec)!!
        assertEquals(model, restored.model)
        assertEquals(TabId(1), restored.model.activeId)
        assertEquals(listOf(HistoryEntry(folder("Download"))), restored.model.tabs[0].back)
        assertEquals(listOf(HistoryEntry(folder("Download/a/b"))), restored.model.tabs[0].forward)
        assertEquals(
            listOf(HistoryEntry(Location.Home), HistoryEntry(Location.Folder(sdRoot.resolve("Music")))),
            restored.model.tabs[1].back,
        )
        assertEquals(3L, restored.model.nextId)
        assertTrue(restored.unavailable.isEmpty())
        assertEquals(1, storedSession().v)
    }

    @Test
    fun oneCorruptTabDoesNotLoseOthers() {
        val model = TabsReducer.initial(folder("a")).then(
            TabAction.Navigate(folder("b")),
            TabAction.NewTab(folder("c")),
            TabAction.NewTab(folder("d")),
            TabAction.Activate(TabId(2)),
        )
        SettingsStore(context).saveSession(model, codec)
        val saved = storedSession()
        // The active tab 2 gets a location nothing decodes; tab 1 one history entry that does not.
        storeSession(
            saved.copy(
                tabs = saved.tabs.map {
                    when (it.id) {
                        1L -> it.copy(back = listOf("garbage") + it.back)
                        2L -> it.copy(location = "garbage")
                        else -> it
                    }
                },
            ),
        )

        val restored = SettingsStore(context).loadSession(codec)!!
        assertEquals(listOf(TabId(1), TabId(3)), restored.model.tabs.map { it.id })
        assertEquals(folder("b"), restored.model.tabs[0].current.location)
        assertEquals(listOf(HistoryEntry(folder("a"))), restored.model.tabs[0].back)
        assertEquals(folder("d"), restored.model.tabs[1].current.location)
        assertEquals(TabId(1), restored.model.activeId)
        assertEquals(4L, restored.model.nextId)

        // A tab that is not a tab at all is left out on its own too; unknown keys are ignored.
        storeSession(
            """{"v":1,"tabs":[{"id":7},{"id":8,"location":"home","back":[],"forward":[],"pinned":true}],"active":7}""",
        )
        val partial = SettingsStore(context).loadSession(codec)!!
        assertEquals(listOf(TabId(8)), partial.model.tabs.map { it.id })
        assertEquals(TabId(8), partial.model.activeId)
        assertEquals(Location.Home, partial.model.active.current.location)
        assertEquals(9L, partial.model.nextId)

        storeSession(saved.copy(tabs = saved.tabs.map { it.copy(location = "garbage") }))
        assertNull(SettingsStore(context).loadSession(codec))

        storeSession("not json")
        assertNull(SettingsStore(context).loadSession(codec))
    }

    @Test
    fun absentVolumeTabIsUnavailableNotHome() {
        val music = Location.Folder(sdRoot.resolve("Music"))
        val model = TabsReducer.initial(folder("Download")).then(
            TabAction.NewTab(Location.Folder(sdRoot)),
            TabAction.Navigate(music),
        )
        SettingsStore(context).saveSession(model, codec)

        val withoutCard = VolumeLocationCodec { listOf(primary) }
        val restored = SettingsStore(context).loadSession(withoutCard)!!
        assertEquals(mapOf(TabId(2) to DecodedLocation.Unavailable("1A2B-3C4D", "Music")), restored.unavailable)
        val tab = restored.model.tabs[1]
        assertEquals(music, tab.current.location)
        assertEquals(listOf(HistoryEntry(Location.Folder(sdRoot))), tab.back)
        assertEquals(TabId(2), restored.model.activeId)
        assertEquals(folder("Download"), restored.model.tabs[0].current.location)

        // A card that is known but not mounted is unavailable the same way.
        val unmounted = VolumeLocationCodec { listOf(primary, volume("1A2B-3C4D", null)) }
        assertEquals(restored.unavailable, SettingsStore(context).loadSession(unmounted)!!.unavailable)

        // Saved again while the card is out, the tab is still there when the card is back.
        SettingsStore(context).saveSession(restored.model, withoutCard)
        val again = SettingsStore(context).loadSession(codec)!!
        assertEquals(model, again.model)
        assertTrue(again.unavailable.isEmpty())
    }

    @Test
    fun searchResultsSavedAsRootFolder() {
        val search = Location.SearchResults(primaryRoot.resolve("DCIM"), "cat")
        assertEquals("folder:primary:DCIM", codec.encode(search))
        val model = TabsReducer.initial(folder("DCIM")).then(
            TabAction.Navigate(search),
            TabAction.Navigate(folder("DCIM/Camera")),
            TabAction.Back,
        )
        SettingsStore(context).saveSession(model, codec)

        val tab = SettingsStore(context).loadSession(codec)!!.model.active
        assertEquals(folder("DCIM"), tab.current.location)
        assertEquals(listOf(HistoryEntry(folder("DCIM"))), tab.back)
        assertEquals(listOf(HistoryEntry(folder("DCIM/Camera"))), tab.forward)
        // Not searched again: the words are kept for the folder to offer to.
        assertEquals("cat", tab.current.searched)
        // Kept as long as the tab stays there, also through the next run.
        SettingsStore(context).saveSession(SettingsStore(context).loadSession(codec)!!.model, codec)
        assertEquals("cat", SettingsStore(context).loadSession(codec)!!.model.active.current.searched)
    }

    @Test
    fun locationTags() {
        assertEquals("home", codec.encode(Location.Home))
        assertEquals("bin", codec.encode(Location.RecycleBin))
        assertEquals("folder:primary:", codec.encode(Location.Folder(primaryRoot)))
        assertEquals("folder:1A2B-3C4D:Music/Live", codec.encode(Location.Folder(sdRoot.resolve("Music/Live"))))
        assertNull(codec.encode(Location.Folder(Paths.get("/proc/self"))))

        assertEquals(DecodedLocation.Ok(Location.Home), codec.decode("home"))
        assertEquals(DecodedLocation.Ok(Location.RecycleBin), codec.decode("bin"))
        assertEquals(DecodedLocation.Ok(Location.Folder(primaryRoot)), codec.decode("folder:primary:"))
        assertEquals(
            DecodedLocation.Ok(Location.Folder(sdRoot.resolve("Music/Live"))),
            codec.decode("folder:1A2B-3C4D:Music/Live"),
        )
        assertEquals(DecodedLocation.Unavailable("5E6F-7A8B", "x"), codec.decode("folder:5E6F-7A8B:x"))
        for (bad in listOf(
            "garbage", "folder:", "folder::Music", "folder:primary", "folder:..:x", "folder:a/b:x", "folder:a\u0000:x",
            "folder:primary:../x", "folder:primary:a/../../x", "folder:primary:/etc", "folder:primary:a/./b",
            "folder:primary:a\u0000b",
        )) {
            assertEquals(bad, DecodedLocation.Invalid, codec.decode(bad))
        }
    }

    @Test
    fun viewSettingsLruKeepsFiveHundred() {
        val store = SettingsStore(context)
        val tiles = ViewSetting(ViewMode.TILES, SortSpec(SortKey.SIZE, ascending = false))
        for (i in 0..500) store.setView("folder:primary:f$i", tiles)
        assertNull(store.viewFor("folder:primary:f0"))
        assertEquals(tiles, store.viewFor("folder:primary:f1"))
        assertEquals(tiles, store.viewFor("folder:primary:f500"))

        val reloaded = SettingsStore(context)
        assertNull(reloaded.viewFor("folder:primary:f0"))
        assertEquals(tiles, reloaded.viewFor("folder:primary:f1"))
        // f1 was just used, so f2 is the least recently used one now.
        reloaded.setView("folder:primary:f501", ViewSetting(ViewMode.LIST, SortSpec()))
        assertNull(reloaded.viewFor("folder:primary:f2"))
        assertEquals(tiles, reloaded.viewFor("folder:primary:f1"))
        assertEquals(tiles, reloaded.viewFor("folder:primary:f3"))
        assertEquals(ViewSetting(ViewMode.LIST, SortSpec()), reloaded.viewFor("folder:primary:f501"))
    }

    @Test
    fun recentsCappedAtTwenty() {
        val store = SettingsStore(context)
        for (i in 0..20) store.addRecent(Recent("primary", "Download/f$i.txt", openedAt = i.toLong()))
        assertEquals(20, store.recents.value.size)
        assertEquals("Download/f20.txt", store.recents.value.first().rel)
        assertTrue(store.recents.value.none { it.rel == "Download/f0.txt" })

        // Opened again: it moves to the front, and is there once.
        store.addRecent(Recent("primary", "Download/f5.txt", openedAt = 100))
        assertEquals(Recent("primary", "Download/f5.txt", 100), store.recents.value.first())
        assertEquals(1, store.recents.value.count { it.rel == "Download/f5.txt" })
        assertEquals(20, store.recents.value.size)

        store.removeRecents { it.rel == "Download/f7.txt" }
        assertEquals(19, store.recents.value.size)
        assertEquals(store.recents.value, SettingsStore(context).recents.value)
    }

    @Test
    fun globalSettingsAndFavoritesPersistUnderTheirKeys() {
        val store = SettingsStore(context)
        assertEquals(GlobalSettings(), store.global.value)
        store.updateGlobal {
            it.copy(
                showExtensions = false,
                systemAccent = true,
                theme = ThemeChoice.LIGHT,
                defaultView = ViewSetting(ViewMode.LARGE_ICONS, SortSpec(SortKey.MODIFIED, ascending = false)),
            )
        }
        val favorites = listOf(
            Favorite("primary", "DCIM", "DCIM"),
            Favorite("1A2B-3C4D", "", "SD card"),
            Favorite("primary", "Download", "Download"),
        )
        store.setFavorites(favorites)
        store.addRecent(Recent("primary", "a.txt", 1))
        store.setView("folder:primary:", ViewSetting(ViewMode.LIST, SortSpec()))
        store.saveSession(TabsReducer.initial(Location.Home), codec)

        val reloaded = SettingsStore(context)
        assertEquals(store.global.value, reloaded.global.value)
        assertEquals(favorites, reloaded.favorites.value)
        assertEquals(setOf("session", "favorites", "recents", "views", "global"), prefs.all.keys)
    }

    @Test
    fun nullContextKeepsInMemory() {
        val store = SettingsStore(null)
        store.updateGlobal { it.copy(showHidden = true, theme = ThemeChoice.DARK) }
        store.setFavorites(listOf(Favorite("primary", "DCIM", "Camera")))
        store.addRecent(Recent("primary", "a.txt", 1))
        store.setView("folder:primary:", ViewSetting(ViewMode.LIST, SortSpec()))
        val model = TabsReducer.initial(folder("Download"))
        store.saveSession(model, codec)

        assertTrue(store.global.value.showHidden)
        assertEquals(ThemeChoice.DARK, store.global.value.theme)
        assertEquals(listOf(Favorite("primary", "DCIM", "Camera")), store.favorites.value)
        assertEquals(listOf(Recent("primary", "a.txt", 1)), store.recents.value)
        assertEquals(ViewSetting(ViewMode.LIST, SortSpec()), store.viewFor("folder:primary:"))
        assertEquals(model, store.loadSession(codec)?.model)

        assertTrue(prefs.all.isEmpty())
        val fresh = SettingsStore(null)
        assertEquals(GlobalSettings(), fresh.global.value)
        assertTrue(fresh.favorites.value.isEmpty())
        assertTrue(fresh.recents.value.isEmpty())
        assertNull(fresh.viewFor("folder:primary:"))
        assertNull(fresh.loadSession(codec))
    }
}
