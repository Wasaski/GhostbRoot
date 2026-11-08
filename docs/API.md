# GhostbRoot — Public API (spec §2)

Base URL (loopback only): `http://127.0.0.1:8765`
Auth: every route except `GET /api/v1/status` requires the session token,
sent as header `X-GhostbRoot-Token: <TOKEN>` (or `Authorization: Bearer <TOKEN>`).
WebSocket accepts `?token=<TOKEN>` or the same headers.

All mutating operations validate input. Invalid data returns a structured
error and never touches the UI:

```json
{ "error": "invalid_width", "message": "width must be between 120 and 1200 dp" }
```

## Endpoints

### `GET /api/v1/status`
Server state. Open (no token required).
Response: `{ "ok": true, "service": "GhostbRoot", "version": "1.0.0", "panels": 0, "sessions": 0, "wsClients": 0 }`

### `POST /api/v1/panels`
Creates a panel.
Body: `{ "title": "Ghost Panel", "width": 340, "height": 450, "id": "optional", "components": [] }`
- `title`: non-empty string, max 120 chars (default `"Ghost Panel"`)
- `width`: 120–1200 dp (default 340); `height`: 120–1600 dp (default 450)
Response `201`: `{ "ok": true, "panel": { "id": "...", "title": "...", "width": 340, "height": 450, "x": null, "y": null, "visible": true, "alpha": 1.0, "components": [] } }`

### `GET /api/v1/panels`
Lists panels. Response: `{ "panels": [...] }`

### `GET /api/v1/panels/{id}`
Returns one panel. `404 panel_not_found` when unknown.

### `PATCH /api/v1/panels/{id}`
Updates `title`, `width`, `height`, `x`, `y`, `visible`, `alpha`. Same ranges as create.

### `DELETE /api/v1/panels/{id}`
Removes the panel and its overlay window.

### `POST /api/v1/panels/{id}/show` / `.../hide`
Sets visibility `true` / `false` (panel state retained while hidden).

### `POST /api/v1/panels/{id}/components`
Adds a component.
Body: `{ "id": "run", "type": "button", "text": "Executar" }`
Types: `label`, `button`, `input`, `slider`, `switch`, `canvas`, `spacer`.
Common fields: `id` (auto-generated when omitted), `text`, `color`, `textSize`.
`canvas` also accepts `width` (20–1200 dp) and `height` (20–1600 dp).
`slider` accepts `value`/`max`; `input` accepts `hint`/`value`; `switch` accepts `checked`.

### `GET /api/v1/panels/{id}/components`
Lists components of a panel.

### `PATCH /api/v1/panels/{id}/components/{component_id}`
Updates component fields (e.g. `text`, `checked`, `value`). `id` and `type` changes are validated.

### `DELETE /api/v1/panels/{id}/components/{component_id}`
Removes a component.

### `POST /api/v1/panels/{id}/canvas/draw`
Canvas drawing ops (max 500 per request).
Body: `{ "ops": [ { "op": "rect", "x": 10, "y": 10, "width": 120, "height": 60, "color": "#38BDF8" } ] }`
Ops: `clear`, `rect`, `circle`, `line`, `text`, `arc`.
Fields: `x`, `y`, `x1`, `y1`, `x2`, `y2`, `width`, `height`, `radius`, `color` (`#RRGGBB`),
`alpha` (0–1), `text`, `textSize`, `strokeWidth`, `style` (`fill`|`stroke`),
`startAngle`, `sweepAngle`. Unknown ops are rejected with `invalid_op`.
`bitmap` is in the validation set but not rendered (fallback: ignored) — remote image
loading is out of scope for a loopback-only server.

### `POST /api/v1/panels/{id}/animate`
Animations.
Body: `{ "type": "fade", "durationMs": 600, "from": 0.0, "to": 1.0, "componentId": "optional" }`
`type`: `fade` | `slide` | `scale` (other values are accepted by the API and
ignored by the renderer). `durationMs`: 50–30000.

### `GET /api/v1/permissions`
Reports overlay-permission guidance. The server never requests it automatically;
the user grants "Display over other apps" in device Settings (app button does this).

### `GET /api/v1/sessions` · `POST /api/v1/sessions` · `DELETE /api/v1/sessions/{id}`
Session registry. `POST` creates a session (`201 { "ok": true, "id": "..." }`).

### `GET /api/v1/events` (WebSocket)
Bidirectional events channel. Server pushes JSON messages:
- `{ "type": "connected", ... }` on connect
- `{ "type": "panel.created" | "panel.updated" | "panel.deleted", "payload": {...} }`
- `{ "type": "component.added" | "component.updated" | "component.deleted", ... }`
- `{ "type": "component.clicked", "payload": { "componentId": "...", "text": "..." } }` (button taps)
- `{ "type": "canvas.draw" | "panel.animate" | "server.started", ... }`

Inbound client messages are forwarded to the app layer as `ws.message` events.
Ping/pong handled per RFC 6455.

## Errors

| HTTP | `error` | When |
|---|---|---|
| 400 | `invalid_body` / `invalid_json` | Body missing or not a JSON object |
| 400 | `invalid_title` / `invalid_width` / `invalid_height` | Out-of-range panel fields |
| 400 | `invalid_component_type` | Unknown component type |
| 400 | `missing_ops` / `invalid_op` / `too_many_ops` | Bad draw request |
| 400 | `invalid_type` / `invalid_duration` | Bad animate request |
| 401 | `unauthorized` | Missing/invalid token |
| 404 | `panel_not_found` / `component_not_found` / `session_not_found` / `not_found` | Unknown id or route |
| 405 | `method_not_allowed` | Wrong method for an existing path |
| 409 | `panel_exists` | Duplicate panel id |
| 500 | `internal_error` | Unexpected failure (logged on device) |

## Security notes

- Server binds `127.0.0.1` only — never on the LAN.
- No remote server; no data leaves the device.
- Token generated on first launch, stored in private app storage, regenerable
  from the app's connection screen. Auth comparison is constant-time.
