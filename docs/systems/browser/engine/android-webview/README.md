# Android WebView Browser Engine

## Purpose

`AndroidWebViewBrowserEngine` is the current concrete RiftBrowser renderer backend. It implements the renderer-neutral `RiftBrowserEngine` contract using Android System WebView while keeping WebView-specific behavior out of RiftBrowser window ownership.

## Source ownership

Primary source: `android/app/src/main/java/com/riftos/app/AndroidWebViewBrowserEngine.kt`.

Parent contract: [`../README.md`](../README.md).

## Responsibilities

The backend owns WebView creation/configuration, HTTPS navigation, back/forward/reload, cookies, popup/auth flows, file chooser delegation, downloads, page progress/title state, render-process failure detection, external scheme handoff, per-tab Desktop Site request identity, the bounded temporary live-page inspector, and exact-origin installation of the browser MCP compatibility bridge.

It also owns WebView security defaults: mixed content blocked, SSL errors cancelled, arbitrary file/content access disabled, Safe Browsing where supported, and permission requests denied unless a future explicit broker replaces that policy.

Desktop Site mode captures the WebView's normal user agent and supported UA metadata at engine creation. When enabled, the engine uses a desktop Chromium/Windows UA, non-mobile Windows client hints plus the Desktop form factor where supported, wide/overview layout and hidden built-in zoom controls; the active URL is then reloaded in place without adding a duplicate navigation-history entry. Disabling restores the captured Android WebView identity. Popup/auth WebViews inherit the owning tab's current mode.

User-initiated popups from an HTTPS page are rendered as a real child WebView inside the owning engine surface. The child remains connected through Android `WebViewTransport`, so OAuth providers can use normal `window.opener` / `window.close` behavior and shared WebView cookies. The popup is bounded to the RiftBrowser renderer plane, carries an explicit native close control, keeps SSL/permission/geolocation restrictions, upgrades plain HTTP to HTTPS, and is destroyed when the tab/engine is destroyed or Desktop Site mode changes. Popups are no longer redirected invisibly into the parent tab.

## Live-page inspector

The active HTTPS tab supports a temporary inspector used by the trusted `riftos-agent browser-inspect` shell family. It can enumerate visible structural metadata (tag, safe id/classes, role/type, bounds and computed layout properties), inspect/focus a safe selector, temporarily hide/show elements, replace non-form text, change a narrow safe attribute set, apply one whitelisted inline style property, toggle a visual outline, and reset all changes.

The inspector deliberately does **not** expose `value`, `textContent`, `innerHTML`, cookies, storage, request/response headers, hidden credential text, arbitrary JavaScript, raw stylesheet injection, attribute/value selectors, `:has()` selector probing, or network-bearing CSS constructs. Mutations are tab-local and page-local: reload/navigation/Desktop Site changes destroy them automatically; `reset` restores the stored inline style/text/attribute state without reloading.

## What it does not own

It does not own desktop geometry, minimize/maximize/taskbar state, MCP tool schemas or execution, workspace permissions, or generic Android capability dispatch. Those belong to RiftBrowserWindow/RiftDesktop, Rift MCP, and the native dispatcher respectively.

## Runtime flow

```text
RiftBrowserWindow
  -> RiftBrowserEngine contract
  -> AndroidWebViewBrowserEngine
  -> Android System WebView
  -> page callbacks / exact-origin MCP bridge
```

State callbacks propagate URL, title, progress, navigation capability and crash status back through `RiftBrowserWindow` to the shell.

## Critical invariants

- WebView guest content never receives the generic native dispatcher.
- Exact-origin MCP injection remains origin-gated.
- Popup WebViews are temporary, visible only inside the owning renderer surface, retain opener linkage, and are cleaned up.
- `destroy()` releases popup, bridge and WebView resources.
- Renderer crashes must be surfaced rather than silently leaving stale state.
- Authentication/cookie behavior must not be mixed into desktop-window code.
- Desktop Site must remain per engine/tab and restore the captured WebView identity when switched off.
- UA Client Hint overrides are feature-gated; unsupported WebView builds fall back to the desktop UA/viewport path rather than failing navigation.

## Failure signatures

- Blank page after renderer death -> render-process-gone handling.
- OAuth/login popup never appears -> `onCreateWindow` / visible popup-host ownership or user-gesture gating.
- OAuth/login completes but the opener does not update -> popup opener linkage, cookie policy or provider restrictions.
- File chooser/download fails -> WebChromeClient/download callbacks or MainActivity delegation.
- External link does nothing -> external scheme handoff.
- ChatGPT renders but Rift MCP compatibility is absent -> exact-origin bridge installation/lifecycle.

## Fix map

WebView settings, cookies, auth popup behavior, download/file chooser callbacks, navigation and renderer failure belong here. Window bounds/visibility belong in `RiftBrowserWindow`; tool execution belongs in Rift MCP.

## Validation

Exercise HTTPS navigation, back/forward/reload, OAuth-style popup flows, cookies, file chooser, downloads, SSL cancellation, external schemes, renderer process loss, pause/resume and exact-origin MCP injection after changes. Verify Desktop Site changes `navigator.userAgent` away from Android/mobile, reports non-mobile Windows/Desktop client hints when supported, affects popups/download requests consistently, and restores the captured mobile identity when disabled.

## Safe extension points

Add WebView-specific compatibility behavior only when it is required by the renderer backend. Renderer-neutral behavior belongs in the engine contract or RiftBrowser owner instead.