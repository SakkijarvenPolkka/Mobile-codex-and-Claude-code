package io.github.sakkijarvenpolkka.trio.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class SearchEngine(val id: String, val name: String, val searchTemplate: String, val suggestTemplate: String?, val suggestFormat: String?)

class Recognizer(val engineId: String, val name: String, host: String, path: String, val params: List<String>) {
    val host = Regex(host)
    val path = Regex(path)
}

data class SearchMatch(val engineId: String, val name: String, val query: String)

class SearchData(val defaultId: String, val engines: List<SearchEngine>, val recognizers: List<Recognizer>) {
    fun engine(id: String): SearchEngine = engines.firstOrNull { it.id == id } ?: engines.first { it.id == defaultId }

    fun buildSearchUrl(engineId: String, query: String): String = fillTemplate(engine(engineId).searchTemplate, query)

    /** Recognises a search-results page: the recognizer, the parameter and the query. */
    fun matchSearch(url: String): Triple<Recognizer, String, String>? {
        val u = WebUrl.parse(url) ?: return null
        val host = u.hostname.lowercase()
        for (r in recognizers) {
            if (!r.host.containsMatchIn(host) || !r.path.containsMatchIn(u.path)) continue
            for (p in r.params) {
                val q = u.queryParam(p)?.replace(Regex("[$SPACE]+"), " ")?.trimJs().orEmpty()
                if (q.isNotEmpty()) return Triple(r, p, q)
            }
        }
        return null
    }

    fun extractSearch(url: String): SearchMatch? = matchSearch(url)?.let { (r, _, q) -> SearchMatch(r.engineId, r.name, q) }

    companion object {
        fun fillTemplate(template: String, query: String): String = template.replace("{searchTerms}", WebUrl.encodeComponent(query.trimJs()))

        fun parse(json: JsonElement): SearchData {
            val o = json.obj()
            val engines = o["engines"].arr().map { e ->
                val x = e.obj()
                SearchEngine(x["id"].strOr(""), x["name"].strOr(""), x["searchTemplate"].strOr(""), x["suggestTemplate"].str(), x["suggestFormat"].str())
            }
            val recognizers = o["recognizers"].arr().map { e ->
                val x = e.obj()
                Recognizer(x["engineId"].strOr(""), x["name"].strOr(""), x["host"].strOr("$^"), x["path"].strOr("$^"), x["params"].strings())
            }
            return SearchData(o["default"].strOr("google"), engines, recognizers)
        }

        /** Suggestion responses: OpenSearch JSON or Naver's format. */
        fun parseSuggestions(format: String?, json: JsonElement?): List<String> {
            val list: List<JsonElement> = when {
                format == "opensearch" && json is JsonArray && json.size > 1 && json[1] is JsonArray -> json[1] as JsonArray
                format == "naver" && json is JsonObject -> {
                    val items = json["items"]
                    if (items is JsonArray && items.isNotEmpty() && items[0] is JsonArray) (items[0] as JsonArray).map { if (it is JsonArray && it.isNotEmpty()) it[0] else it } else emptyList()
                }
                else -> emptyList()
            }
            return list.mapNotNull { it.str()?.trimJs()?.takeIf(String::isNotEmpty) }
        }
    }
}
