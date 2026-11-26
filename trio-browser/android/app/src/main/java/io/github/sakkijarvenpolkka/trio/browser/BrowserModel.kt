package io.github.sakkijarvenpolkka.trio.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sakkijarvenpolkka.trio.R
import io.github.sakkijarvenpolkka.trio.TrioApp
import io.github.sakkijarvenpolkka.trio.core.ClassifyInput
import io.github.sakkijarvenpolkka.trio.core.InputKind

/**
 * Tabs and navigation. Owns one WebView per tab and records history. The activity supplies the
 * hooks that need it (file chooser, fullscreen video) and the agent hub listens for navigations.
 */
class BrowserModel(private val context: Context, private val app: TrioApp) {
    val tabs = mutableStateListOf<Tab>()
    var activeIndex by mutableIntStateOf(0)
    var fullscreenView by mutableStateOf<View?>(null)
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    var findQuery by mutableStateOf<String?>(null)
    var findResult by mutableStateOf(0 to 0)

    /** Set by the activity: shows the system file picker. */
    var fileChooser: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null

    /** Called for agent requests typed in the address bar ("@claude …"). */
    var onAgentRequest: ((agentId: String, query: String) -> Unit)? = null

    /** Called when the active page or its URL changes (agents refresh their context). */
    var onContextChanged: (() -> Unit)? = null

    /** Called for every main-frame navigation of any tab (agents re-check cookies). */
    var onAnyNavigation: (() -> Unit)? = null

    /** Called when the user asks to see the history screen with a query. */
    var onShowHistory: ((String) -> Unit)? = null

    private var nextId = 1L

    val active: Tab? get() = tabs.getOrNull(activeIndex)

    fun newTab(url: String? = null, background: Boolean = false, transition: String? = "typed"): Tab {
        val webView = WebView(context)
        val tab = Tab(nextId++, webView)
        Tab.configure(context, webView)
        webView.webViewClient = TabClient(tab)
        webView.webChromeClient = TabChrome(tab)
        webView.setDownloadListener { dlUrl, userAgent, disposition, mime, _ -> download(dlUrl, userAgent, disposition, mime) }
        webView.setFindListener { active, matches, done -> if (done) findResult = (if (matches > 0) active + 1 else 0) to matches }
        tabs.add(if (background) minOf(activeIndex + 1, tabs.size) else tabs.size, tab)
        if (!background || tabs.size == 1) activate(tabs.indexOf(tab))
        if (url != null) load(tab, url, transition)
        return tab
    }

    fun activate(index: Int) {
        if (index !in tabs.indices) return
        val now = System.currentTimeMillis()
        active?.let { prev -> prev.activeSince?.let { prev.visitAccum += now - it }; prev.activeSince = null; prev.webView.onPause() }
        activeIndex = index
        tabs[index].apply { activeSince = now; webView.onResume() }
        closeFind()
        onContextChanged?.invoke()
    }

