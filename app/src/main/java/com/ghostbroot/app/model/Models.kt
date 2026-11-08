package com.ghostbroot.app.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared data model for the GhostbRoot public API (spec §2).
 * Every mutating endpoint validates its input; invalid data is rejected
 * with a structured error and never touches the UI.
 */
object Validate {
    fun title(v: Any?): String? =
        (v as? String)?.takeIf { it.isNotBlank() && it.length <= 120 }

    fun int(v: Any?, min: Int, max: Int): Int? {
        if (v == null) return null
        val n = when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: return null
            else -> return null
        }
        return n.takeIf { it in min..max }
    }

    fun optInt(o: JSONObject, key: String, min: Int, max: Int): Int? =
        if (!o.has(key) || o.isNull(key)) null else int(o.opt(key), min, max)

    fun str(v: Any?, max: Int = 2000): String? =
        (v as? String)?.takeIf { it.length <= max }

    fun strList(v: Any?): List<String>? {
        val arr = v as? JSONArray ?: return null
        if (arr.length() > 64) return null
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i) ?: return null
            if (s.length > 500) return null
            out.add(s)
        }
        return out
    }

    fun idPatch(o: JSONObject): String? =
        if (!o.has("id")) null else str(o.opt("id"), 80)?.takeIf { it.isNotBlank() }

    val COMPONENT_TYPES = setOf("label", "button", "input", "slider", "switch", "canvas", "spacer")
    val DRAW_OPS = setOf("clear", "rect", "circle", "line", "text", "arc", "bitmap")

    fun componentType(v: Any?): String? =
        (v as? String)?.takeIf { it in COMPONENT_TYPES }
}

data class Panel(
    val id: String,
    var title: String = "Ghost Panel",
    var widthDp: Int = 340,
    var heightDp: Int = 450,
    var xDp: Int? = null,
    var yDp: Int? = null,
    var visible: Boolean = true,
    var alpha: Float = 1.0f,
    val components: MutableList<JSONObject> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("width", widthDp)
        .put("height", heightDp)
        .put("x", xDp ?: JSONObject.NULL)
        .put("y", yDp ?: JSONObject.NULL)
        .put("visible", visible)
        .put("alpha", alpha)
        .put("components", JSONArray(components))

    fun applyPatch(o: JSONObject): Panel {
        Validate.title(o.opt("title"))?.let { title = it }
        Validate.optInt(o, "width", 120, 1200)?.let { widthDp = it }
        Validate.optInt(o, "height", 120, 1600)?.let { heightDp = it }
        if (o.has("x") && !o.isNull("x")) Validate.optInt(o, "x", -2000, 4000)?.let { xDp = it }
        if (o.has("y") && !o.isNull("y")) Validate.optInt(o, "y", -2000, 4000)?.let { yDp = it }
        if (o.has("visible")) visible = o.optBoolean("visible", visible)
        if (o.has("alpha")) alpha = o.optDouble("alpha", alpha.toDouble()).toFloat().coerceIn(0.1f, 1f)
        return this
    }
}

fun errorJson(code: String, message: String, details: String? = null): JSONObject {
    val o = JSONObject().put("error", code).put("message", message)
    if (details != null) o.put("details", details)
    return o
}
