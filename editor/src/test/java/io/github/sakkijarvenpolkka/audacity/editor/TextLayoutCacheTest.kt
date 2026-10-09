// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextLayoutCacheTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun layoutsAreReusedUntilTheirConstraintsStyleOrDensityChange() {
        lateinit var measurer: TextMeasurer
        rule.setContent { measurer = rememberTextMeasurer(cacheSize = 0) }
        rule.waitForIdle()
        val style = TextStyle(fontSize = 12.sp)
        val cache = TextLayoutCache(capacity = 2)
        assertNull(cache.get("Intro", 240, 20, style, 1f))
        val intro = cache.put("Intro", 240, 20, measurer.measure("Intro", style))
        // A frame later: the same object, nothing laid out again.
        assertSame(intro, cache.get(String(charArrayOf('I', 'n', 't', 'r', 'o')), 240, 20, style, 1f))
        assertEquals(1, cache.misses)
        // Other constraints: laid out again (in place).
        assertNull(cache.get("Intro", 240, 30, style, 1f))
        cache.put("Intro", 240, 30, measurer.measure("Intro", style))
        assertEquals(1, cache.size)
        // LRU bound.
        cache.put("Break", 240, 30, measurer.measure("Break", style))
        cache.put("Voice", 240, 30, measurer.measure("Voice", style))
        assertEquals(2, cache.size)
        assertNull(cache.get("Intro", 240, 30, style, 1f))
        // A new style or density clears the cache.
        assertNull(cache.get("Voice", 240, 30, style.copy(fontSize = 14.sp), 1f))
        assertEquals(0, cache.size)
    }
}
