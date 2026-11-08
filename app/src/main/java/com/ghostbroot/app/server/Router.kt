package com.ghostbroot.app.server

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * REST router for the GhostbRoot public API (spec §2).
 * Pure logic over already-parsed JSON — UI effects are delegated to the
 * [Sink] so this class stays unit-testable on the JVM without Android.
 */
object Router {

    interface Sink {
        fun onPanelChanged(panelId: String)
        fun onDraw(panelId: String, ops: JSONArray)
        fun onAnimate(panelId: String, spec: JSONObject)
    }

    data class Response(val status: Int, val body: JSONObject)
    data class Route(val method: String, val segments: List<String>, val handler: Handler)
    fun interface Handler { fun handle(pathArgs: List<String>, body: JSONObject?, query: Map<String, String>): Response }

    private fun ok(body: JSONObject, status: Int = 200) = Response(status, body)
    private fun err(status: Int, code: String, msg: String, details: String? = null) =
        Response(status, com.ghostbroot.app.model.errorJson(code, msg, details))

    // Panels owned by the app layer; Router keeps the single source of truth.
    val panels = LinkedHashMap<String, com.ghostbroot.app.model.Panel>()
    var sink: Sink = object : Sink {
        override fun onPanelChanged(panelId: String) {}
        override fun onDraw(panelId: String, ops: JSONArray) {}
        override fun onAnimate(panelId: String, spec: JSONObject) {}
    }

    fun reset() { panels.clear(); Sessions.clear() }

    fun routes(): List<Route> = listOf(
        Route("GET", listOf("api", "v1", "status"), { _, _, _ ->
            ok(JSONObject()
                .put("ok", true)
                .put("service", "GhostbRoot")
                .put("version", "1.0.0")
                .put("panels", panels.size)
                .put("sessions", Sessions.list().size)
                .put("wsClients", EventBus.count()))
        }),
        Route("POST", listOf("api", "v1", "panels"), { _, body, _ -> createPanel(body) }),
        Route("GET", listOf("api", "v1", "panels"), { _, _, _ ->
            ok(JSONObject().put("panels", JSONArray(panels.values.map { it.toJson() })))
        }),
        Route("GET", listOf("api", "v1", "panels", "*"), { args, _, _ -> getPanel(args[0]) }),
        Route("PATCH", listOf("api", "v1", "panels", "*"), { args, body, _ -> patchPanel(args[0], body) }),
        Route("DELETE", listOf("api", "v1", "panels", "*"), { args, _, _ -> deletePanel(args[0]) }),
        Route("POST", listOf("api", "v1", "panels", "*", "show"), { args, _, _ -> setVisible(args[0], true) }),
        Route("POST", listOf("api", "v1", "panels", "*", "hide"), { args, _, _ -> setVisible(args[0], false) }),
        Route("POST", listOf("api", "v1", "panels", "*", "components"), { args, body, _ -> addComponent(args[0], body) }),
        Route("GET", listOf("api", "v1", "panels", "*", "components"), { args, _, _ -> listComponents(args[0]) }),
        Route("PATCH", listOf("api", "v1", "panels", "*", "components", "*"), { args, body, _ -> patchComponent(args[0], args[1], body) }),
        Route("DELETE", listOf("api", "v1", "panels", "*", "components", "*"), { args, _, _ -> deleteComponent(args[0], args[1]) }),
        Route("POST", listOf("api", "v1", "panels", "*", "canvas", "draw"), { args, body, _ -> draw(args[0], body) }),
        Route("POST", listOf("api", "v1", "panels", "*", "animate"), { args, body, _ -> animate(args[0], body) }),
        Route("GET", listOf("api", "v1", "permissions"), { _, _, _ ->
            ok(JSONObject()
                .put("overlay", "granted_or_pending")
                .put("note", "Overlay permission requires the user to allow 'Display over other apps' in device Settings."))
        }),
        Route("GET", listOf("api", "v1", "sessions"), { _, _, _ ->
            ok(JSONObject().put("sessions", JSONArray(Sessions.list().map { Sessions.toJson(it) })))
        }),
        Route("POST", listOf("api", "v1", "sessions"), { _, _, _ ->
            val s = Sessions.create()
            ok(JSONObject().put("ok", true).put("id", s.id), 201)
        }),
        Route("DELETE", listOf("api", "v1", "sessions", "*"), { args, _, _ ->
            val gone = Sessions.remove(args[0])
            if (gone) ok(JSONObject().put("ok", true)) else err(404, "session_not_found", "Unknown session id")
        })
    )

