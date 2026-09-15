# Android Host

## Purpose

The Android host owns the real Android `Activity`, Android-native RiftDesktop/window manager, process-owned native MCP/RiftShell control plane, the temporary trusted compatibility WebView content canvas, system pickers/permissions, RiftBrowser surface, workspace watcher wiring, and lifecycle handoff between Android and RiftOS.

## Why this boundary exists

RiftOS is a user-space operating environment, not a replacement Android kernel. Anything that requires Android framework authority stays native; the HTML/JS shell asks for narrow operations through message bridges instead of receiving an unrestricted Android object.

## Source ownership

- `android/app/src/main/java/com/riftos/app/MainActivity.kt`
- `android/app/src/main/java/com/riftos/app/RiftNativeDesktop.kt`
- `android/app/src/main/java/com/riftos/app/RiftNativeSystemApps.kt`
- `android/app/src/main/java/com/riftos/app/RiftNativeAppHost.kt`
- `android/app/src/main/java/com/riftos/app/RiftNativeShell.kt`
- `android/app/src/main/java/com/riftos/app/RiftRendererCrashGuard.kt`
- `android/app/src/main/java/com/riftos/app/RiftVolumePaths.kt`
- `src/riftandroid-preload.js`
- `src/riftandroid-platform.js`
- `src/riftandroid-entry.js`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/build.gradle.kts`

Related but separately documented: `RiftNativeDispatcher`, `RiftBrowserWindow`, `RiftBrowserMcpAppBridge`, `RiftWorkspaceWatcher`, and system dump/relay services.

## Responsibilities

`MainActivity` creates/configures the trusted compatibility WebView, installs safe insets, serves local assets through `WebViewAssetLoader`, then gives that WebView to `RiftNativeDesktop` instead of mounting it as the desktop root. `RiftNativeDesktop` owns native launcher/taskbar/window chrome, geometry, focus, content attachment and accessibility controls; the WebView remains only a compatibility app-content canvas for built-ins that have not migrated. `MainActivity` also creates `RiftNativeAppHost`, which owns dedicated Android program surfaces for installed Rift apps. MainActivity receives `RiftAndroid` messages, routes bounded `desktop.*`, `app.runtime.*` and other native requests, owns Android file/directory pickers, forwards transfer progress/results, owns the RiftBrowser content plane, starts the workspace watcher, and starts the relay client only according to saved relay configuration.

`src/riftandroid-platform.js` intentionally exports `globalThis.RiftAndroidAPI` as the shell-facing Android service facade, `globalThis.RiftKernel` as its system subset, and `globalThis.RiftAndroidBack` as the callback invoked by `MainActivity` before Android Back exits the host. These globals are public RiftOS shell surfaces even when no other repository module currently calls every method directly.

`AndroidManifest.xml` owns APK component declarations, Android permissions and package-visibility queries for this host. The `com.vortex3d.app` query exists only so the explicit local development Binder client can resolve/bind the debug Vortex3D package; RiftOS does not declare the Vortex service itself. The `com.riftllm.app` query similarly provides visibility only for the optional fixed RiftLLM Dev API Binder adapter documented in `../riftllm-bridge/README.md`; it grants no authority over RiftLLM private storage and does not make RiftLLM dependent on RiftOS. `res/values/styles.xml` owns the native Activity/window theme and initial system-bar/window background. Keep these synchronized with the Activities/services actually present in source; do not solve missing-component or permission problems in JavaScript.

`MainActivity` is declared `singleTask` because it is the one visible RiftOS desktop authority. Relaunch/foreground requests route back to that Activity instead of creating duplicate compatibility surfaces. MCP shell authority itself is now process-owned by `RiftNativeShell`; resume/focus only attaches the temporary trusted-WebView fallback. A compatibility renderer loss must detach that fallback without taking native MCP/RiftShell commands offline.

It also preserves WebView lifecycle/state across pause/resume/save-state and tears down native resources in `onDestroy`. `RiftRendererCrashGuard` is the process-safety layer for Chromium renderer loss: every RiftOS-owned WebView surface must return handled, record bounded diagnostics, destroy only the dead WebView object, and rebuild/close the owning surface instead of allowing a shared renderer failure to terminate the RiftOS process.

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

Desktop, migrated system-app, installed-program and browser window commands are separated from general native dispatcher calls. `desktop.*` requests terminate in `RiftNativeDesktop`; `system.app.*` terminates in `RiftNativeSystemApps`; `app.runtime.*` terminates in the fixed `RiftNativeAppHost`; `browser.window.*` terminates in `RiftBrowserWindow`. Workspace events have their own callback path. MCP guest-page messaging uses `RiftBrowserMcpAppBridge`, not the general shell bridge.

## Security boundary

The shell bridge is only installed for the RiftOS appassets origin. Guest web content in RiftBrowser must never receive the general `RiftAndroid` dispatcher. ChatGPT compatibility receives only its exact-origin MCP bridge. Android file/content access is additionally disabled in the browser engine's WebView settings.

## State

Android owns process/activity/WebView state and `filesDir`. RiftOS persistent logical state lives under `filesDir/riftfs` or scoped preferences/Keystore owned by the relevant subsystem. `MainActivity` should not become a second store for subsystem state.

## Failure signatures

- Native `rift_shell_exec` core disappears with the compatibility renderer -> `RiftMcpRuntime`/`RiftNativeShell` lifetime regression.
- Compatibility shell cannot call native methods -> bridge installation/origin or `handleKernelRequest`.
- File chooser/save picker never returns -> request-code/result routing in `MainActivity`.
- Browser content floats over minimized windows -> `RiftBrowserWindow` bounds/visibility ownership, not shell WebView z-order hacks.
- Workspace Records stops receiving external changes -> always-on `RiftWorkspaceWatcher` event forwarding / `RiftWorkspaceRecords` reconciliation.
- Insets/right edge are clipped -> Android window inset handling plus desktop geometry, not arbitrary CSS width inflation.
- Launching an installed Rift app kills/restarts the whole RiftOS process -> inspect renderer-loss coverage across **all** WebViews, not only `RiftNativeAppHost`; a Chromium renderer may be shared, so the trusted shell, installed apps, browser main/popup and preview surfaces must all return handled.
- Trusted shell renderer exits -> `MainActivity` records the event, detaches the dead shell WebView and requests one guarded Activity recreation; MCP must never keep a dead shell bridge registered.

## Fix map

Patch `MainActivity` only for Android lifecycle, picker, permission, bridge routing, trusted-shell recovery, host surface, or event-forwarding issues. Shared renderer crash journaling/recovery coordination belongs in `RiftRendererCrashGuard`; individual WebView owners still clean up their own dead view. Put filesystem semantics in `RiftNativeDispatcher`/RiftFS, browser rendering semantics in the browser engine, and MCP semantics in MCP classes.

## Validation

The source validator checks Android host/inset/browser invariants, including renderer-loss containment on every WebView surface. Any native Kotlin change requires an APK build. Test cold start, rotation/resizing where applicable, background/foreground lifecycle, picker cancellation, installed-app/browser/popup/preview renderer loss, and trusted-shell destruction/recreation. A guest-only renderer crash must not terminate RiftOS; a trusted-shell/browser-main renderer crash may recreate the Activity but must keep the Android process alive and leave a crash-recovery record for the next dump.
