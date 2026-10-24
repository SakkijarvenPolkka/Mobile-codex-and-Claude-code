// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every user-facing string exists in English and Korean under the same key. */
class StringsParityTest {

    @Test
    fun sameKeysInEnglishAndKorean() {
        val english = keys(resFile("values/strings.xml"))
        val korean = keys(resFile("values-ko/strings.xml"))
        assertTrue("no strings in values/strings.xml", english.isNotEmpty())
        assertEquals(
            "only in English: ${english - korean}, only in Korean: ${korean - english}",
            english,
            korean,
        )
    }

    @Test
    fun koreanWritesPreviewAsOneWord() {
        // 미리보기, as the command and the pane are named, never 미리 보기.
        val spaced = texts(resFile("values-ko/strings.xml")).filter { "미리 보기" in it }
        assertEquals(emptyList<String>(), spaced)
    }

    /** The text of every `string` of a `strings.xml`. */
    private fun texts(file: File): List<String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it).textContent }
    }

    /** `src/main/res/<path>` of this module (Gradle runs unit tests in the module directory). */
    private fun resFile(path: String): File {
        val candidates = listOf(File("src/main/res", path), File("explorer/src/main/res", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("$path not found (looked at ${candidates.map { it.absolutePath }})")
    }

    /** `string:<name>` and `plurals:<name>` of every entry of a `strings.xml`. */
    private fun keys(file: File): Set<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return listOf("string", "plurals").flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { "$tag:${(nodes.item(it) as Element).getAttribute("name")}" }
        }.toSet()
    }
}
