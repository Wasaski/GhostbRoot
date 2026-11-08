package com.ghostbroot.app.server

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Random

/**
 * Minimal RFC 6455 WebSocket connection over a raw socket (spec §2).
 * Supports text frames, ping/pong, close. Client->server payloads are
 * unmasked per spec; server->client are unmasked.
 */
class WsConnection(private val socket: Socket) {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    @Volatile var closed = false

    fun handshake(headers: Map<String, String>, key: String): String {
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
        )
        return "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
    }

    @Synchronized
    fun sendText(text: String) {
        if (closed) return
        val payload = text.toByteArray(Charsets.UTF_8)
        val header = ByteArray(if (payload.size < 126) 2 else if (payload.size < 65536) 4 else 10)
        header[0] = 0x81.toByte() // FIN + text
        when {
            payload.size < 126 -> header[1] = payload.size.toByte()
            payload.size < 65536 -> {
                header[1] = 126
                header[2] = ((payload.size shr 8) and 0xFF).toByte()
                header[3] = (payload.size and 0xFF).toByte()
            }
            else -> {
                header[1] = 127
                for (i in 0 until 8) header[2 + i] = ((payload.size.toLong() shr (56 - 8 * i)) and 0xFF).toByte()
            }
        }
        output.write(header)
        output.write(payload)
        output.flush()
    }

    /** Returns the next text message, or null on close/ping (handled internally). */
    fun readMessage(): String? {
        val b0 = input.read(); if (b0 < 0) return null
        val fin = (b0 and 0x80) != 0; val opcode = b0 and 0x0F
        val b1 = input.read(); if (b1 < 0) return null
        val masked = (b1 and 0x80) != 0
        var len = b1 and 0x7F
        when (len) {
            126 -> { val hi = input.read(); val lo = input.read(); if (hi < 0 || lo < 0) return null; len = (hi shl 8) or lo }
            127 -> { var v = 0L; repeat(8) { val b = input.read(); if (b < 0) return null; v = (v shl 8) or (b and 0xFFL) }; len = v.toInt() }
        }
        val mask = if (masked) ByteArray(4).also { input.read(it) } else null
        val payload = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(payload, off, len - off); if (n < 0) return null
            off += n
        }
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return when (opcode) {
            0x8 -> { close(); null }                       // close
            0x9 -> { try { sendFrame(0xA, payload) } catch (_: Exception) {}; readMessage() } // ping -> pong
            0xA -> readMessage()                            // pong: keep reading
            0x1, 0x2 -> if (fin) payload.toString(Charsets.UTF_8) else null
            else -> null
        }
    }

    @Synchronized
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val header = byteArrayOf((0x80 or opcode).toByte(), payload.size.toByte())
        output.write(header); output.write(payload); output.flush()
    }

    fun close() {
        if (closed) return
        closed = true
        try { sendFrame(0x8, ByteArray(0)) } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
    }
}

/** Builds the demo Sec-WebSocket-Key (not used by server handshake path). */
fun newWsKey(): String {
    val b = ByteArray(16); Random().nextBytes(b)
    return Base64.getEncoder().encodeToString(b)
}
