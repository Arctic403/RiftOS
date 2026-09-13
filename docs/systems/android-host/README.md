# Android Host

## Purpose

The Android host owns the real Android `Activity`, the shell WebView, exact-origin native bridge installation, system pickers/permissions, native browser surface, workspace watcher wiring, and lifecycle handoff between Android and RiftOS.

## Why this boundary exists

RiftOS is a user-space operating environment, not a replacement Android kernel. Anything that requires Android framework authority stays native; the HTML/JS shell asks for narrow operations through message bridges instead of receiving an unrestricted Android object.

## Source ownership

- `android/app/src/main/java/com/riftos/app/MainActivity.kt`
- `src/riftandroid-preload.js`
- `src/riftandroid-platform.js`
- `src/riftandroid-entry.js`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/build.gradle.kts`

Related but separately documented: `RiftNativeDispatcher`, `RiftBrowserWindow`, `RiftBrowserMcpAppBridge`, `RiftWorkspaceWatcher`, and system dump/relay services.

## Responsibilities

`MainActivity` creates/configures the shell WebView, installs safe insets, serves local assets through `WebViewAssetLoader`, receives `RiftAndroid` messages, routes kernel/native requests, owns Android file and directory pickers, handles notification permission results, forwards transfer progress/results to JavaScript, owns the native RiftBrowser content plane, starts the workspace watcher, and starts the relay client only according to saved relay configuration.

`src/riftandroid-platform.js` intentionally exports `globalThis.RiftAndroidAPI` as the shell-facing Android service facade, `globalThis.RiftKernel` as its system subset, and `globalThis.RiftAndroidBack` as the callback invoked by `MainActivity` before Android Back exits the host. These globals are public RiftOS shell surfaces even when no other repository module currently calls every method directly.

`AndroidManifest.xml` owns APK component declarations and Android permissions for this host. `res/values/styles.xml` owns the native Activity/window theme and initial system-bar/window background. Keep these synchronized with the Activities/services actually present in source; do not solve missing-component or permission problems in JavaScript.

It also preserves WebView lifecycle/state across pause/resume/save-state and tears down native resources in `onDestroy`.

## Control flow

```text
RiftOS JS
  -> core.native.call(method,args)
  -> exact-origin RiftAndroid WebMessage
  -> MainActivity.handleKernelRequest()
  -> native service / RiftNativeDispatcher / browser window / picker
  -> sendNativeResult() or progress/event callback
  -> JS pending request resolves
```

Browser window commands are separated from general native dispatcher calls. Workspace events have their own callback path. MCP guest-page messaging uses `RiftBrowserMcpAppBridge`, not the general shell bridge.

## Security boundary

The shell bridge is only installed for the RiftOS appassets origin. Guest web content in RiftBrowser must never receive the general `RiftAndroid` dispatcher. ChatGPT compatibility receives only its exact-origin MCP bridge. Android file/content access is additionally disabled in the browser engine's WebView settings.

## State

Android owns process/activity/WebView state and `filesDir`. RiftOS persistent logical state lives under `filesDir/riftfs` or scoped preferences/Keystore owned by the relevant subsystem. `MainActivity` should not become a second store for subsystem state.

## Failure signatures

- Entire shell cannot call native methods -> bridge installation/origin or `handleKernelRequest`.
- File chooser/save picker never returns -> request-code/result routing in `MainActivity`.
- Browser content floats over minimized windows -> `RiftBrowserWindow` bounds/visibility ownership, not shell WebView z-order hacks.
- Workspace Records stops receiving external changes -> always-on `RiftWorkspaceWatcher` event forwarding / `RiftWorkspaceRecords` reconciliation.
- Insets/right edge are clipped -> Android window inset handling plus desktop geometry, not arbitrary CSS width inflation.

## Fix map

Patch `MainActivity` only for Android lifecycle, picker, permission, bridge routing, host surface, or event-forwarding issues. Put filesystem semantics in `RiftNativeDispatcher`/RiftFS, browser rendering semantics in the browser engine, and MCP semantics in MCP classes.

## Validation

The source validator checks Android host/inset/browser invariants. Any native Kotlin change requires an APK build. Test cold start, rotation/resizing where applicable, background/foreground lifecycle, picker cancellation, and destruction/recreation for host-level changes.
