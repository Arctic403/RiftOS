# Installed Program Native WebView Engine

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

This is the current live HTML/JavaScript installed-program engine.

It is implemented by `RiftBrowserAppHost.kt`, not by the retained `src/riftrt.js` `native-webview` branch.

## Source ownership

- `RiftBrowserAppHost.kt` — renderer, origin, bridge, capability/filesystem policy, lifecycle.
- `RiftNativeDesktop.kt` — outer window visibility/geometry.
- `MainActivity.kt` — installed-program dispatch.

## Activation

Launcher dispatch for a non-built-in app calls:

`browserAppHost.open({appId, windowId: appId})`

The host creates one managed Android WebView and attaches it directly to the native Desktop window.

No shell DOM, iframe, RiftRT manager, or retained app runner is involved.

## Isolation

Each app derives its own deterministic HTTPS origin from SHA-256(app id).

The WebMessage listener is exact-origin/main-frame.

Third-party cookies are disabled.

DOM storage, file access and content access are disabled.

External top-level navigation is blocked.

## Network

Network load starts blocked unless the app both declares and already has a saved `network` grant.

A user grant can enable network loads for the current instance.

CSP never permits external scripts merely because network is enabled.

## Native authority

The only app/native API is the finite `RiftNativeApp` message bridge.

Capabilities are declaration + user-grant gated.

App filesystem access is confined to its own installed files/AppData and approved D: data roots, with no C:/Programs writes.

## Lifecycle

Managed WebView lifecycle follows:
- Activity resume state;
- native parent attachment;
- actual View visibility.

A minimized/hidden app is paused.

Renderer loss is handled by recording/destroying the dead renderer and closing its native window; native RiftOS remains alive.

## Non-ownership boundaries

This engine does not install/update/uninstall packages and does not implement Worker/WASM/RiftVM selection.

## Critical invariants

- live owner remains RiftBrowserAppHost;
- per-app origins remain distinct;
- only the app's exact origin receives bridge authority;
- hidden renderer stays paused;
- no iframe/shell/RiftRT manager path;
- package filesystem/capability policy remains bounded.

## Failure signatures

- app runs through retained `riftrt.js` -> activation regression;
- two apps share origin -> isolation regression;
- minimized app stays resumed -> lifecycle regression;
- app bridge appears on arbitrary HTTPS page -> origin regression.

## Fix map

Renderer/security/capability behavior -> `RiftBrowserAppHost.kt`.

Window behavior -> `RiftNativeDesktop.kt`.

## Validation

Second source audit must prove MainActivity dispatch, AppHost construction, per-app origin/message listener, WebView settings, lifecycle and absence of a retained RiftRT caller.

Device proof remains part of the Apps installed-program validation gate.
