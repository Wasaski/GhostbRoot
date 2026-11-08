package com.ghostbroot.app

import com.ghostbroot.app.model.Validate
import com.ghostbroot.app.server.Auth
import com.ghostbroot.app.server.Router
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** JVM unit tests for Router + validation (spec §9, CI-runnable). */
class RouterTest {

    @Before fun setUp() { Router.reset() }

    @Test fun statusIsOpen() {
        val r = Router.dispatch("GET", "/api/v1/status", null, emptyMap())
        assertEquals(200, r.status)
        assertTrue(r.body.optBoolean("ok"))
    }

    @Test fun createPanel_minimal() {
        val r = Router.dispatch("POST", "/api/v1/panels", JSONObject(), emptyMap())
        assertEquals(201, r.status)
        assertEquals("Ghost Panel", r.body.getJSONObject("panel").optString("title"))
    }

    @Test fun createPanel_invalidWidth_rejected() {
        val r = Router.dispatch("POST", "/api/v1/panels", JSONObject().put("width", 10), emptyMap())
        assertEquals(400, r.status)
        assertEquals("invalid_width", r.body.optString("error"))
    }

    @Test fun createPanel_invalidTitle_rejected() {
        val r = Router.dispatch("POST", "/api/v1/panels", JSONObject().put("title", "   "), emptyMap())
        assertEquals(400, r.status)
    }

    @Test fun createPanel_duplicateId_conflict() {
        val body = JSONObject().put("id", "p1")
        assertEquals(201, Router.dispatch("POST", "/api/v1/panels", body, emptyMap()).status)
        assertEquals(409, Router.dispatch("POST", "/api/v1/panels", body, emptyMap()).status)
    }

    @Test fun panel_patchDelete_lifecycle() {
        Router.dispatch("POST", "/api/v1/panels", JSONObject().put("id", "p1").put("title", "A"), emptyMap())
        val patched = Router.dispatch("PATCH", "/api/v1/panels/p1", JSONObject().put("title", "B"), emptyMap())
        assertEquals(200, patched.status)
        assertEquals("B", patched.body.getJSONObject("panel").optString("title"))
        assertEquals(200, Router.dispatch("DELETE", "/api/v1/panels/p1", null, emptyMap()).status)
        assertEquals(404, Router.dispatch("GET", "/api/v1/panels/p1", null, emptyMap()).status)
    }

    @Test fun component_addPatchDelete() {
        Router.dispatch("POST", "/api/v1/panels", JSONObject().put("id", "p1"), emptyMap())
        val bad = Router.dispatch("POST", "/api/v1/panels/p1/components", JSONObject().put("type", "nope"), emptyMap())
        assertEquals(400, bad.status)
        val created = Router.dispatch(
            "POST", "/api/v1/panels/p1/components",
            JSONObject().put("id", "c1").put("type", "button").put("text", "Run"), emptyMap())
        assertEquals(201, created.status)
        val patched = Router.dispatch(
            "PATCH", "/api/v1/panels/p1/components/c1", JSONObject().put("text", "Go"), emptyMap())
        assertEquals("Go", patched.body.getJSONObject("component").optString("text"))
        assertEquals(200, Router.dispatch("DELETE", "/api/v1/panels/p1/components/c1", null, emptyMap()).status)
        assertEquals(404, Router.dispatch("DELETE", "/api/v1/panels/p1/components/c1", null, emptyMap()).status)
    }

    @Test fun draw_validatesOps() {
        Router.dispatch("POST", "/api/v1/panels", JSONObject().put("id", "p1"), emptyMap())
        val bad = Router.dispatch("POST", "/api/v1/panels/p1/canvas/draw",
            JSONObject().put("ops", JSONArray().put(JSONObject().put("op", "explode"))), emptyMap())
        assertEquals(400, bad.status)
        val good = Router.dispatch("POST", "/api/v1/panels/p1/canvas/draw",
            JSONObject().put("ops", JSONArray().put(JSONObject().put("op", "rect").put("x", 0).put("y", 0))), emptyMap())
        assertEquals(200, good.status)
    }

    @Test fun unknownRoute_404_methodNotAllowed_405() {
        assertEquals(404, Router.dispatch("GET", "/api/v9/nope", null, emptyMap()).status)
        assertEquals(405, Router.dispatch("PUT", "/api/v1/status", null, emptyMap()).status)
    }

    @Test fun auth_constantTimeCompare() {
        assertTrue(Auth.check("tok", "tok"))
        assertFalse(Auth.check("tok", "t0k"))
        assertFalse(Auth.check(null, "tok"))
        assertFalse(Auth.check("", "tok"))
    }

    @Test fun validate_ranges() {
        assertNull(Validate.int(5, 120, 1200))
        assertEquals(340, Validate.int("340", 120, 1200))
        assertNull(Validate.title(" "))
        assertEquals("x", Validate.title("x"))
    }

    @Test fun sessions_createListDelete() {
        val created = Router.dispatch("POST", "/api/v1/sessions", JSONObject(), emptyMap())
        assertEquals(201, created.status)
        val id = created.body.optString("id")
        val listed = Router.dispatch("GET", "/api/v1/sessions", null, emptyMap())
        assertTrue(listed.body.getJSONArray("sessions").length() >= 1)
        assertEquals(200, Router.dispatch("DELETE", "/api/v1/sessions/$id", null, emptyMap()).status)
        assertEquals(404, Router.dispatch("DELETE", "/api/v1/sessions/$id", null, emptyMap()).status)
    }
}
