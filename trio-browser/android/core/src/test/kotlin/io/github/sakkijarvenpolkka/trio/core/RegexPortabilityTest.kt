package io.github.sakkijarvenpolkka.trio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regular expressions run on ICU on Android and on the JVM in unit tests. ICU rejects some syntax the
 * JVM accepts, such as the (?U) flag, with a PatternSyntaxException that crashes the app, and the two
 * engines give \s, \d and \w different meanings. This test reads every pattern written in the core's
 * and the app's sources (Regex(…), "…".toRegex(), Pattern.compile(…), plain and raw string literals,
 * with templates such as $SPACE expanded) and fails on the JVM, without a device, as soon as one uses
 * syntax that is not portable:
 *  - inline flags, plain or scoped ((?U), (?i:…)): use RegexOption instead;
 *  - Java-only property classes (\p{IsAlphabetic}, \p{javaLowerCase}, POSIX names such as \p{Alpha});
 *  - look-behind without a bounded length (ICU needs one);
 *  - a "}" that closes no "{" (ICU wants it escaped).
 * Core patterns must also match the JavaScript reference exactly, so they spell their classes out
 * instead of using \s, \d or \w (see RegexClasses.kt).
 */
class RegexPortabilityTest {
    private val coreSources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
    private val appSources = File(System.getProperty("trio.app") ?: "../app/src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
    private val sharedDir = File(System.getProperty("trio.shared") ?: "../../shared")

    /** The value of a constant used in templates ("$SPACE"), as the regex engine sees it. */
    private val constants: Map<String, String> = (coreSources + appSources).flatMap { f ->
        Regex("""const val (\w+) = "((?:[^"\\]|\\.)*)"""").findAll(f.readText()).map { it.groupValues[1] to unescape(it.groupValues[2]) }.toList()
    }.toMap()

    data class Pattern(val where: String, val regex: String)

    /** Every pattern literal in [files]. */
    private fun patterns(files: List<File>): List<Pattern> {
        val plain = """"(?:[^"\\\n]|\\.)*""""
        val raw = "\"\"\"[\\s\\S]*?\"\"\""
        val literal = "($raw|$plain)"
        val calls = listOf(
            Regex("""(?:\bRegex|Pattern\.compile)\(\s*$literal"""),
            Regex("""$literal\s*\.toRegex\("""),
        )
        return files.flatMap { file ->
            val text = file.readText()
            calls.flatMap { call ->
                call.findAll(text).map { m ->
                    val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                    Pattern("${file.name}:$line", expand(m.groupValues[1]))
                }.toList()
            }
        }
    }

    /** A Kotlin string literal (with quotes) as the string it makes. */
    private fun expand(literal: String): String {
        val rawString = literal.startsWith("\"\"\"")
        val body = if (rawString) literal.removeSurrounding("\"\"\"") else literal.removeSurrounding("\"")
        val text = if (rawString) body.replace("\${'$'}", "\u0000DOLLAR\u0000") else unescape(body)
        return Regex("""\$\{(\w+)}|\$(\w+)""").replace(text) { m -> constants[m.groupValues[1].ifEmpty { m.groupValues[2] }] ?: "x" }
            .replace("\u0000DOLLAR\u0000", "$")
    }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 == s.length) {
                // A template's "$" stays for expand(); an escaped one ("\$") becomes a literal "$" below.
                out.append(c)
                i++
                continue
            }
            when (val n = s[i + 1]) {
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                'b' -> out.append('\b')
                'u' -> {
                    out.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                    i += 4
                }
                '$' -> out.append("\u0000DOLLAR\u0000")
                else -> out.append(n)
            }
            i += 2
        }
        return out.toString()
    }

    /** Every place a pattern is made, and the ones that are not a literal this test can read. */
    private fun unreadCallSites(files: List<File>): List<String> {
        val literal = "(\"\"\"[\\s\\S]*?\"\"\"|\"(?:[^\"\\\\\\n]|\\\\.)*\")"
        val read = Regex("""(?:\bRegex|Pattern\.compile)\(\s*$literal|$literal\s*\.to(?:Regex|Pattern)\(""")
        val call = Regex("""\bRegex\(|Pattern\.compile\(|\.toRegex\(|\.toPattern\(""")
        return files.flatMap { file ->
            val text = file.readText()
            // A literal read starts where "Regex(" / "Pattern.compile(" starts, or ends where ".toRegex(" ends.
            val covered = read.findAll(text).flatMap { m -> listOf(m.range.first, m.range.last) }.toSet()
            call.findAll(text).filter { c -> c.range.first !in covered && c.range.last !in covered }
                .map { c -> "${file.name}:${text.substring(0, c.range.first).count { it == '\n' } + 1}: ${text.substring(c.range.first).lineSequence().first().take(60)}" }
                .filterNot { it in allowedDynamic }
                .toList()
        }
    }

    /** Patterns that come from the shared data, checked by searchRecognizersInTheSharedDataArePortable. */
    private val allowedDynamic = setOf("Search.kt:10: Regex(host)", "Search.kt:11: Regex(path)")

    /** What makes [regex] unportable, or null. */
    private fun problem(regex: String, core: Boolean): String? {
        if (Regex("""\(\?[a-zA-Z-]+[:)]""").containsMatchIn(regex)) return "inline flag"
        if (Regex("""\\p\{(?:Is|In|java|Alpha|Alnum|Punct|Graph|Print|Blank|Cntrl|XDigit|Space|Lower|Upper|ASCII)""").containsMatchIn(regex)) return "Java-only property class"
        if (Regex("""\(\?<[=!](?:[^()\\]|\\.)*(?:[*+]|\{\d+,})""").containsMatchIn(regex)) return "unbounded look-behind"
        if (strayBrace(regex)) return "unescaped }"
        if (core && Regex("""\\[sSdDwW]""").containsMatchIn(regex)) return "shorthand class (\\s, \\d, \\w differ between ICU, the JVM and JavaScript)"
        return null
    }

    /** True if a "}" closes no "{" outside a character class. */
    private fun strayBrace(regex: String): Boolean {
        var i = 0
        var inClass = 0
        var open = 0
        while (i < regex.length) {
            when (val c = regex[i]) {
                '\\' -> {
                    // \p{…}, \x{…}, \N{…} carry their own braces.
                    if (i + 2 < regex.length && regex[i + 1] in "pPxN" && regex[i + 2] == '{') i = regex.indexOf('}', i).let { if (it < 0) regex.length else it }
                    else i++
                }
                '[' -> inClass++
                ']' -> if (inClass > 0) inClass--
                '{' -> if (inClass == 0) open++
                '}' -> if (inClass == 0) {
                    if (open == 0) return true
                    open--
                }
                else -> Unit
            }
            i++
        }
        return false
    }

    /** A pattern written in a way this test cannot read is not silently skipped: it must be allowed by name. */
    @Test
    fun everyPatternIsOneThisTestReads() {
        val unread = unreadCallSites(coreSources + appSources)
        assertEquals(unread.joinToString("\n"), 0, unread.size)
    }

    /** Flags Android's ICU-backed Pattern rejects while the JVM accepts them. */
    @Test
    fun noFlagsThatIcuRejects() {
        val offenders = (coreSources + appSources).flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (Regex("""\b(?:CANON_EQ|UNICODE_CHARACTER_CLASS)\b""").containsMatchIn(line)) "${f.name}:${i + 1}: $line" else null
            }
        }
        assertEquals(offenders.joinToString("\n"), 0, offenders.size)
    }

    @Test
    fun coreAndAppPatternsArePortable() {
        val core = patterns(coreSources)
        val app = patterns(appSources)
        val offenders = core.mapNotNull { p -> problem(p.regex, core = true)?.let { "${p.where}: $it: ${p.regex}" } } +
            app.mapNotNull { p -> problem(p.regex, core = false)?.let { "${p.where}: $it: ${p.regex}" } }
        assertEquals(offenders.joinToString("\n"), 0, offenders.size)
        assertTrue("core patterns found: ${core.size}", core.size > 20)
        assertTrue("app patterns found: ${app.size}", app.isNotEmpty())
        // The template constant is expanded, so its content is checked too.
        assertTrue(core.any { it.regex.contains("\\u3000") })
    }

    /** The checks themselves catch what ICU rejects, written in every way Kotlin allows. */
    @Test
    fun theChecksCatchUnportableSyntax() {
        val src = File.createTempFile("Patterns", ".kt").apply {
            writeText(
                """
                val a = Regex("(?U)\\s{2,}")
                val b = "(?i:abc)".toRegex()
                val c = java.util.regex.Pattern.compile("(?<=a*)b")
                val d = Regex(${"\"\"\""}x}${"\"\"\""})
                val e = Regex(
                    "\\p{javaLowerCase}"
                )
                val ok = Regex("[0-9]{2,3}\\p{L}\\}")
                """.trimIndent(),
            )
            deleteOnExit()
        }
        val found = patterns(listOf(src)).map { problem(it.regex, core = false) }
        assertEquals(listOf("inline flag", "inline flag", "unbounded look-behind", "unescaped }", "Java-only property class", null).sortedBy { it.toString() }, found.sortedBy { it.toString() })
    }

    @Test
    fun searchRecognizersInTheSharedDataArePortable() {
        val json = File(sharedDir, "search-engines.json").readText()
        val found = Regex(""""(?:host|path)":\s*"((?:[^"\\]|\\.)*)"""").findAll(json).map { unescape(it.groupValues[1]) }.toList()
        val offenders = found.filter { problem(it, core = true) != null }
        assertEquals(offenders.joinToString("\n"), 0, offenders.size)
        assertTrue(found.size > 10)
    }
}
