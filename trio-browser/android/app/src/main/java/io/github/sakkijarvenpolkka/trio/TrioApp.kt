package io.github.sakkijarvenpolkka.trio

import android.app.Application
import androidx.annotation.VisibleForTesting
import io.github.sakkijarvenpolkka.trio.core.ProviderConfig
import io.github.sakkijarvenpolkka.trio.core.SharedData
import io.github.sakkijarvenpolkka.trio.data.Bookmarks
import io.github.sakkijarvenpolkka.trio.data.HistoryDb
import io.github.sakkijarvenpolkka.trio.data.Settings

/** Process-wide services. */
class TrioApp : Application() {
    val shared: SharedData by lazy { SharedData { name -> assets.open(name).bufferedReader().use { it.readText() } } }
    val settings: Settings by lazy { Settings(this) }
    val bookmarks: Bookmarks by lazy { Bookmarks(this) }
    val history: HistoryDb by lazy { HistoryDb(this) }

    /** On-device tests point the agents at local stand-ins for the providers. */
    @VisibleForTesting
    var providerOverride: List<ProviderConfig>? = null

    /** The agents' providers (Claude, Gemini, ChatGPT). */
    val providers: List<ProviderConfig> get() = providerOverride ?: shared.providers

    /** Reads a page script from the shared assets (inject/…). */
    fun injectScript(name: String): String = assets.open("inject/$name").bufferedReader().use { it.readText() }
}
