# RiftOS True OS Architecture

## Decision

RiftOS is **RiftKernel first**, not native-app first and not browser-engine first.

The primary iPhone/iPad runtime is RiftKernel executing inside Apple WebKit. Safari/Home Screen web-app delivery does not require RiftOS to provide a separately signed native executable because RiftKernel is web content hosted by Apple's signed browser process.

This does **not** mean RiftOS replaces or bypasses the iOS kernel. Native executable code and native device privileges remain controlled by iOS and Apple's code-signing rules.

```text
iPhone / iPad
    |
Apple WebKit
    |
Home Screen web app / browser delivery
    |
RiftOS shell
    |
RiftKernel
 |             |              |
RiftFS     RiftWorkspace    RiftBrowser
  |             |              |
 OPFS        JSON API       RiftWebKit WASM
  |                            |
 IndexedDB                WebCore/JSC/Skia
 compatibility                  |
 mirror                     canvas + Wisp
```

## 1. One kernel

`src/riftcore.js` owns process lifecycle, app registration, permissions, mounts, system information and RiftFS.

No delivery mechanism or browser engine is allowed to become a second kernel. Running from a Home Screen icon or loading RiftWebKit changes available services, not RiftKernel authority.

## 2. Runtime vs delivery

`src/riftruntime.js` normalizes the unsigned runtime identity:

```text
mode = riftkernel-webkit
host = Apple WebKit
delivery = home-screen-web-app | browser-tab
appSigningRequiredForKernel = false
```

PWA/web-app technology is a delivery and lifecycle mechanism, not the OS architecture.

## 3. RiftFS is OPFS-first

RiftFS uses Origin Private File System when `navigator.storage.getDirectory()` is available. IndexedDB remains a compatibility mirror and migration store.

OPFS is origin-private. RiftOS can create, enumerate, read and write its own files/directories without claiming access to arbitrary iPhone files.

## 4. RiftWorkspace is the JSON sandbox boundary

`src/riftworkspace-web.js` exposes controlled workspace operations over RiftFS/OPFS. The workspace is independent of the browser engine and must continue working even if RiftWebKit fails or is never loaded.

## 5. WebKit host primitives

RiftKernel may use standards exposed by WebKit, including OPFS, Web Workers, Service Workers, Cache Storage, IndexedDB, Web Share, notifications and WebAssembly when available.

Feature detection is mandatory. A host capability being unavailable must degrade only the service that depends on it.

## 6. Processes and apps

Built-ins, RiftDev sessions and installed RiftApps receive RiftKernel process records. Protected kernel/system processes remain controlled by the process table.

RiftApps use brokered capabilities and RiftFS paths instead of receiving native iOS privileges.

## 7. RiftBrowser is one optional engine service

`src/riftbrowser-kernel.js` installs `RiftKernel.browser`. The only active browser engine is RiftWebKit: WebCore, JavaScriptCore and Skia compiled to WebAssembly.

```text
RiftKernel.browser
       |
RiftWebKit Mobile
       |
WebCore + JSC + Skia WASM
       |
canvas + Wisp networking
```

The engine is lazy-loaded. RiftOS core MUST NOT require WebAssembly to boot.

The Gecko experiment, signed native browser host and legacy CORS transport are not active backends and must not be reintroduced as fallbacks.

Guest pages rendered by RiftWebKit do not receive direct RiftWorkspace, RiftFS or OPFS capabilities.

## 8. Networking

Browser JavaScript cannot expose arbitrary raw TCP sockets to the nested WebCore engine. RiftBrowser therefore uses a Wisp WebSocket endpoint for arbitrary guest HTTP/HTTPS networking.

Wisp is browser transport only. It does not become a kernel, filesystem or workspace authority.

## 9. Offline boot

`sw.js` caches the RiftOS shell and kernel modules. RiftWebKit engine assets are cached lazily after first use and are not part of mandatory boot.

## 10. CI boundaries

The primary workflow is `.github/workflows/riftos-pages.yml`. It validates JavaScript syntax, OPFS-first storage, the RiftWorkspace JSON boundary, WebKit-only browser wiring, removal of fake browser transports, and live Pages deployment of the RiftWebKit manifest.

`riftwebkit-poc.yml` keeps the known-good proof build. `riftwebkit-mobile.yml` builds the full mobile engine.

## 11. Non-goals

RiftOS does not:

- bypass iOS code signing
- replace the iOS kernel
- obtain arbitrary device filesystem access from a web page
- give guest pages direct RiftWorkspace/OPFS authority
- treat a CORS fetch or sanitized iframe as a browser engine
- require RiftWebKit/WASM for kernel boot

## Architecture rule

> **RiftKernel and RiftWorkspace must boot independently of RiftWebKit. RiftBrowser has one browser-engine path, and that path remains a replaceable service rather than OS authority.**
