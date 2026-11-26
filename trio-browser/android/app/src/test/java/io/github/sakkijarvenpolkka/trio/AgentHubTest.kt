package io.github.sakkijarvenpolkka.trio

import android.os.Looper
import android.webkit.CookieManager
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.trio.agents.AgentHub
import io.github.sakkijarvenpolkka.trio.browser.BrowserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
}
