# RiftBrowser

## Purpose

RiftBrowser is a RiftOS-owned desktop browser window. RiftOS owns window chrome and lifecycle; a replaceable native engine owns page rendering.

## Source ownership

- `src/riftos.js` `openBrowser()` — HTML chrome, address controls and synchronization with native bounds/state.
- `RiftBrowserWindow.kt` — native renderer container, geometry, visibility and engine lifecycle.
- `RiftBrowserEngine.kt` — renderer interface.
- `AndroidWebViewBrowserEngine.kt` — current engine.
- `RiftBrowserMcpAppBridge.kt` + browser compatibility assets — exact-origin AI/MCP integration.
- `src/riftdesktop-android.js` — window visibility/focus signals.

## Ownership split

RiftOS HTML owns title/address/taskbar chrome, desktop move/resize/minimize/maximize and user-visible window state. `RiftBrowserWindow` owns the native content surface rectangle and whether that surface is attached/visible. `RiftBrowserEngine` owns navigation/rendering implementation.

This split is intentional so Android System WebView can later be replaced without rewriting the desktop contract.

## Runtime flow

```text
openBrowser()
  -> create desktop window/chrome
  -> browser.window.open via native bridge
  -> RiftBrowserWindow.open()
  -> engine.loadUrl()
  -> engine state callback
  -> shell updates address/loading controls

Resize/focus/minimize
  -> JS syncBounds/visibility event
  -> browser.window.bounds/visible
  -> RiftBrowserWindow positions or hides native surface
```

The native host also keeps `browser.window.state` as an intentional diagnostic/query command even though normal shell updates arrive through the pushed browser-state callback. The obsolete dispatcher-only `browser.open` alias was removed; browser opening goes through the `browser.window.*` host surface.

## Critical invariants

- The native renderer is not a second full-screen activity.
- Hidden/minimized/unfocused/show-desktop browser surfaces become `View.GONE`; session state may remain allocated.
- Browser guest content never receives general `RiftAndroid`/RiftFS authority.
- Exact-origin MCP integration stays behind a separate WebMessage bridge.
- Browser engine swap must not change MCP tool schemas or desktop window APIs.

## Failure signatures

- Page renders outside/over chrome -> native bounds calculation/sync.
- Invisible browser steals taps -> native surface was not hidden.
- Back/forward/address state wrong -> engine state callback or shell `updateState`.
- Auth popup/file chooser/download issue -> WebView engine.
- Local MCP fails only inside ChatGPT Web -> browser MCP bridge/compat asset, not browser rendering generally.

## Fix map

Window/native-surface lifecycle -> `RiftBrowserWindow`.
Web rendering/network/auth/download -> engine.
HTML chrome/desktop synchronization -> `openBrowser()` in `riftos.js`.
ChatGPT tool-loop behavior -> browser MCP compatibility subsystem.

## Validation

Test navigation, redirect/auth popup, file chooser, downloads, back/forward/reload, resize, minimize/restore, show desktop, background/foreground and renderer crash handling. Confirm no guest page can call the general native dispatcher.
