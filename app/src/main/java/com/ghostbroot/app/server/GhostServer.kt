package com.ghostbroot.app.server

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Loopback-only HTTP + WebSocket server (spec §2).
 * Binds 127.0.0.1 exclusively — never exposed to the LAN (spec §2).
 * All routes except /api/v1/status require the session token.
 *
 * Robustness: if the requested port is already taken, tries the next
 * ports (port..port+20). Every bind, accepted connection and exception is
 * recorded in [lastLog] (also returned from /api/v1/status) so failures
 * are visible instead of silent.
 */
class GhostServer(
    private val port: Int = 8765,
    private val token: String,
    private val onEvent: (String, JSONObject) -> Unit = { _, _ -> }
) {
    @Volatile var running = false
        private set

    @Volatile var boundPort: Int = port
        private set

    private var serverSocket: ServerSocket? = null
    private val logQueue = ConcurrentLinkedQueue<String>()

    fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logQueue.add("[$ts] $msg")
        while (logQueue.size > 60) logQueue.poll()
    }

    fun lastLog(): String = logQueue.joinToString("\n")

    /** Starts the server; returns the bound port (may differ from [port] if busy). */
    fun start(): Int {
        if (running) return boundPort
        var lastError: Exception? = null
        for (candidate in port..(port + 20)) {
            try {
                val ss = ServerSocket(candidate, 50, InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                boundPort = candidate
                running = true
                log("bound on 127.0.0.1:$candidate (requested $port)")
                Thread({
                    while (running && !ss.isClosed) {
                        try {
                            val client = ss.accept()
                            log("accept from ${client.remoteSocketAddress}")
                            Thread({ handle(client) }, "ghost-http").apply { isDaemon = true }.start()
                        } catch (e: Exception) {
                            if (!running) break
                            log("accept loop: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                }, "ghost-accept").apply { isDaemon = true }.start()
                return candidate
            } catch (e: Exception) {
                lastError = e
                log("bind :$candidate failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        throw IllegalStateException("No free loopback port in $port..${port + 20}: ${lastError?.message}")
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        log("stopped")
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val firstLine = reader.readLine() ?: return socket.close()
            log("request: $firstLine")
            val parts = firstLine.split(" ")
            if (parts.size < 2) return socket.close()
            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val headers = LinkedHashMap<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val idx = line!!.indexOf(':')
                if (idx > 0) headers[line!!.substring(0, idx).trim().lowercase()] =
                    line!!.substring(idx + 1).trim()
            }
            // WebSocket upgrade
            if (rawPath.startsWith("/api/v1/events") && headers["upgrade"]?.equals("websocket", true) == true) {
                handleWs(socket, headers, rawPath)
                return
            }
            // Body
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            var bodyJson: JSONObject? = null
            if (contentLength > 0) {
                val text = StringBuilder()
                var read = 0
                while (read < contentLength) {
                    val ch = reader.read()
                    if (ch < 0) break
                    text.append(ch.toChar())
                    read++
                }
                bodyJson = try { JSONObject(text.toString()) } catch (_: Exception) { null }
                if (bodyJson == null && method in listOf("POST", "PATCH", "PUT") && text.isNotBlank()) {
                    writeJson(socket, 400, com.ghostbroot.app.model.errorJson("invalid_json", "Request body must be a JSON object"))
                    return
                }
            }
            val (path, query) = parseQuery(rawPath)
            // Auth: everything under /api/v1 requires the token except status health fields.
            val isStatus = path == "/api/v1/status" && method == "GET"
            if (!isStatus && !Auth.check(Auth.extractToken(headers, query), token)) {
                writeJson(socket, 401, com.ghostbroot.app.model.errorJson("unauthorized", "Missing or invalid token"))
                return
            }
            val resp = Router.dispatch(method, path, bodyJson, query)
            if (isStatus) {
                resp.body.put("port", boundPort).put("log", lastLog())
            }
            writeJson(socket, resp.status, resp.body)
        } catch (e: Exception) {
            log("handle error: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString().take(400)}")
            try {
                writeJson(socket, 500, com.ghostbroot.app.model.errorJson("internal_error", "Internal server error"))
            } catch (_: Exception) {}
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleWs(socket: Socket, headers: Map<String, String>, rawPath: String) {
        val (_, query) = parseQuery(rawPath)
        if (!Auth.check(Auth.extractToken(headers, query), token)) {
            writeJson(socket, 401, com.ghostbroot.app.model.errorJson("unauthorized", "Missing or invalid token"))
            return
        }
        val key = headers["sec-websocket-key"]
        if (key.isNullOrBlank()) {
            writeJson(socket, 400, com.ghostbroot.app.model.errorJson("bad_request", "Missing Sec-WebSocket-Key"))
            return
        }
        val conn = WsConnection(socket)
        val upgrade = conn.handshake(headers, key)
        val out = socket.getOutputStream()
        out.write(upgrade.toByteArray(Charsets.US_ASCII))
        out.flush()
        EventBus.register(conn)
        Sessions.create()
        try {
            conn.sendText(JSONObject()
                .put("type", "connected")
                .put("payload", JSONObject().put("service", "GhostbRoot"))
                .put("ts", System.currentTimeMillis()).toString())
            // Forward inbound messages to the app layer; keep reading until disconnect.
            while (!conn.closed) {
                val msg = conn.readMessage() ?: break
                try { onEvent("ws.message", JSONObject(msg)) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        } finally {
            EventBus.unregister(conn)
            conn.close()
        }
    }

    private fun parseQuery(rawPath: String): Pair<String, Map<String, String>> {
        val q = rawPath.indexOf('?')
        if (q < 0) return rawPath to emptyMap()
        val path = rawPath.substring(0, q)
        val query = rawPath.substring(q + 1).split("&").mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq > 0) java.net.URLDecoder.decode(part.substring(0, eq), "UTF-8") to
                java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8") else null
        }.toMap()
        return path to query
    }

    private fun writeJson(socket: Socket, status: Int, body: JSONObject) {
        val text = body.toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 201 -> "Created"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
            else -> "OK"
        }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)
        val out = socket.getOutputStream()
        out.write(head)
        out.write(bytes)
        out.flush()
    }
}
