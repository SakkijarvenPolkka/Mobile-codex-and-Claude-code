package io.github.sakkijarvenpolkka.trio

import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.trio.browser.BrowserModel
import io.github.sakkijarvenpolkka.trio.browser.Tab
import io.github.sakkijarvenpolkka.trio.data.HistoryDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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
}
