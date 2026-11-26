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
import io.github.sakkijarvenpolkka.trio.ui.BrowserScreen
import io.github.sakkijarvenpolkka.trio.ui.LocalBrowser
import io.github.sakkijarvenpolkka.trio.ui.LocalBrowserHolder
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
        LocalBrowserHolder.browser = browser
        browser.onAgentRequest = { id, query -> hub.ask(id, query) }
        browser.onAnyNavigation = { hub.refreshSessions() }
        browser.fileChooser = { callback, params ->
            pendingFiles?.onReceiveValue(null)
            pendingFiles = callback
            runCatching { pickFiles.launch(params.createIntent()) }.isSuccess.also { if (!it) pendingFiles = null }
        }
        browser.newTab(intentUrl(intent))
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
        intentUrl(intent)?.let { browser.newTab(it) }
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
        if (isFinishing) {
            browser.tabs.forEach { browser.finishVisit(it); it.webView.destroy() }
            hub.agents.forEach { it.webView?.destroy() }
            LocalBrowserHolder.browser = null
        }
        super.onDestroy()
    }
}
