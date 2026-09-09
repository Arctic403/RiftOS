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
        Android System WebView (current)
                     |
       ChatGPT exact-origin MCP adapter
                     |
             RiftMcpServer
                     |
             RiftToolHost
                     |
           riftfs/tool-sandbox
```

The AI-tool path above is local to the RiftOS application process. There is no active remote relay, WSS pairing connection or public Rift MCP endpoint.

## Kernel boundary

`src/riftcore.js` provides the RiftOS process/app/service model. Android-native operations are brokered through `RiftAndroid` rather than exposed directly to every app.

RiftKernel does not claim Android kernel privileges and does not bypass Android permission or app-sandbox rules.

## Storage

The active Android RiftFS root is `filesDir/riftfs`. Standard internal directories include `home`, `apps`, `system`, `workspace`, `downloads` and `documents`.

User-selected external directories are mounted through Android Storage Access Framework. Canonical path checks prevent RiftFS path traversal.

Rift MCP tools use a separate app-private `riftfs/tool-sandbox`. Existing alpha `browser-sandbox` data is migrated forward once and is not the active logical namespace.

## Desktop

RiftDesktop is the single window manager for built-ins and RiftRT apps. Files, Settings, RiftBrowser and Rift MCP participate in the RiftOS application model.

The browser's native renderer is a content plane inside a RiftOS-managed window, not a second desktop or full-screen browser Activity. Android System WebView is the current compatibility renderer; RiftEngine/Servo is the target renderer after hardware validation.

## Workspace and RiftDev

RiftWorkspace uses RiftFS on Android. `src/riftworkspace-web.js` supplies the common workspace contract while `src/riftworkspace-android-adapter.js` binds it to native storage.

RiftDev's Android build rewrites its legacy IndexedDB calls to the `RiftDevAndroidDB` compatibility facade backed by RiftWorkspace.

## RiftRT

RiftRT v1 adds worker, iframe and WebAssembly applications without creating a second kernel/window manager. Native ARM64 remains a packaged/future plugin direction; arbitrary downloaded ELF execution is not enabled.

## Browser and MCP security

Normal guest pages do not receive RiftFS, RiftWorkspace or `RiftNativeDispatcher` authority.

The ChatGPT compatibility layer is exact-origin and can send only MCP JSON-RPC to the in-process `RiftMcpServer`. `RiftToolHost` validates tool names, applies local read/write grants, records bounded audit metadata and dispatches into `RiftToolSandbox`.

The browser compatibility layer may depend on ChatGPT composer/rendered-message structure, but that dependency does not own or weaken the device capability boundary.

## Removed architectures

The following are historical only:

- ChatGPT DOM Agent V1/V2/V3,
- remote Rift MCP relay / WSS device pairing,
- WebKit-WASM/Wisp browser path,
- Gecko WASM experiments,
- local LLM runtime.

## Architecture rule

> Android owns device privilege; RiftKernel owns RiftOS authority. Local MCP is a capability adapter over RiftToolHost, and no guest page or normal app receives unrestricted Android access.
