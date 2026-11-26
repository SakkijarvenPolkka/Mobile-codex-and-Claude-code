package io.github.sakkijarvenpolkka.trio.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The core's regular expressions run on ICU on Android and on the JVM in unit tests. ICU rejects
 * inline flags such as (?U) (the app then crashes), and the two engines give \s, \d and \w
 * different meanings. Core patterns must spell their classes out (see RegexClasses.kt); this
 * test fails on the JVM as soon as one does not, without needing a device.
 */
class RegexPortabilityTest {
    private val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
    private val sharedDir = File(System.getProperty("trio.shared") ?: "../../shared")

    /** Inline flags, Java-only property classes and engine-dependent shorthand classes. */
    private val forbidden = Regex("""\(\?[a-zA-Z]+\)|\\\\p\{(?:Is|In|java|Alpha|Alnum|Punct|Graph|Print|Blank|Cntrl|XDigit|Space|Lower|Upper|ASCII)|\\\\[sSdDwW]""")

    @Test
    fun coreSourcesUsePortablePatterns() {
        val offenders = ArrayList<String>()
        val literal = Regex("""Regex\("((?:[^"\\]|\\.)*)"""")
        for (file in sources) {
            file.readLines().forEachIndexed { i, line ->
                for (m in literal.findAll(line)) {
                    if (forbidden.containsMatchIn(m.groupValues[1])) offenders.add("${file.name}:${i + 1}: ${m.groupValues[1]}")
                }
            }
        }
        assertEquals(offenders.joinToString("\n"), 0, offenders.size)
        assert(sources.isNotEmpty()) { "core sources not found" }
    }

    @Test
    fun searchRecognizersInTheSharedDataArePortable() {
        val json = File(sharedDir, "search-engines.json").readText()
        val patterns = Regex(""""(?:host|path)":\s*"((?:[^"\\]|\\.)*)"""").findAll(json).map { it.groupValues[1] }.toList()
        val offenders = patterns.filter { Regex("""\(\?[a-zA-Z]+\)|\\\\[sSdDwW]""").containsMatchIn(it) }
        assertEquals(offenders.joinToString("\n"), 0, offenders.size)
        assert(patterns.size > 10)
    }
}
