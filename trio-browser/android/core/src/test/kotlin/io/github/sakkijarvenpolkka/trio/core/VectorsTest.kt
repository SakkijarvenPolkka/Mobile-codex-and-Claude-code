package io.github.sakkijarvenpolkka.trio.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Runs the shared JSON test vectors (the same ones the desktop tests use). */
class VectorsTest {
    companion object {
        val sharedDir = File(System.getProperty("trio.shared") ?: "../../shared")
        val data = SharedData { File(sharedDir, it).readText() }
        fun vectors(name: String): JsonObject = Json.parseToJsonElement(File(sharedDir, "test-vectors/$name").readText()).jsonObject

        fun kindToJson(k: InputKind): JsonObject = when (k) {
            InputKind.Empty -> JsonObject(mapOf("type" to JsonPrimitive("empty")))
            is InputKind.Url -> JsonObject(mapOf("type" to JsonPrimitive("url"), "url" to JsonPrimitive(k.url)))
            is InputKind.Search -> JsonObject(mapOf("type" to JsonPrimitive("search"), "query" to JsonPrimitive(k.query)))
            is InputKind.Agent -> JsonObject(mapOf("type" to JsonPrimitive("agent"), "agentId" to JsonPrimitive(k.agentId), "query" to JsonPrimitive(k.query)))
            is InputKind.History -> JsonObject(mapOf("type" to JsonPrimitive("history"), "query" to JsonPrimitive(k.query)))
        }
    }

    @Test
    fun classifyInput() {
        val failures = ArrayList<String>()
        for (c in vectors("classify-input.json")["cases"].arr()) {
            val o = c.obj()
            val input = o["in"].strOr("")
            val got = kindToJson(ClassifyInput.classify(input, data.agentKeywords, data.suffixes))
            if (got != o["out"]) failures.add("$input -> $got (expected ${o["out"]})")
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun search() {
        val v = vectors("search.json")
        val failures = ArrayList<String>()
        for (c in v["build"].arr()) {
            val o = c.obj()
            val got = data.searchData.buildSearchUrl(o["engineId"].strOr(""), o["query"].strOr(""))
            if (got != o["out"].str()) failures.add("build ${o["query"]} -> $got")
        }
        for (c in v["extract"].arr()) {
            val o = c.obj()
            val m = data.searchData.extractSearch(o["url"].strOr(""))
            val got: JsonElement = if (m == null) JsonNull else JsonObject(mapOf("engineId" to JsonPrimitive(m.engineId), "name" to JsonPrimitive(m.name), "query" to JsonPrimitive(m.query)))
            if (got != o["out"]) failures.add("extract ${o["url"]} -> $got (expected ${o["out"]})")
        }
        for (c in v["suggestions"].arr()) {
            val o = c.obj()
            val got = SearchData.parseSuggestions(o["format"].str(), o["json"])
            if (got != o["out"].strings()) failures.add("suggest ${o["json"]} -> $got")
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun privacy() {
        val v = vectors("privacy.json")
        val failures = ArrayList<String>()
        for (c in v["category"].arr()) {
            val o = c.obj()
            val got = Privacy.sensitiveCategory(o["url"].strOr(""), data.privacyRules, o["extraHosts"].strings())
            if (got != o["out"].str()) failures.add("category ${o["url"]} -> $got (expected ${o["out"]})")
        }
        for (c in v["sanitize"].arr()) {
            val o = c.obj()
            val got = Privacy.sanitizeUrl(o["url"].strOr(""), data.privacyRules, data.searchData)
            if (got != o["out"].str()) failures.add("sanitize ${o["url"]} -> $got (expected ${o["out"]})")
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun loginState() {
        val v = vectors("login-state.json")
        val now = v["now"].contentOrNullSafe()!!.toLong()
        val failures = ArrayList<String>()
        for (c in v["urls"].arr()) {
            val o = c.obj()
            val got = LoginState.classifyProviderUrl(data.provider(o["provider"].strOr("")), o["url"].strOr(""))
            if (got != o["out"].str()) failures.add("url ${o["url"]} -> $got")
        }
        for (c in v["sessions"].arr()) {
            val o = c.obj()
            val cookies = o["cookies"].arr().map { e ->
                val x = e.obj()
                LoginState.CookieLike(x["domain"].strOr(""), x["name"].strOr(""), x["value"].strOr(""), x["expirationDate"].contentOrNullSafe()?.toDouble())
            }
            val got = LoginState.hasSession(data.provider(o["provider"].strOr("")), cookies, now)
            if (got != o["out"].bool()) failures.add("session ${o["cookies"]} -> $got")
        }
        for (c in v["states"].arr()) {
            val o = c.obj()
            val i = o["in"].obj()
            val got = LoginState.deriveState(i["sessionPresent"].bool()!!, i["staleSession"].bool()!!, i["signingIn"].bool()!!)
            if (got != o["out"].str()) failures.add("state $i -> $got")
        }
        for (c in v["gate"].arr()) {
            val o = c.obj()
            val got = LoginState.gateView(o["state"].strOr(""), o["kind"].strOr(""))
            if (got != o["out"].str()) failures.add("gate $o -> $got")
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun handSmallHistoryScenario() {
        val v = vectors("history-small.json")
        val o = v["options"].obj()
        val opts = StatsOptions(o["now"].contentOrNullSafe()!!.toLong(), o["timeZone"].strOr(""), o["range"].strOr(""), o["excludeSensitive"].bool()!!, o["extraExcludedHosts"].strings())
        val stats = HistoryStatsCalc.compute(v["visits"].arr().map(HistoryStatsCalc::visitFromJson), opts, data).toJson()
        for ((key, expected) in v["expected"].obj()) assertEquals(key, expected, stats[key])
    }
}
