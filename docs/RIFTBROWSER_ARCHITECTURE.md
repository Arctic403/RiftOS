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

ChatGPT receives only normal browser compatibility behavior:

- persistent WebView cookies,
- authentication-flow handling for ChatGPT/OpenAI and common identity-provider hosts,
- same-tab handling for auth popups,
- file chooser/download support provided by the browser host.

RiftBrowser does **not** inject `RiftSandboxFS`, a prompt wrapper, a DOM response parser or an Agent runtime into `chatgpt.com`. No guest page receives direct Rift filesystem access.

Identity providers may still reject embedded WebView authentication independently.

## AI tool integration

First-class AI tools are handled by the separate **Rift Bridge** system app. Rift Bridge owns device-side pairing, read/write grants, audit logging and the outbound connection to external adapters such as the MCP relay.

This keeps ChatGPT DOM structure completely outside the Rift tool execution path.

## Removed browser paths

The following are historical and not current Android backends:

- full-screen `RiftBrowserActivity`,
- ChatGPT DOM Rift Agent V1/V2/V3,
- exact-origin `RiftSandbox` WebMessage bridge injected into `chatgpt.com`,
- WebKit-WASM/Wisp RiftBrowser,
- Gecko WASM experiments,
- CORS fetch/sanitize/iframe browser emulation.

There is one active Android browser renderer: Android System WebView.
