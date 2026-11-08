# GhostbRoot Python SDK

Control GhostbRoot overlay panels from Termux. Zero third-party dependencies.

## Install

```sh
pip install ./python-sdk
```

## Usage

```python
from ghostbroot import GhostbRoot

gb = GhostbRoot(host="127.0.0.1", port=8765, token="TOKEN")
gb.connect()
print(gb.status())

panel = gb.create_panel(title="Ghost Panel", width=340, height=450)
panel.button("Executar", id="run")
panel.label("Status: pronto", id="lbl")
panel.canvas(width=320, height=200, id="cv")
panel.show()

panel.draw([
    {"op": "clear", "color": "#10141C"},
    {"op": "rect", "x": 10, "y": 10, "width": 120, "height": 60, "color": "#38BDF8"},
    {"op": "circle", "x": 200, "y": 60, "radius": 30, "color": "#F472B6"},
    {"op": "text", "x": 10, "y": 100, "text": "Ghost", "color": "#FFFFFF", "textSize": 16},
])
panel.animate("fade", 600, **{"from": 0.0, "to": 1.0})

for p in gb.list_panels():
    print(p.id, p.title)
```

> Note: `from` is a Python keyword, so animation `from`/`to` fields are
> passed via dict unpacking: `panel.animate("fade", 600, **{"from": 0.0, "to": 1.0})`.

## Events

```python
def on_event(msg):
    print(msg["type"], msg.get("payload"))

gb.listen_events(on_event)  # blocks; Ctrl-C to stop
```

## API

See `docs/API.md` for the full HTTP/WebSocket contract. The SDK maps 1:1:

| Method | Endpoint |
|---|---|
| `status()` | `GET /api/v1/status` |
| `create_panel(...)` / `list_panels()` / `get_panel(id)` / `delete_panel(id)` | `/api/v1/panels` |
| `panel.button/label/input/slider/switch/canvas(...)` | `POST .../components` |
| `panel.update_component(id, ...)` | `PATCH .../components/{id}` |
| `panel.remove_component(id)` | `DELETE .../components/{id}` |
| `panel.draw(ops)` | `POST .../canvas/draw` |
| `panel.animate(type, duration_ms)` | `POST .../animate` |
| `panel.show()` / `panel.hide()` / `panel.patch(...)` | `/api/v1/panels/{id}/*` |
| `permissions()` | `GET /api/v1/permissions` |
| `create_session()` / `list_sessions()` / `delete_session(id)` | `/api/v1/sessions` |
| `listen_events(cb)` | `GET /api/v1/events` (WebSocket) |

## Tests

```sh
python -m pytest python-sdk/tests -q
```

The tests run against a faithful in-process mock of the API contract and
need no device.
