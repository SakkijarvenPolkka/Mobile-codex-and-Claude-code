package io.github.sakkijarvenpolkka.trio.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The shared test vectors and golden outputs (trio-browser/shared/test-vectors), checked against
 * the Kotlin core. Each check returns the list of mismatches (empty = pass).
 *
 * Used by the JVM unit tests and by the app's on-device tests: the Android runtime has its own
 * regular-expression engine (ICU), so passing on the JVM alone proves nothing about a phone.
 */
class CoreChecks(private val data: SharedData, private val vectorFile: (String) -> String) {
    private fun vectors(name: String): JsonObject = Json.parseToJsonElement(vectorFile(name)).jsonObject

    private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.so(d: String = ""): String = s() ?: d
    private fun JsonElement?.o(): JsonObject = (this as? JsonObject) ?: JsonObject(emptyMap())
    private fun JsonElement?.a(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())
    private fun JsonElement?.b(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
    private fun JsonElement?.i(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.raw(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.list(): List<String> = a().mapNotNull { it.s() }

    private fun kindToJson(k: InputKind): JsonObject = when (k) {
        InputKind.Empty -> JsonObject(mapOf("type" to JsonPrimitive("empty")))
        is InputKind.Url -> JsonObject(mapOf("type" to JsonPrimitive("url"), "url" to JsonPrimitive(k.url)))
        is InputKind.Search -> JsonObject(mapOf("type" to JsonPrimitive("search"), "query" to JsonPrimitive(k.query)))
        is InputKind.Agent -> JsonObject(mapOf("type" to JsonPrimitive("agent"), "agentId" to JsonPrimitive(k.agentId), "query" to JsonPrimitive(k.query)))
        is InputKind.History -> JsonObject(mapOf("type" to JsonPrimitive("history"), "query" to JsonPrimitive(k.query)))
    }

    fun classifyInput(): List<String> = vectors("classify-input.json")["cases"].a().mapNotNull { c ->
        val o = c.o()
        val input = o["in"].so()
        val got = kindToJson(ClassifyInput.classify(input, data.agentKeywords, data.suffixes))
        if (got != o["out"]) "classify $input -> $got (expected ${o["out"]})" else null
    }

    fun search(): List<String> {
        val v = vectors("search.json")
        val out = ArrayList<String>()
        for (c in v["build"].a()) {
            val o = c.o()
            val got = data.searchData.buildSearchUrl(o["engineId"].so(), o["query"].so())
            if (got != o["out"].s()) out.add("build ${o["query"]} -> $got")
        }
        for (c in v["extract"].a()) {
            val o = c.o()
            val m = data.searchData.extractSearch(o["url"].so())
            val got: JsonElement = if (m == null) JsonNull else JsonObject(mapOf("engineId" to JsonPrimitive(m.engineId), "name" to JsonPrimitive(m.name), "query" to JsonPrimitive(m.query)))
            if (got != o["out"]) out.add("extract ${o["url"]} -> $got (expected ${o["out"]})")
        }
        for (c in v["suggestions"].a()) {
            val o = c.o()
            val got = SearchData.parseSuggestions(o["format"].s(), o["json"])
            if (got != o["out"].list()) out.add("suggest ${o["json"]} -> $got")
        }
        return out
    }

    fun privacy(): List<String> {
        val v = vectors("privacy.json")
        val out = ArrayList<String>()
        for (c in v["category"].a()) {
            val o = c.o()
            val got = Privacy.sensitiveCategory(o["url"].so(), data.privacyRules, o["extraHosts"].list())
            if (got != o["out"].s()) out.add("category ${o["url"]} -> $got (expected ${o["out"]})")
        }
        for (c in v["sanitize"].a()) {
            val o = c.o()
            val got = Privacy.sanitizeUrl(o["url"].so(), data.privacyRules, data.searchData)
            if (got != o["out"].s()) out.add("sanitize ${o["url"]} -> $got (expected ${o["out"]})")
        }
        return out
    }

    fun loginState(): List<String> {
        val v = vectors("login-state.json")
        val now = v["now"].raw()!!.toLong()
        val out = ArrayList<String>()
        for (c in v["urls"].a()) {
            val o = c.o()
            val got = LoginState.classifyProviderUrl(data.provider(o["provider"].so()), o["url"].so())
            if (got != o["out"].s()) out.add("url ${o["url"]} -> $got")
        }
        for (c in v["sessions"].a()) {
            val o = c.o()
            val cookies = o["cookies"].a().map { e ->
                val x = e.o()
                LoginState.CookieLike(x["domain"].so(), x["name"].so(), x["value"].so(), x["expirationDate"].raw()?.toDouble())
            }
            val got = LoginState.hasSession(data.provider(o["provider"].so()), cookies, now)
            if (got != o["out"].b()) out.add("session ${o["cookies"]} -> $got")
        }
        for (c in v["states"].a()) {
            val o = c.o()
            val i = o["in"].o()
            val got = LoginState.deriveState(i["sessionPresent"].b()!!, i["staleSession"].b()!!, i["signingIn"].b()!!)
            if (got != o["out"].s()) out.add("state $i -> $got")
        }
        for (c in v["gate"].a()) {
            val o = c.o()
            val got = LoginState.gateView(o["state"].so(), o["kind"].so())
            if (got != o["out"].s()) out.add("gate $o -> $got")
        }
        return out
    }

    fun smallHistory(): List<String> {
        val v = vectors("history-small.json")
        val o = v["options"].o()
        val opts = StatsOptions(o["now"].raw()!!.toLong(), o["timeZone"].so(), o["range"].so(), o["excludeSensitive"].b()!!, o["extraExcludedHosts"].list())
        val stats = HistoryStatsCalc.compute(v["visits"].a().map(HistoryStatsCalc::visitFromJson), opts, data).toJson()
        return v["expected"].o().mapNotNull { (key, expected) -> if (stats[key] != expected) "history-small $key: ${stats[key]} (expected $expected)" else null }
    }

    private val golden by lazy { vectors("golden.json") }
    private val corpus by lazy { golden["corpus"].o()["visits"].a().map(HistoryStatsCalc::visitFromJson) }

    private fun statsOptions(o: JsonObject) = StatsOptions(
        now = o["now"].raw()!!.toLong(),
        timeZone = o["timeZone"].so(),
        range = o["range"].so(),
        excludeSensitive = o["excludeSensitive"].b()!!,
        extraExcludedHosts = o["extraExcludedHosts"].list(),
    )

    fun goldenStats(): List<String> {
        val out = ArrayList<String>()
        for ((i, c) in golden["statsCases"].a().withIndex()) {
            val o = c.o()
            val got = HistoryStatsCalc.compute(corpus, statsOptions(o["options"].o()), data).toJson()
            val expected = o["stats"].o()
            for (key in expected.keys) if (expected[key] != got[key]) out.add("stats case $i $key: ${got[key]} (expected ${expected[key]})")
            if (expected.keys != got.keys) out.add("stats case $i keys ${got.keys}")
        }
        return out
    }

    fun goldenDigests(): List<String> {
        val out = ArrayList<String>()
        val cases = golden["statsCases"].a()
        for ((i, c) in golden["digestCases"].a().withIndex()) {
            val o = c.o()
            val stats = HistoryStatsCalc.compute(corpus, statsOptions(cases[o["statsCase"].i()!!].o()["options"].o()), data)
            val d = o["options"].o()
            val inc = d["include"].o()
            val got = HistoryDigest.render(
                stats,
                HistoryDigest.Options(d["locale"].so("ko"), HistoryDigest.Include(inc["queries"].b()!!, inc["titles"].b()!!, inc["urls"].so("sites")), d["kind"].so("habits"), d["charLimit"].i()!!),
            )
            val want = o["out"].o()
            if (got.text != want["text"].s()) out.add("digest case $i text differs")
            if (got.truncated != want["truncated"].b()) out.add("digest case $i truncated ${got.truncated}")
        }
        return out
    }

    fun goldenPrompts(): List<String> {
        val out = ArrayList<String>()
        for ((i, c) in golden["promptCases"].a().withIndex()) {
            val o = c.o()
            val inp = o["input"].o()
            val opts = o["options"].o()
            val got = Prompts.build(
                data.prompts,
                o["actionId"].so(),
                Prompts.Input(inp["question"].so(), inp["query"].so(), inp["title"].so(), inp["url"].so(), inp["content"].so()),
                opts["locale"].so("ko"),
                opts["charLimit"].i()!!,
            )
            val want = o["out"].o()
            if (got.text != want["text"].s()) out.add("prompt case $i (${o["actionId"]}) text differs:\n${got.text}\n--- expected ---\n${want["text"].s()}")
            if (got.truncated != want["truncated"].b()) out.add("prompt case $i truncated ${got.truncated}")
        }
        return out
    }
}
