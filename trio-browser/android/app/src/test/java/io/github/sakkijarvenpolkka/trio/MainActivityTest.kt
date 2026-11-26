package io.github.sakkijarvenpolkka.trio

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @Test
    fun launchesWithOneTabAndThreeLockedAgents() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, activity.browser.tabs.size)
            assertEquals(listOf("claude", "gemini", "chatgpt"), activity.hub.agents.map { it.provider.id })
            assertEquals(setOf("signedOut"), activity.hub.agents.map { it.state }.toSet())
            assertNotNull(activity.browser.active?.webView?.parent)
        }
    }

    @Test
    fun opensLinksFromOtherApps() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/page"), ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        Robolectric.buildActivity(MainActivity::class.java, intent).use { controller ->
            val activity = controller.setup().get()
            assertEquals("https://example.com/page", shadowOf(activity.browser.active!!.webView).lastLoadedUrl)
        }
    }

    /** Recreated after being stopped in the background: the tabs come back and the first link is not opened again. */
    @Test
    fun tabsComeBackWhenTheActivityIsRecreated() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/first"), ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { it.browser.newTab("https://example.org/second") }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(listOf("https://example.com/first", "https://example.org/second"), activity.browser.tabs.map { it.url })
                assertEquals(1, activity.browser.activeIndex)
            }
        }
    }

    /** Reopened from Recents, the system gives back the intent that first started Trio: it is not opened again. */
    @Test
    fun reopeningFromRecentsDoesNotOpenTheFirstLinkAgain() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/old-link"), ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        Robolectric.buildActivity(MainActivity::class.java, intent).use { controller ->
            val activity = controller.setup().get()
            assertEquals(1, activity.browser.tabs.size)
            assertEquals("", activity.browser.active!!.url)
        }
    }
}
