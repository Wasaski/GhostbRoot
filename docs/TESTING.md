# GhostbRoot — Portability & Testing (spec §9)

## What CI runs

`.github/workflows/android-build.yml`:
- Unit tests (`RouterTest`) on JVM — API contract, validation ranges,
  panel/component lifecycle, auth, drawing-op validation, sessions.
- APK build (debug + release) on every push / PR / manual dispatch.
- Emulator smoke matrix: API 26, 29, 33, 35 (pixel_6, google_apis, x86_64)
  via `reactivecircus/android-emulator-runner` with the AndroidJUnit4
  smoke test. The matrix is `fail-fast: false`, so one API failure does
  not hide the others. If an emulator cannot start in a runner, the step
  fails and logs show the failure — CI never reports success on an
  untested entry point.

`python-sdk/tests/test_client.py` runs anywhere with Python 3.8+ and
exercises the SDK against a faithful in-process mock of `docs/API.md`.

## What CI cannot run (physical-device checklist)

Run this checklist on at least one real device per ABI (`arm64-v8a`,
`armeabi-v7a`) before claiming support for a device family. The app has
no manufacturer-specific code by design (pure Kotlin, WindowManager,
stdlib sockets), but OEM overlay/background-kill behavior varies — test it:

1. **Android 8 (API 26)** — install, start server, create a panel from the
   Python SDK, verify token auth rejects a wrong token (401).
2. **Recent Android (API 34+)** — same flow; verify foreground-service
   notification appears and server survives app backgrounding.
3. **Orientation** — rotate portrait → landscape with a panel open.
   Expect: no crash, panel re-renders in dp (dp layout adapts; window
   position may reset to the default when re-rendered).
4. **Density** — mdpi and xxxhd devices: panels scale in dp; text must
   remain readable at 340dp width.
5. **Overlay permission denied** — deny "Display over other apps":
   API calls must still succeed (200/201); panels simply don't render.
   Re-grant and restart the server: panels render again.
6. **Overlay permission granted** — panels render, drag works, close via
   `DELETE /api/v1/panels/{id}` removes the window.
7. **Service killed by system** — background the app, force-stop, relaunch:
   server restarts (`START_STICKY`), panel state is per-process (documented
   limitation — state does not persist across force-stop).
8. **Python reconnect** — stop the server, restart it, reconnect the SDK:
   `gb.connect()` must succeed with the same token; old WebSocket clients
   must not break new ones.
9. **WebSocket disconnect** — register a WS listener, kill/reconnect it:
   `EventBus` must keep broadcasting to remaining clients without errors.
10. **Multiple panels** — create ≥3 panels with different sizes, resize one,
    delete one; verify others are unaffected.
11. **Multi-ABI** — install the `arm64-v8a` APK on an ARM64 device and the
    `armeabi-v7a` APK on a 32-bit ARM device. No `.so` files are bundled;
    expect identical behavior.
12. **No Termux installed** — app must work standalone; the connection
    screen reports "Termux: not detected" and the API is unaffected.

## Compatibility statement

Per spec §1: GhostbRoot does **not** claim compatibility with every device.
Build config is `minSdk 26`, `targetSdk 34`, pure Kotlin, no native code,
no root, no custom ROM, no manufacturer APIs, no external apps beyond
Termux (optional), no PC, no remote server. Fallbacks: overlay-denied is
a silent no-op (state retained), unknown canvas ops are rejected at the
API layer, unknown component types render an explanatory label instead of
crashing.
