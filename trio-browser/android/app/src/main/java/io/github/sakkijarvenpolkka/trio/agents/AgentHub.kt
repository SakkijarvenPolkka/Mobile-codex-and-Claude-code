package io.github.sakkijarvenpolkka.trio.agents

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sakkijarvenpolkka.trio.R
import io.github.sakkijarvenpolkka.trio.TrioApp
import io.github.sakkijarvenpolkka.trio.browser.BrowserModel
import io.github.sakkijarvenpolkka.trio.browser.Tab
import io.github.sakkijarvenpolkka.trio.browser.dispose
import io.github.sakkijarvenpolkka.trio.core.HistoryDigest
import io.github.sakkijarvenpolkka.trio.core.HistoryStatsCalc
import io.github.sakkijarvenpolkka.trio.core.LoginState
import io.github.sakkijarvenpolkka.trio.core.Privacy
import io.github.sakkijarvenpolkka.trio.core.Prompts
import io.github.sakkijarvenpolkka.trio.core.ProviderConfig
import io.github.sakkijarvenpolkka.trio.core.StatsOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.TimeZone

/** What the floating panel shows on top of the provider. */
data class Preview(
    val id: Int,
    val actionId: String,
    val source: String,
    val text: String,
    val truncated: Boolean,
    val counts: IntArray? = null,
)

/** Options of the history analysis form. */
data class HistoryForm(
    val range: String = "7d",
    val kind: String = "habits",
    val question: String = "",
    val queries: Boolean = true,
    val titles: Boolean = true,
    val paths: Boolean = false,
)

/** One agent: login state, its provider WebView and what its panel shows. */
class Agent(val provider: ProviderConfig) {
    var sessionPresent by mutableStateOf(false)
    var staleSession by mutableStateOf(false)
    var signingIn by mutableStateOf(false)
    var signingOut = false
    var sessionSignature = ""
    var lockReason by mutableStateOf("signedOut")
    var pendingLabel by mutableStateOf<String?>(null)
    var pending: (() -> Unit)? = null
    var url by mutableStateOf("")
    var loading by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)

    /** A sign-in window shown in the panel, and the address it is on. */
    var popup by mutableStateOf<WebView?>(null)
    var popupUrl by mutableStateOf("")
    var webView: WebView? = null

    /** The page Trio asked the provider view to load, until it has finished loading. */
    internal var loadingUrl: String? = null
    internal var loadingSince = 0L
    internal var loadFailed = false
    internal var goneAt = -60_000L

    /** The text Trio is putting into the composer. */
    internal var job: HandoffJob? = null

    /** Windows the provider opened whose first page is not known yet; they are not shown. */
    internal val popupCandidates = ArrayList<WebView>()

    val state: String get() = LoginState.deriveState(sessionPresent, staleSession, signingIn)

    val urlKind: String get() = if (url.isEmpty() || url == "about:blank") "external" else LoginState.classifyProviderUrl(provider, url)

    /** The login gate: may the provider's page be shown right now? */
    val showProvider: Boolean get() = url.isNotEmpty() && url != "about:blank" && state != "signedOut" && LoginState.gateView(state, urlKind) == "provider"
}

/** One handoff: Trio puts [text] into the composer of the page it is meant for and checks that it arrived. */
internal class HandoffJob(val token: String, val text: String, val label: String) {
    var started = false
    var finished = false
    var deadline = 0L
    var injections = 0
}

/**
 * The three agents (Claude, Gemini, ChatGPT). Invariant: an agent's chat app is shown, and text is
 * handed to it, only in the signedIn state (session cookie present and no bounce to sign-in).
 */
