# RiftBrowser Architecture

## Current decision

RiftBrowser on Android uses **Android System WebView as a native content surface inside a RiftOS desktop window**.

```text
RiftDesktop browser window
        |
RiftOS title/address/taskbar chrome
        |
 browser.window.* native bridge
        |
   RiftBrowserWindow
        |
 Android System WebView
        |
      guest page
```

RiftOS owns window state and browser chrome. Android owns page rendering, cookies, downloads, file selection and WebView lifecycle.

## Window behavior

The browser is not a full-screen Activity. `src/riftos.js` creates the browser window and synchronizes its content rectangle/visibility with `RiftBrowserWindow` through native bridge methods including open, navigate, back, forward, reload, bounds, visible, state and close.

This keeps RiftBrowser aligned with Files, Settings, RiftDev and RiftRT windows.

## Security defaults

- JavaScript and DOM storage are enabled for normal web compatibility.
- mixed content remains blocked,
- Safe Browsing is enabled where supported,
- SSL errors are cancelled,
- camera/microphone/geolocation are not automatically granted,
- arbitrary Android file/content access is not exposed,
- external `mailto`, `tel` and `geo` schemes are handed to Android.

## ChatGPT behavior

ChatGPT gets normal browser compatibility plus the **Rift MCP App compatibility adapter** on the exact `https://chatgpt.com` and `https://www.chatgpt.com` main-frame origins.

The adapter receives no filesystem object and no general native dispatcher. Its only native surface is `RiftMcpNative`, an exact-origin WebMessage endpoint that accepts MCP JSON-RPC and terminates at the in-process `RiftMcpServer` / `RiftToolHost` policy boundary.

At startup it performs `initialize` and `tools/list`, then publishes the live manifest to ChatGPT conversation context. A strict `<rift_call>...</rift_call>` envelope is translated to `tools/call`, and the structured result is fed back into the conversation.

Because ChatGPT plans without custom MCP registration do not expose a supported local tool API, this compatibility layer still depends on the ChatGPT composer and semantic rendered-message attributes. That dependency is isolated to `riftbrowser-mcp-app.js`; it does not own permissions, filesystem execution or audit.

RiftBrowser does **not** inject `RiftSandboxFS`, expose the sandbox directly, reuse the old fenced `rift-tool` protocol, or revive Agent V1/V2/V3.

Identity providers may still reject embedded WebView authentication independently.

## AI tool integration

`RiftToolHost` is the single device-side authority for both browser compatibility and remote MCP adapters. It owns canonical schemas, read/write grants, sandbox routing and audit logging.

Write tools are disabled by default. The ChatGPT page cannot override device policy.

## Removed browser paths

The following are historical and not current Android backends:

- full-screen `RiftBrowserActivity`,
- ChatGPT DOM Rift Agent V1/V2/V3,
- exact-origin `RiftSandbox` / `RiftSandboxFS` filesystem bridge,
- fenced `rift-tool` packets and V3 response-node tracking,
- WebKit-WASM/Wisp RiftBrowser,
- Gecko WASM experiments,
- CORS fetch/sanitize/iframe browser emulation.

There is one active Android browser renderer: Android System WebView.
