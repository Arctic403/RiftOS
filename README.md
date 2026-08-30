# RiftOS

RiftOS is an **unsigned, WebKit-hosted user-space operating environment** built around one RiftKernel runtime.

The primary runtime is RiftKernel running inside Apple WebKit on iPhone/iPad. Adding RiftOS to the Home Screen is a delivery/launch mechanism; it is not a reduced runtime and does not require RiftOS to ship a signed native executable.

## Current architecture

```text
iPhone / iPad
    |
Apple-signed WebKit host
    |
Home Screen web app / Safari delivery
    |
RiftOS shell
    |
RiftKernel
 |            |             |
RiftFS    RiftWorkspace   RiftBrowser
  |            |             |
 OPFS       JSON API      Engine Adapter
  |                          |
 IndexedDB                 Gecko WASM
 compatibility                |
 mirror                    canvas
                              |
                         Wisp transport
```

RiftKernel is **not the iOS kernel**. It is a user-space OS runtime implemented in JavaScript on top of WebKit capabilities. Apple still controls device-level privileges and signing for native executable code.

## Stable foundation

The stable RiftOS boundary is:

- RiftKernel process/app/service authority
- RiftFS backed primarily by Origin Private File System (OPFS)
- IndexedDB compatibility/migration storage
- RiftWorkspace JSON API
- service-worker/offline shell infrastructure
- Web Workers and web-platform capabilities when available
- RiftApps and RiftDev

RiftOS core and RiftWorkspace **MUST NOT require WebAssembly or native iOS code to boot**.

## RiftWorkspace

`src/riftworkspace-web.js` exposes the unsigned workspace boundary through `window.RiftWorkspace` and `window.RiftWorkspaceJSON`.

```text
RiftApp / AI bridge
        |
RiftWorkspace JSON API
        |
      RiftFS
        |
       OPFS
```

The workspace supports list/stat/read/write/mkdir/remove/move, snapshots, patch preview/apply, history and rollback. Guest browser pages do not receive direct RiftWorkspace or OPFS capabilities.

## RiftBrowser

RiftBrowser is currently an **experimental engine browser**, not a stable browser product.

```text
RiftKernel.browser
       |
RiftBrowser Engine Adapter
       |
Gecko compiled to WebAssembly
       |
     canvas
       |
Wisp WebSocket transport
```

The primary unsigned browser experiment is the MPL-2.0 `HeyPuter/firefox-wasm` `gecko.js` engine. Gecko is downloaded/staged during Pages deployment and lazy-loaded only when RiftBrowser is opened.

The old CORS fetch/sanitize/`srcdoc` browser transport has been removed. If Gecko is unavailable, RiftBrowser reports that no rendering engine is available and can hand the URL to the host browser; it does not pretend a CORS document fetch is a browser engine.

Arbitrary external networking from Gecko requires a configured Wisp endpoint. Browser-engine failure must not prevent RiftKernel, RiftFS, OPFS, RiftWorkspace or the rest of RiftOS from working.

## Optional native capability host

`native/ios/` remains optional for future capabilities such as a signed `WKWebView` browser surface or native filesystem integration.

Native Swift/ARM execution on a physical iPhone still requires Apple-authorized signing. The unsigned RiftKernel web runtime does not.

## GitHub Actions

`.github/workflows/riftos-pages.yml` is the primary product workflow. It validates the JS-first kernel/workspace architecture, stages the pinned Gecko WASM release, deploys the generated RiftOS artifact, and verifies the live engine manifest.

`.github/workflows/riftos-native-ios.yml` remains optional/manual-only.

## Architecture rules

1. RiftKernel and RiftWorkspace boot without WASM or native iOS code.
2. Gecko WASM is an optional, replaceable RiftBrowser service.
3. RiftBrowser does not contain a fake CORS/iframe browser backend.
4. Guest pages never receive raw RiftFS/OPFS workspace capability.
5. Wisp is transport for the guest engine, not the RiftKernel.
6. Native WebKit remains an optional signed capability adapter, not the definition of RiftOS.

See:

- `docs/TRUE_OS_ARCHITECTURE.md`
- `docs/RIFTBROWSER_ARCHITECTURE.md`
- `docs/RIFTWORKSPACE_WEB_ARCHITECTURE.md`
- `docs/UNSIGNED_WEBKIT_RUNTIME.md`
