package com.ghostbroot.app.server

import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Token authentication (spec §2/§4). Constant-time compare, header or query param. */
object Auth {
    fun generate(): String = UUID.randomUUID().toString()

    fun check(provided: String?, expected: String): Boolean {
        if (provided.isNullOrBlank() || expected.isBlank()) return false
        if (provided.length != expected.length) return false
        var diff = 0
        for (i in provided.indices) diff = diff or (provided[i].code xor expected[i].code)
        return diff == 0
    }

    fun extractToken(headers: Map<String, String>, query: Map<String, String>): String? =
        headers["x-ghostbroot-token"] ?: headers["authorization"]?.removePrefix("Bearer ")?.trim()
            ?: query["token"]
}

/** In-memory sessions (spec §2): one per authenticated connection. */
object Sessions {
    data class Session(val id: String = UUID.randomUUID().toString(), val createdAt: Long = System.currentTimeMillis())

    private val map = ConcurrentHashMap<String, Session>()

    fun create(): Session = Session().also { map[it.id] = it }
    fun get(id: String): Session? = map[id]
    fun list(): List<Session> = map.values.sortedBy { it.createdAt }
    fun remove(id: String): Boolean = map.remove(id) != null

    fun toJson(s: Session): JSONObject =
        JSONObject().put("id", s.id).put("createdAt", s.createdAt)

    fun clear() = map.clear()
}

/** Fan-out event bus -> all WebSocket clients (spec §2). */
object EventBus {
    private val clients = ConcurrentHashMap.newKeySet<WsConnection>()

    fun register(c: WsConnection) { clients.add(c) }
    fun unregister(c: WsConnection) { clients.remove(c) }
    fun count(): Int = clients.size

    fun broadcast(type: String, payload: JSONObject) {
        val msg = JSONObject().put("type", type).put("payload", payload).put("ts", System.currentTimeMillis()).toString()
        clients.toList().forEach { c ->
            try { c.sendText(msg) } catch (_: Exception) { clients.remove(c) }
        }
    }
}
