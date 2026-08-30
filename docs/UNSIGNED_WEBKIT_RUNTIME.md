# Unsigned WebKit Runtime Validation

This document records the technical basis for RiftOS's primary runtime decision.

## Claim

RiftKernel can run on stock iPhone/iPad without distributing a separately signed RiftOS native executable when it is delivered as web content and executed by Apple WebKit.

That works because RiftKernel is JavaScript/WebAssembly-capable user-space code hosted by Apple's browser/web-app process. It is not an unsigned native ARM executable and it does not bypass iOS code signing.

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
RiftKernel JS/WASM inside WebKit         -> no separate RiftOS IPA signature required
Native Swift/ARM RiftOS executable       -> Apple signing/provisioning required
```

## What this validates

The following are valid primary RiftOS building blocks without a signed RiftOS IPA:

- RiftKernel JavaScript runtime
- WebAssembly modules supported by WebKit
- RiftFS on OPFS
- IndexedDB compatibility data
- Web Workers
- Service Workers and Cache Storage
- standalone Home Screen launch
- RiftApps/RiftDev implemented within the web runtime
- web-platform share/clipboard/notification features when available

## What this does not validate

The unsigned web runtime does not gain:

- iOS kernel privileges
- arbitrary device filesystem access
- arbitrary native Swift/ARM execution
- the ability to instantiate privileged `WKWebView` objects from JavaScript
- permission to bypass CORS, CSP, origin isolation or frame restrictions

Those boundaries are intentional and are documented throughout RiftOS.

## Repository validation contract

CI should fail if the active documentation again claims that:

- RiftOS Native is the required production runtime
- the Home Screen runtime is a reduced `PWA MODE`
- OPFS is only a preview/compatibility filesystem
- native signing is required to run RiftKernel itself

The optional `native/ios/` tree may remain for future native-only capabilities, but it is not the kernel dependency chain.
