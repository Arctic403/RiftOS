# RiftRT native-webview Engine

## Purpose

`native-webview` is the V1 Android renderer for installed HTML/JS-compatible RiftOS programs. The name describes the renderer implementation, not the old web-desktop model: the renderer is a dedicated Android `WebView` View attached directly to a native RiftDesktop WindowRecord.

There is no iframe and no guest app DOM inside the trusted shell WebView.

## Runtime path

```text
C:/Programs/<id>/package.json
  -> RiftRT launch(native-webview)
  -> native RiftDesktop window
  -> app.runtime.open
  -> RiftNativeAppHost
  -> dedicated Android WebView
  -> RiftNativeDesktop.attachContent
```

The WebView gets the fixed local origin `https://app.riftos.local/<app-id>/`. Local package assets are served from the installed package object; file/content URI access is disabled. External network loading starts blocked and can only become available when the installed manifest declares `network` and the user grant exists/is approved.

## Bridge

A single `RiftNativeApp` WebMessage endpoint provides the bounded Rift API. Supported V1 families are app lifecycle/title/info, app storage, permission request, bounded RiftFS text/list operations, clipboard, share and the bounded RiftBuild controller. No generic `native.call`, shell, MCP or reflection bridge is exposed.

## Security invariants

- Fixed origin and app id must match the installed package.
- Entry/asset and RiftFS paths reject traversal.
- CSP denies frames/object embedding and network by default.
- Bridge messages and text operations are bounded.
- AppData stays on D: and program payload stays on C:.
- Generic app filesystem grants cannot rewrite C: or another program's AppData; writes are restricted to approved D: user/project roots and the caller's own AppData.
- The renderer is destroyed when the native program session closes.

## Source ownership

- `src/riftrt.js` — selects/launches `native-webview`, owns RiftRT process/session lifecycle and calls the bounded native app-runtime routes.
- `android/app/src/main/java/com/riftos/app/RiftNativeAppHost.kt` — dedicated Android WebView, local package origin/asset serving, permission checks and bounded guest API.
- `android/app/src/main/java/com/riftos/app/RiftNativeDesktop.kt` — native WindowRecord and content-view attachment/focus/geometry lifecycle.
- `android/app/src/main/java/com/riftos/app/RiftVolumePaths.kt` — native C:/D: resolver used by app filesystem boundaries.

## Failure signatures

- program window exists but renderer is blank/missing -> app-runtime open/host construction/content attachment failed.
- app appears inside shell DOM or an iframe -> native-webview boundary regressed.
- local package assets 404 while install metadata exists -> fixed-origin asset resolver or package path validation drifted.
- network works before a declared/granted `network` capability -> WebView default-deny regression.
- app can write C:/Programs, C:/ProgramData, another app's AppData, or escape an approved D: root -> native filesystem containment regression.
- closing/minimizing/restoring leaves the Android renderer visible or alive incorrectly -> `RiftNativeDesktop` content-view lifecycle mismatch.
- a guest WebView renderer dies and the entire RiftOS Activity/process exits -> `RiftNativeAppHost.onRenderProcessGone()` isolation regressed; renderer loss must close only the affected installed-program surface and be recorded in app-runtime state.

## Fix map

Renderer creation, CSP/origin, asset interception, WebMessage API and app filesystem containment -> `RiftNativeAppHost.kt`.
RiftRT route/lifecycle selection -> `src/riftrt.js`.
Window/content attachment and z-order -> `RiftNativeDesktop.kt`.
Drive mapping -> `RiftVolumePaths.kt` plus RiftFS contract; do not invent a second path map in the renderer.

## Validation

Install and launch a V1 package; verify the dedicated Android WebView is attached to the native WindowRecord and never the shell DOM. Exercise local asset loads, denied/granted network, own AppData read/write, approved D: roots, denied C:/system and foreign-AppData writes, clipboard/share/build-controller permissions, minimize/restore/focus/resize/close, and repeated launch/dispose without leaked renderer state. Force or simulate renderer loss and verify `onRenderProcessGone()` returns handled, removes/destroys only that guest surface, closes its native window, records `lastRendererCrash`, and leaves the RiftOS shell/MCP runtime alive.

## Future compiler ABI

This engine is a compatibility target for V1. R.O.P.E's compiled Rift ABI may later target a native UI/WASM/packaged-plugin engine. That compiler work must not undo the installer, volume, permission or native-window contracts established here.
