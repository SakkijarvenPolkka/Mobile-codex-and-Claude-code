// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** What the manifest and the resources ask of the system: predictive back, the window by day and night, backups. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppConfigTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun predictiveBackIsOptedInto() {
        // With nothing left to go back to, the system's back to the home screen animates (Android 13 and later):
        // as the app's manifest is built, the one the tests run with.
        val manifest = context.assets.openXmlResourceParser("AndroidManifest.xml")
        var application: Boolean? = null
        while (application == null && manifest.next() != XmlPullParser.END_DOCUMENT) {
            if (manifest.eventType == XmlPullParser.START_TAG && manifest.name == "application") {
                assertEquals(ExplorerApp::class.java.name, manifest.getAttributeValue(ANDROID, "name"))
                application = manifest.getAttributeBooleanValue(ANDROID, "enableOnBackInvokedCallback", false)
            }
        }
        assertEquals(true, application)
    }

    @Test
    fun windowFollowsDayAndNight() {
        // What shows before the app draws (the window, the splash screen) is the app's background, light or dark.
        assertEquals(0xFFF3F3F3.toInt(), windowBackground(night = false))
        assertTrue(isLightTheme(night = false))
        assertEquals(0xFF202020.toInt(), windowBackground(night = true))
        assertFalse(isLightTheme(night = true))
    }

    @Test
    fun deviceStateIsNeitherBackedUpNorMoved() {
        // The settings and the operations journal name this device's volumes and paths.
        val expected = setOf("sharedpref" to "explorer_settings.xml", "file" to "ops/")
        val excluded = excludedBySection()
        assertEquals(expected, excluded["cloud-backup"])
        assertEquals(expected, excluded["device-transfer"])
    }

    /** The app's window theme in the light or the [night] configuration. */
    private fun theme(night: Boolean): ContextThemeWrapper {
        val configuration = Configuration(context.resources.configuration).apply {
            val mode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
        }
        return ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.Theme_Explorer)
    }

    private fun attribute(night: Boolean, attr: Int): TypedValue =
        TypedValue().also { theme(night).theme.resolveAttribute(attr, it, true) }

    private fun windowBackground(night: Boolean): Int = attribute(night, android.R.attr.windowBackground).data

    private fun isLightTheme(night: Boolean): Boolean = attribute(night, android.R.attr.isLightTheme).data != 0

    /** The (domain, path) excluded in each section of the data extraction rules. */
    private fun excludedBySection(): Map<String, Set<Pair<String, String>>> {
        val found = HashMap<String, MutableSet<Pair<String, String>>>()
        val xml = context.resources.getXml(R.xml.data_extraction_rules)
        var section: String? = null
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType != XmlPullParser.START_TAG) continue
            when (xml.name) {
                "cloud-backup", "device-transfer" -> section = xml.name
                "exclude" -> found.getOrPut(checkNotNull(section)) { HashSet() } +=
                    xml.getAttributeValue(null, "domain") to xml.getAttributeValue(null, "path")
            }
        }
        return found
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
