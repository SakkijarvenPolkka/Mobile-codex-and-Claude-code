package io.github.sakkijarvenpolkka.trio

import io.github.sakkijarvenpolkka.trio.core.CookieRule
import io.github.sakkijarvenpolkka.trio.core.ProviderConfig
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A stand-in for an agent provider, served from the test process: a chat app with a composer and an
 * answer that links to another site, a sign-in page, an outside page, and a page whose title is
 * shadowed by a named element. The app reaches it at http://127.0.0.1:port; "localhost" is the same
 * server under a host the provider does not own (an outside site).
 */
class MockProvider : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val app: String get() = "http://127.0.0.1:$port/claude/new"
    val outside: String get() = "http://localhost:$port/elsewhere"
    val clobbered: String get() = "http://127.0.0.1:$port/clobbered"

    init {
        thread(isDaemon = true, name = "mock-provider") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                // A request the WebView gives up on (a closed page) fails here; that must not end the test run.
                thread(isDaemon = true) { runCatching { serve(socket) } }
            }
        }
    }

    /** Claude, pointed at this server; signed in when a "sessionKey" cookie is set for 127.0.0.1. */
    fun claude(real: ProviderConfig): ProviderConfig = real.copy(
        appUrl = app,
        loginUrl = "http://127.0.0.1:$port/claude/login",
        logoutUrl = null,
        appHosts = listOf("127.0.0.1"),
        authHosts = emptyList(),
        authPathPrefixes = listOf("/claude/login"),
        anyOf = listOf(CookieRule("127.0.0.1", "sessionKey", null)),
        allOf = emptyList(),
    )

    private fun page(path: String): Pair<Int, String> = when (path.substringBefore('?')) {
        "/claude/new" -> 200 to """
            <!doctype html><html><head><meta name="viewport" content="width=device-width"><title>Mock Claude</title></head>
            <body style="margin:0;font:16px sans-serif">
              <div class="ProseMirror" contenteditable="true" style="min-height:60px;border:1px solid #888;padding:8px"></div>
              <p>An earlier answer, with its source:</p>
              <a id="source" href="$outside" target="_blank" rel="noopener" style="display:block;height:240px;background:#dde">Source</a>
            </body></html>
        """.trimIndent()
        "/claude/login" -> 200 to "<!doctype html><title>Sign in</title><p>Sign in</p>"
        "/elsewhere" -> 200 to "<!doctype html><title>Elsewhere</title><p>An outside page</p>"
        "/clobbered" -> 200 to """
            <!doctype html><html><head><title>Clobbered Title</title></head>
            <body><img name="title" alt=""><form name="title"></form>
            <main><h1>Article</h1><p>${"Readable text of the clobbered article. ".repeat(20)}</p></main></body></html>
        """.trimIndent()
        else -> 404 to "<!doctype html><title>Not found</title>"
    }

    private fun serve(socket: Socket) = socket.use { s ->
        val reader = s.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return@use
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        val (status, body) = page(requestLine.split(' ').getOrElse(1) { "/" })
        val bytes = body.toByteArray()
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        s.getOutputStream().apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    override fun close() {
        server.close()
    }
}
