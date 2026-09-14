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

## Future compiler ABI

This engine is a compatibility target for V1. R.O.P.E's compiled Rift ABI may later target a native UI/WASM/packaged-plugin engine. That compiler work must not undo the installer, volume, permission or native-window contracts established here.
