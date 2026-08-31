# Unsigned WebKit Runtime Validation

This document records the technical basis for RiftOS's primary runtime decision.

## Claim

RiftKernel can run on stock iPhone/iPad without distributing a separately signed RiftOS native executable when it is delivered as web content and executed by Apple WebKit.

RiftWebKit is also web content: WebCore/JavaScriptCore/Skia are compiled to WebAssembly and run inside the outer Apple WebKit process. This is not unsigned native ARM execution and does not bypass iOS code signing.

## Platform basis

### Origin Private File System

WebKit exposes Origin Private File System (OPFS) capabilities that RiftOS maps to RiftFS. IndexedDB remains the compatibility mirror when needed.

### Home Screen web apps

RiftOS can run as a standalone Home Screen web app on iPhone/iPad using the standards-based WebKit runtime.

### Native signing remains mandatory for native code

A native Swift/ARM executable still requires Apple's signing and provisioning chain. RiftOS does not depend on such an executable.

```text
RiftKernel JS inside WebKit             -> no separate RiftOS IPA required
RiftWebKit WebAssembly inside WebKit    -> no separate RiftOS IPA required
Native ARM executable                   -> Apple signing/provisioning required
```

## Valid unsigned building blocks

- RiftKernel JavaScript runtime
- RiftFS on OPFS
- IndexedDB compatibility data
- RiftWorkspace JSON API
- Web Workers
- Service Workers and Cache Storage
- standalone Home Screen launch
- RiftApps/RiftDev implemented within the web runtime
- web-platform share/clipboard/notification features when available
- RiftWebKit WebAssembly browser service

## RiftWebKit is optional to kernel boot

RiftBrowser uses a pinned WebKit Emscripten port:

```text
RiftKernel.browser
       |
RiftWebKit Mobile
       |
WebCore + JavaScriptCore + Skia WASM
       |
canvas + Wisp transport
```

The engine is **not** part of the RiftOS boot dependency chain. It is staged separately and loaded only when RiftBrowser needs it.

If RiftWebKit cannot initialize, RiftKernel, RiftFS, OPFS and RiftWorkspace must continue functioning normally.

The previous Gecko experiment, signed native browser fallback and legacy CORS-fetch/HTML-sanitizer transport are not part of the active browser architecture.

## What this does not validate

The unsigned web runtime does not gain:

- iOS kernel privileges
- arbitrary device filesystem access
- arbitrary native Swift/ARM execution
- permission to disable the outer WebKit host's security model
- permission for RiftWebKit guest pages to access RiftWorkspace directly

A nested browser engine can implement guest-page networking/rendering inside the sandbox, but it remains hosted by the outer WebKit process and does not become an iOS-native privileged process.

## Repository validation contract

CI should fail if the active architecture again claims that:

- a signed native RiftOS app is required to run RiftKernel
- the Home Screen runtime is a reduced mode
- OPFS is only a preview filesystem
- RiftWebKit/WASM is required for RiftKernel/RiftWorkspace boot
- Gecko or a signed native browser fallback is an active RiftBrowser backend
- the removed CORS document transport is a RiftBrowser backend
