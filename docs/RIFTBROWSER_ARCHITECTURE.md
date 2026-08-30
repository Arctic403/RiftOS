# RiftBrowser Architecture

## Decision

RiftBrowser is a **RiftKernel browser service hosted by Apple WebKit**.

The primary RiftOS runtime is unsigned web-delivered RiftKernel. A signed Swift app is optional and must not be treated as a prerequisite for the OS.

## Primary path

```text
Home Screen web app / Safari
          |
     Apple WebKit
          |
      RiftKernel
          |
RiftKernel.browser
          |
    web-transport
          |
WebKit security model
```

RiftOS owns browser state, URL normalization, tabs/history/bookmarks, browser UI and OS integration. The host browser owns standards rendering and security enforcement.

## What the unsigned browser can do

The WebKit-hosted browser service can:

- manage RiftBrowser tabs and logical history
- normalize addresses/searches
- persist browser state
- fetch/render CORS-readable HTML/text through the constrained web transport
- use top-level/external navigation when embedded transport is not allowed
- keep browser behavior integrated with RiftKernel processes/apps

## Hard web boundary

JavaScript running inside a Home Screen web app cannot create its own privileged arbitrary `WKWebView`.

Therefore RiftBrowser must respect:

- CORS
- CSP
- `frame-ancestors` / iframe restrictions
- origin isolation
- WebKit navigation/security policies

A site such as a login-heavy or frame-blocking service may need to open as normal top-level WebKit navigation instead of rendering inside the RiftBrowser document surface.

This is a host security boundary, not evidence that RiftKernel is "only a PWA".

## Runtime identity

`src/riftruntime.js` reports the normal unsigned state as:

```text
mode: riftkernel-webkit
host: Apple WebKit
delivery: home-screen-web-app
```

The browser backend may still report `web-transport`; that describes how RiftBrowser obtains page content, not the identity of the OS runtime.

## Optional native backend

The existing `native-webkit` backend remains optional for a future signed/native capability host:

```text
RiftKernel.browser
       |
riftBrowser bridge
       |
RiftBrowserStore
       |
WKWebView tabs
```

That optional backend can provide unrestricted normal `WKWebView` navigation, downloads and native browser chrome. It is not required for RiftKernel, RiftFS, RiftApps or the Home Screen operating environment.

## Desktop rendering

Desktop-site preferences such as `WKWebpagePreferences.preferredContentMode = .desktop` apply only to the optional native `WKWebView` backend.

The unsigned web runtime cannot force the containing iOS WebKit web app to impersonate a separate desktop browser engine for arbitrary third-party sites. RiftBrowser should instead provide responsive RiftOS chrome and let WebKit enforce the page's normal rendering/security rules.

## Security boundary

Normal third-party page content never receives privileged RiftOS capabilities merely because it is displayed or fetched by RiftBrowser.

RiftFS, OPFS, app permissions and kernel state remain scoped to the RiftOS origin/runtime.

## Removed direction

RiftEngine, custom WebCore/JSC WASM builds, Emscripten browser-engine tooling and large prebuilt engine binaries remain intentionally removed.

The rule is now:

```text
Use Apple WebKit as the host engine.
Build RiftKernel above it.
Never rebuild WebKit just to make RiftOS feel like an OS.
```
