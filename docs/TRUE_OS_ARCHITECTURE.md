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
 OPFS        JSON API       Engine Adapter
  |                            |
 IndexedDB                   Gecko WASM
 compatibility                  |
 mirror                       canvas
                                 |
                            Wisp transport

Optional signed capability path:
RiftKernel -> RiftNative -> Swift/WKWebView/native Files APIs
```

## 1. One kernel

`src/riftcore.js` owns process lifecycle, app registration, permissions, mounts, system information and RiftFS.

No delivery mechanism or browser engine is allowed to become a second kernel. Running from a Home Screen icon, Safari tab, Gecko WASM service, or optional native wrapper changes capabilities, not RiftKernel authority.

## 2. Runtime vs delivery

`src/riftruntime.js` normalizes runtime identity.

For the unsigned web path:

```text
mode = riftkernel-webkit
host = Apple WebKit
delivery = home-screen-web-app | browser-tab
appSigningRequiredForKernel = false
```

PWA/web-app technology is a delivery and lifecycle mechanism, not the OS architecture.

## 3. RiftFS is OPFS-first

RiftFS uses Origin Private File System when `navigator.storage.getDirectory()` is available. IndexedDB remains a compatibility mirror and migration store.

```text
/
├── home/
├── apps/
├── system/
└── mounts/
```

OPFS is origin-private. RiftOS can create, enumerate, read and write its own files/directories without claiming access to arbitrary iPhone files.

## 4. RiftWorkspace is the JSON sandbox boundary

`src/riftworkspace-web.js` exposes controlled workspace operations over RiftFS/OPFS.

```text
RiftWorkspace JSON API
        |
      RiftFS
        |
       OPFS
```

The workspace is independent of the browser engine. It must continue working even if Gecko fails or is never loaded.

## 5. WebKit host primitives

RiftKernel may use standards exposed by WebKit, including OPFS, Web Workers, Service Workers, Cache Storage, IndexedDB, Web Share, notifications and WebAssembly when available.

Feature detection is mandatory. A host capability being unavailable must degrade only the service that depends on it.

## 6. Processes and apps

Built-ins, RiftDev sessions and installed RiftApps receive RiftKernel process records. Protected kernel/system processes remain controlled by the process table.

RiftApps use brokered capabilities and RiftFS paths instead of receiving native iOS privileges.

## 7. RiftBrowser is an optional engine service

`src/riftbrowser-kernel.js` installs `RiftKernel.browser`. The current unsigned browser experiment uses a replaceable engine adapter with `Gecko WASM` as the primary engine.

```text
RiftKernel.browser
       |
RiftBrowser Engine Adapter
       |
Gecko WASM
       |
     canvas
       |
Wisp networking
```

The Gecko engine is lazy-loaded. RiftOS core MUST NOT require WebAssembly to boot.

The removed legacy CORS transport is not a browser backend and must not be reintroduced. When no real RiftBrowser engine is available, the service reports `unavailable` and may offer an external host-browser handoff.

Guest pages rendered by Gecko do not receive direct RiftWorkspace, RiftFS or OPFS capabilities.

## 8. Networking

Browser JavaScript cannot expose arbitrary raw TCP sockets to the nested Gecko engine. RiftBrowser therefore uses a Wisp WebSocket endpoint for arbitrary guest HTTP/HTTPS networking.

Wisp is browser transport only. It does not become a kernel, filesystem or workspace authority.

## 9. Offline boot

`sw.js` caches the RiftOS shell and kernel modules. Service workers improve offline boot/lifecycle and provide the current cross-origin-isolation response headers used by the Gecko experiment; they are not the kernel itself.

The large Gecko binary is not part of the mandatory core boot path.

## 10. Optional native capability layer

`native/ios/` remains available for future signed capabilities such as user-approved Files mounts, native share/clipboard integration and a full `WKWebView` tab host.

It is optional. A physical-device native executable still requires Apple-authorized signing. The unsigned RiftKernel web runtime does not.

## 11. CI boundaries

The primary workflow is `.github/workflows/riftos-pages.yml`.

It validates JavaScript syntax, OPFS-first storage, the RiftWorkspace JSON boundary, Gecko browser staging, cross-origin-isolation support, removal of the legacy CORS browser transport, and live Pages deployment of the engine manifest.

`.github/workflows/riftos-native-ios.yml` is manual-only and validates the optional Swift capability host.

## 12. Non-goals

RiftOS does not:

- bypass iOS code signing
- replace the iOS kernel
- obtain arbitrary device filesystem access from a web page
- give guest pages direct RiftWorkspace/OPFS authority
- treat a CORS fetch or sanitized iframe as a browser engine
- require Gecko/WASM for kernel boot

## Architecture rule

> **RiftKernel and RiftWorkspace must boot independently of every browser engine. RiftBrowser may use Gecko WASM, but Gecko is a replaceable service, not an OS dependency.**