    /** Dispatches one HTTP request. Returns 404 for unknown routes, 405 for wrong method. */
    fun dispatch(method: String, path: String, body: JSONObject?, query: Map<String, String>): Response {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        var pathMatched = false
        for (r in routes()) {
            if (r.segments.size != segments.size) continue
            val args = ArrayList<String>()
            var match = true
            for (i in r.segments.indices) {
                if (r.segments[i] == "*") args.add(segments[i]) else if (r.segments[i] != segments[i]) { match = false; break }
            }
            if (!match) continue
            pathMatched = true
            if (r.method == method) return r.handler.handle(args, body, query)
        }
        return if (pathMatched) err(405, "method_not_allowed", "Method $method not allowed for $path")
        else err(404, "not_found", "No such route: $path")
    }

    // --- Panels -------------------------------------------------------------
    private fun createPanel(body: JSONObject?): Response {
        if (body == null) return err(400, "invalid_body", "JSON object required")
        val title = if (body.has("title")) com.ghostbroot.app.model.Validate.title(body.opt("title"))
            ?: return err(400, "invalid_title", "title must be a non-empty string <=120 chars") else "Ghost Panel"
        val width = com.ghostbroot.app.model.Validate.optInt(body, "width", 120, 1200) ?: 340
        if (body.has("width") && com.ghostbroot.app.model.Validate.optInt(body, "width", 120, 1200) == null)
            return err(400, "invalid_width", "width must be between 120 and 1200 dp")
        val height = com.ghostbroot.app.model.Validate.optInt(body, "height", 120, 1600) ?: 450
        if (body.has("height") && com.ghostbroot.app.model.Validate.optInt(body, "height", 120, 1600) == null)
            return err(400, "invalid_height", "height must be between 120 and 1600 dp")
        val id = com.ghostbroot.app.model.Validate.str(body.opt("id"), 80)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        if (panels.containsKey(id)) return err(409, "panel_exists", "Panel id already exists")
        val panel = com.ghostbroot.app.model.Panel(id = id, title = title, widthDp = width, heightDp = height)
        if (body.has("components")) {
            val comps = body.opt("components")
            if (comps is JSONArray) {
                for (i in 0 until comps.length()) {
                    val c = comps.optJSONObject(i) ?: continue
                    validateComponent(c)?.let { return err(400, it.first, it.second) }
                    panel.components.add(c)
                }
            } else return err(400, "invalid_components", "components must be an array")
        }
        panels[id] = panel
        sink.onPanelChanged(id)
        EventBus.broadcast("panel.created", panel.toJson())
        return ok(JSONObject().put("ok", true).put("panel", panel.toJson()), 201)
    }

    private fun getPanel(id: String): Response {
        val p = panels[id] ?: return err(404, "panel_not_found", "Unknown panel id: $id")
        return ok(JSONObject().put("panel", p.toJson()))
    }

    private fun patchPanel(id: String, body: JSONObject?): Response {
        val p = panels[id] ?: return err(404, "panel_not_found", "Unknown panel id: $id")
        if (body == null) return err(400, "invalid_body", "JSON object required")
        if (body.has("title") && com.ghostbroot.app.model.Validate.title(body.opt("title")) == null)
            return err(400, "invalid_title", "title must be a non-empty string <=120 chars")
        if (body.has("width") && com.ghostbroot.app.model.Validate.optInt(body, "width", 120, 1200) == null)
            return err(400, "invalid_width", "width must be between 120 and 1200 dp")
        if (body.has("height") && com.ghostbroot.app.model.Validate.optInt(body, "height", 120, 1600) == null)
            return err(400, "invalid_height", "height must be between 120 and 1600 dp")
        p.applyPatch(body)
        sink.onPanelChanged(id)
        EventBus.broadcast("panel.updated", p.toJson())
        return ok(JSONObject().put("ok", true).put("panel", p.toJson()))
    }

    private fun setVisible(id: String, visible: Boolean): Response {
        val p = panels[id] ?: return err(404, "panel_not_found", "Unknown panel id: $id")
        p.visible = visible
        sink.onPanelChanged(p.id)
        return ok(JSONObject().put("ok", true).put("id", p.id))
    }

    private fun deletePanel(id: String): Response {
        if (panels.remove(id) == null) return err(404, "panel_not_found", "Unknown panel id: $id")
        sink.onPanelChanged(id)
        EventBus.broadcast("panel.deleted", JSONObject().put("id", id))
        return ok(JSONObject().put("ok", true).put("id", id))
    }

    // --- Components ---------------------------------------------------------
    private fun validateComponent(c: JSONObject): Pair<String, String>? {
        val type = com.ghostbroot.app.model.Validate.componentType(c.opt("type"))
            ?: return "invalid_component_type" to "type must be one of ${com.ghostbroot.app.model.Validate.COMPONENT_TYPES}"
        if (com.ghostbroot.app.model.Validate.idPatch(c) == null && !c.has("id"))
            c.put("id", UUID.randomUUID().toString())
        if (c.has("text") && (((c.opt("text") as? String)?.length) ?: 0) > 2000)
            return "invalid_text" to "text too long"
        if (type == "canvas" && c.has("width") && com.ghostbroot.app.model.Validate.optInt(c, "width", 20, 1200) == null)
            return "invalid_width" to "canvas width must be between 20 and 1200 dp"
        if (type == "canvas" && c.has("height") && com.ghostbroot.app.model.Validate.optInt(c, "height", 20, 1600) == null)
            return "invalid_height" to "canvas height must be between 20 and 1600 dp"
        return null
    }

