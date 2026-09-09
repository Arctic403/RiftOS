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

ChatGPT receives additional browser integration:

- persistent WebView cookies,
- authentication-flow handling for ChatGPT/OpenAI and common identity-provider hosts,
- same-tab handling for auth popups,
- exact-origin RiftSandbox WebMessage bridge,
- injected `RiftSandboxFS`,
- optional Rift Agent adapter.

Identity providers may still reject embedded WebView authentication independently.

## Rift Agent

Rift Agent is browser-side orchestration, not a native OpenAI tool registration. While enabled it intercepts the user's ChatGPT send action, adds a sandbox tool contract, parses rendered assistant code blocks, executes only the fixed local tool allowlist and posts tool results back into the same chat.

Agent-internal task wrappers, tool-call turns and tool-result turns are masked/hidden in the WebView UI so the visible conversation remains readable.

## Removed browser paths

The following are historical and not current Android backends:

- full-screen `RiftBrowserActivity`,
- WebKit-WASM/Wisp RiftBrowser,
- Gecko WASM experiments,
- CORS fetch/sanitize/iframe browser emulation.

There is one active Android browser renderer: Android System WebView.
