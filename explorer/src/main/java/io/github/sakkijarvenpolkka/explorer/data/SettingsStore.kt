/*
 * Files — the settings, favorites, recent files, folder views and saved tabs, kept in
 * SharedPreferences.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.sakkijarvenpolkka.explorer.state.HistoryEntry
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.SelectionState
import io.github.sakkijarvenpolkka.explorer.state.TabId
import io.github.sakkijarvenpolkka.explorer.state.TabState
import io.github.sakkijarvenpolkka.explorer.state.TabsModel
import io.github.sakkijarvenpolkka.explorer.state.TabsReducer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A saved session read back: the tabs, and those whose current folder is on a volume that is
 * not mounted now (they keep their place; see [DecodedLocation.Unavailable.location]).
 */
data class RestoredSession(val model: TabsModel, val unavailable: Map<TabId, DecodedLocation.Unavailable>)

/**
 * The app's saved state, one JSON value per key, each read once when created. A null [context]
 * keeps everything in memory only (tests). Whatever does not decode is left out on its own (one
 * tab, history entry, favorite, recent file or folder view); a setting that does not decode is
 * its default.
 */
class SettingsStore(context: Context?) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The stored values when there is no [prefs]. */
    private val memory = HashMap<String, String>()

    private val _global = MutableStateFlow(readJson(K_GLOBAL)?.let { decode(GLOBAL, it) } ?: GlobalSettings())
    val global: StateFlow<GlobalSettings> = _global.asStateFlow()

    private val _favorites = MutableStateFlow(readList(K_FAVORITES, Favorite.serializer()))

    /** The pinned folders, in the user's order. */
    val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()

    private val _recents = MutableStateFlow(readList(K_RECENTS, Recent.serializer()).take(RECENTS_CAP))

    /** The files opened last, newest first, at most [RECENTS_CAP]. */
    val recents: StateFlow<List<Recent>> = _recents.asStateFlow()

    /** Folder views by key, least recently used first. */
    private val views = LinkedHashMap<String, ViewSetting>(16, 0.75f, true).apply { putAll(readViews()) }

    /** Replaces the settings; nothing is written when [f] changes nothing. */
    @Synchronized
    fun updateGlobal(f: (GlobalSettings) -> GlobalSettings) {
        val new = f(_global.value)
        if (new == _global.value) return
        _global.value = new
        write(K_GLOBAL, json.encodeToString(GLOBAL, new))
    }

    /**
     * The saved tabs, or null when there are none or none decodes. A tab whose current location
     * does not decode is left out, as is any history entry that does not; if the active tab is
     * left out, the first tab left is active. New tabs get ids above every restored one. A tab
     * saved at search results is at the folder searched, which keeps the words
     * ([HistoryEntry.searched]): the search is not run again.
     */
    @Synchronized
    fun loadSession(codec: LocationCodec): RestoredSession? {
        val session = readJson(K_SESSION) as? JsonObject ?: return null
        val stored = (session["tabs"] as? JsonArray ?: return null)
            .mapNotNull { decode(PersistedTab.serializer(), it) }
            .distinctBy { it.id }
        val unavailable = LinkedHashMap<TabId, DecodedLocation.Unavailable>()
        val tabs = stored.mapNotNull { tab ->
            val id = TabId(tab.id)
            val current = codec.decode(tab.location)
            val location = current.locationOrNull() ?: return@mapNotNull null
            if (current is DecodedLocation.Unavailable) unavailable[id] = current
            val entry = HistoryEntry(location, searched = tab.search?.takeIf { location is Location.Folder })
            TabState(id, entry, tab.back.history(codec), tab.forward.history(codec), SelectionState())
        }
        if (tabs.isEmpty()) return null
        val active = (session["active"] as? JsonPrimitive)?.longOrNull
        val activeId = tabs.firstOrNull { it.id.value == active }?.id ?: tabs.first().id
        return RestoredSession(TabsModel(tabs, activeId, tabs.maxOf { it.id.value } + 1), unavailable)
    }

    /**
     * Saves the tabs and their history as [codec] tags; a location it cannot tag is left out
     * (a tab whose current one it cannot tag, with that tab's history). Of a tab at search
     * results, or at the folder restored for them, the words searched for are saved too.
     */
    @Synchronized
    fun saveSession(m: TabsModel, codec: LocationCodec) {
        val tabs = m.tabs.mapNotNull { tab ->
            val location = codec.encode(tab.current.location) ?: return@mapNotNull null
            val search = (tab.current.location as? Location.SearchResults)?.query ?: tab.current.searched
            PersistedTab(tab.id.value, location, tab.back.tags(codec), tab.forward.tags(codec), search)
        }
        val session = PersistedSession(tabs = tabs, active = m.activeId.value)
        write(K_SESSION, json.encodeToString(PersistedSession.serializer(), session))
    }

    @Synchronized
    fun setFavorites(list: List<Favorite>) {
        _favorites.value = list
        write(K_FAVORITES, json.encodeToString(FAVORITES, list))
    }

    /** Puts [r] first, in place of an older entry for the same file, keeping the newest [RECENTS_CAP]. */
    @Synchronized
    fun addRecent(r: Recent) {
        val others = _recents.value.filterNot { it.uuid == r.uuid && it.rel == r.rel }
        setRecents((listOf(r) + others).take(RECENTS_CAP))
    }

    @Synchronized
    fun removeRecents(pred: (Recent) -> Boolean) {
        val kept = _recents.value.filterNot(pred)
        if (kept.size != _recents.value.size) setRecents(kept)
    }

    /**
     * The view of the folder [key] (its [VolumeLocationCodec] tag), or null for the default one.
     * Counts as a use: the [VIEWS_CAP] views used last are kept (the order is saved with the next
     * [setView]).
     */
    @Synchronized
    fun viewFor(key: String): ViewSetting? = views[key]

    @Synchronized
    fun setView(key: String, v: ViewSetting) {
        views[key] = v
        while (views.size > VIEWS_CAP) views.remove(views.keys.first())
        write(K_VIEWS, json.encodeToString(VIEWS, views))
    }

    private fun setRecents(list: List<Recent>) {
        _recents.value = list
        write(K_RECENTS, json.encodeToString(RECENTS, list))
    }

    private fun read(key: String): String? = if (prefs != null) prefs.getString(key, null) else memory[key]

    private fun write(key: String, value: String) {
        if (prefs != null) prefs.edit { putString(key, value) } else memory[key] = value
    }

    /** The JSON under [key], or null when there is none or it is not JSON. */
    private fun readJson(key: String): JsonElement? = read(key)?.let { decodeOrNull { json.parseToJsonElement(it) } }

    /** The elements of the JSON array under [key] that decode. */
    private fun <T> readList(key: String, serializer: KSerializer<T>): List<T> {
        val array = readJson(key) as? JsonArray ?: return emptyList()
        return array.mapNotNull { decode(serializer, it) }
    }

    /** The stored views that decode, least recently used first. */
    private fun readViews(): Map<String, ViewSetting> {
        val stored = readJson(K_VIEWS) as? JsonObject ?: return emptyMap()
        val decoded = LinkedHashMap<String, ViewSetting>()
        for ((key, value) in stored) decode(ViewSetting.serializer(), value)?.let { decoded[key] = it }
        return decoded
    }

    private fun <T> decode(serializer: KSerializer<T>, element: JsonElement): T? =
        decodeOrNull { json.decodeFromJsonElement(serializer, element) }

    private fun List<HistoryEntry>.tags(codec: LocationCodec): List<String> = mapNotNull { codec.encode(it.location) }

    private fun List<String>.history(codec: LocationCodec): List<HistoryEntry> =
        mapNotNull { tag -> codec.decode(tag).locationOrNull()?.let { HistoryEntry(it) } }
            .takeLast(TabsReducer.HISTORY_CAP)

    private fun DecodedLocation.locationOrNull(): Location? = when (this) {
        is DecodedLocation.Ok -> location
        is DecodedLocation.Unavailable -> location
        DecodedLocation.Invalid -> null
    }

    /** [block]'s value, or null when the stored text does not decode (`SerializationException` included). */
    private inline fun <T> decodeOrNull(block: () -> T): T? = try {
        block()
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        /** The most recent files kept. */
        const val RECENTS_CAP = 20

        /** The most folder views kept. */
        const val VIEWS_CAP = 500

        const val PREFS_NAME = "explorer_settings"
        const val K_SESSION = "session"
        const val K_FAVORITES = "favorites"
        const val K_RECENTS = "recents"
        const val K_VIEWS = "views"
        const val K_GLOBAL = "global"

        val json = Json { ignoreUnknownKeys = true }
        val GLOBAL = GlobalSettings.serializer()
        val FAVORITES = ListSerializer(Favorite.serializer())
        val RECENTS = ListSerializer(Recent.serializer())
        val VIEWS = MapSerializer(String.serializer(), ViewSetting.serializer())
    }
}
