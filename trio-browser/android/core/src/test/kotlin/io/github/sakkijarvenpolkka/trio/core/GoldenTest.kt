package io.github.sakkijarvenpolkka.trio.core

import io.github.sakkijarvenpolkka.trio.core.VectorsTest.Companion.data
import io.github.sakkijarvenpolkka.trio.core.VectorsTest.Companion.vectors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Kotlin port must reproduce the desktop reference outputs exactly (golden.json). */
class GoldenTest {
    private val golden = vectors("golden.json")
    private val visits = golden["corpus"].obj()["visits"].arr().map(HistoryStatsCalc::visitFromJson)

    private fun options(o: JsonObject) = StatsOptions(
        now = o["now"].contentOrNullSafe()!!.toLong(),
        timeZone = o["timeZone"].strOr(""),
        range = o["range"].strOr(""),
        excludeSensitive = o["excludeSensitive"].bool()!!,
        extraExcludedHosts = o["extraExcludedHosts"].strings(),
    )

    @Test
    fun statistics() {
        for ((i, c) in golden["statsCases"].arr().withIndex()) {
            val o = c.obj()
            val got = HistoryStatsCalc.compute(visits, options(o["options"].obj()), data).toJson()
            val expected = o["stats"].obj()
            for (key in expected.keys) assertEquals("stats case $i: $key", expected[key], got[key])
            assertEquals("stats case $i keys", expected.keys, got.keys)
        }
    }

    @Test
    fun digests() {
        val cases = golden["statsCases"].arr()
        for ((i, c) in golden["digestCases"].arr().withIndex()) {
            val o = c.obj()
            val statsCase = cases[o["statsCase"].int()!!].obj()
            val stats = HistoryStatsCalc.compute(visits, options(statsCase["options"].obj()), data)
            val d = o["options"].obj()
            val inc = d["include"].obj()
            val got = HistoryDigest.render(
                stats,
                HistoryDigest.Options(d["locale"].strOr("ko"), HistoryDigest.Include(inc["queries"].bool()!!, inc["titles"].bool()!!, inc["urls"].strOr("sites")), d["kind"].strOr("habits"), d["charLimit"].int()!!),
            )
            val out = o["out"].obj()
            assertEquals("digest case $i text", out["text"].str(), got.text)
            assertEquals("digest case $i truncated", out["truncated"].bool(), got.truncated)
        }
    }

    @Test
    fun prompts() {
        for ((i, c) in golden["promptCases"].arr().withIndex()) {
            val o = c.obj()
            val inp = o["input"].obj()
            val opts = o["options"].obj()
            val got = Prompts.build(
                data.prompts,
                o["actionId"].strOr(""),
                Prompts.Input(inp["question"].strOr(""), inp["query"].strOr(""), inp["title"].strOr(""), inp["url"].strOr(""), inp["content"].strOr("")),
                opts["locale"].strOr("ko"),
                opts["charLimit"].int()!!,
            )
            val out = o["out"].obj()
            assertEquals("prompt case $i (${o["actionId"]}) text", out["text"].str(), got.text)
            assertEquals("prompt case $i truncated", out["truncated"].bool(), got.truncated)
        }
    }

    @Test
    fun handoff() {
        val claude = data.provider("claude")
        assertEquals(LoginState.Handoff.Insert("https://claude.ai/new"), LoginState.planHandoff(claude, "hi", "new"))
        assertEquals(LoginState.Handoff.Clipboard, LoginState.planHandoff(claude, "x".repeat(100_001), "new"))
        val withPrefill = claude.copy(prefill = Prefill("https://x.test/?q={prompt}", 20, false))
        assertEquals(LoginState.Handoff.Url("https://x.test/?q=a%20b"), LoginState.planHandoff(withPrefill, "a b", "new"))
        void(JsonPrimitive(0))
    }

    private fun void(@Suppress("UNUSED_PARAMETER") x: Any) = Unit
}
