/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

class AssetInstallerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** AssetManager-like view of a directory (directories list their children,
     *  files list nothing). */
    private class DirSource(val root: File) : AssetInstaller.AssetSource {
        var opened = 0
        override fun list(path: String): List<String> {
            val f = File(root, path)
            return if (f.isDirectory) f.list()!!.sorted() else emptyList()
        }
        override fun open(path: String): InputStream {
            val f = File(root, path)
            if (!f.isFile) throw FileNotFoundException(path)
            opened++
            return f.inputStream()
        }
    }

    private fun assets(): File {
        val root = tmp.newFolder("apk-assets")
        File(root, "audacity/nyquist/rawwaves").mkdirs()
        File(root, "audacity/plug-ins").mkdirs()
        File(root, "audacity/nyquist/nyquist.lsp").writeText("(nyquist)")
        File(root, "audacity/nyquist/rawwaves/sinewave.raw").writeBytes(ByteArray(16) { it.toByte() })
        File(root, "audacity/plug-ins/highpass.ny").writeText("\$nyquist plug-in")
        File(root, "audacity/locale/ko/LC_MESSAGES").mkdirs()
        File(root, "audacity/locale/ko/LC_MESSAGES/audacity.mo").writeBytes(byteArrayOf(0xde.toByte(), 0x12, 0x04, 0x95.toByte()))
        return root
    }

    @Test
    fun extractsOnceAndKeepsPreferences() {
        val source = DirSource(assets())
        val audacityDir = File(tmp.newFolder("files"), "audacity")
        audacityDir.mkdirs()
        File(audacityDir, "audacity.cfg").writeText("[GUI]\nSolo=Simple\n")
        File(audacityDir, "nyquist").mkdirs()
        File(audacityDir, "nyquist/stale.lsp").writeText("old")
        File(audacityDir, "locale/de/LC_MESSAGES").mkdirs()
        File(audacityDir, "locale/de/LC_MESSAGES/audacity.mo").writeText("stale catalog")
        val installer = AssetInstaller(source, audacityDir)

        assertEquals(4, installer.installIfNeeded("1:a"))
        // The engine's catalogs (API.md §2.1): filesDir/audacity/locale/<lang>/LC_MESSAGES/audacity.mo
        assertEquals(4L, File(audacityDir, "locale/ko/LC_MESSAGES/audacity.mo").length())
        assertFalse(File(audacityDir, "locale/de").exists())
        assertEquals("(nyquist)", File(audacityDir, "nyquist/nyquist.lsp").readText())
        assertEquals(16, File(audacityDir, "nyquist/rawwaves/sinewave.raw").length())
        assertTrue(File(audacityDir, "plug-ins/highpass.ny").isFile)
        assertFalse(File(audacityDir, "nyquist/stale.lsp").exists())
        assertEquals("[GUI]\nSolo=Simple\n", File(audacityDir, "audacity.cfg").readText())
        assertTrue(installer.isUpToDate("1:a"))

        // Same version: nothing copied again
        assertEquals(0, installer.installIfNeeded("1:a"))
        assertEquals(4, source.opened)

        // New version: replaced
        assertFalse(installer.isUpToDate("2:b"))
        assertEquals(4, installer.installIfNeeded("2:b"))
        assertTrue(File(audacityDir, "audacity.cfg").exists())

        // A deleted runtime directory is reinstalled even with the same stamp
        File(audacityDir, "locale").deleteRecursively()
        assertFalse(installer.isUpToDate("2:b"))
        assertEquals(4, installer.installIfNeeded("2:b"))
    }

    @Test
    fun missingAssetsInstallEmptyDirectories() {
        val empty = tmp.newFolder("no-assets")
        val audacityDir = File(tmp.newFolder("files2"), "audacity")
        val installer = AssetInstaller(DirSource(empty), audacityDir)
        assertEquals(0, installer.installIfNeeded("x"))
        assertTrue(File(audacityDir, "nyquist").isDirectory)
        assertTrue(File(audacityDir, "plug-ins").isDirectory)
        assertTrue(File(audacityDir, "locale").isDirectory)
        assertTrue(installer.isUpToDate("x"))
    }
}
