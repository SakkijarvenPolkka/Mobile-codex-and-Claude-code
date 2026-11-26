package io.github.sakkijarvenpolkka.trio.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** User settings in SharedPreferences, exposed as Compose state. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var searchEngine by pref("search.engine", "google")
    var saveHistory by pref("privacy.saveHistory", true)
    var excludeSensitive by pref("privacy.excludeSensitive", true)
    var previewPage by pref("agents.preview.page", true)
    var previewSelection by pref("agents.preview.selection", false)
    var previewSearch by pref("agents.preview.search", false)
    var claudeEnabled by pref("agents.enabled.claude", true)
    var geminiEnabled by pref("agents.enabled.gemini", true)
    var chatgptEnabled by pref("agents.enabled.chatgpt", true)
    var bubbleVisible by pref("agents.dockVisible", true)
    val pageCharLimit: Int get() = prefs.getInt("agents.pageCharLimit", 12000)
    val historyCharLimit: Int get() = prefs.getInt("agents.historyCharLimit", 8000)

    fun agentEnabled(id: String): Boolean = when (id) {
        "claude" -> claudeEnabled
        "gemini" -> geminiEnabled
        "chatgpt" -> chatgptEnabled
        else -> false
    }

    fun setAgentEnabled(id: String, on: Boolean) {
        when (id) {
            "claude" -> claudeEnabled = on
            "gemini" -> geminiEnabled = on
            "chatgpt" -> chatgptEnabled = on
        }
    }

    private fun <T> pref(key: String, default: T) = Pref(key, default)

    private inner class Pref<T>(private val key: String, default: T) {
        @Suppress("UNCHECKED_CAST")
        private val state = mutableStateOf(
            when (default) {
                is Boolean -> prefs.getBoolean(key, default) as T
                is String -> (prefs.getString(key, default) ?: default) as T
                else -> default
            },
        )

        operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = state.value

        operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
            state.value = value
            prefs.edit().apply {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                }
            }.apply()
        }
    }
}
