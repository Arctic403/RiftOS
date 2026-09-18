# RiftRT / Legacy Runtime Manager

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

RiftRT is the legacy browser-hosted executable/runtime manager retained in `src/riftrt.js`.

The current Android APK does **not** activate that manager.

Live execution paths that replaced parts of its old role are separate subsystems:
- Rift++ executable execution -> `RiftHeadlessJsRuntime.kt` + packaged `riftvm.js`;
- installed HTML/JS programs -> `RiftBrowserAppHost.kt`.

Those replacements must not be described as “RiftRT is live.”

## Source ownership

Retained manager:
- `src/riftrt.js`

Live replacement owners:
- `RiftHeadlessJsRuntime.kt`
- `RiftNativeShell.kt`
- packaged `src/riftvm.js`
- packaged `src/riftpp-core.js`
- `RiftBrowserAppHost.kt`

## Activation proof

Current Android source contains:
- no Kotlin `RiftRT` class;
- no Android manifest component for RiftRT;
- no Gradle packaging reference to `riftrt.js`;
- no live Kotlin caller of `globalThis.RiftRT`.

Retained calls in `src/riftapps.js` and `src/riftos.js` are also un-packaged shell-era references.

The final native Desktop `riftrt:<app>` compatibility aliases were removed during this audit because no live producer created those window IDs.

## Current live executable paths

### Rift++ / .rxe

```text
RiftNativeShell
 -> RiftHeadlessJsRuntime
 -> QuickJS
 -> packaged riftpp-core.js
 -> packaged riftvm.js
```

This path is not RiftRT.

### Existing installed HTML/JS program

```text
MainActivity launcher
 -> RiftBrowserAppHost
 -> dedicated Android WebView
```

This path is not the old `launchNativeWebView()` inside retained `riftrt.js`.

## Retained RiftRT engine branches

`src/riftrt.js` still contains historical branches for:
- native-webview;
- worker-js;
- wasm-base64;
- rift-vm;
- native-arm64.

Source presence is not activation.

Each branch is classified independently in the engine inventory.

## Why retained code remains

The retained file is useful as:
- design history;
- migration reference;
- tests/reference for older package semantics.

It is not a runtime authority and must not be patched to fix current APK execution unless a future migration explicitly reactivates it.

## Non-ownership boundaries

Legacy RiftRT does not own:
- current installed program renderer;
- current Rift++ VM host;
- Desktop windows;
- current app grants;
- build execution;
- package install/update/uninstall.

## Critical invariants

- `riftrt.js` remains un-packaged unless deliberately reactivated;
- no browser-shell dependency is reintroduced to execute Rift++;
- installed programs stay under the audited App Host;
- retained engine names never become evidence of live capability;
- native Desktop does not preserve a phantom `riftrt:` window namespace.

## Failure signatures

- docs say RiftRT is current Android runtime manager -> status regression;
- Gradle starts packaging `riftrt.js` without dedicated audit -> activation regression;
- native Desktop recreates `riftrt:<app>` aliases -> stale-runtime regression;
- Rift++ execution requires DOM/window manager -> headless boundary regression;
- Apps launch through retained `globalThis.RiftRT` -> current host regression.

## Fix map

Legacy manager/reference logic -> `src/riftrt.js`.

Live RiftVM execution -> RiftVM/headless subsystem.

Live installed program execution -> Apps / native WebView app engine.

## Validation

Second source audit must prove:
- zero Kotlin RiftRT implementation/callers;
- zero manifest/Gradle activation;
- retained `globalThis.RiftRT` callers are only un-packaged JS;
- no Kotlin `riftrt:` window namespace remains;
- live Rift++ and installed-app paths terminate in their replacement owners.

Source verification does not reactivate or device-prove retained RiftRT.
