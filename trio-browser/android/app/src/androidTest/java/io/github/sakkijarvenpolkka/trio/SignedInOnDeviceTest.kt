package io.github.sakkijarvenpolkka.trio

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The signed-in flows on a real WebView, against a local stand-in for Claude (no real account needed):
 * the first question after starting goes into the composer, the app survives its renderer crashing,
 * a hostile page cannot crash "Summarize page", and links in an answer open as browser tabs.
 */
@RunWith(AndroidJUnit4::class)
class SignedInOnDeviceTest {
    private val app: TrioApp = ApplicationProvider.getApplicationContext()
    private lateinit var mock: MockProvider

    @Before
    fun setUp() {
        mock = MockProvider()
        app.providerOverride = app.shared.providers.map { if (it.id == "claude") mock.claude(it) else it }
        clearCookies()
        CookieManager.getInstance().setCookie("http://127.0.0.1/", "sessionKey=test-session")
        CookieManager.getInstance().flush()
    }

    @After
    fun tearDown() {
        app.providerOverride = null
        clearCookies()
        mock.close()
    }

    private fun clearCookies() {
        val done = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { CookieManager.getInstance().removeAllCookies { done.countDown() } }
        done.await(5, TimeUnit.SECONDS)
    }

    private fun <T> ActivityScenario<MainActivity>.read(f: (MainActivity) -> T): T {
        var out: Result<T>? = null
        onActivity { out = runCatching { f(it) } }
        return out!!.getOrThrow()
    }

    private fun ActivityScenario<MainActivity>.waitFor(what: String, timeoutMs: Long = 30_000, done: (MainActivity) -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!read(done)) {
            if (SystemClock.uptimeMillis() > end) fail("timed out waiting for $what")
            Thread.sleep(150)
        }
    }

    /** Runs [script] in the view [pick] returns and gives back its result as a string. */
    private fun ActivityScenario<MainActivity>.js(pick: (MainActivity) -> WebView?, script: String): String? {
        val latch = CountDownLatch(1)
        var out: String? = null
        onActivity { a ->
            val wv = pick(a)
            if (wv == null) latch.countDown() else wv.evaluateJavascript(script) { out = it; latch.countDown() }
        }
        latch.await(10, TimeUnit.SECONDS)
        return out?.let { raw -> runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrDefault(raw) }
    }

    private fun claudeView(a: MainActivity) = a.hub.agent("claude").webView

    private fun ActivityScenario<MainActivity>.waitForAppPage() {
        waitFor("the chat app") { a -> a.hub.agent("claude").url == mock.app && !a.hub.agent("claude").loading }
        val end = SystemClock.uptimeMillis() + 15_000
        while (js(::claudeView, "document.readyState === 'complete' && !!document.querySelector('.ProseMirror')") != "true") {
            if (SystemClock.uptimeMillis() > end) fail("the chat app did not load")
            Thread.sleep(150)
        }
    }

    /** Right after starting, the agent has no page yet: the question must land in the app's composer. */
    @Test
    fun theFirstQuestionAfterStartingGoesIntoTheComposer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals("signedIn", scenario.read { it.hub.agent("claude").state })
            scenario.onActivity { it.hub.ask("claude", "hello from the device test") }
            scenario.waitFor("the handoff to end", 60_000) { !it.hub.agent("claude").busy }
            assertEquals(app.getString(R.string.notice_inserted), scenario.read { it.hub.agent("claude").notice })
            val composer = scenario.js(::claudeView, "document.querySelector('.ProseMirror').textContent")
            assertTrue("composer: $composer", composer.orEmpty().contains("hello from the device test"))
        }
    }

    /** Two questions in a row while the app loads: the later one is what ends up in the composer, once. */
    @Test
    fun askingAgainWhileTheAppLoadsInsertsTheLatestQuestion() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.hub.ask("claude", "first question")
                it.hub.ask("claude", "second question")
            }
            scenario.waitFor("the handoff to end", 60_000) { !it.hub.agent("claude").busy }
            assertEquals(app.getString(R.string.notice_inserted), scenario.read { it.hub.agent("claude").notice })
            val composer = scenario.js(::claudeView, "document.querySelector('.ProseMirror').textContent").orEmpty()
            assertTrue("composer: $composer", composer.contains("second question"))
            assertEquals("composer: $composer", 1, Regex("second question").findAll(composer).count())
        }
    }

    /** All WebViews share one renderer; when it crashes, the agents and tabs must let go and the app live on. */
    @Test
    fun theAppSurvivesItsRendererCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.hub.show("claude") }
            scenario.waitForAppPage()
            val before = scenario.read { claudeView(it) }
            scenario.onActivity { it.browser.active!!.webView.loadUrl("chrome://crash") }
            scenario.waitFor("the agent's page to be made again", 30_000) { a -> claudeView(a).let { it != null && it !== before } }
            scenario.waitForAppPage()
            assertNotSame(before, scenario.read { claudeView(it) })
            assertEquals(1, scenario.read { it.browser.tabs.size })
            assertEquals("claude", scenario.read { it.hub.openId })
        }
    }

    /** A page can shadow document.title with a named element; summarizing it shows the real title. */
    @Test
    fun summarizingAPageWithAShadowedTitleWorks() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.browser.open(mock.clobbered) }
            scenario.waitFor("the page") { a -> a.browser.active?.let { it.url == mock.clobbered && !it.loading } == true }
            scenario.onActivity { it.hub.runAction("claude", "summarize_page") }
            scenario.waitFor("the preview") { it.hub.preview != null }
            val preview = scenario.read { it.hub.preview }!!
            assertTrue(preview.source, preview.source.contains("Clobbered Title"))
            assertTrue(preview.text.contains("Readable text of the clobbered article."))
        }
    }

    /** A link in an answer (target=_blank) opens as a browser tab, not inside the agent panel. */
    @Test
    fun aLinkInAnAnswerOpensAsABrowserTab() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.hub.show("claude") }
            scenario.waitForAppPage()
            // Tap the link like a finger does (a page cannot open windows without a tap).
            val where = scenario.js(::claudeView, "(function(){var r=document.getElementById('source').getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2,window.devicePixelRatio].join(',');})()")
            assertNotNull(where)
            val (x, y, dpr) = where!!.split(',').map { it.toFloat() }
            val origin = IntArray(2)
            scenario.onActivity { claudeView(it)!!.getLocationOnScreen(origin) }
            tap(origin[0] + x * dpr, origin[1] + y * dpr)
            scenario.waitFor("the link in a tab") { a -> a.browser.tabs.size == 2 && a.browser.active?.url == mock.outside }
            assertEquals("the panel shows no window", null, scenario.read { it.hub.agent("claude").popup })
            assertEquals(mock.app, scenario.read { it.hub.agent("claude").url })
        }
    }

    private fun tap(x: Float, y: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val t = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, x, y, 0))
    }
}
