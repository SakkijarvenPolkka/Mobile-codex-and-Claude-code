package io.github.sakkijarvenpolkka.trio.core

/** Mirrors desktop/src/core/login-state.js and handoff.js. */
object LoginState {
    data class CookieLike(val domain: String, val name: String, val value: String, val expirationDate: Double? = null)

    private fun hostIn(host: String, hosts: List<String>) = hosts.any { host == it || host.endsWith(".$it") }

    /** "app", "auth" or "external". */
    fun classifyProviderUrl(p: ProviderConfig, url: String): String {
        val u = WebUrl.parse(url) ?: return "external"
        val host = u.hostname.lowercase()
        if (hostIn(host, p.appHosts)) {
            val path = u.path.lowercase()
            return if (p.authPathPrefixes.any { path == it.removeSuffix("/") || path.startsWith(it) }) "auth" else "app"
        }
        return if (hostIn(host, p.authHosts)) "auth" else "external"
    }

    fun cookieMatches(c: CookieLike, r: CookieRule): Boolean {
        val domain = c.domain.removePrefix(".").lowercase()
        if (domain != r.domain && !domain.endsWith("." + r.domain)) return false
        return if (r.name != null) c.name == r.name else r.namePrefix != null && c.name.startsWith(r.namePrefix)
    }

    fun hasSession(p: ProviderConfig, cookies: List<CookieLike>, nowMs: Long): Boolean {
        val live = cookies.filter { it.value.isNotEmpty() && (it.expirationDate == null || it.expirationDate * 1000 > nowMs) }
        if (p.allOf.isNotEmpty() && !p.allOf.all { r -> live.any { cookieMatches(it, r) } }) return false
        if (p.anyOf.isNotEmpty() && !p.anyOf.any { r -> live.any { cookieMatches(it, r) } }) return false
        return p.allOf.isNotEmpty() || p.anyOf.isNotEmpty()
    }

    /** Parses a WebView CookieManager string ("a=1; b=2") for one domain. */
    fun parseCookieHeader(domain: String, header: String?): List<CookieLike> =
        header.orEmpty().split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else CookieLike(domain, part.substring(0, eq).trim(), part.substring(eq + 1).trim())
        }

    fun sessionDomains(p: ProviderConfig): List<String> = (p.anyOf + p.allOf).map { it.domain }.distinct()

    /** "signedIn", "signingIn" or "signedOut". */
    fun deriveState(sessionPresent: Boolean, staleSession: Boolean, signingIn: Boolean): String = when {
        sessionPresent && !staleSession -> "signedIn"
        signingIn -> "signingIn"
        else -> "signedOut"
    }

    /** "provider" or "lock". */
    fun gateView(state: String, kind: String): String = when (state) {
        "signedIn" -> "provider"
        "signingIn" -> if (kind == "app") "lock" else "provider"
        else -> "lock"
    }

    sealed class Handoff {
        data class Url(val url: String) : Handoff()
        data class Insert(val url: String?) : Handoff()
        data object Clipboard : Handoff()
    }

    const val MAX_INSERT_CHARS = 100_000

    fun planHandoff(p: ProviderConfig, text: String, target: String): Handoff {
        if (text.length > MAX_INSERT_CHARS) return Handoff.Clipboard
        val pre = p.prefill
        if (pre != null && !pre.autoSubmits && target == "new" && text.isNotEmpty()) {
            val encoded = WebUrl.encodeComponent(text)
            if (encoded.length <= pre.maxEncodedChars) return Handoff.Url(pre.template.replace("{prompt}", encoded))
        }
        return Handoff.Insert(if (target == "new") p.appUrl else null)
    }
}
