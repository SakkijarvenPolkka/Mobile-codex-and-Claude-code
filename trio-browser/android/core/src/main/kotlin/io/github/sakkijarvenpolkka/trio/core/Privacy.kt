package io.github.sakkijarvenpolkka.trio.core

import kotlinx.serialization.json.JsonElement

data class CategoryRule(val hostSuffixes: List<String>, val hostKeywords: List<String>, val hostPrefixes: List<String>, val pathSegments: List<String>)

class PrivacyRules(val categories: List<Pair<String, CategoryRule>>, val localHostSuffixes: List<String>, val tokenMinLength: Int, val hexMinLength: Int) {
    companion object {
        fun parse(json: JsonElement): PrivacyRules {
            val o = json.obj()
            val cats = o["categories"].obj().entries.map { (name, e) ->
                val c = e.obj()
                name to CategoryRule(c["hostSuffixes"].strings(), c["hostKeywords"].strings(), c["hostPrefixes"].strings(), c["pathSegments"].strings())
            }
            val t = o["tokenSegment"].obj()
            return PrivacyRules(cats, o["localHostSuffixes"].strings(), t["minLength"].int() ?: 24, t["hexMinLength"].int() ?: 16)
        }
    }
}

/** Mirrors desktop/src/core/privacy.js. */
object Privacy {
    private fun hostMatches(host: String, suffix: String) = host == suffix || host.endsWith(".$suffix")

    fun sensitiveCategory(url: String, rules: PrivacyRules, extraHosts: List<String> = emptyList()): String? {
        val u = WebUrl.parse(url) ?: return "internal"
        val host = u.hostname.lowercase()
        for (h in extraHosts) {
            val clean = h.trimJs().lowercase().removePrefix("*.").removePrefix("www.")
            if (clean.isNotEmpty() && hostMatches(host, clean)) return "user"
        }
        if (Hosts.isLocalHost(host, rules.localHostSuffixes)) return "local"
        val tokens = host.split('.', '-').filter { it.isNotEmpty() }
        val segments = u.path.lowercase().split('/').filter { it.isNotEmpty() }.map { WebUrl.percentDecodeOrNull(it) ?: it }
        for ((name, rule) in rules.categories) {
            if (rule.hostSuffixes.any { hostMatches(host, it) }) return name
            if (tokens.any { t -> t in rule.hostKeywords || rule.hostPrefixes.any { t.startsWith(it) } }) return name
            if (segments.any { it in rule.pathSegments }) return name
        }
        return null
    }

    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

    fun isTokenLike(segment: String, rules: PrivacyRules): Boolean {
        if (UUID.matches(segment)) return true
        if (segment.length >= rules.hexMinLength && segment.matches(Regex("^[0-9a-fA-F]+$")) && segment.any { it in 'a'..'f' || it in 'A'..'F' } && segment.any(Char::isDigit)) return true
        return segment.length >= rules.tokenMinLength && segment.matches(Regex("^[A-Za-z0-9_-]+$")) && segment.any { it in '0'..'9' } && segment.any { it in 'a'..'z' || it in 'A'..'Z' }
    }

    fun sanitizeUrl(url: String, rules: PrivacyRules, searchData: SearchData): String? {
        val u = WebUrl.parse(url) ?: return null
        val path = u.path.split('/').joinToString("/") { seg ->
            if (seg.isEmpty()) seg
            else {
                val d = WebUrl.percentDecodeOrNull(seg) ?: seg
                when {
                    d.contains('@') -> ":email"
                    isTokenLike(d, rules) -> ":id"
                    else -> seg
                }
            }
        }
        val query = searchData.matchSearch(url)?.let { (_, param, q) -> "?$param=${WebUrl.encodeComponent(q)}" } ?: ""
        return "${u.scheme}://${u.hostWithPort.lowercase()}$path$query"
    }
}
