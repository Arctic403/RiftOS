# RiftOS

RiftOS is an **unsigned, WebKit-hosted user-space operating environment** built around one RiftKernel runtime.

The primary runtime is the RiftKernel running inside Apple WebKit on iPhone/iPad. Adding RiftOS to the Home Screen is the delivery/launch mechanism; it is not a separate "PWA mode" and it does not require RiftOS to ship a signed native executable.

## Primary architecture

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
 |      |         |          |
RiftFS Processes  Apps   Capabilities
  |
 OPFS
  |
 IndexedDB compatibility mirror

RiftKernel.browser
    |
web-transport / top-level WebKit navigation
```

RiftKernel is **not the iOS kernel**. It is a user-space OS abstraction implemented in JavaScript and WebAssembly-capable WebKit. Apple still controls device-level privileges and requires signing for native executable code.

## What runs without app signing

The unsigned RiftKernel runtime can provide:

- process and app lifecycle
- RiftFS backed primarily by Origin Private File System (OPFS)
- directories/files and persistent OS state
- permissions/capability brokerage inside RiftOS
- service workers and offline shell caching
- Web Workers for isolated/background-capable kernel tasks while the web runtime is active
- WebAssembly services
- installed RiftApps and RiftDev
- Home Screen standalone launch
- supported web notifications/share/clipboard capabilities when the host allows them

No separate RiftOS IPA signature is required for those web-platform capabilities.

## RiftFS

`src/riftcore.js` already uses OPFS as the primary RiftFS backend when available and keeps IndexedDB as a compatibility mirror/migration store.

```text
/
├── home/
├── apps/
├── system/
└── mounts/
```

The web runtime owns its origin-private filesystem. It does not claim arbitrary access to the iPhone filesystem.

## Runtime identity

`src/riftruntime.js` separates **runtime** from **delivery**.

Typical unsigned iPhone state:

```text
mode: riftkernel-webkit
host: Apple WebKit
delivery: home-screen-web-app
appSigningRequiredForKernel: false
```

A future native bridge changes available capabilities, not the identity of RiftKernel.

## RiftBrowser

`src/riftbrowser-kernel.js` remains the browser service owned by RiftKernel.

In the unsigned WebKit runtime, RiftBrowser is constrained by normal web security: CORS, CSP, frame restrictions and other browser boundaries still apply. RiftOS cannot create a privileged arbitrary `WKWebView` from JavaScript.

Sites that cannot be safely rendered by the web transport must open through normal top-level WebKit navigation/external browser behavior. A signed native host can optionally provide the `native-webkit` backend later, but it is not required for RiftOS itself.

## Optional native capability host

`native/ios/` remains an optional experiment/capability layer for features the web platform cannot expose, such as native filesystem mounts and a full custom `WKWebView` tab host.

It is **not the production definition of RiftOS** and its GitHub Action is manual-only. Native executables still require Apple-authorized signing before installation on a physical iPhone.

## GitHub Actions

- `.github/workflows/riftos-pages.yml` validates and deploys the primary unsigned RiftKernel/WebKit runtime.
- `.github/workflows/riftos-native-ios.yml` is an optional manual compile check for the Swift capability host.

CI validates OPFS, runtime identity, standalone delivery assets, service-worker caching and the browser security boundary.

## Architecture rule

Do not reintroduce a custom JSC/WebCore browser-engine build. RiftOS uses the WebKit engine already provided by Apple and builds its kernel/services above the web platform.

See:

- `docs/TRUE_OS_ARCHITECTURE.md`
- `docs/RIFTBROWSER_ARCHITECTURE.md`
- `docs/UNSIGNED_WEBKIT_RUNTIME.md`
