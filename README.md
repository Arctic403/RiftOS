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
 OPFS       JSON API      RiftWebKit
  |                          |
 IndexedDB            WebCore + JSC + Skia
 compatibility                |
 mirror                      WASM
                              |
                         canvas + Wisp
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

RiftOS core and RiftWorkspace **MUST NOT require the RiftWebKit WASM browser engine to boot**.

## RiftWorkspace

`src/riftworkspace-web.js` exposes the unsigned workspace boundary through `window.RiftWorkspace` and `window.RiftWorkspaceJSON`.

The workspace supports list/stat/read/write/mkdir/remove/move, snapshots, patch preview/apply, history and rollback. Guest browser pages do not receive direct RiftWorkspace or OPFS capabilities.

## RiftBrowser

RiftBrowser is now a **WebKit-only browser path**.

```text
RiftKernel.browser
       |
RiftWebKit Mobile host
       |
WebCore + JavaScriptCore + Skia
       |
 WebAssembly canvas
       |
Wisp WebSocket transport
```

The engine port is pinned to `theogbob/WebkitWasm` and the exact WebKit/Emscripten commits recorded by the RiftWebKit workflows. The proven `riftwebkit-poc-v1` artifact is the compile/runtime safety baseline. The full `riftwebkit-mobile-v1` build adds real URL loading, guest JavaScript, networking, cookies/storage and persistence while remaining single-threaded for the initial iPhone target.

The old Gecko WASM route and the signed native browser backend are retired. The old CORS fetch/sanitize/`srcdoc` transport also remains removed.

Arbitrary external networking from the nested WebCore engine uses a configured Wisp endpoint. Browser-engine failure must not prevent RiftKernel, RiftFS, OPFS, RiftWorkspace or the rest of RiftOS from working.

## GitHub Actions

`.github/workflows/riftos-pages.yml` validates and deploys the WebKit-only RiftOS runtime.

`.github/workflows/riftwebkit-poc.yml` preserves the proven WebCore/JSC/Skia survival build.

`.github/workflows/riftwebkit-mobile.yml` builds the full single-threaded mobile RiftWebKit engine package and publishes it for Pages to consume.

## Architecture rules

1. RiftKernel and RiftWorkspace boot without the browser WASM engine.
2. RiftBrowser has one engine ID: `riftwebkit`.
3. RiftBrowser does not contain Gecko, a signed native browser fallback, or a fake CORS/iframe browser backend.
4. Guest pages never receive raw RiftFS/OPFS workspace capability.
5. Wisp is transport for the guest engine, not RiftKernel authority.
6. Heavy engine artifacts load lazily.

See:

- `docs/TRUE_OS_ARCHITECTURE.md`
- `docs/RIFTBROWSER_ARCHITECTURE.md`
- `docs/RIFTWORKSPACE_WEB_ARCHITECTURE.md`
- `docs/UNSIGNED_WEBKIT_RUNTIME.md`
