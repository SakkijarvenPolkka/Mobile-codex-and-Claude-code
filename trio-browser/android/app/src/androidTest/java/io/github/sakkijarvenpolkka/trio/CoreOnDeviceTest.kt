package io.github.sakkijarvenpolkka.trio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sakkijarvenpolkka.trio.core.CoreChecks
import io.github.sakkijarvenpolkka.trio.core.HistoryStatsCalc
import io.github.sakkijarvenpolkka.trio.core.Prompts
import io.github.sakkijarvenpolkka.trio.core.SharedData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shared vectors and golden outputs, on the Android runtime. The JVM unit tests cannot catch
 * differences in the platform (for example ICU regular expressions); these can.
 */
@RunWith(AndroidJUnit4::class)
class CoreOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val data = SharedData { name -> instrumentation.targetContext.assets.open(name).bufferedReader().use { it.readText() } }
    private val checks = CoreChecks(data) { name -> instrumentation.context.assets.open(name).bufferedReader().use { it.readText() } }

    private fun pass(failures: List<String>) = assertEquals(failures.joinToString("\n"), 0, failures.size)

    /** What every "ask" in the app does first: build the prompt. */
    @Test
    fun askPromptBuilds() {
        val p = Prompts.build(data.prompts, "ask", Prompts.Input(question = "  hello　 world  "), "ko", 100_000)
        assertEquals("hello　 world", p.text)
        val search = Prompts.build(data.prompts, "search_page", Prompts.Input(query = "서울  날씨"), "ko", 20_000)
        assertTrue(search.text, search.text.contains("‘서울 날씨’"))
    }

    @Test
    fun historyTermsSplitOnUnicodeSpaceAndPunctuation() {
        assertEquals(listOf("서울", "날씨", "rust"), HistoryStatsCalc.queryTerms("서울　날씨, rust!"))
    }

    @Test fun classifyInput() = pass(checks.classifyInput())

    @Test fun search() = pass(checks.search())

    @Test fun privacy() = pass(checks.privacy())

    @Test fun loginState() = pass(checks.loginState())

    @Test fun smallHistoryScenario() = pass(checks.smallHistory())

    @Test fun goldenStatistics() = pass(checks.goldenStats())

    @Test fun goldenDigests() = pass(checks.goldenDigests())

    @Test fun goldenPrompts() = pass(checks.goldenPrompts())
}
