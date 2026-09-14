# RiftBrowser Architecture

## Current decision

RiftBrowser is a **RiftOS-owned browser window and lifecycle**. Android System WebView is the current compatibility renderer, not the browser abstraction itself.

```text
RiftDesktop browser window
        |
RiftOS title/address/taskbar chrome
        |
 browser.window.* native bridge
        |
   RiftBrowserWindow
        |
 dedicated owned renderer surface
        |
   RiftBrowserEngine
        |
 AndroidWebViewBrowserEngine (current backend)
```

`RiftBrowserWindow` owns geometry, visibility, minimize/restore behavior, the renderer container and a bounded multi-tab registry. Each tab owns its own `RiftBrowserEngine` instance and therefore its own navigation history/title/render state. Only the selected tab's renderer can be visible/clickable; inactive tab engines are paused and kept `View.GONE`. `AndroidWebViewBrowserEngine` owns WebView-specific page rendering, cookies, downloads, auth popups and per-tab WebView lifecycle.

## Window behavior

The browser is not a full-screen Activity and the renderer is not a parallel full-host surface. `src/riftos.js` synchronizes the browser content rectangle with `RiftBrowserWindow` through open, navigate, back, forward, reload, tab.new, tab.select, tab.close, bounds, visible, state and close. One desktop browser window may own up to 8 live native tab engines; this cap intentionally bounds WebView memory pressure on low-end/32-bit Android.

Critical invariants are enforced by source validation: **exactly one selected tab renderer may be `View.VISIBLE`, and when RiftBrowser is minimized, hidden, unfocused, or Show Desktop is used, every tab renderer plus the native container is `View.GONE`.** Inactive engine objects stay allocated and paused so tab history/session state survives without an invisible native sibling stealing touch/focus or covering later windows.

Desktop minimize/restore emits `riftos:window-visibility` so the native renderer is hidden immediately instead of waiting for a delayed bounds pass.

## Engine contract

`RiftBrowserEngine` is the renderer boundary. The current implementation is `AndroidWebViewBrowserEngine`. A future Gecko/Servo/Chromium embedder can implement the same browser-facing lifecycle without changing the RiftOS desktop contract.

The compatibility backend currently provides:

- HTTPS navigation, back/forward/reload,
- cookies and auth popup handling,
- downloads and file chooser integration,
- renderer state/progress,
- exact-origin ChatGPT MCP injection,
- safe WebView defaults.

## Security defaults

- JavaScript and DOM storage are enabled for normal web compatibility.
- Mixed content remains blocked.
- Safe Browsing is enabled where supported.
- SSL errors are cancelled.
- Camera/microphone/geolocation are not automatically granted.
- Arbitrary Android file/content access is disabled.
- External `mailto`, `tel` and `geo` schemes are handed to Android.

## ChatGPT + local MCP

On exact ChatGPT HTTPS origins, the WebView compatibility backend installs `riftbrowser-mcp-app.js` plus the exact-origin `RiftMcpNative` WebMessage channel.

```text
ChatGPT Web
    |
riftbrowser-mcp-app.js
    |
RiftMcpNative WebMessage
    |
RiftBrowserMcpAppBridge
    |
RiftMcpServer
    |
RiftToolHost
    |
RiftToolSandbox
```

The page does not receive a filesystem object or the general Android dispatcher. `RiftToolHost` remains the device-side permission/audit authority.

## Streaming performance rule

The compatibility layer must never scan the complete chat on every token/DOM mutation. It uses mutation-scoped message tracking, delayed batching, serialized calls and a compact one-shot tool manifest per conversation route.

## Renderer migration

Android System WebView is a compatibility backend, not the long-term design target. The MCP/tool host must remain renderer-independent; a renderer swap must not change tool schemas, grants, audit or sandbox behavior.

See `RIFTBROWSER_ENGINE_MIGRATION.md`.

## Removed browser paths

Historical only:

- full-screen `RiftBrowserActivity`,
- full-host hidden/parked RiftBrowser WebView,
- Rift AI task/transport orchestration,
- ChatGPT DOM Agent V1/V2/V3,
- direct `RiftSandbox`/`RiftSandboxFS` guest-page access,
- WebKit-WASM/Wisp browser,
- Gecko WASM experiments,
- CORS fetch/sanitize/iframe browser emulation.
