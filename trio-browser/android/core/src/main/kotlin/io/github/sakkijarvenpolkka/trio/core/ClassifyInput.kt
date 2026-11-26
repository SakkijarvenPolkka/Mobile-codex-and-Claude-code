package io.github.sakkijarvenpolkka.trio.core

/** What address-bar text means. Mirrors desktop/src/core/classify-input.js. */
sealed class InputKind {
    data object Empty : InputKind()
    data class Url(val url: String) : InputKind()
    data class Search(val query: String) : InputKind()
    data class Agent(val agentId: String, val query: String) : InputKind()
    data class History(val query: String) : InputKind()
}

object ClassifyInput {
    private val ALL = setOf("all", "모두", "전체")
    private val HISTORY = setOf("history", "기록")
    private val INTERNAL_PAGES = setOf("newtab", "history", "bookmarks", "downloads", "settings", "about")
    private val AT = Regex("^@([^$SPACE]+)(?:[$SPACE]+(.*))?$", RegexOption.DOT_MATCHES_ALL)
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val LABEL = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")
    private val WS = Regex("[$SPACE]")

    fun classify(text: String, agentKeywords: Map<String, String>, suffixes: SuffixData): InputKind {
        val input = text.trim()
        if (input.isEmpty()) return InputKind.Empty
        if (input.startsWith("?")) {
            val q = input.substring(1).trim()
            return if (q.isEmpty()) InputKind.Empty else InputKind.Search(q)
        }
        if (input.startsWith("@")) {
            AT.matchEntire(input)?.let { m ->
                val word = m.groupValues[1].lowercase()
                val rest = m.groupValues[2].trim()
                agentKeywords[word]?.let { return InputKind.Agent(it, rest) }
                if (word in ALL) return InputKind.Agent("all", rest)
                if (word in HISTORY) return InputKind.History(rest)
            }
            return InputKind.Search(input)
        }
        val search = InputKind.Search(input)

        if (Regex("^[a-zA-Z]:[\\\\/]").containsMatchIn(input)) {
            return InputKind.Url(fileUrl("/" + input.replace('\\', '/')))
        }
        if (input.startsWith("/") && !input.startsWith("//")) return InputKind.Url(fileUrl(input))

        SCHEME.matchEntire(input)?.let { m ->
            val name = m.groupValues[1].lowercase()
            val rest = m.groupValues[2]
            when (name) {
                "http", "https" -> return WebUrl.parse(input)?.let { InputKind.Url(it.href) } ?: search
                "file" -> return InputKind.Url(fileUrl(rest.removePrefix("//")))
                "trio" -> return internalUrl(rest) ?: search
                "mailto" -> return if (WS.containsMatchIn(rest)) search else InputKind.Url(input)
                "about", "chrome" -> {
                    val page = rest.removePrefix("//").removeSuffix("/").lowercase()
                    if (name == "about" && page == "blank") return InputKind.Url("about:blank")
                    return if (page in INTERNAL_PAGES) InputKind.Url("trio://$page/") else search
                }
            }
            if (!Regex("^[0-9]+(?:[/?#]|$)").containsMatchIn(rest)) return search
        }

        if (WS.containsMatchIn(input)) return search
        val split = input.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) input.length else it }
        val authority = input.substring(0, split)
        val tail = input.substring(split)
        if (authority.contains('@')) return search

        val host: String
        val port: String
        val v6 = Regex("^(\\[[0-9a-fA-F:.]+\\])(?::([0-9]*))?$").matchEntire(authority)
        if (v6 != null) {
            host = v6.groupValues[1]
            port = v6.groupValues[2]
        } else {
            val hp = Regex("^([^:]+)(?::([0-9]+))?$").matchEntire(authority) ?: return search
            host = hp.groupValues[1]
            port = hp.groupValues[2]
        }
        if (port.isNotEmpty()) {
            val n = port.toLongOrNull() ?: return search
            if (n < 1 || n > 65535) return search
        }
        val lower = host.lowercase()
        if (v6 != null || lower == "localhost" || Hosts.isIpv4(lower)) return parsedOr("http://$authority$tail", search)
        if (lower.matches(Regex("^[0-9.]+$"))) return search
        val ascii = WebUrl.toAscii(lower) ?: return search
        val labels = ascii.split('.')
        if (!labels.all { it.length in 1..63 && LABEL.matches(it) }) return search
        if (labels.size == 1) return if (port.isNotEmpty()) parsedOr("http://$authority$tail", search) else search
        if (!Hosts.hasKnownTld(ascii, suffixes)) return search
        return parsedOr("https://$authority$tail", search)
    }

    private fun parsedOr(candidate: String, fallback: InputKind): InputKind =
        WebUrl.parse(candidate)?.let { InputKind.Url(it.href) } ?: fallback

    private fun fileUrl(path: String): String {
        val p = if (path.startsWith("/")) path else "/$path"
        return "file://" + WebUrl.encode(p, " \"#<>?`{}")
    }

    private fun internalUrl(rest: String): InputKind? {
        val m = Regex("^//([^/?#]*)(.*)$", RegexOption.DOT_MATCHES_ALL).matchEntire(rest) ?: return null
        val host = m.groupValues[1].lowercase()
        if (host.isEmpty()) return null
        val tail = m.groupValues[2]
        val path = if (tail.isEmpty()) "/" else if (tail.startsWith("/")) tail else "/$tail"
        return InputKind.Url("trio://$host$path")
    }
}
