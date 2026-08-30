# RiftOS True OS Architecture

## Decision

RiftOS is **RiftKernel first**, not native-app first.

The primary iPhone/iPad runtime is the RiftKernel executing inside Apple WebKit. Safari/Home Screen web-app delivery does not require RiftOS to provide a separately signed native executable because the JavaScript/WebAssembly runtime is hosted by Apple's signed browser process.

This does **not** mean RiftOS replaces or bypasses the iOS kernel. Native executable code and native device privileges remain controlled by iOS and Apple code-signing rules.

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
 |        |          |           |
RiftFS  Processes   RiftApps   Capabilities
  |
 OPFS
  |
 IndexedDB compatibility mirror

RiftKernel.browser
    |
web-transport / host navigation

Optional future/experimental path:
RiftKernel -> RiftNative -> Swift/WKWebView/native Files APIs
```

## 1. One kernel

`src/riftcore.js` owns process lifecycle, app registration, permissions, mounts, system information and RiftFS.

No delivery mechanism is allowed to become a second kernel. Running from a Home Screen icon, Safari tab, or optional native wrapper changes host capabilities, not the RiftKernel authority.

## 2. Runtime vs delivery

`src/riftruntime.js` normalizes the runtime identity.

For the unsigned web path:

```text
mode = riftkernel-webkit
host = Apple WebKit
delivery = home-screen-web-app | browser-tab
appSigningRequiredForKernel = false
```

The old `PWA MODE` label is intentionally retired. PWA/web-app technology is a delivery and lifecycle mechanism, not the OS architecture.

## 3. RiftFS is OPFS-first

RiftFS uses the Origin Private File System when `navigator.storage.getDirectory()` is available. IndexedDB remains a compatibility mirror and migration store.

```text
/
├── home/
├── apps/
├── system/
└── mounts/
```

OPFS is origin-private. RiftOS can create, enumerate, read and write its own files/directories without claiming access to arbitrary iPhone files.

## 4. WebKit host primitives

RiftKernel may use standards exposed by WebKit, including:

- OPFS / File System API
- Web Workers
- WebAssembly
- Service Workers
- Cache Storage
- IndexedDB
- Web Share where available
- Notifications/Web Push for supported Home Screen web apps

Feature detection is mandatory. A host capability being unavailable must degrade a service, not replace the kernel.

## 5. Processes and apps

Built-ins, RiftDev sessions and installed RiftApps receive RiftKernel process records. Protected kernel/system processes remain controlled by the process table.

RiftApps use brokered capabilities and RiftFS paths instead of receiving magical iOS privileges.

## 6. Browser service

`src/riftbrowser-kernel.js` installs `RiftKernel.browser`.

The primary unsigned backend is `web-transport`. It can manage RiftOS browser state and render content that the web security model permits.

Normal browser protections still apply:

- CORS
- Content Security Policy
- frame restrictions
- origin isolation
- navigation/download restrictions imposed by WebKit

RiftKernel cannot manufacture an unrestricted arbitrary `WKWebView` from JavaScript. When a site cannot be embedded/read safely, RiftBrowser must use normal top-level/external WebKit navigation rather than pretending those protections do not exist.

## 7. Offline boot

`sw.js` caches the RiftOS shell and kernel modules. Service workers are a host service used to improve offline boot and lifecycle; they are not the kernel itself.

RiftFS data persists independently through OPFS/IndexedDB subject to WebKit storage policy.

## 8. Optional native capability layer

`native/ios/` remains available for experiments or future distribution where native-only APIs are valuable.

That layer may expose:

- user-approved Files mounts
- RiftWorkspace
- native share/clipboard integrations
- full custom `WKWebView` tabs

It is optional. A physical-device native executable still requires Apple-authorized signing. The unsigned RiftKernel web runtime does not.

## 9. CI boundaries

The primary workflow is `.github/workflows/riftos-pages.yml`.

It validates:

- JavaScript syntax
- OPFS-first RiftFS code
- `riftruntime.js` runtime identity
- standalone web-app manifest
- service-worker caching
- browser architecture/security wording
- absence of the removed custom WebCore/JSC/WASM engine

`.github/workflows/riftos-native-ios.yml` is manual-only and validates the optional Swift capability host.

## 10. Non-goals

RiftOS does not:

- bypass iOS code signing
- replace the iOS kernel
- obtain arbitrary device filesystem access from a web page
- disable WebKit origin/CORS/CSP security
- ship its own WebCore/JSC fork

The project goal is a capable user-space operating environment built on the web platform Apple already provides.
