# RiftOS

RiftOS is a touch-first operating environment with one RiftKernel runtime and a native iOS host.

It runs in two modes:

- **Web/PWA mode** for the RiftOS shell, RiftDev, RiftFS and lightweight browser-state testing.
- **RiftOS Native** inside Swift + `WKWebView`, where RiftBrowser becomes a full custom iPhone browser powered by Apple WebKit.

## Current architecture

```text
RiftOS Desktop / RiftDev / RiftApps
                |
            RiftKernel
       _________|__________
      |         |          |
   RiftFS   Processes   BrowserService
      |                    |
 OPFS/IDB              RiftNative
                           |
                    Swift iOS host
                           |
                 RiftBrowser / WKWebView
                           |
                      Apple WebKit
```

RiftOS owns the browser chrome, tabs, kernel state, bookmarks, app integration and native bridge. Apple WebKit owns HTML, CSS, JavaScript, cookies, networking and page rendering.

There is no custom WebCore/JSC/WASM browser engine in the active repository anymore.

## RiftKernel

`src/riftcore.js` is the runtime authority for:

- boot state and versioning
- process/PID lifecycle
- application registry
- capability grants
- RiftNative bridge state
- mount table
- system information
- RiftFS

`src/riftbrowser-kernel.js` attaches `RiftKernel.browser` as the browser service. It owns logical tabs, history, bookmarks, URL normalization and backend selection.

The browser has only two execution backends:

1. `native-webkit` — the full browser inside RiftOS Native.
2. `web-transport` — a lightweight PWA fallback for kernel/UI testing when native WebKit is unavailable.

## RiftBrowser

The production browser lives in `native/ios/RiftOSNative/RiftBrowser.swift`.

Each tab owns a normal `WKWebView` with persistent website data. RiftBrowser provides:

- multi-tab browsing
- address/search input
- back/forward/reload
- popup handling
- persistent cookies/site data through WebKit
- desktop website mode by default
- a per-tab Desktop / Mobile website toggle
- normal HTTP/HTTPS browsing

Desktop mode uses WebKit's `preferredContentMode = .desktop`; the browser does not fake a desktop page by compiling another engine.

Browser tabs deliberately do **not** receive the privileged `riftNative` script-message handler. Ordinary websites, including ChatGPT, cannot call RiftWorkspace or native filesystem methods.

## RiftFS and RiftWorkspace

RiftFS prefers OPFS in web mode and keeps the old IndexedDB filesystem only as a compatibility mirror.

```text
/
├── home/
├── apps/
├── system/
└── mounts/
```

The native host creates `Documents/RiftWorkspace` and exposes it as the protected `/mounts/RiftWorkspace` mount. Trusted RiftOS code can use native list/read/write/move/remove methods and the JSON patch transaction/rollback API.

## RiftDev

RiftDev remains a pinned mirror of `Arctic403/Editor`. RiftOS adds its integration overlay only to the staged Pages copy. The Editor source repository is not modified by RiftOS.

## GitHub Actions

The active workflows are intentionally small:

- `.github/workflows/riftos-pages.yml` validates and deploys the web/PWA shell.
- `.github/workflows/riftos-native-ios.yml` generates the Xcode project and compiles both Simulator and unsigned physical-iPhone targets.

The old RiftEngine/JSC/WebCore build/promote workflows were removed with the custom engine source and prebuilt WASM files.

Signed App Store/TestFlight delivery is not configured in the active repo. The unsigned device build remains useful for compile validation, while Simulator artifacts can be used for cloud/simulator testing.

## Browser development rule

Do not add another browser engine to RiftOS unless the project explicitly changes direction again.

New browser features should be implemented at one of these layers:

```text
RiftKernel.browser        browser state / OS API
src/riftbrowser-ui.js     PWA diagnostics + fallback UI
RiftNative               trusted shell-to-native calls
RiftBrowser.swift         production browser behavior
WKWebView / Apple WebKit  web platform implementation
```

See `docs/RIFTBROWSER_ARCHITECTURE.md` and `docs/TRUE_OS_ARCHITECTURE.md`.
