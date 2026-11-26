package io.github.sakkijarvenpolkka.trio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import io.github.sakkijarvenpolkka.trio.agents.AgentHub
import io.github.sakkijarvenpolkka.trio.browser.BrowserModel
import io.github.sakkijarvenpolkka.trio.browser.dispose
import io.github.sakkijarvenpolkka.trio.ui.BrowserScreen
import io.github.sakkijarvenpolkka.trio.ui.LocalBrowser
import io.github.sakkijarvenpolkka.trio.ui.TrioTheme

class MainActivity : ComponentActivity() {
    lateinit var browser: BrowserModel
        private set
    lateinit var hub: AgentHub
        private set
    private var pendingFiles: ValueCallback<Array<Uri>>? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingFiles?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        pendingFiles = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TrioApp
        browser = BrowserModel(this, app)
        hub = AgentHub(this, app, browser)
        browser.onAgentRequest = { id, query -> hub.ask(id, query) }
        browser.onAnyNavigation = { hub.refreshSessions() }
        browser.fileChooser = { callback, params ->
            pendingFiles?.onReceiveValue(null)
            pendingFiles = callback
            runCatching { pickFiles.launch(params.createIntent()) }.isSuccess.also { if (!it) pendingFiles = null }
        }
        // Recreated after the process was stopped in the background (or a change of configuration the
        // activity does not handle itself): the tabs come back, and the intent that first started the
        // activity is not opened a second time.
        val saved = savedInstanceState?.getStringArrayList(STATE_TABS)
        if (saved != null) browser.restore(saved, savedInstanceState.getInt(STATE_ACTIVE))
        else browser.newTab(intentUrl(intent))
        setContent {
            TrioTheme {
                CompositionLocalProvider(LocalBrowser provides browser) {
                    BrowserScreen(app, browser, hub)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentUrl(intent)?.let { browser.newTab(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Addresses only (no page state), within the size a saved state may have.
        val urls = browser.tabs.take(MAX_SAVED_TABS).map { tab ->
            (tab.deferredUrl ?: tab.url).takeIf { (it.startsWith("http://") || it.startsWith("https://")) && it.length <= MAX_SAVED_URL }.orEmpty()
        }
        outState.putStringArrayList(STATE_TABS, ArrayList(urls))
        outState.putInt(STATE_ACTIVE, browser.activeIndex.coerceIn(0, maxOf(0, urls.size - 1)))
    }

    private fun intentUrl(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_WEB_SEARCH) {
            val q = intent.getStringExtra("query") ?: return null
            val app = application as TrioApp
            return app.shared.searchData.buildSearchUrl(app.settings.searchEngine, q)
        }
        return intent.data?.toString()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    override fun onPause() {
        super.onPause()
        browser.onAppPause()
    }

    override fun onResume() {
        super.onResume()
        browser.onAppResume()
        hub.refreshSessions()
    }

    override fun onDestroy() {
        // This activity's tabs and agents go with it, also when it is recreated: the new one has its own.
        browser.tabs.forEach { browser.finishVisit(it); it.webView.dispose() }
        hub.destroy()
        pendingFiles?.onReceiveValue(null)
        pendingFiles = null
        super.onDestroy()
    }

    private companion object {
        const val STATE_TABS = "trio.tabs"
        const val STATE_ACTIVE = "trio.activeTab"
        const val MAX_SAVED_TABS = 50
        const val MAX_SAVED_URL = 4096
    }
}
