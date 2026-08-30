# RiftBrowser Architecture

## Decision

RiftBrowser is a custom RiftOS browser built on **Apple WebKit**, not a custom WebKit/WebCore/JSC build.

RiftOS owns the browser product. Apple owns the rendering engine implementation provided by `WKWebView`.

## Production path

```text
RiftBrowser launcher / RiftShell
              |
       RiftKernel.browser
              |
          RiftNative
              |
      RiftBrowserStore
              |
   RiftBrowserTabSession
              |
           WKWebView
              |
         Apple WebKit
```

## Responsibilities

### RiftKernel.browser

Owns OS-level browser state and API:

- logical tabs
- active-tab selection
- URL/search normalization
- bookmarks
- lightweight history
- native-vs-PWA backend selection

### RiftBrowser.swift

Owns native browser behavior:

- real WebKit tabs
- address/search field
- back/forward/reload
- loading progress
- popup/new-window handling
- persistent website data
- desktop/mobile content mode

### Apple WebKit

Owns:

- HTML/CSS parsing
- DOM
- JavaScript execution
- layout/painting
- networking
- cookies/cache/site data
- normal web compatibility

## Desktop-first mode

Every new native RiftBrowser tab starts with:

```swift
configuration.defaultWebpagePreferences.preferredContentMode = .desktop
```

The browser chrome exposes a per-tab Desktop / Mobile toggle. Switching mode updates WebKit's content preference and reloads the current page.

This is the supported WebKit mechanism used instead of maintaining a desktop-UA emulation engine.

## Security boundary

The privileged `riftNative` handler is installed only in the trusted RiftOS shell WKWebView.

RiftBrowser tabs are ordinary website contexts and cannot directly access:

- RiftWorkspace
- native mounts
- JSON patch APIs
- clipboard/native notification methods through RiftNative
- other privileged shell capabilities

Any future website-to-OS integration must go through an explicit permission/broker design; do not inject unrestricted filesystem bridges into browser tabs.

## PWA fallback

GitHub Pages cannot embed every website because the host browser still enforces CORS, CSP and frame restrictions.

`web-transport` exists only so the PWA build can exercise the browser kernel, tabs, bookmarks and UI. It may render safe CORS-readable text/HTML documents or send the destination to the external browser.

It is not a replacement for native RiftBrowser.

## Removed architecture

The following are no longer part of RiftOS:

- RiftEngine browser backend
- custom WebCore-to-WASM port
- custom JSC WASM browser runtime
- Emscripten browser-engine build chain
- prebuilt JSC/WebCore WASM artifacts
- engine promotion workflows

If browser compatibility is missing, fix the RiftBrowser/WKWebView integration first rather than starting another engine port.

## Next browser features

Build new features around the existing WebKit browser:

- persistent native tab/session restoration
- native bookmarks/history storage
- downloads into RiftWorkspace
- share/open-in actions
- per-site desktop/mobile preference
- content blockers/privacy controls
- search-engine settings
- find-in-page
- tab groups/private profile if needed

These are browser-product features and do not require compiling WebKit.
