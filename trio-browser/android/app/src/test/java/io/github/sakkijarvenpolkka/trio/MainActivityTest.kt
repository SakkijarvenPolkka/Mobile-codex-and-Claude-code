package io.github.sakkijarvenpolkka.trio

import android.content.Intent
import android.net.Uri
import android.os.Looper
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
}
