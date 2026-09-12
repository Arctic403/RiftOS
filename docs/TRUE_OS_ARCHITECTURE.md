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
                 Rift AI HTML
                     |
            hidden ChatGPT Web
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
            riftfs/workspace
```

Tool execution remains local to the RiftOS application process. An optional outbound WSS client can transport MCP JSON-RPC through a public relay without granting the relay direct filesystem access.

## Kernel boundary

`src/riftcore.js` provides the RiftOS process/app/service model. Android-native operations are brokered through `RiftAndroid` rather than exposed directly to every app.

RiftKernel does not claim Android kernel privileges and does not bypass Android permission or app-sandbox rules.

## Storage

The active Android RiftFS root is `filesDir/riftfs`. Standard internal directories include `home`, `apps`, `system`, `workspace`, `downloads` and `documents`.

User-selected external directories are mounted through Android Storage Access Framework. Canonical path checks prevent RiftFS path traversal.

Rift MCP filesystem tools are hard-scoped to the canonical app-private `riftfs/workspace` tree shared with Files and workspace tooling. Legacy `tool-sandbox/workspace` and `browser-sandbox/workspace` trees are migration sources only and are never exposed as active namespaces.

## Desktop

RiftDesktop is the single window manager for built-ins and RiftRT apps. Files, Settings, RiftBrowser, Rift AI and Rift MCP participate in the RiftOS application model.

The browser's native renderer is a content plane inside a RiftOS-managed window, not a second desktop or full-screen browser Activity. Android System WebView is the current compatibility renderer; RiftEngine/Servo is the target renderer after hardware validation.

## Workspace

RiftWorkspace uses RiftFS on Android. `src/riftworkspace-web.js` supplies the common workspace contract while `src/riftworkspace-android-adapter.js` binds it to native storage.


## RiftRT

RiftRT v1 adds worker, iframe and WebAssembly applications without creating a second kernel/window manager. Native ARM64 remains a packaged/future plugin direction; arbitrary downloaded ELF execution is not enabled.

## Browser and MCP security

Normal guest pages do not receive RiftFS, RiftWorkspace or `RiftNativeDispatcher` authority.

The ChatGPT compatibility layer is exact-origin and can send only MCP JSON-RPC to the in-process `RiftMcpServer`. The optional relay transport reaches that same server over an authenticated outbound WSS connection. `RiftToolHost` validates tool names, applies local read/write grants, records bounded audit metadata and dispatches into `RiftToolSandbox`.

The browser compatibility layer may depend on ChatGPT composer/rendered-message structure, but that dependency does not own or weaken the device capability boundary.

## Rift AI boundary

Rift AI is a trusted RiftOS shell application, not a guest webpage. It displays project metadata, assistant output, logs and local diffs by calling local `ai.*` native capabilities. It never receives or stores a model API key because the model transport is exclusively the authenticated ChatGPT Web page in RiftBrowser.

Rollback originals are stored under `filesDir/rift-ai`, outside the MCP-visible `riftfs/workspace`. ChatGPT can mutate only through the fixed MCP tool registry and cannot modify the journal that is used to review/revert those mutations. RiftBrowser privately tags only active Rift AI `tools/call` requests with a native-created session ID, preventing unrelated visible-chat MCP writes from being attributed to a persistent AI review session.

Review state and transport state are separate. A completed/stopped/error/interrupted session may remain available for diff review while its hidden ChatGPT renderer is no longer running. A process restart converts stale active transport state to `interrupted` while retaining rollback data. Accept/revert are locked while transport is active, and a new task cannot replace pending unreviewed changes.

## Removed architectures

The following are historical only:

- ChatGPT DOM Agent V1/V2/V3,
- WebKit-WASM/Wisp browser path,
- Gecko WASM experiments,
- local LLM runtime.

## Architecture rule

> Android owns device privilege; RiftKernel owns RiftOS authority. Local MCP is a capability adapter over RiftToolHost, and no guest page or normal app receives unrestricted Android access.
