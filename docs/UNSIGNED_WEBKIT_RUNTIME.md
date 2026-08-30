# Unsigned WebKit Runtime Validation

This document records the technical basis for RiftOS's primary runtime decision.

## Claim

RiftKernel can run on stock iPhone/iPad without distributing a separately signed RiftOS native executable when it is delivered as web content and executed by Apple WebKit.

That works because RiftKernel is JavaScript user-space code hosted by Apple's browser/web-app process. Optional WebAssembly services, including the current Gecko browser experiment, are still web content executed inside that host. This is not unsigned native ARM execution and does not bypass iOS code signing.

## Official platform evidence

### WebKit Origin Private File System

WebKit documents the File System API with Origin Private File System (OPFS), including files, directories, enumeration and worker-only synchronous access handles. WebKit states OPFS support is available on iOS 15.2 and later.

Source: https://webkit.org/blog/12257/the-file-system-access-api-with-origin-private-file-system/

RiftOS maps this capability to RiftFS in `src/riftcore.js` and falls back to an IndexedDB compatibility mirror when OPFS is unavailable.

### Home Screen web apps

WebKit documents that sites can run as standalone Home Screen web apps on iPhone/iPad. Web Push for Home Screen web apps uses standards-based Push/Notifications/Service Workers and does not require Apple Developer Program membership.

Source: https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/

WebKit also documents iOS/iPadOS 26 behavior where websites added to the Home Screen open as web apps by default unless the user chooses otherwise.

Source: https://webkit.org/blog/17333/webkit-features-in-safari-26-0/

### Native executable signing remains mandatory

Apple's Platform Security documentation states that executable code on iOS must be signed and that third-party apps must be validated/signed through Apple's code-signing chain.

Source: https://support.apple.com/en-ca/guide/security/sec7c917bf14/web

This is why RiftOS makes a strict distinction:

```text
RiftKernel JS inside WebKit                  -> no separate RiftOS IPA signature required
Optional Gecko/WebAssembly inside WebKit    -> no separate RiftOS IPA signature required
Native Swift/ARM RiftOS executable          -> Apple signing/provisioning required
```

## What this validates

The following are valid RiftOS building blocks without a signed RiftOS IPA:

- RiftKernel JavaScript runtime
- RiftFS on OPFS
- IndexedDB compatibility data
- RiftWorkspace JSON API
- Web Workers
- Service Workers and Cache Storage
- standalone Home Screen launch
- RiftApps/RiftDev implemented within the web runtime
- web-platform share/clipboard/notification features when available
- optional WebAssembly services supported by the host

## Gecko WASM is optional

RiftBrowser currently experiments with the MPL-2.0 `HeyPuter/firefox-wasm` Gecko engine compiled to WebAssembly.

```text
RiftKernel.browser
       |
RiftBrowser Engine Adapter
       |
Gecko WASM
       |
     canvas
       |
Wisp transport for arbitrary external networking
```

Gecko is **not** part of the RiftOS boot dependency chain. The large browser engine is staged separately and loaded only when RiftBrowser needs it.

If Gecko cannot initialize, RiftKernel, RiftFS, OPFS and RiftWorkspace must continue functioning normally.

The removed legacy CORS-fetch/HTML-sanitizer browser transport is not part of the supported architecture.

## What this does not validate

The unsigned web runtime does not gain:

- iOS kernel privileges
- arbitrary device filesystem access
- arbitrary native Swift/ARM execution
- the ability to instantiate privileged `WKWebView` objects from JavaScript
- permission to disable the outer WebKit host's security model
- permission for Gecko guest pages to access RiftWorkspace directly

A nested browser engine can implement its own guest-page networking/rendering model inside the sandbox, but it remains hosted by the outer WebKit process and does not become an iOS-native privileged process.

## Repository validation contract

CI should fail if the active architecture again claims that:

- RiftOS Native is the required production runtime
- the Home Screen runtime is a reduced `PWA MODE`
- OPFS is only a preview/compatibility filesystem
- native signing is required to run RiftKernel itself
- WebAssembly or Gecko is required for RiftKernel/RiftWorkspace boot
- the removed CORS document transport is a RiftBrowser backend

The optional `native/ios/` tree may remain for future native-only capabilities, but it is not in the kernel dependency chain.
