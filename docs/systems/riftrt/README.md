# RiftRT Application Runtime

## Purpose

RiftRT is the execution layer between an installed RiftOS program and RiftDesktop. Installation is owned by RiftApps; RiftRT launches only the installed copy registered under C:/Programs.

RiftRT does not own a second desktop/window manager. Android `RiftNativeDesktop` remains the authoritative window frame, focus, geometry, taskbar and close lifecycle.

## Engines

### native-webview (V1 default)

HTML/JS-compatible installed programs run in a **dedicated Android `WebView` View** created by `RiftNativeAppHost` and attached directly to the Android-owned RiftDesktop content rectangle. This is not an iframe, is not a child of the trusted shell WebView, and has an independent Android renderer/view lifecycle.

The target exists to make the installer/native-window architecture usable immediately while R.O.P.E's compiled Rift ABI/toolchain is built. It is an execution backend, not the permanent definition of a Rift app.

Old `riftrt.json` values declaring `iframe` are translated to `native-webview`; the iframe engine itself no longer exists.

### worker-js

Compatibility/runtime experimentation engine. A Worker receives a constrained Rift API and drives a host canvas. It stays capability-gated and cannot become a raw shell bridge.

### wasm-base64

Sandboxed WebAssembly compatibility engine using the existing bounded Rift ABI/frame-command surface.

### native-arm64

Reserved packaged-plugin direction. RiftOS does not execute arbitrary downloaded ELF binaries from writable storage.

## Native app host

`RiftNativeAppHost.kt` owns V1 native program surfaces. It loads only the installed package selected by app id, serves package assets through the fixed `https://app.riftos.local` origin, disables file/content access, denies frames, and exposes a fixed WebMessage API. It does not expose the general `RiftNativeDispatcher` method namespace.

The app surface is mounted with `RiftNativeDesktop.attachContent(windowId, view)`. Minimize/restore/move/resize/close follows the same Android-owned WindowRecord as every other native RiftDesktop window.

A normal program `fs.write` grant does not make C: writable. Native app filesystem access is restricted to approved D: user/project roots plus that program's own AppData; read access additionally permits that program's own `C:/Programs/<id>` directory. Installer/system code remains the authority that changes C:/Programs, C:/ProgramData and C:/Toolchains.

## Capabilities and data

Native app calls are checked against the installed manifest and the same persisted `permissions:<appId>` grants used by RiftOS. A first-use request is surfaced as an Android permission dialog. Filesystem calls use the fixed C:/D: resolver and canonical RiftFS containment.

Program-local state lives under D:/Users/Default/AppData/<id>. `build.local` remains a bounded controller capability; the APK continues to report/behave as no local compiler executor until the real toolchain worker ships.

## Source ownership

- `src/riftrt.js` — engine parsing, kernel process/session lifecycle, native-window coordination, Worker/WASM compatibility engines and runtime manager.
- `android/app/src/main/java/com/riftos/app/RiftNativeAppHost.kt` — Android V1 installed-program view/bridge.
- `android/app/src/main/java/com/riftos/app/RiftNativeDesktop.kt` — window/surface authority.
- `src/riftapps.js` — installation/registry only.

## Invariants

- No installed Rift program is rendered in an iframe.
- Normal installed V1 programs get an Android-owned content View separate from the shell WebView.
- RiftRT waits for the native WindowRecord before attaching the native program surface.
- Closing via app, native close button, taskbar/process kill or Android Back converges on one window/process cleanup path.
- Native app messaging exposes fixed methods, bounded payloads and declared capabilities only.
- The trusted shell WebView remains an internal compatibility plane for built-ins that have not yet migrated; it is not an installed-app host.

## Failure signatures

- installed app launches but no native content surface appears -> `app.runtime.open`, `RiftNativeAppHost`, or `RiftNativeDesktop.attachContent` path failed.
- app content appears inside the trusted shell WebView/iframe -> retired execution model regressed.
- window closes visually but the RiftRT process/session remains -> native close/process cleanup convergence broke.
- app filesystem access reaches C: system roots or another app's AppData -> native-host containment/capability regression.
- `build.local` claims compilation is available while the native executor is absent -> RiftBuild/RiftRT capability reporting drifted.

## Fix map

Engine parsing/session lifecycle and Worker/WASM/native-webview launch -> `src/riftrt.js`.
Installed Android WebView surface and bounded guest bridge -> `RiftNativeAppHost.kt`.
Window attachment/focus/geometry/close ownership -> `RiftNativeDesktop.kt`.
Install/registry state -> `src/riftapps.js`; do not repair installer failures inside RiftRT.

## Validation

Test install -> launch -> native surface, move/resize/minimize/maximize/restore, taskbar focus, app-initiated close, native close and process termination. Accessibility should see the app's dedicated Android WebView node inside the native window, not a shell iframe. Verify denied/granted filesystem and clipboard calls, package asset containment, network default-deny behavior and no arbitrary native method passthrough.
