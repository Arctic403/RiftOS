# RiftOS Architecture

## Decision

RiftOS is a **user-space OS environment hosted by Android**. RiftKernel is the authority for RiftOS apps, windows, services, capabilities and RiftFS namespace; Android/Linux remains the real device kernel and authority for processes, permissions, storage providers, networking and hardware.

```text
Android / Linux kernel
        |
 Android application sandbox
        |
     MainActivity
        |
 RiftOS shell WebView
        |
     RiftKernel
   /      |        \
RiftFS  RiftRT   RiftDesktop
  |                  |
filesDir + SAF       apps/windows
                     |
                 RiftBrowser
                     |
             RiftBrowserWindow
                     |
            Android System WebView

External AI protocol
        |
    remote adapter
        |
   Rift Bridge app
        |
  device capability policy
        |
riftfs/browser-sandbox
```

## Kernel boundary

`src/riftcore.js` provides the RiftOS process/app/service model. Android-native operations are brokered through `RiftAndroid` rather than exposed directly to every app.

RiftKernel does not claim Android kernel privileges and does not bypass Android permission or app-sandbox rules.

## Storage

The active Android RiftFS root is `filesDir/riftfs`. Standard internal directories include `home`, `apps`, `system`, `workspace`, `downloads` and `documents`.

User-selected external directories are mounted through Android Storage Access Framework. Canonical path checks prevent RiftFS path traversal.

## Desktop

RiftDesktop is the single window manager for built-ins and RiftRT apps. Files, Settings, RiftBrowser and Rift Bridge participate in the RiftOS application model.

The browser's native WebView is a content plane inside a RiftOS-managed window, not a second desktop or full-screen browser Activity.

## Workspace and RiftDev

RiftWorkspace uses RiftFS on Android. `src/riftworkspace-web.js` supplies the common workspace contract while `src/riftworkspace-android-adapter.js` binds it to native storage.

RiftDev's Android build rewrites its legacy IndexedDB calls to the `RiftDevAndroidDB` compatibility facade backed by RiftWorkspace.

## RiftRT

RiftRT v1 adds worker, iframe and WebAssembly applications without creating a second kernel/window manager. Native ARM64 remains a packaged/future plugin direction; arbitrary downloaded ELF execution is not enabled.

## Browser and bridge security

Normal guest pages, including `chatgpt.com`, do not receive RiftFS or RiftWorkspace authority. RiftBrowser does not inject an agent or filesystem bridge into guest content.

Rift Bridge owns a separate app-private `riftfs/browser-sandbox` tool scope. Remote adapters can call only the fixed bridge tool registry and only when the device-side read/write grants permit the operation.

## Historical web/iOS work

Earlier RiftOS research used OPFS, service workers and a WebKit-WASM/Wisp browser path. The removed ChatGPT DOM Agent is also historical. None of those are active Android APK architecture.

## Architecture rule

> Android owns device privilege; RiftKernel owns RiftOS authority. Native services are brokered capabilities, and no guest page or normal app receives unrestricted Android access.
