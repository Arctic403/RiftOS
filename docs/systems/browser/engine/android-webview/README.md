# Android WebView Browser Engine

## Purpose

`AndroidWebViewBrowserEngine` is the current concrete RiftBrowser renderer backend. It implements the renderer-neutral `RiftBrowserEngine` contract using Android System WebView while keeping WebView-specific behavior out of RiftBrowser window ownership.

## Source ownership

Primary source: `android/app/src/main/java/com/riftos/app/AndroidWebViewBrowserEngine.kt`.

Parent contract: [`../README.md`](../README.md).

## Responsibilities

The backend owns WebView creation/configuration, HTTPS navigation, back/forward/reload, cookies, popup/auth flows, file chooser delegation, downloads, page progress/title state, render-process failure detection, external scheme handoff and exact-origin installation of the browser MCP compatibility bridge.

It also owns WebView security defaults: mixed content blocked, SSL errors cancelled, arbitrary file/content access disabled, Safe Browsing where supported, and permission requests denied unless a future explicit broker replaces that policy.

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
- Popup WebViews are temporary and cleaned up.
- `destroy()` releases popup, bridge and WebView resources.
- Renderer crashes must be surfaced rather than silently leaving stale state.
- Authentication/cookie behavior must not be mixed into desktop-window code.

## Failure signatures

- Blank page after renderer death -> render-process-gone handling.
- OAuth/login loops -> popup redirect or cookie policy.
- File chooser/download fails -> WebChromeClient/download callbacks or MainActivity delegation.
- External link does nothing -> external scheme handoff.
- ChatGPT renders but Rift MCP compatibility is absent -> exact-origin bridge installation/lifecycle.

## Fix map

WebView settings, cookies, auth popup behavior, download/file chooser callbacks, navigation and renderer failure belong here. Window bounds/visibility belong in `RiftBrowserWindow`; tool execution belongs in Rift MCP.

## Validation

Exercise HTTPS navigation, back/forward/reload, OAuth-style popup flows, cookies, file chooser, downloads, SSL cancellation, external schemes, renderer process loss, pause/resume and exact-origin MCP injection after changes.

## Safe extension points

Add WebView-specific compatibility behavior only when it is required by the renderer backend. Renderer-neutral behavior belongs in the engine contract or RiftBrowser owner instead.