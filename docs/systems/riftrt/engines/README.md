# Executable Engine Inventory

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

This inventory classifies execution engines by current Android reachability, not by source-file presence.

## Source ownership

Live installed-program renderer:
- `RiftBrowserAppHost.kt`

Live RiftVM/headless execution path:
- `src/riftvm.js`
- `RiftHeadlessJsRuntime.kt`
- `RiftNativeShell.kt`

Retained engine inventory/reference implementation:
- `src/riftrt.js`

Child engine READMEs own their detailed engine-specific contracts.

## Source-proven live engines

### Installed-program native WebView host

Owner:
- `RiftBrowserAppHost.kt`

Role:
- runs already-installed HTML/JS Rift programs in dedicated Android WebViews.

This is the current installed-app renderer, not the old RiftRT `native-webview` branch.

### RiftVM under headless QuickJS

Owners:
- packaged `src/riftvm.js`;
- `RiftHeadlessJsRuntime.kt`;
- `RiftNativeShell.kt`.

Role:
- inspect/run Rift++ `.rxe` executables.

This is the current VM execution path, not retained RiftRT's VM session manager.

## Retained inactive engines

### Worker-JS

Implementation remains only in un-packaged `src/riftrt.js`.

No current Kotlin/Gradle caller.

### WASM-base64

Implementation remains only in un-packaged `src/riftrt.js`.

No current Kotlin/Gradle caller.

## Reserved / roadmap

### native-arm64

Only a retained engine name/branch exists in `src/riftrt.js`.

No current Android native runtime accepts arbitrary package-native payloads.

## Removed ambiguity

The old RiftRT manager itself is inactive.

Native Desktop no longer carries the obsolete `riftrt:<app>` window-ID compatibility namespace.

Therefore a current window/app cannot be mistaken for evidence that retained RiftRT engine selection is running.

## Engine activation rule

An engine is live only when all are proven:
1. current Android caller;
2. current packaging/build wiring;
3. explicit owner/lifecycle;
4. bounded authority/security contract.

Source text in an un-packaged module satisfies none of those by itself.

## Critical invariants

- only the two source-proven live paths are labeled live;
- retained Worker/WASM stay inactive until an actual caller/package path appears;
- native-arm64 stays unsupported until a separately audited native design exists;
- live RiftVM remains headless;
- installed WebView rendering remains under the Apps host.

## Failure signatures

- inventory calls Worker/WASM active because functions exist in riftrt.js -> reachability error;
- installed app host is called legacy RiftRT -> ownership error;
- native-arm64 is described as downloaded-native execution -> security/status error;
- VM is described as browser-dependent -> live-path regression.

## Fix map

Installed WebView engine -> Apps / native-webview child.

RiftVM -> RiftVM child.

Retained Worker/WASM/native-arm64 design -> retained `riftrt.js` child docs.

## Validation

Second audit must compare Kotlin callers, Gradle asset inclusion, manifest wiring and retained `riftrt.js` branches.

Each child has its own source audit and can be VERIFIED as active, inactive or roadmap without changing this inventory classification.
