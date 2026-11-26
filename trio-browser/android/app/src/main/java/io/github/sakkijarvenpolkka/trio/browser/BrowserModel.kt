package io.github.sakkijarvenpolkka.trio.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Message
import android.os.SystemClock
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

    /** False while the activity is in the background; a tab whose renderer died then loads on return. */
    private var resumed = true
    private var shownTabGoneAt = -60_000L

    val active: Tab? get() = tabs.getOrNull(activeIndex)

    private fun createTab(): Tab {
        val webView = WebView(context)
        val tab = Tab(nextId++, webView)
        Tab.configure(context, webView)
        webView.webViewClient = TabClient(tab)
        webView.webChromeClient = TabChrome(tab)
        webView.setDownloadListener { dlUrl, userAgent, disposition, mime, _ -> download(dlUrl, userAgent, disposition, mime) }
        webView.setFindListener { active, matches, done -> if (done) findResult = (if (matches > 0) active + 1 else 0) to matches }
        return tab
    }

    fun newTab(url: String? = null, background: Boolean = false, transition: String? = "typed"): Tab {
        val tab = createTab()
        tabs.add(if (background) minOf(activeIndex + 1, tabs.size) else tabs.size, tab)
        if (!background || tabs.size == 1) activate(tabs.indexOf(tab))
        if (url != null) load(tab, url, transition)
        return tab
    }

    /** Reopens saved tabs; only the shown one loads now, the others when they are shown. */
    fun restore(urls: List<String>, active: Int) {
        for (url in urls) {
            val tab = createTab()
            tabs.add(tab)
            if (url.isNotEmpty()) defer(tab, url)
        }
        if (tabs.isEmpty()) newTab(null) else activate(active.coerceIn(0, tabs.size - 1))
    }

    private fun defer(tab: Tab, url: String) {
        tab.url = url
        tab.deferredUrl = url
        tab.pendingTransition = "reload"
    }

    fun activate(index: Int) {
        if (index !in tabs.indices) return
        val now = System.currentTimeMillis()
        active?.let { prev -> prev.activeSince?.let { prev.visitAccum += now - it }; prev.activeSince = null; prev.webView.onPause() }
        activeIndex = index
        show(tabs[index])
        closeFind()
        onContextChanged?.invoke()
    }

    /** Makes [tab] the one on screen: it runs again and loads its page if that was put off. */
    private fun show(tab: Tab) {
        tab.activeSince = System.currentTimeMillis()
        tab.webView.onResume()
        tab.deferredUrl?.let { url ->
            tab.deferredUrl = null
            load(tab, url, tab.pendingTransition ?: "reload")
        }
    }

    fun closeTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        val wasActive = index == activeIndex
        finishVisit(tab)
        tabs.removeAt(index)
        tab.webView.dispose()
        when {
            tabs.isEmpty() -> {
                activeIndex = 0
                newTab(null)
            }
            wasActive -> {
                // The tab that took its place (or the new last one) comes on screen, running again.
                activeIndex = minOf(index, tabs.size - 1)
                show(tabs[activeIndex])
                closeFind()
            }
            index < activeIndex -> activeIndex -= 1
        }
        onContextChanged?.invoke()
    }

    fun load(tab: Tab, url: String, transition: String?) {
        tab.pendingTransition = transition
        tab.deferredUrl = null
        tab.error = null
        tab.url = url
        tab.mainUrl = url
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
        if (tab.error != null || tab.deferredUrl != null) {
            tab.crashed = false
            load(tab, tab.deferredUrl ?: tab.url, "reload")
        } else {
            tab.pendingTransition = "reload"
            tab.webView.reload()
        }
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
        resumed = false
        active?.let { tab -> tab.activeSince?.let { tab.visitAccum += System.currentTimeMillis() - it }; tab.activeSince = null }
        CookieManager.getInstance().flush()
    }

    fun onAppResume() {
        resumed = true
        active?.let { tab ->
            tab.activeSince = System.currentTimeMillis()
            // Its renderer was reclaimed while Trio was in the background: load it now.
            if (tab.deferredUrl != null && tab.error == null) show(tab)
        }
    }

    // Downloads ------------------------------------------------------------------------------------

    /** Hands a download to the system's download service (tabs and agent panels). */
    fun download(url: String, userAgent: String, disposition: String?, mime: String?) {
        val scheme = Uri.parse(url).scheme?.lowercase()
        // Files made by the page itself (blob:, data:) cannot be handed to the download service.
        if (scheme != "http" && scheme != "https") return toast(context.getString(R.string.notice_download_unsupported))
        runCatching {
            val name = URLUtil.guessFileName(url, disposition, mime)
            val request = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("User-Agent", userAgent)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // Android 9 needs a storage permission for the shared Downloads folder; Trio's own folder needs none.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            else request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            name
        }.onSuccess { toast(context.getString(R.string.notice_download, it)) }
            .onFailure { toast(context.getString(R.string.notice_download_failed)) }
    }

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    // WebView clients ------------------------------------------------------------------------------

    private inner class TabClient(private val tab: Tab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val scheme = request.url.scheme?.lowercase().orEmpty()
            if (scheme in setOf("http", "https", "about", "data", "blob", "javascript")) {
                if (request.isForMainFrame) tab.mainUrl = url
                return false
            }
            // Other apps open from the page itself or from a tap, never from an embedded frame (an ad) on its own.
            if (request.isForMainFrame || request.hasGesture()) openExternal(tab, url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // Another page (back, forward, a link): the error shown for the one that failed no longer applies.
            if (tab.error != null && !sameUrl(url, tab.errorUrl)) tab.error = null
            tab.mainUrl = url
            tab.url = url
            tab.favicon = favicon
            tab.refreshNav()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (tab.error != null && !sameUrl(url, tab.errorUrl)) tab.error = null
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
            if (request.isForMainFrame) {
                tab.error = error.description?.toString() ?: "ERROR"
                tab.errorUrl = request.url.toString()
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel()
            // Only the page itself: a picture or script with a bad certificate is just left out.
            if (sameUrl(error.url, tab.mainUrl)) {
                tab.error = "SSL"
                tab.errorUrl = error.url
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // All tabs share one renderer. Each is replaced by a fresh tab at the same place; only the
            // one on screen loads again now (reloading every tab at once could exhaust memory again).
            val index = tabs.indexOf(tab)
            if (index < 0) {
                view.dispose()
                return true
            }
            val wasActive = index == activeIndex
            finishVisit(tab)
            tabs.removeAt(index)
            tab.webView.dispose()
            val replacement = createTab()
            tabs.add(index, replacement)
            val url = (tab.deferredUrl ?: tab.url).takeIf { it.startsWith("http://") || it.startsWith("https://") }
            url?.let { defer(replacement, it) }
            if (wasActive) {
                val now = SystemClock.uptimeMillis()
                val again = now - shownTabGoneAt < 15_000
                shownTabGoneAt = now
                when {
                    // A page that crashed the renderer (or did again right after reloading) is not reloaded on its
                    // own, which could repeat forever and take the agents down each time: it shows Try again.
                    url != null && (detail.didCrash() || again) -> {
                        replacement.crashed = true
                        replacement.error = context.getString(R.string.error_crashed)
                        replacement.errorUrl = url
                        replacement.activeSince = System.currentTimeMillis()
                        replacement.webView.onResume()
                    }
                    // Reclaimed for memory while Trio is in the background: it loads when Trio is back.
                    !resumed -> replacement.webView.onResume()
                    else -> show(replacement)
                }
                onContextChanged?.invoke()
            }
            return true
        }
    }

    private fun sameUrl(a: String?, b: String?): Boolean = a != null && b != null && a.substringBefore('#').trimEnd('/') == b.substringBefore('#').trimEnd('/')

    private fun openExternal(tab: Tab, url: String) {
        val intent = runCatching {
            if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }.getOrNull() ?: return
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // As in Chrome: the page's own fallback address, else the app's page in the store.
            val fallback = runCatching { intent.getStringExtra("browser_fallback_url") }.getOrNull()
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            val pkg = intent.`package`
            when {
                fallback != null -> load(tab, fallback, "link")
                pkg != null && openStore(pkg) -> Unit
                else -> toast(context.getString(R.string.notice_no_app))
            }
        } catch (e: RuntimeException) {
            toast(context.getString(R.string.notice_no_app))
        }
    }

    private fun openStore(pkg: String): Boolean = runCatching {
        val store = Uri.parse("market://details").buildUpon().appendQueryParameter("id", pkg).build()
        context.startActivity(Intent(Intent.ACTION_VIEW, store).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

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
