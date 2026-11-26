package io.github.sakkijarvenpolkka.trio

import android.net.Uri
import android.os.Looper
import android.net.http.SslCertificate
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.trio.browser.BrowserModel
import io.github.sakkijarvenpolkka.trio.browser.Tab
import io.github.sakkijarvenpolkka.trio.data.HistoryDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class BrowserModelTest {
    private val app: TrioApp = ApplicationProvider.getApplicationContext()

    @Test
    fun addressBarOpensUrlsSearchesAndAsksAgents() {
        val browser = BrowserModel(app, app)
        var asked: Pair<String, String>? = null
        browser.onAgentRequest = { id, q -> asked = id to q }
        val tab = browser.newTab(null)
        browser.navigate("naver.com")
        assertEquals("https://naver.com/", shadowOf(tab.webView).lastLoadedUrl)
        browser.navigate("서울 날씨")
        assertEquals("https://www.google.com/search?q=%EC%84%9C%EC%9A%B8%20%EB%82%A0%EC%94%A8", shadowOf(tab.webView).lastLoadedUrl)
        browser.navigate("@클로드 안녕")
        assertEquals("claude" to "안녕", asked)
        browser.navigate("javascript:alert(1)")
        assertTrue(shadowOf(tab.webView).lastLoadedUrl!!.startsWith("https://www.google.com/search?q=javascript"))
    }

    @Test
    fun tabsOpenActivateAndClose() {
        val browser = BrowserModel(app, app)
        val first = browser.newTab("https://example.com/")
        val second = browser.newTab("https://example.org/")
        assertEquals(1, browser.activeIndex)
        browser.closeTab(second)
        assertEquals(first, browser.active)
        browser.closeTab(first)
        assertEquals(1, browser.tabs.size) // a fresh tab replaces the last one
    }

    @Test
    fun userAgentHidesTheWebViewMarkers() {
        val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/156.0.0.0 Mobile Safari/537.36"
        assertEquals("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36", Tab.cleanUserAgent(ua))
    }

    @Test
    fun historyStoresSearchesAndKoreanSubstrings() {
        val db = HistoryDb(app, null)
        val t0 = 1_764_115_200_000L
        val a = db.addVisit("https://news.naver.com/a", "크로뮴 브라우저 소식", t0, "typed", null)
        db.addVisit("https://example.com/b", "Example", t0 + 1000, "link", a)
        db.addVisit("https://news.naver.com/a", "", t0 + 2000, "reload", null)
        db.setDuration(a, 61_000)
        assertEquals(listOf("https://news.naver.com/a"), db.query("브라", null, 10).map { it.url })
        assertEquals(2, db.query("", null, 10).size)
        assertEquals("https://news.naver.com/a", db.suggest("news", 5).first().first)
        val visits = db.allVisits()
        assertEquals(3, visits.size)
        assertEquals(61_000L, visits[0].durationMs)
        db.deleteVisit(visits[1].id)
        assertTrue(db.suggest("example", 5).isEmpty())
    }

    private fun request(url: String, mainFrame: Boolean = true, gesture: Boolean = true) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = gesture
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    private fun resumed(tab: Tab) = shadowOf(tab.webView).wasOnResumeCalled()
    private fun forgetResume(tab: Tab) = ReflectionHelpers.setField(shadowOf(tab.webView), "onResumeCalled", false)

    /** The tab that comes on screen when the shown one closes runs again (a paused WebView looks frozen). */
    @Test
    fun closingTheShownTabResumesTheOneThatTakesItsPlace() {
        val browser = BrowserModel(app, app)
        val first = browser.newTab("https://example.com/")
        val second = browser.newTab("https://example.org/")
        forgetResume(first)
        browser.closeTab(second)
        assertSame(first, browser.active)
        assertTrue(resumed(first))
    }

    @Test
    fun closingAnEarlierTabKeepsTheShownOne() {
        val browser = BrowserModel(app, app)
        val a = browser.newTab("https://a.example/")
        browser.newTab("https://b.example/")
        val c = browser.newTab("https://c.example/")
        assertSame(c, browser.active)
        browser.closeTab(a)
        assertSame(c, browser.active)
        assertEquals(1, browser.activeIndex)
    }

    /** An error belongs to the address that failed: back, forward or a link to another page clears it. */
    @Test
    fun anErrorIsClearedWhenAnotherPageStarts() {
        val browser = BrowserModel(app, app)
        val tab = browser.newTab("https://example.com/")
        val client = shadowOf(tab.webView).webViewClient
        val failed = "https://offline.example/"
        client.shouldOverrideUrlLoading(tab.webView, request(failed))
        client.onPageStarted(tab.webView, failed, null)
        // What onReceivedError records for a main-frame failure.
        tab.error = "net::ERR_NAME_NOT_RESOLVED"
        tab.errorUrl = failed
        assertEquals("net::ERR_NAME_NOT_RESOLVED", tab.error)
        client.doUpdateVisitedHistory(tab.webView, failed, false)
        assertNotNull("the error page commit keeps it", tab.error)
        client.onPageStarted(tab.webView, "https://example.com/", null)
        assertNull(tab.error)
    }

    @Test
    fun restoredTabsLoadWhenTheyAreShown() {
        val browser = BrowserModel(app, app)
        browser.restore(listOf("https://a.example/", "", "https://c.example/"), active = 2)
        assertEquals(3, browser.tabs.size)
        assertEquals(2, browser.activeIndex)
        assertEquals("https://c.example/", shadowOf(browser.tabs[2].webView).lastLoadedUrl)
        assertNull("not loaded until shown", shadowOf(browser.tabs[0].webView).lastLoadedUrl)
        assertEquals("https://a.example/", browser.tabs[0].url)
        browser.activate(0)
        assertEquals("https://a.example/", shadowOf(browser.tabs[0].webView).lastLoadedUrl)
        assertTrue(browser.tabs[1].url.isEmpty())
    }

    /** All tabs share one renderer: each crashed tab is replaced, and only the one on screen loads again now. */
    @Test
    fun aCrashedRendererReplacesEachTabAndReloadsOnlyTheShownOne() {
        val browser = BrowserModel(app, app)
        val a = browser.newTab("https://a.example/")
        val b = browser.newTab("https://b.example/")
        val gone = object : RenderProcessGoneDetail() {
            override fun didCrash() = false
            override fun rendererPriorityAtExit() = 0
        }
        for (tab in listOf(a, b)) assertTrue(shadowOf(tab.webView).webViewClient.onRenderProcessGone(tab.webView, gone))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, browser.tabs.size)
        assertFalse(browser.tabs.contains(a) || browser.tabs.contains(b))
        assertEquals(1, browser.activeIndex)
        assertEquals("https://b.example/", shadowOf(browser.active!!.webView).lastLoadedUrl)
        assertNull(shadowOf(browser.tabs[0].webView).lastLoadedUrl)
        assertEquals("https://a.example/", browser.tabs[0].url)
    }

    /** As in Chrome: an app link for an app that is not installed opens the page's fallback address. */
    @Test
    fun appLinksFallBackWhenTheAppIsMissing() {
        shadowOf(app).checkActivities(true)
        val browser = BrowserModel(app, app)
        val tab = browser.newTab("https://shop.example/")
        val client = shadowOf(tab.webView).webViewClient
        val link = "intent://pay/#Intent;scheme=cardapp;package=com.example.missing;S.browser_fallback_url=https%3A%2F%2Fshop.example%2Fpay%2Fweb;end"
        assertTrue(client.shouldOverrideUrlLoading(tab.webView, request(link)))
        assertEquals("https://shop.example/pay/web", shadowOf(tab.webView).lastLoadedUrl)
        // No fallback, no store on the device: say so instead of doing nothing.
        assertTrue(client.shouldOverrideUrlLoading(tab.webView, request("intent://x/#Intent;scheme=nothing;package=com.example.none;end")))
        assertEquals(app.getString(R.string.notice_no_app), ShadowToast.getTextOfLatestToast())
    }

    /** An embedded frame (an ad) cannot open another app on its own; the page or a tap can. */
    @Test
    fun framesOpenOtherAppsOnlyFromATap() {
        val browser = BrowserModel(app, app)
        val tab = browser.newTab("https://news.example/")
        val client = shadowOf(tab.webView).webViewClient
        assertTrue(client.shouldOverrideUrlLoading(tab.webView, request("market://details?id=com.example.ad", mainFrame = false, gesture = false)))
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(client.shouldOverrideUrlLoading(tab.webView, request("market://details?id=com.example.app", mainFrame = false, gesture = true)))
        assertNotNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun downloadsTheDownloadServiceCannotTakeSaySo() {
        val browser = BrowserModel(app, app)
        val tab = browser.newTab("https://app.example/")
        shadowOf(tab.webView).downloadListener.onDownloadStart("blob:https://app.example/6f1c", "UA", null, "text/csv", 10)
        assertEquals(app.getString(R.string.notice_download_unsupported), ShadowToast.getTextOfLatestToast())
    }

    private fun gone(crashed: Boolean) = object : RenderProcessGoneDetail() {
        override fun didCrash() = crashed
        override fun rendererPriorityAtExit() = 0
    }

    /** A page that crashed the renderer is not reloaded on its own (it could crash it again, forever): it offers Try again. */
    @Test
    fun aPageThatCrashedTheRendererWaitsForTryAgain() {
        val browser = BrowserModel(app, app)
        val tab = browser.newTab("https://heavy.example/")
        assertTrue(shadowOf(tab.webView).webViewClient.onRenderProcessGone(tab.webView, gone(crashed = true)))
        val replacement = browser.active!!
        assertTrue(replacement.crashed)
        assertEquals(app.getString(R.string.error_crashed), replacement.error)
        assertNull(shadowOf(replacement.webView).lastLoadedUrl)
        browser.reload()
        assertEquals("https://heavy.example/", shadowOf(replacement.webView).lastLoadedUrl)
        assertNull(replacement.error)
        assertFalse(replacement.crashed)
    }

    /** Killed for memory: loaded again once, but not again if it goes right away (a loop). */
    @Test
    fun aTabKilledForMemoryLoadsAgainOnceAndNotInALoop() {
        val browser = BrowserModel(app, app)
        val tab = browser.newTab("https://news.example/")
        shadowOf(tab.webView).webViewClient.onRenderProcessGone(tab.webView, gone(crashed = false))
        val second = browser.active!!
        assertEquals("https://news.example/", shadowOf(second.webView).lastLoadedUrl)
        shadowOf(second.webView).webViewClient.onRenderProcessGone(second.webView, gone(crashed = false))
        val third = browser.active!!
        assertNull(shadowOf(third.webView).lastLoadedUrl)
        assertEquals(app.getString(R.string.error_crashed), third.error)
    }

    @Test
    fun aTabReclaimedInTheBackgroundLoadsWhenTrioIsBack() {
        val browser = BrowserModel(app, app)
        val tab = browser.newTab("https://news.example/")
        browser.onAppPause()
        shadowOf(tab.webView).webViewClient.onRenderProcessGone(tab.webView, gone(crashed = false))
        val replacement = browser.active!!
        assertNull(shadowOf(replacement.webView).lastLoadedUrl)
        browser.onAppResume()
        assertEquals("https://news.example/", shadowOf(replacement.webView).lastLoadedUrl)
    }

    private fun committed(tab: Tab, url: String) {
        shadowOf(tab.webView).webViewClient.onPageStarted(tab.webView, url, null)
        // What WebView.getUrl() answers: the page that is there.
        ReflectionHelpers.setField(shadowOf(tab.webView), "originalUrl", url)
    }

    /** A page's fallback address must not show in the address bar (with its lock) before that page arrives. */
    @Test
    fun aFallbackDoesNotShowItsAddressBeforeItArrives() {
        shadowOf(app).checkActivities(true)
        val browser = BrowserModel(app, app)
        val tab = browser.newTab("https://shop.example/")
        committed(tab, "https://shop.example/")
        val client = shadowOf(tab.webView).webViewClient
        val link = "intent://x#Intent;scheme=nope;S.browser_fallback_url=https%3A%2F%2Fwww.google.com%2Fgenerate_204;end"
        assertTrue(client.shouldOverrideUrlLoading(tab.webView, request(link)))
        assertEquals("https://www.google.com/generate_204", shadowOf(tab.webView).lastLoadedUrl)
        assertEquals("https://shop.example/", tab.url)
        // It never arrives (no content): the address stays that of the page on screen.
        ReflectionHelpers.setField(shadowOf(tab.webView), "originalUrl", "https://shop.example/")
        client.onPageFinished(tab.webView, "https://www.google.com/generate_204")
        assertEquals("https://shop.example/", tab.url)
        // A frame gets no fallback at all.
        val frame = "intent://y#Intent;scheme=nope;S.browser_fallback_url=https%3A%2F%2Fevil.example%2F;end"
        assertTrue(client.shouldOverrideUrlLoading(tab.webView, request(frame, mainFrame = false, gesture = true)))
        assertEquals("https://www.google.com/generate_204", shadowOf(tab.webView).lastLoadedUrl)
    }

    /** A typed address that never arrives (a download, no content) gives the address bar back to the page there. */
    @Test
    fun aTypedAddressThatNeverArrivesShowsThePageThatIsThere() {
        val browser = BrowserModel(app, app)
        val tab = browser.newTab("https://a.example/")
        committed(tab, "https://a.example/")
        browser.load(tab, "https://files.example/report.pdf", "typed")
        assertEquals("https://files.example/report.pdf", tab.url)
        ReflectionHelpers.setField(shadowOf(tab.webView), "originalUrl", "https://a.example/")
        shadowOf(tab.webView).webViewClient.onPageFinished(tab.webView, "https://files.example/report.pdf")
        assertEquals("https://a.example/", tab.url)
    }

    /** A refused certificate on a load WebView did not ask about (a form sent, back) still shows the error. */
    @Test
    fun aRefusedCertificateOnAFormOrBackShowsTheError() {
        val browser = BrowserModel(app, app)
        val tab = browser.newTab("https://a.example/")
        committed(tab, "https://a.example/")
        val client = shadowOf(tab.webView).webViewClient
        val handler = ReflectionHelpers.callConstructor(SslErrorHandler::class.java)
        val cert = SslCertificate("bad.example", "nobody", "20260101000000Z", "20270101000000Z")
        client.onReceivedSslError(tab.webView, handler, SslError(SslError.SSL_UNTRUSTED, cert, "https://bad.example/login"))
        assertNull("not known yet to be the page itself", tab.error)
        client.onPageFinished(tab.webView, "https://bad.example/login")
        assertEquals("SSL", tab.error)
        assertEquals("https://bad.example/login", tab.errorUrl)
        assertEquals("the page under the error is still the one shown", "https://a.example/", tab.url)
        // Back closes the error (the page under it is still there) instead of skipping that page.
        assertTrue(browser.errorOverPage)
        browser.back()
        assertNull(tab.error)
        // A picture with a bad certificate on a page is not an error for the page.
        client.onReceivedSslError(tab.webView, handler, SslError(SslError.SSL_UNTRUSTED, cert, "https://bad.example/pixel.png"))
        client.onPageFinished(tab.webView, "https://a.example/")
        assertNull(tab.error)
    }

    /** "Try again" on an error loads the address that failed, not the page under the error. */
    @Test
    fun tryAgainLoadsTheAddressThatFailed() {
        val browser = BrowserModel(app, app)
        val tab = browser.newTab("https://a.example/")
        committed(tab, "https://a.example/")
        tab.error = "SSL"
        tab.errorUrl = "https://bad.example/login"
        browser.reload()
        assertEquals("https://bad.example/login", shadowOf(tab.webView).lastLoadedUrl)
        assertNull(tab.error)
    }

    /** A web address in intent form, or one aimed at Trio itself, stays in the tab instead of opening Trio again. */
    @Test
    fun intentsForTrioItselfStayInTheTab() {
        val browser = BrowserModel(app, app)
        val tab = browser.newTab("https://a.example/")
        committed(tab, "https://a.example/")
        val client = shadowOf(tab.webView).webViewClient
        assertTrue(client.shouldOverrideUrlLoading(tab.webView, request("intent://evil.example/#Intent;scheme=https;package=${app.packageName};end", gesture = false)))
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals("https://evil.example/", shadowOf(tab.webView).lastLoadedUrl)
        assertEquals("https://a.example/", tab.url)
        assertTrue(client.shouldOverrideUrlLoading(tab.webView, request("intent://web.example/p#Intent;scheme=https;end", gesture = false)))
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals("https://web.example/p", shadowOf(tab.webView).lastLoadedUrl)
    }

    @Test
    fun theStoppedTitleGoesWithItsError() {
        val browser = BrowserModel(app, app)
        val tab = browser.newTab("https://heavy.example/")
        shadowOf(tab.webView).webViewClient.onRenderProcessGone(tab.webView, gone(crashed = true))
        val replacement = browser.active!!
        assertTrue(replacement.crashed)
        browser.load(replacement, "https://other.example/", "typed")
        assertFalse(replacement.crashed)
    }
}
