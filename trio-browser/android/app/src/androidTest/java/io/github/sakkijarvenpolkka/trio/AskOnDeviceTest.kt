package io.github.sakkijarvenpolkka.trio

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakkijarvenpolkka.trio.agents.HistoryForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The user's path: ask an agent from the address bar, from search, and analyse history. */
@RunWith(AndroidJUnit4::class)
class AskOnDeviceTest {
    @Test
    fun askingALockedAgentShowsItsLockScreenInsteadOfCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.browser.navigate("@claude 오늘 날씨 알려줘") }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val claude = activity.hub.agent("claude")
                assertEquals("claude", activity.hub.openId)
                assertEquals("signedOut", claude.state)
                assertNotNull("the request waits for sign-in", claude.pendingLabel)
            }
            scenario.onActivity { it.hub.ask("all", "세 모델 비교") }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity -> for (a in activity.hub.agents) assertNotNull(a.provider.id, a.pendingLabel) }
        }
    }

    @Test
    fun askingAboutASearchAndHistoryAnalysisDoNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.browser.active!!.url = "https://www.google.com/search?q=trio+browser"
                activity.hub.runAction("gemini", "search_page")
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("gemini", activity.hub.openId)
                assertNotNull(activity.hub.agent("gemini").pendingLabel)
                assertTrue(activity.hub.buildHistoryPreview(HistoryForm()).text.isNotEmpty())
            }
        }
    }
}
