package io.github.sakkijarvenpolkka.trio.agents

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
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
import io.github.sakkijarvenpolkka.trio.core.HistoryDigest
import io.github.sakkijarvenpolkka.trio.core.HistoryStatsCalc
import io.github.sakkijarvenpolkka.trio.core.LoginState
import io.github.sakkijarvenpolkka.trio.core.Prompts
import io.github.sakkijarvenpolkka.trio.core.ProviderConfig
import io.github.sakkijarvenpolkka.trio.core.StatsOptions
import kotlinx.serialization.json.Json
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
    var lockReason by mutableStateOf("signedOut")
    var pendingLabel by mutableStateOf<String?>(null)
    var pending: (() -> Unit)? = null
    var url by mutableStateOf("")
    var loading by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    var popup by mutableStateOf<WebView?>(null)
    var webView: WebView? = null

    val state: String get() = LoginState.deriveState(sessionPresent, staleSession, signingIn)

    val urlKind: String get() = if (url.isEmpty() || url == "about:blank") "external" else LoginState.classifyProviderUrl(provider, url)

    /** The login gate: may the provider's page be shown right now? */
    val showProvider: Boolean get() = url.isNotEmpty() && url != "about:blank" && state != "signedOut" && LoginState.gateView(state, urlKind) == "provider"
}

/**
 * The three agents (Claude, Gemini, ChatGPT). Invariant: an agent's chat app is shown, and text is
 * handed to it, only in the signedIn state (session cookie present and no bounce to sign-in).
 */
