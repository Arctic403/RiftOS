# RiftBrowser Architecture

## Current decision

RiftBrowser on Android currently uses **Android System WebView as a compatibility content surface inside a RiftOS desktop window** while RiftEngine/Servo is developed as the long-term renderer.

```text
RiftDesktop browser window
        |
RiftOS title/address/taskbar chrome
        |
 browser.window.* native bridge
        |
   RiftBrowserWindow
        |
 Android System WebView (current compatibility renderer)
```

RiftOS owns window state and browser chrome. Android currently owns page rendering, cookies, downloads, file selection and WebView lifecycle.

## Window behavior

The browser is not a full-screen Activity. `src/riftos.js` creates the browser window and synchronizes its content rectangle/visibility with `RiftBrowserWindow` through native bridge methods including open, navigate, back, forward, reload, bounds, visible, state and close.

## Security defaults

- JavaScript and DOM storage are enabled for normal web compatibility.
- mixed content remains blocked,
- Safe Browsing is enabled where supported,
- SSL errors are cancelled,
- camera/microphone/geolocation are not automatically granted,
- arbitrary Android file/content access is not exposed,
- external `mailto`, `tel` and `geo` schemes are handed to Android.

## ChatGPT + local MCP

On exact ChatGPT HTTPS origins, RiftBrowser installs `riftbrowser-mcp-app.js` plus an exact-origin `RiftMcpNative` WebMessage channel. The page-facing layer can send only MCP JSON-RPC to the local `RiftMcpServer`; it does not receive a filesystem object or the general Android dispatcher.

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

`RiftToolHost` owns local read/write permissions and audit. There is no remote relay or pairing dependency.

Because some ChatGPT plans do not expose a supported local custom-MCP registration path, the browser compatibility asset still observes the composer and semantic assistant-message elements. This is isolated to the page adapter and is not the tool security boundary.

## Streaming performance rule

The compatibility layer must never scan the complete chat on every token/DOM mutation. It uses mutation-scoped message tracking, delayed batching, serialized calls and a compact one-shot tool manifest per conversation route. CI rejects the removed whole-chat scanner.

## Renderer migration

Android System WebView is no longer the design target. RiftEngine/Servo is the preferred future renderer. The MCP/tool host is renderer-independent; a renderer swap must not change tool schemas, grants, audit or sandbox behavior.

See `RIFTBROWSER_ENGINE_MIGRATION.md`.

## Removed browser paths

Historical only:

- full-screen `RiftBrowserActivity`,
- ChatGPT DOM Agent V1/V2/V3,
- direct `RiftSandbox`/`RiftSandboxFS` guest-page access,
- WebKit-WASM/Wisp browser,
- Gecko WASM experiments,
- CORS fetch/sanitize/iframe browser emulation.
