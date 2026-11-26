package io.github.sakkijarvenpolkka.trio.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Bookmark(val id: String, val title: String, val url: String)

/** Bookmarks stored as JSON in SharedPreferences. */
class Bookmarks(context: Context) {
    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
    val items = mutableStateListOf<Bookmark>()

    init {
        runCatching {
            val arr = JSONArray(prefs.getString("items", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(Bookmark(o.getString("id"), o.optString("title"), o.getString("url")))
            }
        }
    }

    fun find(url: String): Bookmark? = items.firstOrNull { it.url == url }

    fun toggle(title: String, url: String): Boolean {
        val existing = find(url)
        if (existing != null) items.remove(existing) else items.add(Bookmark(UUID.randomUUID().toString(), title.ifBlank { url }, url))
        save()
        return existing == null
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
        save()
    }

    fun search(text: String, limit: Int): List<Bookmark> {
        val terms = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        return items.filter { b -> terms.all { b.title.lowercase().contains(it) || b.url.lowercase().contains(it) } }.take(limit)
    }

    private fun save() {
        val arr = JSONArray()
        for (b in items) arr.put(JSONObject().put("id", b.id).put("title", b.title).put("url", b.url))
        prefs.edit().putString("items", arr.toString()).apply()
    }
}
