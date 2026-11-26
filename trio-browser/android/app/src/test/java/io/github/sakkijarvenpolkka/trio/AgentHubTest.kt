package io.github.sakkijarvenpolkka.trio

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.trio.agents.AgentHub
import io.github.sakkijarvenpolkka.trio.browser.BrowserModel
import io.github.sakkijarvenpolkka.trio.agents.HistoryForm
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.time.Duration

/** The login gate on Android, driven through the real AgentHub with Robolectric's WebView and cookies. */
@RunWith(RobolectricTestRunner::class)
class AgentHubTest {
    private lateinit var app: TrioApp
    private lateinit var browser: BrowserModel
    private lateinit var hub: AgentHub

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        CookieManager.getInstance().removeAllCookies(null)
        browser = BrowserModel(app, app)
        hub = AgentHub(app, app, browser)
    }

    private fun idle(ms: Long = 0) {
        if (ms > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) else shadowOf(Looper.getMainLooper()).idle()
    }

    /** Simulates the provider WebView committing a navigation. */
    private fun navigate(id: String, url: String) {
        val a = hub.agent(id)
        val wv = hub.webViewFor(a)
        shadowOf(wv).webViewClient.doUpdateVisitedHistory(wv, url, false)
        idle()
    }

    /** Simulates the provider WebView loading a page to the end. */
    private fun loaded(id: String, url: String) {
        val wv = hub.webViewFor(hub.agent(id))
        val client = shadowOf(wv).webViewClient
        client.onPageStarted(wv, url, null)
        client.doUpdateVisitedHistory(wv, url, false)
        client.onPageFinished(wv, url)
        idle()
    }

    private fun signInClaude() {
        CookieManager.getInstance().setCookie("https://claude.ai/", "sessionKey=abc; Secure; HttpOnly")
        hub.refreshSessions()
        idle()
    }

    private fun request(url: String, mainFrame: Boolean = true, gesture: Boolean = true) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = gesture
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    private val crash = object : RenderProcessGoneDetail() {
        override fun didCrash() = true
        override fun rendererPriorityAtExit() = 0
    }

    /** Answers the insert check the way the page does once the composer has the text. */
    private fun answerInsertCheck(wv: WebView, ok: Boolean) {
        val check = shadowOf(wv).lastEvaluatedJavascript
        val token = Regex("r\\.token===\"([^\"]+)\"").find(check)?.groupValues?.get(1) ?: error("no insert check in: $check")
        shadowOf(wv).lastEvaluatedJavascriptCallback.onReceiveValue(JsonPrimitive("""{"token":"$token","state":"done","ok":$ok}""").toString())
        idle()
    }

    private fun clipboardText() = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()

    /** Opens a window from the provider page the way WebView does (target=_blank, window.open). */
    private fun openWindow(chrome: WebChromeClient, opener: WebView): WebView {
        val transport = opener.WebViewTransport()
        val msg = Message.obtain(Handler(Looper.getMainLooper()))
        msg.obj = transport
        assertTrue(chrome.onCreateWindow(opener, false, true, msg))
        idle()
        return transport.webView!!
    }

    @Test
    fun everyAgentStartsLockedAndHidden() {
        for (a in hub.agents) {
            assertEquals("signedOut", a.state)
            assertFalse(a.showProvider)
        }
    }

    @Test
    fun sessionCookieUnlocksAndLoadsTheApp() {
        CookieManager.getInstance().setCookie("https://claude.ai/", "sessionKey=abc; Secure; HttpOnly")
        hub.refreshSessions()
        idle()
        val claude = hub.agent("claude")
        assertEquals("signedIn", claude.state)
        assertNull("nothing loads until the panel opens", claude.webView)
        hub.show("claude")
        assertEquals("https://claude.ai/new", shadowOf(hub.webViewFor(claude)).lastLoadedUrl)
        assertEquals("signedOut", hub.agent("gemini").state)
    }

    @Test
    fun signInShowsOnlyAuthenticationPages() {
        hub.signIn("gemini")
        val gemini = hub.agent("gemini")
        assertEquals("signingIn", gemini.state)
        assertTrue(shadowOf(hub.webViewFor(gemini)).lastLoadedUrl!!.startsWith("https://accounts.google.com/"))
        navigate("gemini", "https://accounts.google.com/v3/signin/identifier")
        assertTrue("auth page is visible while signing in", gemini.showProvider)
    }

    @Test
    fun reachingTheAppWithoutASessionLocksAgain() {
        hub.signIn("chatgpt")
        navigate("chatgpt", "https://chatgpt.com/")
        val chatgpt = hub.agent("chatgpt")
        assertFalse("the anonymous chat app is never shown", chatgpt.showProvider)
        idle(2000)
        assertEquals("signedOut", chatgpt.state)
        assertEquals("signInIncomplete", chatgpt.lockReason)
    }

    @Test
    fun completingSignInUnlocks() {
        hub.signIn("chatgpt")
        CookieManager.getInstance().setCookie("https://chatgpt.com/", "__Secure-next-auth.session-token.0=xyz")
        navigate("chatgpt", "https://chatgpt.com/")
        assertEquals("signedIn", hub.agent("chatgpt").state)
        assertTrue(hub.agent("chatgpt").showProvider)
    }

    @Test
    fun bouncingToSignInMarksTheSessionStale() {
        CookieManager.getInstance().setCookie("https://claude.ai/", "sessionKey=expired")
        hub.refreshSessions()
        navigate("claude", "https://claude.ai/login?returnTo=%2Fnew")
        assertEquals("signingIn", hub.agent("claude").state)
    }

    @Test
    fun otherCookiesDoNotRevalidateAStaleSession() {
        CookieManager.getInstance().setCookie("https://claude.ai/", "sessionKey=revoked")
        hub.refreshSessions()
        navigate("claude", "https://claude.ai/login")
        assertEquals("signingIn", hub.agent("claude").state)
        CookieManager.getInstance().setCookie("https://claude.ai/", "_ga=analytics-1")
        hub.refreshSessions()
        assertEquals("still stale after an unrelated cookie", "signingIn", hub.agent("claude").state)
        CookieManager.getInstance().setCookie("https://claude.ai/", "sessionKey=fresh")
        hub.refreshSessions()
        assertEquals("signedIn", hub.agent("claude").state)
    }

    @Test
    fun signOutLocksAtOnceAndRunsTheLogoutPage() {
        CookieManager.getInstance().setCookie("https://claude.ai/", "sessionKey=abc")
        hub.refreshSessions()
        hub.show("claude")
        hub.signOut("claude")
        val claude = hub.agent("claude")
        assertEquals("signedOut", claude.state)
        assertFalse(claude.showProvider)
        assertEquals("https://claude.ai/logout", shadowOf(hub.webViewFor(claude)).lastLoadedUrl)
    }

    @Test
    fun requestsWaitForSignInThenRun() {
        hub.ask("gemini", "여행 계획")
        idle()
        val gemini = hub.agent("gemini")
        assertEquals("gemini", hub.openId)
        assertNotNull(gemini.pendingLabel)
        CookieManager.getInstance().setCookie("https://google.com/", "SID=1; Domain=.google.com")
        hub.refreshSessions()
        idle()
        assertNull(gemini.pendingLabel)
        assertEquals("https://gemini.google.com/app", shadowOf(hub.webViewFor(gemini)).lastLoadedUrl)
    }

    @Test
    fun externalLinksFromAnAnswerOpenInATab() {
        CookieManager.getInstance().setCookie("https://claude.ai/", "sessionKey=abc")
        hub.refreshSessions()
        val claude = hub.agent("claude")
        val wv = hub.webViewFor(claude)
        val request = object : android.webkit.WebResourceRequest {
            override fun getUrl() = android.net.Uri.parse("https://example.com/source")
            override fun isForMainFrame() = true
            override fun isRedirect() = false
            override fun hasGesture() = true
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = emptyMap<String, String>()
        }
        assertTrue(shadowOf(wv).webViewClient.shouldOverrideUrlLoading(wv, request))
        assertEquals("https://example.com/source", browser.active?.url)
    }

    /** The text goes in once the chat app has loaded, never into the page that was there before. */
    @Test
    fun theTextGoesInOnceTheAppHasLoaded() {
        signInClaude()
        hub.ask("claude", "hello")
        idle()
        val claude = hub.agent("claude")
        val wv = hub.webViewFor(claude)
        assertEquals("https://claude.ai/new", shadowOf(wv).lastLoadedUrl)
        assertNull("nothing runs in the page before the app has loaded", shadowOf(wv).lastEvaluatedJavascript)
        assertTrue(claude.busy)
        loaded("claude", "https://claude.ai/new")
        answerInsertCheck(wv, ok = true)
        assertFalse(claude.busy)
        assertEquals(app.getString(R.string.notice_inserted), claude.notice)
    }

    /** A page that never finishes loading, or never answers, still ends the handoff: the text is on the clipboard. */
    @Test
    fun aHandoffThatNeverHearsBackEndsOnTheClipboard() {
        signInClaude()
        hub.ask("claude", "never answered")
        idle(AgentHub.LOAD_TIMEOUT_MS + AgentHub.INSERT_TIMEOUT_MS + 5_500)
        val claude = hub.agent("claude")
        assertFalse(claude.busy)
        assertEquals(app.getString(R.string.notice_clipboard), claude.notice)
        assertTrue(clipboardText().orEmpty().contains("never answered"))
    }

    /** The app bounces through sign-in and back with the same session: what was asked meanwhile goes through. */
    @Test
    fun aBounceThroughSignInResumesTheRequestThatWaited() {
        CookieManager.getInstance().setCookie("https://google.com/", "SID=1; Domain=.google.com")
        hub.refreshSessions()
        hub.show("gemini")
        val gemini = hub.agent("gemini")
        navigate("gemini", "https://gemini.google.com/app")
        navigate("gemini", "https://accounts.google.com/CheckCookie?continue=https://gemini.google.com/app")
        assertEquals("signingIn", gemini.state)
        hub.ask("gemini", "다시 물어볼게")
        assertNotNull(gemini.pendingLabel)
        navigate("gemini", "https://gemini.google.com/app")
        idle(1_600)
        assertEquals("signedIn", gemini.state)
        assertNull("the waiting request ran", gemini.pendingLabel)
        assertTrue(gemini.busy)
    }

    /** All WebViews share one renderer: when it dies, the agent lets go of its view instead of taking the app down. */
    @Test
    fun aRendererCrashDropsTheAgentViewAndLoadsItAgain() {
        signInClaude()
        hub.show("claude")
        val claude = hub.agent("claude")
        val wv = hub.webViewFor(claude)
        assertTrue(shadowOf(wv).webViewClient.onRenderProcessGone(wv, crash))
        assertTrue(shadowOf(wv).wasDestroyCalled())
        idle()
        val again = claude.webView
        assertNotNull(again)
        assertNotSame(wv, again)
        assertEquals("https://claude.ai/new", shadowOf(again!!).lastLoadedUrl)
        assertEquals(app.getString(R.string.notice_agent_reloaded, "Claude"), claude.notice)
    }

    @Test
    fun aRendererCrashDuringAHandoffKeepsTheTextOnTheClipboard() {
        signInClaude()
        hub.ask("claude", "keep me")
        idle()
        val claude = hub.agent("claude")
        val wv = hub.webViewFor(claude)
        assertTrue(shadowOf(wv).webViewClient.onRenderProcessGone(wv, crash))
        idle()
        assertFalse(claude.busy)
        assertTrue(clipboardText().orEmpty().contains("keep me"))
    }

    /** A page that crashes the renderer again right after reloading is not reloaded in a loop. */
    @Test
    fun aRendererThatKeepsCrashingIsNotReloadedInALoop() {
        signInClaude()
        hub.show("claude")
        val claude = hub.agent("claude")
        val first = hub.webViewFor(claude)
        shadowOf(first).webViewClient.onRenderProcessGone(first, crash)
        idle()
        val second = claude.webView!!
        shadowOf(second).webViewClient.onRenderProcessGone(second, crash)
        idle()
        assertNull(claude.webView)
        assertEquals(app.getString(R.string.notice_agent_stopped, "Claude"), claude.notice)
    }

    @Test
    fun aRendererCrashClosesASignInWindow() {
        hub.signIn("claude")
        val claude = hub.agent("claude")
        val wv = hub.webViewFor(claude)
        val popup = openWindow(shadowOf(wv).webChromeClient!!, wv)
        assertFalse(shadowOf(popup).webViewClient.shouldOverrideUrlLoading(popup, request("https://accounts.google.com/o/oauth2/v2/auth")))
        assertSame(popup, claude.popup)
        assertTrue(shadowOf(popup).webViewClient.onRenderProcessGone(popup, crash))
        assertNull(claude.popup)
        assertTrue(shadowOf(popup).wasDestroyCalled())
    }

    /** As on desktop: windows from an answer open in a browser tab, sign-in windows in the panel, the app in the agent. */
    @Test
    fun windowsGoWhereTheirFirstPageBelongs() {
        signInClaude()
        hub.show("claude")
        val claude = hub.agent("claude")
        val wv = hub.webViewFor(claude)
        val chrome = shadowOf(wv).webChromeClient!!

        val link = openWindow(chrome, wv)
        assertNull("not shown before its page is known", claude.popup)
        assertTrue(shadowOf(link).webViewClient.shouldOverrideUrlLoading(link, request("https://example.com/source")))
        idle()
        assertEquals("https://example.com/source", browser.active?.url)
        assertNull(claude.popup)
        assertTrue(shadowOf(link).wasDestroyCalled())

        val chat = openWindow(chrome, wv)
        assertTrue(shadowOf(chat).webViewClient.shouldOverrideUrlLoading(chat, request("https://claude.ai/chat/42")))
        idle()
        assertEquals("https://claude.ai/chat/42", shadowOf(wv).lastLoadedUrl)

        val signIn = openWindow(chrome, wv)
        assertFalse(shadowOf(signIn).webViewClient.shouldOverrideUrlLoading(signIn, request("https://accounts.google.com/o/oauth2/v2/auth")))
        assertSame(signIn, claude.popup)
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth", claude.popupUrl)
        // Once shown, it may go anywhere a sign-in goes; a window it opens becomes a tab.
        assertFalse(shadowOf(signIn).webViewClient.shouldOverrideUrlLoading(signIn, request("https://claude.ai/api/auth/callback")))
        val nested = openWindow(shadowOf(signIn).webChromeClient!!, signIn)
        assertTrue(shadowOf(nested).webViewClient.shouldOverrideUrlLoading(nested, request("https://policies.google.com/privacy")))
        idle()
        assertEquals("https://policies.google.com/privacy", browser.active?.url)
        assertSame(signIn, claude.popup)
    }

    @Test
    fun lockingTheAgentClosesItsSignInWindow() {
        hub.signIn("claude")
        val claude = hub.agent("claude")
        val wv = hub.webViewFor(claude)
        val popup = openWindow(shadowOf(wv).webChromeClient!!, wv)
        shadowOf(popup).webViewClient.shouldOverrideUrlLoading(popup, request("https://accounts.google.com/signin"))
        assertSame(popup, claude.popup)
        hub.cancelSignIn("claude")
        assertNull("a locked agent shows no sign-in window", claude.popup)
        assertTrue(shadowOf(popup).wasDestroyCalled())
    }

    @Test
    fun fileButtonsInTheAgentPanelOpenThePicker() {
        var asked = 0
        browser.fileChooser = { _, _ -> asked++; true }
        val params = object : WebChromeClient.FileChooserParams() {
            override fun getMode() = MODE_OPEN
            override fun getAcceptTypes() = arrayOf("image/*")
            override fun isCaptureEnabled() = false
            override fun getTitle(): CharSequence? = null
            override fun getFilenameHint(): String? = null
            override fun createIntent() = Intent(Intent.ACTION_GET_CONTENT)
        }
        hub.signIn("claude")
        val wv = hub.webViewFor(hub.agent("claude"))
        assertTrue(shadowOf(wv).webChromeClient!!.onShowFileChooser(wv, ValueCallback {}, params))
        val popup = openWindow(shadowOf(wv).webChromeClient!!, wv)
        shadowOf(popup).webViewClient.shouldOverrideUrlLoading(popup, request("https://accounts.google.com/signin"))
        assertTrue(shadowOf(popup).webChromeClient!!.onShowFileChooser(popup, ValueCallback {}, params))
        assertEquals(2, asked)
    }

    /** A page decides what extract-page.js returns; a title that is not text must not crash the app. */
    @Test
    fun summarizingAPageWhoseFieldsAreNotTextDoesNotCrash() {
        signInClaude()
        val tab = browser.newTab("https://example.com/article")
        hub.runAction("claude", "summarize_page")
        shadowOf(tab.webView).lastEvaluatedJavascriptCallback.onReceiveValue(
            """{"title":{},"url":"https://example.com/article?token=x","lang":"","selection":"","text":"Readable text of the article.","truncated":false}""",
        )
        idle()
        val preview = hub.preview
        assertNotNull(preview)
        assertTrue(preview!!.text.contains("Readable text of the article."))
        assertTrue(preview.source.contains("example.com"))

        hub.preview = null
        hub.runAction("claude", "summarize_page")
        shadowOf(tab.webView).lastEvaluatedJavascriptCallback.onReceiveValue("""{"title":[1,2],"url":{"x":1},"text":{"a":"b"}}""")
        idle()
        assertNull(hub.preview)
        assertEquals(app.getString(R.string.notice_page_empty), hub.agent("claude").notice)

        hub.runAction("claude", "summarize_page")
        shadowOf(tab.webView).lastEvaluatedJavascriptCallback.onReceiveValue(null)
        idle()
        assertEquals(app.getString(R.string.notice_page_unavailable), hub.agent("claude").notice)
    }

    /** The history analysis is built off the main thread; an answer for an older form is dropped. */
    @Test
    fun historyAnalysisIsBuiltInTheBackground() {
        app.history.addVisit("https://news.naver.com/main/a", "뉴스", System.currentTimeMillis() - 60_000, "typed", null)
        hub.historyForm = HistoryForm(range = "7d")
        runBlocking { hub.refreshHistoryPreview() }
        val first = hub.historyPreview
        assertNotNull(first)
        assertTrue(first!!.text, first.text.contains("naver.com"))
        hub.historyForm = HistoryForm(range = "30d", kind = "custom", question = "무엇을 많이 읽었지?")
        runBlocking { hub.refreshHistoryPreview() }
        assertTrue(hub.historyPreview!!.text.contains("무엇을 많이 읽었지?"))
        assertTrue(hub.historyPreview!!.id != first.id)
    }

    /** Downloads from the agent panel go to the download service like a tab's, or say why they cannot. */
    @Test
    fun downloadsFromTheAgentPanelAreHandled() {
        signInClaude()
        val wv = hub.webViewFor(hub.agent("claude"))
        shadowOf(wv).downloadListener.onDownloadStart("blob:https://claude.ai/3f2a", "UA", null, "text/csv", 10)
        assertEquals(app.getString(R.string.notice_download_unsupported), ShadowToast.getTextOfLatestToast())
        shadowOf(wv).downloadListener.onDownloadStart("https://claude.ai/files/report.pdf", "UA", "attachment; filename=report.pdf", "application/pdf", 10)
        assertEquals(app.getString(R.string.notice_download, "report.pdf"), ShadowToast.getTextOfLatestToast())
    }
}
