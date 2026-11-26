package io.github.sakkijarvenpolkka.trio

import android.net.Uri
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
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
}
