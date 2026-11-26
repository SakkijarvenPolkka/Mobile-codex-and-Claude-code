package io.github.sakkijarvenpolkka.trio.core

import kotlinx.serialization.json.JsonElement

/** Top-level domains and multi-level public suffixes (shared/site-suffixes.json). */
class SuffixData(val tlds: Set<String>, val multiLevelSuffixes: Set<String>) {
    companion object {
        fun parse(json: JsonElement): SuffixData {
            val o = json.obj()
            return SuffixData(o["tlds"].strings().toSet(), o["multiLevelSuffixes"].strings().toSet())
        }
    }
}

object Hosts {
    fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { it.length in 1..3 && it.all(Char::isDigit) && it.toInt() <= 255 }
    }

    fun isIpv6(host: String): Boolean = host.contains(':') && host.matches(Regex("[0-9a-fA-F:.]+"))

    fun isLocalHost(host: String, localSuffixes: List<String> = listOf("localhost", "local", "internal", "lan", "home.arpa")): Boolean {
        if (host.isEmpty()) return false
        if (localSuffixes.any { host == it || host.endsWith(".$it") }) return true
        if (isIpv4(host)) {
            val (a, b) = host.split('.').map { it.toInt() }
            return a == 10 || a == 127 || a == 0 || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)
        }
        if (isIpv6(host)) {
            val h = host.lowercase()
            return h == "::1" || h == "::" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
        }
        return !host.contains('.')
    }

    /** "news.naver.com" -> "naver.com", "www.bbc.co.uk" -> "bbc.co.uk". */
    fun siteKey(host: String, data: SuffixData): String {
        val h = host.lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")
        if (h.isEmpty() || isIpv4(h) || isIpv6(h)) return h
        val labels = h.split('.')
        if (labels.size <= 2) return h.removePrefix("www.")
        for (i in 1 until labels.size - 1) {
            val suffix = labels.subList(i, labels.size).joinToString(".")
            if (suffix in data.multiLevelSuffixes) return labels.subList(i - 1, labels.size).joinToString(".")
        }
        return labels.takeLast(2).joinToString(".")
    }

    fun hasKnownTld(host: String, data: SuffixData): Boolean {
        val ascii = WebUrl.toAscii(host.removeSuffix(".")) ?: return false
        if (ascii.isEmpty()) return false
        return ascii.substringAfterLast('.') in data.tlds
    }

    /** "https://example.com/" -> "https://example.com" for display. */
    fun displayUrl(url: String): String {
        val u = WebUrl.parse(url) ?: return url
        return if (u.path == "/" && u.query == null && u.fragment == null) "${u.scheme}://${u.hostWithPort}" else url
    }
}
