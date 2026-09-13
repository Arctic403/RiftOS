# RiftOS Architecture

## Decision

RiftOS is a **user-space OS environment hosted by Android**. Android/Linux owns the real process, permission, storage-provider, network and hardware boundary. RiftKernel owns the RiftOS app/process model, capabilities and RiftFS namespace inside the Android application sandbox.

## Active runtime stack

```text
Android / Linux kernel
        |
Android application sandbox
        |
    MainActivity
   /     |       \
  /      |        \
 /       |         \
RiftOS   |      native services
shell    |      (storage, picker,
WebView  |       notifications,
  |      |       diagnostics...)
  |      |
  |   RiftBrowserWindow
  |      |
  |   RiftBrowserEngine
  |      |
  |   AndroidWebViewBrowserEngine
  |      |
  |   guest web content
  |      |
  |   exact-origin MCP compatibility asset
  |      |
  |   RiftBrowserMcpAppBridge
  |      |
  |      +--------------------+
  |                           |
RiftKernel                RiftMcpServer
 /   |   \                    |
/    |    \               RiftToolHost
|    |     \              /           \
|    |      \     RiftToolSandbox   RiftShellBridge
|    |       \          |                |
|    |        \   riftfs/workspace       |
|    |         \                     trusted shell WebView
|    |          \
RiftFS  RiftRT  RiftDesktop
  |
filesDir/riftfs + SAF mounts
```

An optional outbound `RiftMcpRelayClient` transports the same MCP JSON-RPC through the public relay. The relay is transport-only and receives no direct filesystem authority.

## Boot and shell boundary

`MainActivity` hosts the trusted RiftOS shell WebView through packaged local assets. `src/riftandroid-entry.js` imports the shell/runtime modules in a fixed order. `RiftAndroid` is an exact-origin native WebMessage host; `RiftNativeTransport` is the JavaScript-side message transport installed during Android preload.

A module-evaluation failure before `src/riftos.js` prevents the boot splash from being dismissed, so boot-time references and import order are architecture-critical.

## Kernel boundary

`src/riftcore.js` provides `RiftOSCore`, including the logical filesystem API, process records, permission broker and native request correlation. Consumer systems call the kernel/native service boundary instead of reaching Android framework APIs directly.

RiftKernel does not claim Android kernel privilege and does not bypass Android permission or application-sandbox rules.

## Storage and workspace

The active Android RiftFS root is `filesDir/riftfs`. Internal roots include `home`, `apps`, `system`, `workspace`, `downloads` and `documents`. User-selected external folders are mounted through Android Storage Access Framework.

`src/riftworkspace-web.js` is the common/local workspace layer. `src/riftworkspace-android-adapter.js` is the **single Android workspace implementation** and replaces `window.RiftWorkspace` after the common layer loads. `RiftWorkspaceJSON` resolves the active workspace object at call time.

Rift MCP filesystem/project tools are hard-scoped to the canonical app-private `filesDir/riftfs/workspace` tree. They cannot address sibling RiftFS roots or SAF mounts.

## Desktop

RiftDesktop is the permanent Android RiftOS shell/window manager. Files, Settings, RiftBrowser, Workspace Records, Rift MCP and RiftRT applications participate in the same window/taskbar model. Narrow screens change responsive geometry; they do not switch RiftOS into a separate mobile/app-takeover mode.

The browser's native renderer is a content plane positioned inside a RiftOS-managed window. The current backend is Android System WebView behind `RiftBrowserEngine`; a future renderer can replace it without changing the desktop or MCP capability contracts.

## RiftRT

RiftRT v1 supports iframe, Worker JavaScript and base64 WebAssembly application engines using the existing RiftDesktop/process model and capability broker. The `native-arm64` engine remains a reserved packaged/plugin direction; arbitrary downloaded native ELF execution is not enabled.

## Browser security boundary

Normal guest pages do not receive `RiftAndroid`, RiftFS, RiftWorkspace, `RiftNativeDispatcher` or RiftShell authority.

On approved HTTPS origins, `RiftBrowserMcpAppBridge` exposes only the `RiftMcpNative` MCP message channel. The injected compatibility asset obtains the live tool manifest and can translate bounded raw `[RIFT_CALL]` blocks into local MCP `tools/call` requests.

The compatibility asset does **not** own tool execution. It does not implement filesystem operations, does not execute RiftShell and does not expose a general native object.

When a compatibility-mode tool finishes, its bounded `[RIFT_RESULT]` text is staged into the visible web composer. The adapter does not click Send; the user is the explicit send boundary. Registered/relay MCP clients receive MCP responses directly and do not use this composer path.

## MCP authority

`RiftMcpServer` owns MCP JSON-RPC framing and duplicate-request coalescing. `RiftToolHost` owns the fixed tool registry, tool schemas, local read/write grants and bounded audit metadata. `RiftToolSandbox` owns workspace containment, payload/operation limits, Project Intelligence and filesystem mutations.

Mutating `rift_workspace_exec` batches use copy-on-write transactional rollback: either the whole batch commits or touched paths are restored. Snapshot/hash guards can reject stale edits. There is **no active hidden Rift AI task controller, private AI session ID or persistent AI mutation journal**. Durable review/history should use explicit source-control/project workflows rather than an unreachable parallel session layer.

`rift_shell_exec` is intentionally outside the filesystem sandbox but is still not an Android/Linux shell. `RiftShellBridge` is registered against the trusted shell WebView by `MainActivity`, executes the existing RiftShell command parser, and returns a correlated one-way `mcp.shell.result` message. The guest browser never receives `RiftShellMcp`.

## Transfer boundary

Large copy/move/ZIP/unzip operations use `RiftTransferQueue` plus the dedicated native `transferExecutor`, `RiftTransferManifest` verification and bounded progress events to `RiftTransferUI`. The retired job/manager/service/registry transfer stack and its unused cancellation plumbing are not active architecture.

## Public surfaces

Global bridge/runtime names are intentional API boundaries and are inventoried in [`PUBLIC_SURFACES.md`](PUBLIC_SURFACES.md). New `Rift*` globals should not be added casually; they must have an owner, consumer/boundary and documentation entry.

## Removed architectures

Historical only:

- Rift AI workspace/cockpit, hidden task controller, target picker, AI event channel and persistent AI-session journal,
- ChatGPT DOM Agent V1/V2/V3,
- old chat-facing JSON/XML tool envelopes,
- full-screen standalone `RiftBrowserActivity`,
- old desktop/mobile mode toggle,
- dead transfer job/manager/service/registry path,
- WebKit-WASM/Wisp browser path,
- Gecko WASM experiments,
- local LLM runtime.

## Architecture rule

> Android owns device privilege; RiftKernel owns RiftOS authority. MCP is a capability adapter over `RiftToolHost`; workspace mutations stay sandboxed, RiftShell execution stays in the trusted shell, and guest pages never receive unrestricted Android access.