    private fun addComponent(panelId: String, body: JSONObject?): Response {
        val p = panels[panelId] ?: return err(404, "panel_not_found", "Unknown panel id: $panelId")
        if (body == null) return err(400, "invalid_body", "JSON object required")
        validateComponent(body)?.let { return err(400, it.first, it.second) }
        p.components.add(body)
        sink.onPanelChanged(panelId)
        EventBus.broadcast("component.added", JSONObject().put("panelId", panelId).put("component", body))
        return ok(JSONObject().put("ok", true).put("component", body), 201)
    }

    private fun listComponents(panelId: String): Response {
        val p = panels[panelId] ?: return err(404, "panel_not_found", "Unknown panel id: $panelId")
        return ok(JSONObject().put("components", JSONArray(p.components)))
    }

    private fun patchComponent(panelId: String, compId: String, body: JSONObject?): Response {
        val p = panels[panelId] ?: return err(404, "panel_not_found", "Unknown panel id: $panelId")
        val c = p.components.firstOrNull { it.optString("id") == compId }
            ?: return err(404, "component_not_found", "Unknown component id: $compId")
        if (body == null) return err(400, "invalid_body", "JSON object required")
        if (body.has("type") && com.ghostbroot.app.model.Validate.componentType(body.opt("type")) == null)
            return err(400, "invalid_component_type", "Unknown component type")
        val keys = body.keys()
        while (keys.hasNext()) { val k = keys.next(); if (k != "id") c.put(k, body.opt(k)) }
        sink.onPanelChanged(panelId)
        EventBus.broadcast("component.updated", JSONObject().put("panelId", panelId).put("component", c))
        return ok(JSONObject().put("ok", true).put("component", c))
    }

    private fun deleteComponent(panelId: String, compId: String): Response {
        val p = panels[panelId] ?: return err(404, "panel_not_found", "Unknown panel id: $panelId")
        val removed = p.components.removeIf { it.optString("id") == compId }
        if (!removed) return err(404, "component_not_found", "Unknown component id: $compId")
        sink.onPanelChanged(panelId)
        EventBus.broadcast("component.deleted", JSONObject().put("panelId", panelId).put("componentId", compId))
        return ok(JSONObject().put("ok", true))
    }

    // --- Canvas + animation -------------------------------------------------
    private fun draw(panelId: String, body: JSONObject?): Response {
        if (!panels.containsKey(panelId)) return err(404, "panel_not_found", "Unknown panel id: $panelId")
        if (body == null) return err(400, "invalid_body", "JSON object required")
        val ops = body.opt("ops") ?: return err(400, "missing_ops", "ops array is required")
        if (ops !is JSONArray) return err(400, "invalid_ops", "ops must be an array")
        if (ops.length() > 500) return err(400, "too_many_ops", "Max 500 drawing ops per request")
        for (i in 0 until ops.length()) {
            val op = ops.optJSONObject(i) ?: return err(400, "invalid_op", "ops[$i] must be an object")
            val kind = op.optString("op")
            if (kind !in com.ghostbroot.app.model.Validate.DRAW_OPS)
                return err(400, "invalid_op", "Unknown drawing op '$kind' at index $i")
        }
        sink.onDraw(panelId, ops)
        EventBus.broadcast("canvas.draw", JSONObject().put("panelId", panelId).put("ops", ops))
        return ok(JSONObject().put("ok", true).put("ops", ops.length()))
    }

    private fun animate(panelId: String, body: JSONObject?): Response {
        if (!panels.containsKey(panelId)) return err(404, "panel_not_found", "Unknown panel id: $panelId")
        if (body == null) return err(400, "invalid_body", "JSON object required")
        val type = com.ghostbroot.app.model.Validate.str(body.opt("type"), 40)
            ?: return err(400, "invalid_type", "type must be a string")
        val duration = com.ghostbroot.app.model.Validate.optInt(body, "durationMs", 50, 30000)
            ?: return err(400, "invalid_duration", "durationMs must be between 50 and 30000")
        val spec = JSONObject()
            .put("type", type)
            .put("durationMs", duration)
            .put("from", if (body.has("from")) body.opt("from") else 0.0)
            .put("to", if (body.has("to")) body.opt("to") else 1.0)
        if (body.has("componentId")) spec.put("componentId", body.opt("componentId"))
        sink.onAnimate(panelId, spec)
        EventBus.broadcast("panel.animate", JSONObject().put("panelId", panelId).put("spec", spec))
        return ok(JSONObject().put("ok", true).put("spec", spec))
    }
}
