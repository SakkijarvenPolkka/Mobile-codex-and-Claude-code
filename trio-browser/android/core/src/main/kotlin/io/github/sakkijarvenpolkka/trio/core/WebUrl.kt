package io.github.sakkijarvenpolkka.trio.core

import java.net.IDN
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * A parsed http(s) URL serialised the way the WHATWG URL standard (and therefore Chromium and the
 * desktop JavaScript core) does: lower-case scheme and host, IDN hosts in punycode, default ports
 * dropped, an empty path becomes "/", dot segments resolved, and unsafe characters percent-encoded.
 */
data class WebUrl(
    val scheme: String,
    val userInfo: String,
    val host: String,
    val port: Int?,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    /** Host with the port when it is not the default one ("example.com:8080"). */
    val hostWithPort: String get() = if (port != null) "$host:$port" else host

    /** Host without IPv6 brackets. */
    val hostname: String get() = host.removePrefix("[").removeSuffix("]")

    val href: String
        get() = buildString {
            append(scheme).append("://")
            if (userInfo.isNotEmpty()) append(userInfo).append('@')
            append(hostWithPort).append(path)
            if (query != null) append('?').append(query)
            if (fragment != null) append('#').append(fragment)
        }

    /** First value of a query parameter, decoded like URLSearchParams.get(). */
    fun queryParam(name: String): String? {
        val q = query ?: return null
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = formDecode(if (eq < 0) pair else pair.substring(0, eq))
            if (key == name) return formDecode(if (eq < 0) "" else pair.substring(eq + 1))
        }
        return null
    }

    override fun toString(): String = href

    companion object {
        private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(.*)$", RegexOption.DOT_MATCHES_ALL)

        /** Parses an absolute http or https URL; null when it is not one or is invalid. */
        fun parse(input: String): WebUrl? {
            val trimmed = input.trim { it <= ' ' }.replace(Regex("[\t\n\r]"), "")
            val m = SCHEME.matchEntire(trimmed) ?: return null
            val scheme = m.groupValues[1].lowercase()
            if (scheme != "http" && scheme != "https") return null
            var rest = m.groupValues[2].replace('\\', '/')
            rest = rest.trimStart('/')
            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authorityEnd)
            var tail = rest.substring(authorityEnd)
            val at = authority.lastIndexOf('@')
            val userInfo = if (at >= 0) encode(authority.substring(0, at), USERINFO) else ""
            val hostPort = if (at >= 0) authority.substring(at + 1) else authority
            val (rawHost, rawPort) = splitHostPort(hostPort) ?: return null
            val host = parseHost(rawHost) ?: return null
            var port: Int? = null
            if (rawPort.isNotEmpty()) {
                if (!rawPort.all { it.isDigit() }) return null
                val n = rawPort.toLongOrNull() ?: return null
                if (n > 65535) return null
                port = n.toInt()
            }
            if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443)) port = null

            var fragment: String? = null
            val hash = tail.indexOf('#')
            if (hash >= 0) {
                fragment = encode(tail.substring(hash + 1), FRAGMENT)
                tail = tail.substring(0, hash)
            }
            var query: String? = null
            val qm = tail.indexOf('?')
            if (qm >= 0) {
                query = encode(tail.substring(qm + 1), SPECIAL_QUERY)
                tail = tail.substring(0, qm)
            }
            val path = normalizePath(tail)
            return WebUrl(scheme, userInfo, host, port, path, query, fragment)
        }

        private fun splitHostPort(s: String): Pair<String, String>? {
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 0) return null
                val after = s.substring(end + 1)
                if (after.isNotEmpty() && !after.startsWith(":")) return null
                return s.substring(0, end + 1) to after.removePrefix(":")
            }
            val colon = s.lastIndexOf(':')
            return if (colon >= 0) s.substring(0, colon) to s.substring(colon + 1) else s to ""
        }

        /** Lower-cases, converts IDN to ASCII and validates a host; IPv6 keeps its brackets. */
        fun parseHost(raw: String): String? {
            if (raw.isEmpty()) return null
            if (raw.startsWith("[")) {
                val inner = raw.substring(1, raw.length - 1).lowercase()
                if (!inner.matches(Regex("[0-9a-f:.]+")) || !inner.contains(':')) return null
                return "[$inner]"
            }
            val decoded = percentDecodeOrNull(raw) ?: return null
            val ascii = toAscii(decoded) ?: return null
            if (ascii.isEmpty() || ascii.any { it in FORBIDDEN_HOST }) return null
            return ascii
        }

        /** Converts a (possibly Unicode) host to lower-case ASCII; null when invalid. */
        fun toAscii(host: String): String? = try {
            IDN.toASCII(host.lowercase(), IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (_: IllegalArgumentException) {
            null
        }

        private const val FORBIDDEN_HOST = " #%/:<>?@[\\]^|\u0000\t\n\r"

        private fun normalizePath(raw: String): String {
            if (raw.isEmpty()) return "/"
            val segments = raw.split('/').drop(1)
            val out = ArrayList<String>()
            for ((i, seg) in segments.withIndex()) {
                val last = i == segments.size - 1
                when (seg.lowercase()) {
                    ".", "%2e" -> if (last) out.add("")
                    "..", ".%2e", "%2e.", "%2e%2e" -> {
                        if (out.isNotEmpty()) out.removeAt(out.size - 1)
                        if (last) out.add("")
                    }
                    else -> out.add(encode(seg, PATH))
                }
            }
            return "/" + out.joinToString("/")
        }

        // Percent-encode sets from the URL standard (characters beyond the C0 controls).
        private const val FRAGMENT = " \"<>`"
        private const val QUERY = " \"#<>"
        private const val SPECIAL_QUERY = "$QUERY'"
        private const val PATH = "$QUERY?`{}"
        private const val USERINFO = "$PATH/:;=@[\\]^|"

        /** Percent-encodes C0 controls, non-ASCII (as UTF-8) and the given characters. */
        fun encode(s: String, set: String): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(StandardCharsets.UTF_8)) {
                val c = b.toInt() and 0xff
                if (c < 0x20 || c >= 0x7f || set.indexOf(c.toChar()) >= 0) sb.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
                else sb.append(c.toChar())
            }
            return sb.toString()
        }

        private val HEX = "0123456789ABCDEF".toCharArray()
        private const val UNRESERVED = "-_.!~*'()"

        /** JavaScript's encodeURIComponent. */
        fun encodeComponent(s: String): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(StandardCharsets.UTF_8)) {
                val c = b.toInt() and 0xff
                val ch = c.toChar()
                if ((ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || UNRESERVED.indexOf(ch) >= 0) sb.append(ch)
                else sb.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
            }
            return sb.toString()
        }

        /** application/x-www-form-urlencoded decoding (URLSearchParams): "+" is a space. */
        fun formDecode(s: String): String = lenientDecode(s.replace('+', ' '))

        /** Percent-decodes, replacing invalid UTF-8 with U+FFFD (like the URL standard). */
        fun lenientDecode(s: String): String {
            if (!s.contains('%')) return s
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                    bytes.write(s.substring(i + 1, i + 3).toInt(16))
                    i += 3
                } else {
                    bytes.write(c.toString().toByteArray(StandardCharsets.UTF_8))
                    i++
                }
            }
            return String(bytes.toByteArray(), StandardCharsets.UTF_8)
        }

        /** JavaScript's decodeURIComponent: null when the input is malformed. */
        fun percentDecodeOrNull(s: String): String? {
            if (!s.contains('%')) return s
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    if (i + 2 >= s.length || !isHex(s[i + 1]) || !isHex(s[i + 2])) return null
                    bytes.write(s.substring(i + 1, i + 3).toInt(16))
                    i += 3
                } else {
                    bytes.write(c.toString().toByteArray(StandardCharsets.UTF_8))
                    i++
                }
            }
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }

        private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    }
}
