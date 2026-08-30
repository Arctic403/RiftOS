# RiftBrowser Architecture

## Decision

RiftBrowser is a **native-first custom iOS browser built around Apple WebKit**.

RiftOS owns tabs, chrome, session state, kernel integration, downloads and OS actions. `WKWebView` supplies the web engine.

## Production path

```text
bundled RiftOS shell
       |
RiftKernel.browser
       |
riftBrowser command channel
       |
RiftBrowserStore
       |
RiftBrowserTabSession
       |
    WKWebView
       |
  Apple WebKit
```

Native state flows back in the opposite direction through `RiftBrowserKernelSync`, so the kernel can inspect the real Swift tab set rather than maintaining an unrelated fake tab model.

## Bundled shell

RiftOS Native does not load a privileged GitHub Pages document.

During the Xcode build, the root shell assets are copied into `RiftOSNative.app/Web`. `RiftBundleSchemeHandler` serves them through the local `riftos://` scheme and `RiftOSWebView` boots `riftos:///index.html`.

Main-frame web navigation is always diverted into RiftBrowser.

## Browser kernel API

`RiftKernel.browser` owns the OS-facing browser API:

- open / new tab
- navigate
- select / close tab
- back / forward
- reload / stop
- Desktop / Mobile mode
- share
- Find on Page
- bookmarks and lightweight kernel history
- native browser state inspection

In native mode those commands are sent through `RiftBrowserCommandBridge`. In the web development preview they fall back to the constrained `web-transport` implementation.

## Native tabs

Every real tab is a `RiftBrowserTabSession` containing its own `WKWebView`.

The browser currently supports:

- persistent default `WKWebsiteDataStore`
- Desktop Website mode on new tabs
- per-tab Mobile/Desktop override
- navigation gestures
- progress and back/forward state
- popup/new-window capture into new RiftBrowser tabs
- page error recovery UI
- WebKit download handling
- Find on Page
- Share sheet
- session restoration through `UserDefaults`

## Downloads

Downloads use `WKDownload` and are written directly into:

```text
RiftWorkspace/downloads/
```

Names are sanitized and collisions receive numbered filenames. The browser UI reports the saved filename after completion.

## Desktop-first rendering

New tabs set WebKit page preferences to `.desktop`. The navigation delegate also supplies the selected content mode for each navigation, so the setting is per-tab and persists when that tab is restored.

This is WebKit's supported content-mode mechanism; no separate desktop rendering engine is compiled.

## Security boundary

There are two different WKWebView roles:

1. **Trusted RiftOS shell** — local bundled `riftos://` content with the privileged RiftNative and browser command handlers.
2. **RiftBrowser tabs** — ordinary web content with no privileged RiftOS script handlers.

Never attach RiftNative, RiftWorkspace, JSON patch or browser-command handlers to normal website tabs.

## Web preview

GitHub Pages is a development target only. The `web-transport` backend can test kernel/UI behavior and some CORS-readable documents, but it is not the production browser.

## Removed direction

RiftEngine, custom WebCore/JSC WASM builds, Emscripten browser-engine tooling and prebuilt engine binaries are intentionally absent. Missing browser-product features should be added around WKWebView, not by reintroducing a second engine.
