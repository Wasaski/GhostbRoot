# GhostbRoot

Overlay control plane for Android: a local HTTP + WebSocket API in the APK,
driven from Termux with a Python SDK. No root, no custom ROM, no remote
server, no PC required.

## How it works

- The **APK** runs a loopback-only server (`127.0.0.1:8765`) with token auth.
  It renders overlay panels (WindowManager) and exposes a documented REST +
  WebSocket API (`docs/API.md`).
- The **Python SDK** (`python-sdk/`, stdlib-only) speaks that API from Termux.
- The APK works standalone; Termux is only needed when you want to drive it
  programmatically.
- **GitHub Actions** build debug + release APKs on every push, run unit
  tests and an emulator matrix, and publish a signed release (with SHA256)
  on version tags `v*.*.*`.

## Quick start

### 1. Install the APK
Download from the GitHub release (or the `ghostbroot-debug-apks` artifact
of a workflow run). Open the app:

- Tap **Start server** → note the host/port/token shown.
- Tap **Allow display over other apps** and grant it.

### 2. Install the SDK in Termux
```sh
pkg install python
pip install ./python-sdk
```

### 3. Drive it
```python
from ghostbroot import GhostbRoot

gb = GhostbRoot(host="127.0.0.1", port=8765, token="TOKEN")
gb.connect()
print(gb.status())

panel = gb.create_panel(title="Ghost Panel", width=340, height=450)
panel.button("Executar", id="run")
panel.show()
```

Connection info can be copied from the app screen ("Copy connection info").
Manual config only — no auto-discovery (spec §4).

## Features

- Panels: create / update / delete / show / hide, dp-sized, multi-panel.
- Components: `label`, `button`, `input`, `slider`, `switch`, `canvas`, `spacer`.
- Canvas: `clear`, `rect`, `circle`, `line`, `text`, `arc` (≤500 ops/request).
- Animations: `fade`, `slide`, `scale` (CanvasView + re-render).
- Events: WebSocket fan-out (`/api/v1/events`) including button taps.
- Sessions: create / list / delete.
- Errors: structured JSON, input always validated before touching the UI.

## API

Full contract: [`docs/API.md`](docs/API.md).
SDK reference & examples: [`python-sdk/README.md`](python-sdk/README.md).

## Portability (spec §1/§7/§8)

- `minSdk 26` (Android 8.0), `compileSdk 34`, `targetSdk 34`, JDK 17.
- Pure Kotlin — **no native libraries**, so ABI builds are trivial:
  per-ABI APK splits for `arm64-v8a` and `armeabi-v7a`.
- No third-party runtime dependencies: HTTP server on `java.net.ServerSocket`
  (loopback only), WebSocket implemented per RFC 6455, JSON via `org.json`.
- dp-based layout → adapts to density and orientation; no manufacturer APIs;
  no root; no external apps; no PC; no remote server.
- Fallbacks documented in [`docs/TESTING.md`](docs/TESTING.md): overlay
  denied = API keeps working (no render), unknown component = explanatory
  label, `bitmap` canvas op accepted but not rendered.
- Compatibility is **not** claimed for every device without tests — see the
  physical-device checklist in `docs/TESTING.md`.

## Build

Local (needs JDK 17 + Android SDK 34):
```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

CI: `.github/workflows/android-build.yml` (push / PR / `workflow_dispatch`).
Releases: push a tag `v1.0.0` → `release.yml` builds, signs (requires repo
secrets `ANDROID_KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`), verifies with `apksigner`, writes `SHA256SUMS.txt` and
publishes debug + signed release APKs. If signing secrets are missing the
release job **fails** — an unsigned APK is never published as trusted.

SDK tests: `PYTHONPATH=python-sdk/src python -m pytest python-sdk/tests -q`

## Project layout

```
app/                      Android Kotlin app (server, UI, models)
python-sdk/               Python SDK (stdlib only)
docs/                     API.md, TESTING.md
.github/workflows/        android-build.yml, release.yml
```

## License

MIT.