    fun closeTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        finishVisit(tab)
        tabs.removeAt(index)
        tab.webView.destroy()
        if (tabs.isEmpty()) {
            activeIndex = 0
            newTab(null)
        } else {
            activeIndex = minOf(activeIndex, tabs.size - 1).let { if (index < activeIndex) it - 1 else it }.coerceIn(0, tabs.size - 1)
            tabs[activeIndex].activeSince = System.currentTimeMillis()
        }
        onContextChanged?.invoke()
    }

    fun load(tab: Tab, url: String, transition: String?) {
        tab.pendingTransition = transition
        tab.error = null
        tab.url = url
        tab.webView.loadUrl(url)
    }

    /** Acts on address-bar text: open a URL, search, ask an agent or search history. */
    fun navigate(input: String, newTab: Boolean = false) {
        val shared = app.shared
        when (val kind = ClassifyInput.classify(input, shared.agentKeywords, shared.suffixes)) {
            InputKind.Empty -> Unit
            is InputKind.Url -> open(internalToWeb(kind.url) ?: return, newTab, "typed")
            is InputKind.Search -> search(kind.query, newTab)
            is InputKind.Agent -> onAgentRequest?.invoke(kind.agentId, kind.query)
            is InputKind.History -> onShowHistory?.invoke(kind.query)
        }
    }

    /** Internal desktop pages have native screens on Android; only web URLs load in tabs. */
    private fun internalToWeb(url: String): String? {
        if (url.startsWith("trio://history")) {
            onShowHistory?.invoke("")
            return null
        }
        return if (url.startsWith("trio://")) null else url
    }

    fun search(query: String, newTab: Boolean = false) {
        open(app.shared.searchData.buildSearchUrl(app.settings.searchEngine, query), newTab, "search")
    }

    fun open(url: String, newTab: Boolean = false, transition: String? = "link") {
        val tab = active
        if (newTab || tab == null) newTab(url, transition = transition) else load(tab, url, transition)
    }

    fun back() = active?.webView?.takeIf { it.canGoBack() }?.goBack()
    fun forward() = active?.webView?.takeIf { it.canGoForward() }?.goForward()

    fun reload() {
        val tab = active ?: return
        tab.pendingTransition = "reload"
        if (tab.error != null) {
            tab.error = null
            tab.webView.loadUrl(tab.url)
        } else tab.webView.reload()
    }

    fun stop() = active?.webView?.stopLoading()

    fun find(query: String) {
        findQuery = query
        if (query.isEmpty()) active?.webView?.clearMatches() else active?.webView?.findAllAsync(query)
    }

    fun findNext(forward: Boolean) = active?.webView?.findNext(forward)

    fun closeFind() {
        findQuery = null
        findResult = 0 to 0
        active?.webView?.clearMatches()
    }

    fun exitFullscreen() {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenView = null
        fullscreenCallback = null
    }

    // History ---------------------------------------------------------------------------------------

    private fun recordVisit(tab: Tab, url: String, isReload: Boolean) {
        finishVisit(tab)
        val transition = tab.pendingTransition ?: if (isReload) "reload" else "link"
        tab.pendingTransition = null
        if (!app.settings.saveHistory || !(url.startsWith("http://") || url.startsWith("https://"))) return
        val from = if (transition == "link") tab.visitId else null
        tab.visitId = runCatching { app.history.addVisit(url, tab.webView.title?.takeIf { it != url }.orEmpty(), System.currentTimeMillis(), transition, from) }.getOrNull()
        tab.visitAccum = 0
        if (tab == active) tab.activeSince = System.currentTimeMillis()
    }

    fun finishVisit(tab: Tab) {
        val id = tab.visitId ?: return
        val now = System.currentTimeMillis()
        val total = tab.visitAccum + (tab.activeSince?.let { now - it } ?: 0)
        runCatching { app.history.setDuration(id, minOf(total, 30 * 60 * 1000L)) }
        tab.visitId = null
        tab.visitAccum = 0
        tab.activeSince = if (tab == active) now else null
    }

    fun onAppPause() {
        active?.let { tab -> tab.activeSince?.let { tab.visitAccum += System.currentTimeMillis() - it }; tab.activeSince = null }
        CookieManager.getInstance().flush()
    }

    fun onAppResume() {
        active?.activeSince = System.currentTimeMillis()
    }

    // Downloads ------------------------------------------------------------------------------------

    private fun download(url: String, userAgent: String, disposition: String?, mime: String?) {
        val name = URLUtil.guessFileName(url, disposition, mime)
        runCatching {
            val request = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("User-Agent", userAgent)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(context, context.getString(R.string.notice_download, name), Toast.LENGTH_SHORT).show()
        }
    }

    // WebView clients ------------------------------------------------------------------------------

    private inner class TabClient(private val tab: Tab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val scheme = request.url.scheme?.lowercase().orEmpty()
            if (scheme in setOf("http", "https", "about", "data", "blob", "javascript")) return false
            openExternal(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tab.url = url
            tab.favicon = favicon
            tab.refreshNav()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            tab.url = url
            tab.refreshNav()
            recordVisit(tab, url, isReload)
            onAnyNavigation?.invoke()
            if (tab == active) onContextChanged?.invoke()
        }

        override fun onPageFinished(view: WebView, url: String) {
            tab.refreshNav()
            view.title?.takeIf { it.isNotBlank() && it != url }?.let { tab.title = it }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) tab.error = error.description?.toString() ?: "ERROR"
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel()
            tab.error = "SSL"
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Replace the crashed tab with a fresh one at the same place.
            val index = tabs.indexOf(tab)
            if (index < 0) return true
            val wasActive = index == activeIndex
            tabs.removeAt(index)
            tab.webView.destroy()
            val replacement = newTab(tab.url.takeIf { it.startsWith("http") }, background = true, transition = "reload")
            tabs.remove(replacement)
            tabs.add(index, replacement)
            replacement.crashed = true
            if (wasActive || tabs.size == 1) activate(index)
            return true
        }
    }

    private fun openExternal(url: String) {
        runCatching {
            val intent = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure { if (it !is ActivityNotFoundException) it.printStackTrace() }
    }

    private inner class TabChrome(private val tab: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
            tab.refreshNav()
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (title.isNullOrBlank() || title == view.url) return
            tab.title = title
            tab.visitId?.let { id -> runCatching { app.history.setTitle(id, title) } }
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            tab.favicon = icon
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val popup = newTab(null, background = false, transition = "link")
            (resultMsg.obj as WebView.WebViewTransport).webView = popup.webView
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            tabs.firstOrNull { it.webView == window }?.let { closeTab(it) }
        }

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean =
            fileChooser?.invoke(filePathCallback, fileChooserParams) ?: false

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            fullscreenView = view
            fullscreenCallback = callback
        }

        override fun onHideCustomView() {
            fullscreenView = null
            fullscreenCallback = null
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // Camera and microphone for web pages are not offered in this version.
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }
    }
}
