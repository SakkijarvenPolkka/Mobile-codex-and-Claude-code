// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class MimeTypesTest {

    private val defaultLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun baseMimeTypeDropsParametersAndCase() {
        assertEquals("text/html", baseMimeType("TEXT/HTML; charset=utf-8"))
        assertEquals("image/jpeg", baseMimeType(" image/jpeg "))
        assertEquals("application/pdf", baseMimeType("application/pdf"))
        // Lower case whatever the default locale: no dotless i.
        Locale.setDefault(Locale.forLanguageTag("tr"))
        assertEquals("video/x-msvideo", baseMimeType("VIDEO/X-MSVIDEO"))
        assertNull(baseMimeType(null))
        assertNull(baseMimeType(" ; charset=utf-8"))
    }
}
