# RiftBrowser Engine

## Purpose

The browser engine is the renderer abstraction behind RiftBrowser. `RiftBrowserEngine` defines the small lifecycle/navigation contract; `AndroidWebViewBrowserEngine` implements it using Android System WebView.

## Source ownership

- `RiftBrowserEngine.kt` — interface: `currentUrl`, `loadUrl`, back/forward capability/actions, `reload`, `state`, `onResume`, `onPause`, `destroy`.
- `AndroidWebViewBrowserEngine.kt` — WebView implementation; concrete backend guide: [`android-webview/README.md`](android-webview/README.md).

## Current WebView responsibilities

The Android engine configures WebView settings, cookies, normal navigation, popup/auth handling, file chooser delegation, downloads, SSL cancellation, external scheme handoff, render-process failure state, progress/state callbacks and exact-origin MCP bridge installation.

It denies WebView permission requests by default and does not automatically grant camera/microphone/geolocation. Arbitrary file/content access is disabled. Safe Browsing is enabled where supported and mixed content remains blocked.

## State flow

Engine `state()` exposes renderer-facing state consumed by `RiftBrowserWindow`/shell, including URL/navigation capability, title/progress and crash status. Page start/finish/history/progress callbacks trigger the supplied `stateChanged` callback.

## Why this boundary exists

RiftBrowser should be a RiftOS concept, not a synonym for WebView. Keeping WebView-specific code here gives a migration seam for Servo/Gecko/Chromium or another renderer while preserving desktop and MCP architecture.

## Critical invariants

- Never broaden guest WebView native access to solve application-layer issues.
- SSL errors are cancelled, not ignored.
- Permission prompts are denied unless an explicit future broker is designed.
- Popup/auth windows must be cleaned up and not become orphan native surfaces.
- Exact-origin MCP bridge installation remains origin-gated.
- `destroy()` must clean popup, bridge and WebView resources.

## Failure signatures

- Blank/crashed page after renderer death -> `onRenderProcessGone`/recreation policy.
- Login popup loops -> popup redirect/auth-flow logic/cookie policy.
- Download/file chooser unavailable -> engine callbacks + `MainActivity` delegation.
- ChatGPT opens but tools absent -> MCP bridge/tool catalog, not generic WebView navigation.

## Fix map

WebView setting/cookie/auth/download/navigation/render-process issues belong here. Window geometry belongs in `RiftBrowserWindow`. Desktop behavior belongs in RiftDesktop. Tool execution never belongs in the engine.

## Validation

Test standard HTTPS navigation, OAuth-style popup/redirect flows, file chooser, download manager, external `mailto/tel/geo`, SSL error cancellation, minimize/resume and renderer process failure. Re-run MCP exact-origin tests after changing origin/page lifecycle callbacks.

## Engine replacement checklist

A replacement engine must satisfy every `RiftBrowserEngine` method, provide equivalent state callbacks, fit the owned native renderer container, obey visibility/lifecycle, support required authentication/file flows, and preserve the exact-origin capability boundary without exposing general native APIs.
