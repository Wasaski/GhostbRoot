"""Typed wrappers over the GhostbRoot REST API (spec §2 endpoints)."""

from __future__ import annotations

import base64
import json
import os
import socket
import struct
import threading
import urllib.error
import urllib.request
from typing import Any, Callable, Dict, List, Optional


class GhostbRootError(RuntimeError):
    """API error returned by the server (structured error body)."""

    def __init__(self, status: int, code: str, message: str):
        super().__init__(f"{code}: {message} (HTTP {status})")
        self.status = status
        self.code = code
        self.message = message


class GhostbRootAuthError(GhostbRootError):
    pass


class GhostbRootConnectionError(ConnectionError):
    pass


class Panel:
    """A GhostbRoot overlay panel. Returned by :meth:`GhostbRoot.create_panel`."""

    def __init__(self, client: "GhostbRoot", data: Dict[str, Any]):
        self._client = client
        self.id: str = data["id"]
        self.title: str = data.get("title", "Ghost Panel")
        self.width: int = data.get("width", 340)
        self.height: int = data.get("height", 450)
        self.visible: bool = data.get("visible", True)
        self.components: List[Dict[str, Any]] = list(data.get("components", []))

    # Component builders ----------------------------------------------------
    def button(self, text: str, id: Optional[str] = None, **extra: Any) -> Dict[str, Any]:
        return self.add_component({"type": "button", "text": text, **({"id": id} if id else {}), **extra})

    def label(self, text: str, id: Optional[str] = None, **extra: Any) -> Dict[str, Any]:
        return self.add_component({"type": "label", "text": text, **({"id": id} if id else {}), **extra})

    def input(self, hint: str = "", id: Optional[str] = None, **extra: Any) -> Dict[str, Any]:
        return self.add_component({"type": "input", "hint": hint, **({"id": id} if id else {}), **extra})

    def slider(self, value: int = 0, max: int = 100, id: Optional[str] = None, **extra: Any) -> Dict[str, Any]:
        return self.add_component(
            {"type": "slider", "value": value, "max": max, **({"id": id} if id else {}), **extra})

    def switch(self, text: str = "", checked: bool = False, id: Optional[str] = None, **extra: Any) -> Dict[str, Any]:
        return self.add_component(
            {"type": "switch", "text": text, "checked": checked, **({"id": id} if id else {}), **extra})

    def canvas(self, width: int = 320, height: int = 200, id: Optional[str] = None, **extra: Any) -> Dict[str, Any]:
        return self.add_component(
            {"type": "canvas", "width": width, "height": height, **({"id": id} if id else {}), **extra})

    # Operations -------------------------------------------------------------
    def add_component(self, component: Dict[str, Any]) -> Dict[str, Any]:
        result = self._client._request("POST", f"/api/v1/panels/{self.id}/components", component)
        comp = result["component"]
        self.components.append(comp)
        return comp

    def update_component(self, component_id: str, **fields: Any) -> Dict[str, Any]:
        result = self._client._request(
            "PATCH", f"/api/v1/panels/{self.id}/components/{component_id}", fields)
        return result["component"]

    def remove_component(self, component_id: str) -> None:
        self._client._request("DELETE", f"/api/v1/panels/{self.id}/components/{component_id}")
        self.components = [c for c in self.components if c.get("id") != component_id]

    def draw(self, ops: List[Dict[str, Any]]) -> int:
        return self._client._request(
            "POST", f"/api/v1/panels/{self.id}/canvas/draw", {"ops": ops})["ops"]

    def animate(self, type: str, duration_ms: int, **extra: Any) -> Dict[str, Any]:
        return self._client._request(
            "POST", f"/api/v1/panels/{self.id}/animate",
            {"type": type, "durationMs": duration_ms, **extra})["spec"]

    def show(self) -> None:
        self._client._request("POST", f"/api/v1/panels/{self.id}/show")

    def hide(self) -> None:
        self._client._request("POST", f"/api/v1/panels/{self.id}/hide")

    def patch(self, **fields: Any) -> "Panel":
        result = self._client._request("PATCH", f"/api/v1/panels/{self.id}", fields)
        return Panel(self._client, result["panel"])

    def delete(self) -> None:
        self._client._request("DELETE", f"/api/v1/panels/{self.id}")

    def __repr__(self) -> str:  # pragma: no cover - debug helper
        return f"Panel(id={self.id!r}, title={self.title!r})"


