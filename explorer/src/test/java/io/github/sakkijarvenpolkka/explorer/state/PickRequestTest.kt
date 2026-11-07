// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickRequestTest {

    @Test
    fun extraMimeTypesWin() {
        val request = PickRequest.of("*/*", arrayOf("image/png", "application/pdf"), false)
        assertEquals(listOf("image/png", "application/pdf"), request.mimeTypes)
        assertFalse(request.allowMultiple)
        assertTrue(request.accepts("image/png"))
        assertTrue(request.accepts("application/pdf"))
        // The type is not used once there are extra types: `*/*` takes nothing more.
        assertFalse(request.accepts("image/jpeg"))
        assertFalse(request.accepts(null))
    }

    @Test
    fun typeUsedWithoutExtra() {
        val request = PickRequest.of("image/*", null, true)
        assertEquals(listOf("image/*"), request.mimeTypes)
        assertTrue(request.allowMultiple)
        // No extra type in the list, or none that is a type, is no list.
        assertEquals(listOf("image/*"), PickRequest.of("image/*", emptyArray(), false).mimeTypes)
        assertEquals(listOf("image/*"), PickRequest.of("image/*", arrayOf(" ", "; q=1"), false).mimeTypes)
    }

    @Test
    fun defaultsToAny() {
        assertEquals(listOf("*/*"), PickRequest.of(null, null, false).mimeTypes)
        assertEquals(listOf("*/*"), PickRequest.of(" ", emptyArray(), false).mimeTypes)
        assertTrue(PickRequest.of(null, null, false).accepts("application/x-anything"))
    }

    @Test
    fun wildcardFamily() {
        val images = PickRequest.of("image/*", null, false)
        assertTrue(images.accepts("image/heic"))
        assertTrue(images.accepts("image/png"))
        assertFalse(images.accepts("video/mp4"))
        // The whole top-level type: not a type that starts like it, nor one that has it after the slash.
        assertFalse(images.accepts("imagex/png"))
        assertFalse(images.accepts("application/image"))
        assertFalse(images.accepts("image"))
    }

    @Test
    fun exactMatch() {
        val pdf = PickRequest.of("application/pdf", null, false)
        assertTrue(pdf.accepts("application/pdf"))
        assertFalse(pdf.accepts("application/pdfx"))
        assertFalse(pdf.accepts("application/zip"))
        assertFalse(pdf.accepts("text/plain"))
    }

    @Test
    fun normalizesCaseAndParameters() {
        val png = PickRequest.of("Image/PNG; q=1", null, false)
        assertEquals(listOf("image/png"), png.mimeTypes)
        assertTrue(png.accepts("image/png"))
        assertTrue(png.accepts(" IMAGE/png; charset=binary"))
        val extra = PickRequest.of(null, arrayOf(" Text/Plain ", "IMAGE/*"), false)
        assertEquals(listOf("text/plain", "image/*"), extra.mimeTypes)
    }

    @Test
    fun upperCaseExtensionStillMatches() {
        val images = PickRequest.of("image/*", null, false)
        assertTrue(images.accepts(MimeResolver().mimeOf("IMG_0001.HEIC")))
    }

    @Test
    fun unknownMimeOnlyForAnyType() {
        assertTrue(PickRequest.of("*/*", null, false).accepts(null))
        assertTrue(PickRequest.of(null, null, false).accepts(null))
        assertTrue(PickRequest.of(null, arrayOf("image/*", "*/*"), false).accepts(null))
        assertFalse(PickRequest.of("image/*", null, false).accepts(null))
        assertFalse(PickRequest.of("application/octet-stream", null, false).accepts(null))
        // A blank type is not known either.
        assertFalse(PickRequest.of("image/*", null, false).accepts(" "))
        assertTrue(PickRequest.of("*/*", null, false).accepts(" "))
    }
}