class AgentHub(
    private val context: Context,
    private val app: TrioApp,
    private val browser: BrowserModel,
    providers: List<ProviderConfig> = app.providers,
) {
    val agents: List<Agent> = providers.map(::Agent)
    var openId by mutableStateOf<String?>(null)
    var preview by mutableStateOf<Preview?>(null)
    var historyOpen by mutableStateOf(false)
    var historyPreview by mutableStateOf<Preview?>(null)
    var historyForm by mutableStateOf(HistoryForm())
    var lastUsed = agents.first().provider.id
    private var previewSeq by mutableIntStateOf(1)
    private var jobSeq = 0
    private val main = Handler(Looper.getMainLooper())

    fun agent(id: String): Agent = agents.first { it.provider.id == id }
    val open: Agent? get() = openId?.let(::agent)

    init {
        refreshSessions()
    }

    // Sessions ----------------------------------------------------------------------------------

    /** Reads the providers' session cookies from the WebView cookie store. */
    fun refreshSessions() {
        val cm = CookieManager.getInstance()
        val now = System.currentTimeMillis()
        for (a in agents) {
            val before = a.state
            val cookies = LoginState.sessionDomains(a.provider).flatMap { domain ->
                LoginState.parseCookieHeader(domain, cm.getCookie("https://$domain/"))
            }
            val present = LoginState.hasSession(a.provider, cookies, now)
            // A stale session (the app bounced to sign-in) becomes valid again only when the
            // session cookie itself changes, e.g. after signing in.
            val rules = a.provider.anyOf + a.provider.allOf
            val signature = cookies.filter { c -> rules.any { LoginState.cookieMatches(c, it) } }.map { "${it.name}=${it.value}" }.sorted().joinToString(";")
            if (signature != a.sessionSignature) {
                a.sessionSignature = signature
                a.staleSession = false
            }
            a.sessionPresent = present
            if (present && !a.staleSession) a.signingIn = false
            afterStateChange(a, before)
        }
    }

    private fun afterStateChange(a: Agent, before: String) {
        val now = a.state
        if (now != "signedOut") a.lockReason = "signedOut"
        if (now == "signedOut" && before != "signedOut") {
            cancelJob(a)
            dropPopups(a)
            // While signing out, the hidden view keeps running the provider's logout page.
            if (!a.signingOut) blank(a)
            if (openId == a.provider.id) {
                preview = null
                historyOpen = false
            }
        }
        if (now == "signedIn" && before != "signedIn") {
            // Load the chat app only for the panel on screen; other agents load when opened.
            if (openId == a.provider.id && a.urlKind != "app") loadProvider(a, a.provider.appUrl)
            a.pending?.let { run ->
                a.pending = null
                a.pendingLabel = null
                main.post(run)
            }
        }
    }

    private fun onProviderNavigate(a: Agent, url: String) {
        a.url = url
        val kind = LoginState.classifyProviderUrl(a.provider, url)
        val before = a.state
        if (before == "signedIn" && kind == "auth") {
            a.staleSession = true
            a.signingIn = true
            afterStateChange(a, before)
            return
        }
        if (before == "signingIn" && kind == "app") {
            refreshSessions()
            if (a.state != "signedIn") {
                main.postDelayed({
                    // Signing in was cancelled, finished or locked meanwhile: nothing to decide.
                    if (a.state != "signingIn") return@postDelayed
                    // The app was reached with the same session cookie: it is valid after all.
                    val was = a.state
                    a.staleSession = false
                    refreshSessions()
                    if (a.state == "signedIn") {
                        // refreshSessions saw no change (staleSession was cleared first); resume what waited.
                        afterStateChange(a, was)
                    } else {
                        a.signingIn = false
                        a.lockReason = "signInIncomplete"
                        blank(a)
                        afterStateChange(a, was)
                    }
                }, 1500)
            }
        }
    }

    // Provider WebViews --------------------------------------------------------------------------

    fun webViewFor(a: Agent): WebView = a.webView ?: WebView(context).also { wv ->
        Tab.configure(context, wv)
        wv.webViewClient = ProviderClient(a)
        wv.webChromeClient = ProviderChrome(a)
        wv.setDownloadListener { url, userAgent, disposition, mime, _ -> browser.download(url, userAgent, disposition, mime) }
        a.webView = wv
    }

    private val loadWaiters = HashMap<String, MutableList<() -> Unit>>()

    /**
     * Loads [url] in the provider view; [onLoaded] runs when that load (or a later one) has finished.
     * Asking for the page that is already loading joins that load: starting it again would abort the
     * first load, and WebView reports an aborted load as finished while the old page is still there.
     */
    private fun loadProvider(a: Agent, url: String, onLoaded: (() -> Unit)? = null) {
        if (onLoaded != null) loadWaiters.getOrPut(a.provider.id) { ArrayList() }.add(onLoaded)
        val now = SystemClock.uptimeMillis()
        if (a.webView != null && a.loadingUrl == url && now - a.loadingSince < JOIN_LOAD_MS) return
        a.loadingUrl = url
        a.loadingSince = now
        a.loadFailed = false
        a.url = url
        webViewFor(a).loadUrl(url)
    }

    private fun blank(a: Agent) {
        a.loadingUrl = null
        a.webView?.loadUrl("about:blank")
    }

    private inner class ProviderClient(private val a: Agent) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val scheme = request.url.scheme?.lowercase().orEmpty()
            if (scheme != "http" && scheme != "https" && scheme != "about") return true
            if (a.state == "signedIn" && LoginState.classifyProviderUrl(a.provider, url) == "external") {
                browser.open(url, newTab = true)
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (view !== a.webView) return
            a.loading = true
            a.loadFailed = false
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (view !== a.webView) return
            a.loading = false
            a.loadingUrl = null
            loadWaiters.remove(a.provider.id)?.forEach { main.post(it) }
            if (a.signingOut) main.postDelayed({ finishSignOut(a) }, 2000)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view === a.webView && request.isForMainFrame) a.loadFailed = true
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (view !== a.webView) return
            if (url != "about:blank") onProviderNavigate(a, url) else a.url = url
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) = handler.cancel()

        /** All of the app's WebViews share one renderer; when it dies, each must let go of its view or Android kills the app. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            providerViewGone(a, view)
            return true
        }
    }

    private inner class ProviderChrome(private val a: Agent) : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (a.state == "signedOut" || !isUserGesture) return false
            return attachPopup(a, resultMsg, nested = false)
        }

        override fun onCloseWindow(window: WebView) = closePopup(a)

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean =
            browser.fileChooser?.invoke(filePathCallback, fileChooserParams) ?: false
    }

    /** The renderer behind the provider view crashed or was killed for memory: drop the view (it is made again when needed). */
    private fun providerViewGone(a: Agent, view: WebView) {
        view.dispose()
        if (a.webView !== view) return
        a.webView = null
        a.url = ""
        a.loading = false
        a.loadingUrl = null
        loadWaiters.remove(a.provider.id)
        val now = SystemClock.uptimeMillis()
        // A page that takes the renderer down again right after a reload is not reloaded in a loop.
        val again = now - a.goneAt < 15_000
        a.goneAt = now
        val job = a.job
        if (job != null) finish(a, job, ok = false)
        else if (openId == a.provider.id) notice(a, context.getString(if (again) R.string.notice_agent_stopped else R.string.notice_agent_reloaded, a.provider.name))
        if (openId != a.provider.id || again) return
        main.post {
            if (a.webView != null || openId != a.provider.id) return@post
            when (a.state) {
                "signedIn" -> loadProvider(a, a.provider.appUrl)
                "signingIn" -> loadProvider(a, a.provider.loginUrl)
            }
        }
    }

    // Popups ------------------------------------------------------------------------------------------

    /** Gives a window.open / target=_blank request a hidden WebView; its first page decides where it goes. */
    private fun attachPopup(a: Agent, resultMsg: Message, nested: Boolean): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val popup = WebView(context)
        Tab.configure(context, popup)
        popup.webViewClient = PopupClient(a, nested)
        popup.webChromeClient = PopupChrome(a)
        a.popupCandidates.add(popup)
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    /**
     * Where a window's first page belongs, as on desktop: sign-in pages (and anything but the app while
     * signing in) open in the panel, the provider's app loads in the agent view, other sites open in a
     * browser tab. Windows opened by a sign-in window always become tabs. Returns true if [view] keeps the page.
     */
    internal fun routePopup(a: Agent, view: WebView, url: String, nested: Boolean): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()
        val web = scheme == "http" || scheme == "https"
        val kind = if (web) LoginState.classifyProviderUrl(a.provider, url) else "none"
        val state = a.state
        a.popupCandidates.remove(view)
        when {
            !web || state == "signedOut" -> Unit
            nested -> browser.open(url, newTab = true)
            kind == "auth" || (state == "signingIn" && kind != "app") -> {
                val old = a.popup
                a.popup = view
                a.popupUrl = url
                if (old != null && old !== view) old.dispose()
                return true
            }
            kind == "app" && state == "signedIn" -> loadProvider(a, url)
            kind == "external" && state == "signedIn" -> browser.open(url, newTab = true)
        }
        // Not destroyed inside its own callback.
        main.post { view.dispose() }
        return false
    }

    private inner class PopupClient(private val a: Agent, private val nested: Boolean) : WebViewClient() {
        private var routed = false

        /** True if the view goes on loading [url]. */
        private fun route(view: WebView, url: String): Boolean {
            if (routed || url == "about:blank") return true
            routed = true
            return routePopup(a, view, url, nested)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase().orEmpty()
            val allowed = scheme == "http" || scheme == "https" || scheme == "about"
            if (!request.isForMainFrame) return !allowed
            // A window whose first page is not a web page (data:, intent:, …) is dropped by route().
            val keep = route(view, request.url.toString())
            return !allowed || !keep
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (!route(view, url)) {
                view.stopLoading()
                return
            }
            if (view === a.popup && url != "about:blank") a.popupUrl = url
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (view === a.popup && url != "about:blank") a.popupUrl = url
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) = handler.cancel()

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (a.popup === view) {
                a.popup = null
                a.popupUrl = ""
            }
            a.popupCandidates.remove(view)
            view.dispose()
            return true
        }
    }

    private inner class PopupChrome(private val a: Agent) : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            return attachPopup(a, resultMsg, nested = true)
        }

        override fun onCloseWindow(window: WebView) {
            if (window === a.popup) closePopup(a)
            else if (a.popupCandidates.remove(window)) window.dispose()
        }

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean =
            browser.fileChooser?.invoke(filePathCallback, fileChooserParams) ?: false
    }

    fun closePopup(a: Agent) {
        a.popup?.dispose()
        a.popup = null
        a.popupUrl = ""
        refreshSessions()
    }

    private fun dropPopups(a: Agent) {
        a.popup?.dispose()
        a.popup = null
        a.popupUrl = ""
        a.popupCandidates.toList().forEach { it.dispose() }
        a.popupCandidates.clear()
    }

    /** Destroys every agent WebView (the activity is going away). */
    fun destroy() {
        for (a in agents) {
            cancelJob(a)
            dropPopups(a)
            a.webView?.dispose()
            a.webView = null
        }
        loadWaiters.clear()
        main.removeCallbacksAndMessages(null)
    }

    // Panel ------------------------------------------------------------------------------------------

    fun toggle(id: String) {
        if (openId == id) openId = null else show(id)
    }

    fun show(id: String) {
        val a = open(id)
        if (a.state == "signedIn" && a.urlKind != "app") loadProvider(a, a.provider.appUrl)
    }

    /** Brings the agent's panel up without loading anything. */
    private fun open(id: String): Agent {
        val a = agent(id)
        lastUsed = id
        openId = id
        refreshSessions()
        return a
    }

    fun signIn(id: String) {
        val a = agent(id)
        val before = a.state
        a.signingIn = true
        a.lockReason = "signedOut"
        preview = null
        historyOpen = false
        loadProvider(a, a.provider.loginUrl)
        afterStateChange(a, before)
    }

    fun cancelSignIn(id: String) {
        val a = agent(id)
        val before = a.state
        a.signingIn = false
        a.pending = null
        a.pendingLabel = null
        blank(a)
        afterStateChange(a, before)
    }

    fun signOut(id: String) {
        val a = agent(id)
        val logout = a.provider.logoutUrl
        if (logout != null) {
            // Lock at once; the logout page runs hidden until it has finished.
            val before = a.state
            a.signingOut = true
            a.staleSession = true
            a.signingIn = false
            loadProvider(a, logout)
            afterStateChange(a, before)
            main.postDelayed({ finishSignOut(a) }, 15_000)
        } else notice(a, context.getString(R.string.notice_sign_out_in_app, a.provider.name))
    }

    private fun finishSignOut(a: Agent) {
        if (!a.signingOut || a.loading) return
        a.signingOut = false
        if (a.state == "signedOut") blank(a)
        refreshSessions()
    }

    fun newChat(id: String) {
        val a = agent(id)
        if (a.state == "signedIn") loadProvider(a, a.provider.appUrl)
    }

    private fun notice(a: Agent, text: String) {
        a.notice = text
        main.postDelayed({ if (a.notice == text) a.notice = null }, 6000)
    }

    private fun setPending(a: Agent, label: String, run: () -> Unit) {
        a.pending = run
        a.pendingLabel = label
        show(a.provider.id)
    }

    // Handoff --------------------------------------------------------------------------------------

    /** Puts text into the provider's composer; never sends it. */
    fun handoff(id: String, text: String, target: String, label: String) {
        val a = agent(id)
        lastUsed = id
        if (a.state != "signedIn") {
            setPending(a, label) { handoff(id, text, target, label) }
            return
        }
        open(id)
        when (val plan = LoginState.planHandoff(a.provider, text, target)) {
            LoginState.Handoff.Clipboard -> {
                cancelJob(a)
                toClipboard(a, text)
            }
            is LoginState.Handoff.Url -> loadProvider(a, plan.url) { notice(a, context.getString(R.string.notice_inserted)) }
            is LoginState.Handoff.Insert -> {
                // A newer request replaces one still in progress (nothing is ever sent, so nothing is lost).
                a.job?.finished = true
                val job = HandoffJob("trio-${++jobSeq}-${SystemClock.uptimeMillis()}", text, label)
                a.job = job
                a.busy = true
                val start = { startInsert(a, job) }
                // The new chat, else the page already loading, else the chat app if it is not open yet.
                val url = plan.url ?: a.loadingUrl ?: a.provider.appUrl.takeIf { a.urlKind != "app" || a.webView == null }
                if (url != null) loadProvider(a, url, start) else start()
                // A page that never reports that it finished loading still gets the text, and every handoff ends.
                main.postDelayed(start, LOAD_TIMEOUT_MS)
                main.postDelayed({ if (!job.finished) finish(a, job, ok = false) }, LOAD_TIMEOUT_MS + INSERT_TIMEOUT_MS + 5000)
            }
        }
    }

    private fun startInsert(a: Agent, job: HandoffJob) {
        if (job.started || job.finished || a.job !== job) return
        job.started = true
        if (a.state != "signedIn") {
            // The app bounced to sign-in: try again once the agent is signed in.
            cancelJob(a)
            setPending(a, job.label) { handoff(a.provider.id, job.text, "current", job.label) }
            return
        }
        if (a.loadFailed) {
            finish(a, job, ok = false)
            return
        }
        job.deadline = SystemClock.uptimeMillis() + INSERT_TIMEOUT_MS
        inject(a, job)
        pollInsert(a, job)
    }

    private fun inject(a: Agent, job: HandoffJob) {
        val wv = a.webView ?: return
        job.injections++
        val composer = app.injectScript("find-composer.js")
        val selectors = JsonArray(a.provider.composerSelectors.map(::JsonPrimitive)).toString()
        val expected = job.text.replace("\r", "").length
        val script = """
            (function(){
              var token = ${JsonPrimitive(job.token)};
              window.__trioInsert = {token: token, state: 'pending'};
              var find = $composer;
              find('focus', $selectors, 15000).then(function(r){
                if (!r.found) { window.__trioInsert = {token: token, state: 'done', ok: false}; return; }
                var ok = document.execCommand('insertText', false, ${JsonPrimitive(job.text)});
                return find('measure', $selectors, 0).then(function(m){
                  window.__trioInsert = {token: token, state: 'done', ok: !!ok && m.found && (m.length - r.length) >= $expected * 0.9};
                });
              }).catch(function(){ window.__trioInsert = {token: token, state: 'done', ok: false}; });
              return 'started';
            })()
        """.trimIndent()
        wv.requestFocus()
        wv.evaluateJavascript(script, null)
    }

    private fun pollInsert(a: Agent, job: HandoffJob) {
        if (job.finished || a.job !== job) return
        val wv = a.webView ?: return finish(a, job, ok = false)
        val token = JsonPrimitive(job.token)
        wv.evaluateJavascript("(function(){var r=window.__trioInsert;return JSON.stringify(r&&r.token===$token?r:null);})()") { raw ->
            if (job.finished || a.job !== job) return@evaluateJavascript
            val result = runCatching {
                val inner = Json.parseToJsonElement(raw).jsonPrimitive.contentOrNull ?: "null"
                Json.parseToJsonElement(inner) as? JsonObject
            }.getOrNull()
            val state = (result?.get("state") as? JsonPrimitive)?.contentOrNull
            when {
                state == "done" -> finish(a, job, ok = (result?.get("ok") as? JsonPrimitive)?.booleanOrNull == true)
                SystemClock.uptimeMillis() > job.deadline -> finish(a, job, ok = false)
                else -> {
                    // The page the text was meant for has been replaced (a redirect, the app reloading itself):
                    // start again in the page that is there now.
                    if (result == null && a.loadingUrl == null && job.injections < MAX_INJECTIONS) inject(a, job)
                    main.postDelayed({ pollInsert(a, job) }, 300)
                }
            }
        }
    }

    private fun finish(a: Agent, job: HandoffJob, ok: Boolean) {
        if (job.finished) return
        job.finished = true
        if (a.job === job) {
            a.job = null
            a.busy = false
        }
        if (ok) notice(a, context.getString(R.string.notice_inserted)) else toClipboard(a, job.text)
    }

    private fun cancelJob(a: Agent) {
        a.job?.finished = true
        a.job = null
        a.busy = false
    }

    private fun toClipboard(a: Agent, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        runCatching { cm.setPrimaryClip(ClipData.newPlainText("Trio", text)) }
        notice(a, context.getString(R.string.notice_clipboard))
    }

    // Actions ----------------------------------------------------------------------------------------

    private fun locale(): String = if (Locale.getDefault().language == "ko") "ko" else "en"

    /** A question typed by the user ("@claude …", "@all …"). */
    fun ask(target: String, query: String) {
        val ids = if (target == "all") agents.filter { app.settings.agentEnabled(it.provider.id) }.map { it.provider.id } else listOf(target)
        for (id in ids) {
            if (query.isBlank()) {
                show(id)
                continue
            }
            val prompt = Prompts.build(app.shared.prompts, "ask", Prompts.Input(question = query), locale(), 100_000)
            handoff(id, prompt.text, "new", context.getString(R.string.action_ask))
        }
    }

    /** Runs a quick action against the active tab. */
    fun runAction(id: String, actionId: String, question: String = "") {
        val a = agent(id)
        lastUsed = id
        if (actionId.startsWith("history_")) {
            openHistory(id)
            return
        }
        val tab = browser.active
        val url = tab?.url.orEmpty()
        if (actionId == "search_page") {
            val match = app.shared.searchData.extractSearch(url)
            if (match == null) {
                show(id)
                notice(a, context.getString(R.string.notice_no_search))
                return
            }
            val prompt = Prompts.build(app.shared.prompts, actionId, Prompts.Input(query = match.query), locale(), app.settings.pageCharLimit)
            deliverOrPreview(a, actionId, context.getString(R.string.source_search, match.query), prompt, app.settings.previewSearch)
            return
        }
        if (tab == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            show(id)
            notice(a, context.getString(R.string.notice_page_unavailable))
            return
        }
        tab.webView.evaluateJavascript(app.injectScript("extract-page.js")) { raw ->
            // The result comes from the page: whatever it holds, it must not take the app down.
            runCatching { onPageExtracted(a, actionId, question, raw) }.onFailure {
                show(id)
                notice(a, context.getString(R.string.notice_page_unavailable))
            }
        }
    }

    private fun onPageExtracted(a: Agent, actionId: String, question: String, raw: String?) {
        val id = a.provider.id
        val page = runCatching { Json.parseToJsonElement(raw ?: "null").jsonObject }.getOrNull()
        if (page == null) {
            show(id)
            notice(a, context.getString(R.string.notice_page_unavailable))
            return
        }
        // Only string values count: a page can make document.title (or any field) something else.
        fun field(k: String) = (page[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val host = runCatching { android.net.Uri.parse(field("url")).host.orEmpty() }.getOrDefault("")
        val selection = actionId.endsWith("_selection")
        val content = if (selection) field("selection").trim() else field("text")
        if (content.isBlank()) {
            show(id)
            notice(a, context.getString(if (selection) R.string.notice_no_selection else R.string.notice_page_empty))
            return
        }
        val title = field("title").ifBlank { host }
        // Query strings, fragments, tokens and e-mail addresses never go into a prompt.
        val safeUrl = Privacy.sanitizeUrl(field("url"), app.shared.privacyRules, app.shared.searchData) ?: host
        val prompt = Prompts.build(app.shared.prompts, actionId, Prompts.Input(question = question, title = field("title"), url = safeUrl, content = content), locale(), app.settings.pageCharLimit)
        val source = context.getString(if (selection) R.string.source_selection else R.string.source_page, title, host)
        deliverOrPreview(a, actionId, source, prompt, if (selection) app.settings.previewSelection else app.settings.previewPage)
    }

    private fun deliverOrPreview(a: Agent, actionId: String, source: String, prompt: Prompts.Result, previewWanted: Boolean) {
        val label = context.getString(R.string.action_ask)
        val go = {
            if (previewWanted) {
                show(a.provider.id)
                preview = Preview(previewSeq++, actionId, source, prompt.text, prompt.truncated)
            } else handoff(a.provider.id, prompt.text, "new", label)
        }
        if (a.state != "signedIn") setPending(a, label, go) else go()
    }

    fun confirmPreview(text: String, target: String) {
        val id = openId ?: return
        preview = null
        if (text.isNotBlank()) handoff(id, text.trim(), target, context.getString(R.string.action_ask))
    }

    // History analysis -----------------------------------------------------------------------------

    fun openHistory(id: String = lastUsed) {
        val a = agent(id)
        val go = {
            show(id)
            preview = null
            historyOpen = true
        }
        if (a.state != "signedIn") setPending(a, context.getString(R.string.action_history), go) else go()
    }

    /** The history analysis for [f]. Reads the whole history database: call it off the main thread. */
    fun buildHistoryPreview(f: HistoryForm): Preview {
        val stats = HistoryStatsCalc.compute(
            app.history.allVisits(),
            StatsOptions(System.currentTimeMillis(), TimeZone.getDefault().id, f.range, app.settings.excludeSensitive, emptyList()),
            app.shared,
        )
        val digest = HistoryDigest.render(stats, HistoryDigest.Options(locale(), HistoryDigest.Include(f.queries, f.titles, if (f.paths) "paths" else "sites"), f.kind, app.settings.historyCharLimit))
        val prompt = Prompts.build(app.shared.prompts, "history_${f.kind}", Prompts.Input(question = f.question, content = digest.text, historyKind = f.kind), locale(), app.settings.historyCharLimit + 2000)
        return Preview(
            0,
            "history_${f.kind}",
            context.getString(R.string.source_history, f.range),
            prompt.text,
            digest.truncated || prompt.truncated,
            intArrayOf(stats.totals.visits, stats.totals.sites, stats.totals.searches, stats.totals.excluded),
        )
    }

    /** Rebuilds the history panel's text for the current form, in the background. */
    suspend fun refreshHistoryPreview() {
        val f = historyForm
        val built = withContext(Dispatchers.Default) { runCatching { buildHistoryPreview(f) } }
        if (historyForm != f) return
        built.onSuccess { historyPreview = it.copy(id = previewSeq++) }.onFailure {
            historyPreview = null
            open?.let { a -> notice(a, context.getString(R.string.notice_history_failed)) }
        }
    }

    fun sendHistory(text: String, toAll: Boolean) {
        val id = openId ?: return
        historyOpen = false
        val ids = if (toAll) agents.filter { it.state == "signedIn" && app.settings.agentEnabled(it.provider.id) }.map { it.provider.id } else listOf(id)
        for (x in ids) handoff(x, text.trim(), "new", context.getString(R.string.action_history))
    }

    companion object {
        /** A load of the same page asked for within this time joins the one in progress. */
        const val JOIN_LOAD_MS = 30_000L
        /** How long a handoff waits for its page to finish loading before it tries anyway. */
        const val LOAD_TIMEOUT_MS = 25_000L
        /** How long the composer may take to appear and take the text. */
        const val INSERT_TIMEOUT_MS = 20_000L
        const val MAX_INJECTIONS = 4
    }
}