class GhostbRoot:
    """Client for the GhostbRoot local API (spec §3/§4).

    Manual configuration only — no auto-discovery required::

        gb = GhostbRoot(host="127.0.0.1", port=8765, token="TOKEN")
        gb.connect()
        print(gb.status())

        panel = gb.create_panel(title="Ghost Panel", width=340, height=450)
        panel.button("Executar", id="run")
        panel.show()
    """

    def __init__(
        self,
        host: str = "127.0.0.1",
        port: int = 8765,
        token: str = "",
        *,
        timeout: float = 10.0,
    ):
        self.host = host
        self.port = port
        self.token = token
        self.timeout = timeout
        self._ws_thread: Optional[threading.Thread] = None
        self._ws_sock: Optional[socket.socket] = None

    # HTTP plumbing ----------------------------------------------------------
    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"

    def _request(self, method: str, path: str, body: Optional[Any] = None) -> Dict[str, Any]:
        data = None
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["X-GhostbRoot-Token"] = self.token
        if body is not None:
            data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", errors="replace")
            try:
                payload = json.loads(raw)
                code = payload.get("error", "http_error")
                message = payload.get("message", raw)
            except ValueError:
                code, message = "http_error", raw
            if exc.code == 401:
                raise GhostbRootAuthError(exc.code, code, message) from exc
            raise GhostbRootError(exc.code, code, message) from exc
        except (urllib.error.URLError, OSError) as exc:
            raise GhostbRootConnectionError(
                f"Cannot reach GhostbRoot at {self.base_url} — is the APK running and the server started? ({exc})"
            ) from exc

    # Connection / status ------------------------------------------------------
    def connect(self) -> Dict[str, Any]:
        """Verify the server is reachable and the token is accepted."""
        return self.status()

    def status(self) -> Dict[str, Any]:
        return self._request("GET", "/api/v1/status")

    def permissions(self) -> Dict[str, Any]:
        return self._request("GET", "/api/v1/permissions")

    # Panels ---------------------------------------------------------------
    def create_panel(
        self,
        title: str = "Ghost Panel",
        width: int = 340,
        height: int = 450,
        id: Optional[str] = None,
        components: Optional[List[Dict[str, Any]]] = None,
        **extra: Any,
    ) -> Panel:
        body: Dict[str, Any] = {"title": title, "width": width, "height": height, **extra}
        if id:
            body["id"] = id
        if components:
            body["components"] = components
        result = self._request("POST", "/api/v1/panels", body)
        return Panel(self, result["panel"])

    def list_panels(self) -> List[Panel]:
        return [Panel(self, p) for p in self._request("GET", "/api/v1/panels")["panels"]]

    def get_panel(self, panel_id: str) -> Panel:
        return Panel(self, self._request("GET", f"/api/v1/panels/{panel_id}")["panel"])

    def delete_panel(self, panel_id: str) -> None:
        self._request("DELETE", f"/api/v1/panels/{panel_id}")

    # Sessions ---------------------------------------------------------------
    def list_sessions(self) -> List[Dict[str, Any]]:
        return self._request("GET", "/api/v1/sessions")["sessions"]

    def create_session(self) -> Dict[str, Any]:
        return self._request("POST", "/api/v1/sessions", {})

    def delete_session(self, session_id: str) -> None:
        self._request("DELETE", f"/api/v1/sessions/{session_id}")

    # WebSocket events (spec §2: /api/v1/events) -----------------------------
    def listen_events(self, on_event: Callable[[Dict[str, Any]], None], max_messages: Optional[int] = None) -> None:
        """Blocking listen on /api/v1/events. Calls ``on_event`` per JSON message."""
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        self._ws_sock = sock
        handshake = (
            "GET /api/v1/events HTTP/1.1\r\n"
            f"Host: {self.host}:{self.port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            f"X-GhostbRoot-Token: {self.token}\r\n\r\n"
        )
        sock.sendall(handshake.encode("ascii"))
        buffer = b""
        while b"\r\n\r\n" not in buffer:
            chunk = sock.recv(4096)
            if not chunk:
                raise GhostbRootConnectionError("WebSocket handshake closed unexpectedly")
            buffer += chunk
        header, _, _ = buffer.partition(b"\r\n\r\n")
        if b"401" in header.split(b"\r\n", 1)[0]:
            raise GhostbRootAuthError(401, "unauthorized", "Invalid token for WebSocket")
        count = 0
        try:
            while max_messages is None or count < max_messages:
                message = self._read_ws_frame(sock)
                if message is None:
                    break
                on_event(message)
                count += 1
        finally:
            self.close_events()

    def _read_ws_frame(self, sock: socket.socket) -> Optional[Dict[str, Any]]:
        header = sock.recv(2)
        if len(header) < 2:
            return None
        opcode = header[0] & 0x0F
        length = header[1] & 0x7F
        if length == 126:
            length = struct.unpack(">H", sock.recv(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", sock.recv(8))[0]
        payload = b""
        while len(payload) < length:
            chunk = sock.recv(min(65536, length - len(payload)))
            if not chunk:
                return None
            payload += chunk
        if opcode == 0x8:
            return None
        if opcode == 0x9:  # ping -> pong
            sock.sendall(bytes([0x8A, len(payload)]) + payload)
            return self._read_ws_frame(sock)
        try:
            return json.loads(payload.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return None

    def close_events(self) -> None:
        if self._ws_sock is not None:
            try:
                self._ws_sock.close()
            except OSError:
                pass
            self._ws_sock = None