class AgentHub(private val context: Context, private val app: TrioApp, private val browser: BrowserModel) {
    val agents: List<Agent> = app.shared.providers.map(::Agent)
    var openId by mutableStateOf<String?>(null)
    var preview by mutableStateOf<Preview?>(null)
    var historyOpen by mutableStateOf(false)
    var historyPreview by mutableStateOf<Preview?>(null)
    var historyForm by mutableStateOf(HistoryForm())
    var lastUsed = agents.first().provider.id
    private var previewSeq by mutableIntStateOf(1)
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
            if (present != a.sessionPresent) a.staleSession = false
            a.sessionPresent = present
            if (present && !a.staleSession) a.signingIn = false
            afterStateChange(a, before)
        }
    }

    private fun afterStateChange(a: Agent, before: String) {
        val now = a.state
        if (now != "signedOut") a.lockReason = "signedOut"
        if (now == "signedOut" && before != "signedOut") {
            a.webView?.loadUrl("about:blank")
            if (openId == a.provider.id) {
                preview = null
                historyOpen = false
            }
        }
        if (now == "signedIn" && before != "signedIn") {
            if (a.urlKind != "app") loadProvider(a, a.provider.appUrl)
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
            if (a.signingOut) {
                a.signingOut = false
                a.signingIn = false
            } else a.signingIn = true
            afterStateChange(a, before)
            return
        }
        if (before == "signingIn" && kind == "app") {
            refreshSessions()
            if (a.state != "signedIn") {
                main.postDelayed({
                    a.staleSession = false
                    refreshSessions()
                    if (a.state != "signedIn") {
                        val was = a.state
                        a.signingIn = false
                        a.lockReason = "signInIncomplete"
                        a.webView?.loadUrl("about:blank")
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
        a.webView = wv
    }

    private val loadWaiters = HashMap<String, MutableList<() -> Unit>>()

    private fun loadProvider(a: Agent, url: String, onLoaded: (() -> Unit)? = null) {
        if (onLoaded != null) loadWaiters.getOrPut(a.provider.id) { ArrayList() }.add(onLoaded)
        a.url = url
        webViewFor(a).loadUrl(url)
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
            a.loading = true
        }

        override fun onPageFinished(view: WebView, url: String) {
            a.loading = false
            loadWaiters.remove(a.provider.id)?.forEach { main.post(it) }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (url != "about:blank") onProviderNavigate(a, url) else a.url = url
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) = handler.cancel()
    }

    private inner class ProviderChrome(private val a: Agent) : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // Sign-in popups ("Continue with Google") open inside the panel and keep their opener.
            val popup = WebView(context)
            Tab.configure(context, popup)
            popup.webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) = handler.cancel()
            }
            popup.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) = closePopup(a)
            }
            a.popup = popup
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) = closePopup(a)
    }

    fun closePopup(a: Agent) {
        a.popup?.destroy()
        a.popup = null
        refreshSessions()
    }

    // Panel ------------------------------------------------------------------------------------------

    fun toggle(id: String) {
        if (openId == id) openId = null else show(id)
    }

    fun show(id: String) {
        val a = agent(id)
        lastUsed = id
        openId = id
        preview = preview?.takeIf { openId == id }
        refreshSessions()
        if (a.state == "signedIn" && a.urlKind != "app") loadProvider(a, a.provider.appUrl)
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
        a.webView?.loadUrl("about:blank")
        afterStateChange(a, before)
    }

    fun signOut(id: String) {
        val a = agent(id)
        val logout = a.provider.logoutUrl
        if (logout != null) {
            a.signingOut = true
            loadProvider(a, logout)
        } else notice(a, context.getString(R.string.notice_sign_out_in_app, a.provider.name))
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
        show(id)
        when (val plan = LoginState.planHandoff(a.provider, text, target)) {
            LoginState.Handoff.Clipboard -> toClipboard(a, text)
            is LoginState.Handoff.Url -> loadProvider(a, plan.url) { notice(a, context.getString(R.string.notice_inserted)) }
            is LoginState.Handoff.Insert -> {
                a.busy = true
                val go = { insert(a, text) }
                val target = plan.url
                if (target != null) loadProvider(a, target, go) else go()
            }
        }
    }

    private fun toClipboard(a: Agent, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Trio", text))
        notice(a, context.getString(R.string.notice_clipboard))
        a.busy = false
    }

    private fun insert(a: Agent, text: String) {
        if (a.state != "signedIn") {
            a.busy = false
            setPending(a, context.getString(R.string.action_ask)) { handoff(a.provider.id, text, "current", context.getString(R.string.action_ask)) }
            return
        }
        val wv = webViewFor(a)
        val composer = app.injectScript("find-composer.js")
        val selectors = kotlinx.serialization.json.JsonArray(a.provider.composerSelectors.map(::JsonPrimitive)).toString()
        val expected = text.replace("\r", "").length
        val script = """
            (function(){
              window.__trioInsert = {state:'pending'};
              var find = $composer;
              find('focus', $selectors, 15000).then(function(r){
                if (!r.found) { window.__trioInsert = {state:'done', ok:false}; return; }
                var ok = document.execCommand('insertText', false, ${JsonPrimitive(text)});
                return find('measure', $selectors, 0).then(function(m){
                  window.__trioInsert = {state:'done', ok: !!ok && m.found && (m.length - r.length) >= $expected * 0.9};
                });
              }).catch(function(){ window.__trioInsert = {state:'done', ok:false}; });
              return 'started';
            })()
        """.trimIndent()
        wv.requestFocus()
        wv.evaluateJavascript(script, null)
        pollInsert(a, text, System.currentTimeMillis() + 20_000)
    }

    private fun pollInsert(a: Agent, text: String, deadline: Long) {
        val wv = a.webView ?: return
        wv.evaluateJavascript("JSON.stringify(window.__trioInsert || null)") { raw ->
            val result = runCatching {
                val inner = Json.parseToJsonElement(raw).jsonPrimitive.contentOrNull ?: "null"
                Json.parseToJsonElement(inner) as? JsonObject
            }.getOrNull()
            val done = result?.get("state")?.jsonPrimitive?.contentOrNull == "done"
            when {
                done && result?.get("ok")?.jsonPrimitive?.booleanOrNull == true -> {
                    a.busy = false
                    notice(a, context.getString(R.string.notice_inserted))
                }
                done || System.currentTimeMillis() > deadline -> toClipboard(a, text)
                else -> main.postDelayed({ pollInsert(a, text, deadline) }, 300)
            }
        }
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
            val page = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            if (page == null) {
                show(id)
                notice(a, context.getString(R.string.notice_page_unavailable))
                return@evaluateJavascript
            }
            fun field(k: String) = page[k]?.jsonPrimitive?.contentOrNull.orEmpty()
            val host = runCatching { android.net.Uri.parse(field("url")).host.orEmpty() }.getOrDefault("")
            val selection = actionId.endsWith("_selection")
            val content = if (selection) field("selection").trim() else field("text")
            if (content.isBlank()) {
                show(id)
                notice(a, context.getString(if (selection) R.string.notice_no_selection else R.string.notice_page_empty))
                return@evaluateJavascript
            }
            val title = field("title").ifBlank { host }
            val prompt = Prompts.build(app.shared.prompts, actionId, Prompts.Input(question = question, title = field("title"), url = field("url"), content = content), locale(), app.settings.pageCharLimit)
            val source = context.getString(if (selection) R.string.source_selection else R.string.source_page, title, host)
            deliverOrPreview(a, actionId, source, prompt, if (selection) app.settings.previewSelection else app.settings.previewPage)
        }
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
            rebuildHistoryPreview()
        }
        if (a.state != "signedIn") setPending(a, context.getString(R.string.action_history), go) else go()
    }

    fun rebuildHistoryPreview() {
        val f = historyForm
        val stats = HistoryStatsCalc.compute(
            app.history.allVisits(),
            StatsOptions(System.currentTimeMillis(), TimeZone.getDefault().id, f.range, app.settings.excludeSensitive, emptyList()),
            app.shared,
        )
        val digest = HistoryDigest.render(stats, HistoryDigest.Options(locale(), HistoryDigest.Include(f.queries, f.titles, if (f.paths) "paths" else "sites"), f.kind, app.settings.historyCharLimit))
        val prompt = Prompts.build(app.shared.prompts, "history_${f.kind}", Prompts.Input(question = f.question, content = digest.text, historyKind = f.kind), locale(), app.settings.historyCharLimit + 2000)
        historyPreview = Preview(
            previewSeq++,
            "history_${f.kind}",
            context.getString(R.string.source_history, f.range),
            prompt.text,
            digest.truncated || prompt.truncated,
            intArrayOf(stats.totals.visits, stats.totals.sites, stats.totals.searches, stats.totals.excluded),
        )
    }

    fun sendHistory(text: String, toAll: Boolean) {
        val id = openId ?: return
        historyOpen = false
        val ids = if (toAll) agents.filter { it.state == "signedIn" && app.settings.agentEnabled(it.provider.id) }.map { it.provider.id } else listOf(id)
        for (x in ids) handoff(x, text.trim(), "new", context.getString(R.string.action_history))
    }
}
