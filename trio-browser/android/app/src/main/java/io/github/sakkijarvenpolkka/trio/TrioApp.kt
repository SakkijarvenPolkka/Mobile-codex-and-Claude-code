package io.github.sakkijarvenpolkka.trio

import android.app.Application
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

    /** Reads a page script from the shared assets (inject/…). */
    fun injectScript(name: String): String = assets.open("inject/$name").bufferedReader().use { it.readText() }
}
