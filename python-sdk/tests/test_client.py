"""Tests for the GhostbRoot Python SDK.

Runs against a faithful in-process mock of the documented API contract
(docs/API.md). No Android device or network needed.
"""

import json
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from ghostbroot import GhostbRoot, GhostbRootAuthError, GhostbRootError

TOKEN = "test-token"
PANELS = {}
SESSIONS = {}


def _panel(pid, title="Ghost Panel", width=340, height=450):
    PANELS[pid] = {"id": pid, "title": title, "width": width, "height": height,
                   "visible": True, "alpha": 1.0, "components": []}
    return PANELS[pid]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _auth_ok(self):
        return self.headers.get("X-GhostbRoot-Token") == TOKEN

    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _deny(self):
        self._send(401, {"error": "unauthorized", "message": "Missing or invalid token"})

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        return json.loads(self.rfile.read(length).decode())

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/api/v1/status":
            return self._send(200, {"ok": True, "service": "GhostbRoot", "panels": len(PANELS)})
        if not self._auth_ok():
            return self._deny()
        if path == "/api/v1/panels":
            return self._send(200, {"panels": list(PANELS.values())})
        if path.startswith("/api/v1/panels/"):
            pid = path.rsplit("/", 1)[-1]
            if pid in PANELS:
                return self._send(200, {"panel": PANELS[pid]})
            return self._send(404, {"error": "panel_not_found", "message": "Unknown panel id"})
        if path == "/api/v1/permissions":
            return self._send(200, {"overlay": "granted_or_pending"})
        if path == "/api/v1/sessions":
            return self._send(200, {"sessions": list(SESSIONS.values())})
        if path.endswith("/components"):
            pid = path.split("/")[4]
            return self._send(200, {"components": PANELS[pid]["components"]})
        return self._send(404, {"error": "not_found", "message": "No such route"})

    def do_POST(self):
        body = self._body()
        if not self._auth_ok():
            return self._deny()
        path = urllib.parse.urlparse(self.path).path
        if path == "/api/v1/panels":
            pid = body.get("id", "auto-1")
            width = body.get("width", 340)
            if not isinstance(width, int) or not (120 <= width <= 1200):
                return self._send(400, {"error": "invalid_width", "message": "width must be between 120 and 1200 dp"})
            if pid in PANELS:
                return self._send(409, {"error": "panel_exists", "message": "Panel id already exists"})
            panel = _panel(pid, body.get("title", "Ghost Panel"), width, body.get("height", 450))
            return self._send(201, {"ok": True, "panel": panel})
        parts = path.strip("/").split("/")
        # /api/v1/panels/{id}/components | .../show | .../hide | .../canvas/draw | .../animate
        if len(parts) >= 5 and parts[0:3] == ["api", "v1", "panels"]:
            pid = parts[3]
            if pid not in PANELS:
                return self._send(404, {"error": "panel_not_found", "message": "Unknown panel id"})
            if parts[4] == "components":
                comp = dict(body)
                comp.setdefault("id", "c1")
                PANELS[pid]["components"].append(comp)
                return self._send(201, {"ok": True, "component": comp})
            if parts[4] == "show":
                PANELS[pid]["visible"] = True
                return self._send(200, {"ok": True})
            if parts[4] == "hide":
                PANELS[pid]["visible"] = False
                return self._send(200, {"ok": True})
            if parts[4] == "canvas" and parts[5] == "draw":
                return self._send(200, {"ok": True, "ops": len(body.get("ops", []))})
            if parts[4] == "animate":
                return self._send(200, {"ok": True, "spec": body})
        if path == "/api/v1/sessions":
            sid = "s1"
            SESSIONS[sid] = {"id": sid, "createdAt": 0}
            return self._send(201, {"ok": True, "id": sid})
        return self._send(404, {"error": "not_found", "message": "No such route"})

    def do_PATCH(self):
        body = self._body()
        path = urllib.parse.urlparse(self.path).path
        parts = path.strip("/").split("/")
        if len(parts) == 4 and parts[0:3] == ["api", "v1", "panels"]:
            pid = parts[3]
            if pid not in PANELS:
                return self._send(404, {"error": "panel_not_found", "message": "Unknown panel id"})
            PANELS[pid].update(body)
            return self._send(200, {"ok": True, "panel": PANELS[pid]})
        if len(parts) == 6 and parts[4] == "components":
            pid, cid = parts[3], parts[5]
            for c in PANELS[pid]["components"]:
                if c["id"] == cid:
                    c.update(body)
                    return self._send(200, {"ok": True, "component": c})
            return self._send(404, {"error": "component_not_found", "message": "Unknown component id"})
        return self._send(404, {"error": "not_found", "message": "No such route"})

    def do_DELETE(self):
        path = urllib.parse.urlparse(self.path).path
        parts = path.strip("/").split("/")
        if len(parts) == 4 and parts[0:3] == ["api", "v1", "panels"]:
            if parts[3] in PANELS:
                del PANELS[parts[3]]
                return self._send(200, {"ok": True})
        if len(parts) == 6 and parts[4] == "components":
            pid, cid = parts[3], parts[5]
            PANELS[pid]["components"] = [c for c in PANELS[pid]["components"] if c["id"] != cid]
            return self._send(200, {"ok": True})
        if len(parts) == 4 and parts[0:3] == ["api", "v1", "sessions"]:
            if parts[3] in SESSIONS:
                del SESSIONS[parts[3]]
                return self._send(200, {"ok": True})
        return self._send(404, {"error": "not_found", "message": "No such route"})


@pytest.fixture(scope="module")
def server():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield port
    srv.shutdown()


@pytest.fixture()
def gb_factory(server):
    def make(token=TOKEN):
        return GhostbRoot(host="127.0.0.1", port=server, token=token)
    return make


def test_status_open_without_token(gb_factory):
    assert gb_factory(token="") .status()["ok"] is True


def test_auth_required(gb_factory):
    with pytest.raises(GhostbRootAuthError):
        gb_factory(token="wrong").list_panels()


def test_panel_crud(gb_factory):
    gb = gb_factory()
    panel = gb.create_panel(title="T", width=300, height=400, id="p-crud")
    assert panel.title == "T"
    assert gb.get_panel("p-crud").title == "T"
    panel.patch(title="T2")
    assert gb.get_panel("p-crud").title == "T2"
    assert [p.id for p in gb.list_panels()] and "p-crud" in [p.id for p in gb.list_panels()]
    gb.delete_panel("p-crud")
    with pytest.raises(GhostbRootError):
        gb.get_panel("p-crud")


def test_invalid_width_rejected(gb_factory):
    with pytest.raises(GhostbRootError):
        gb_factory().create_panel(width=10)


def test_components_and_draw(gb_factory):
    gb = gb_factory()
    panel = gb.create_panel(id="p-comp", title="C")
    panel.button("Executar", id="run")
    panel.label("Hi", id="lbl")
    assert len(panel.components) == 2
    panel.update_component("lbl", text="Bye")
    n = panel.draw([{"op": "rect", "x": 0, "y": 0, "width": 10, "height": 10}])
    assert n == 1
    panel.remove_component("run")
    assert len(panel.components) == 1


def test_show_hide_animate(gb_factory):
    gb = gb_factory()
    panel = gb.create_panel(id="p-vis", title="V")
    panel.hide()
    assert gb.get_panel("p-vis").visible is False
    panel.show()
    spec = panel.animate("fade", 600, **{"from": 0.0, "to": 1.0})
    assert spec["type"] == "fade"
