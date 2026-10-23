// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ExtensionsTest {

    private val defaultLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun extensionIsAfterTheLastDotInLowerCase() {
        assertEquals("pdf", extensionOf("Report.PDF"))
        assertEquals("gz", extensionOf("a.tar.gz"))
    }

    @Test
    fun noExtensionForADotThatStartsOrEndsTheName() {
        assertEquals("", extensionOf(".bashrc"))
        assertEquals("", extensionOf("a."))
        assertEquals("", extensionOf("README"))
        assertEquals("", extensionOf(""))
    }

    @Test
    fun lowerCaseIgnoresTheDefaultLocale() {
        // Turkish lower-cases "I" to the dotless "ı".
        Locale.setDefault(Locale.forLanguageTag("tr"))
        assertEquals("zip", extensionOf("A.ZIP"))
    }

    @Test
    fun extensionStartIsTheDotBeforeTheExtension() {
        assertEquals(6, extensionStart("Report.PDF"))
        assertEquals(5, extensionStart("a.tar.gz"))
        assertEquals(-1, extensionStart(".bashrc"))
        assertEquals(-1, extensionStart("a."))
        assertEquals(-1, extensionStart("README"))
    }
}
