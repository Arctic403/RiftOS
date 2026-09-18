# RiftOS Public and Cross-Layer Surfaces

This inventory separates live packaged authority from retained reference/compatibility JavaScript. A name in retained source is not automatically a live APK surface.

## Live native/system surfaces

| Surface | Owner | Purpose |
| --- | --- | --- |
| `RiftShellExecutor` | `RiftNativeShell.kt` | Process-owned bounded shell executor used by native Terminal and MCP. |
| Rift++ headless runtime | `RiftHeadlessJsRuntime.kt` | Loads only trusted Rift++ Core/RiftVM assets in QuickJS; no DOM/WebView/network/process authority. |
| MCP tool catalog | `RiftToolHost.kt` | Canonical fixed 18-tool schemas/permissions/audit. |
| Workspace/Code Mode | `RiftToolSandbox.kt` | Workspace-only filesystem, Project Intelligence and transactional operations. |
| Workspace Records | `RiftWorkspaceRecords.kt` | Private local history/diff record source. |
| Native desktop | `RiftNativeDesktop.kt` | Android window/taskbar/z-order/geometry authority. |
| Native built-ins | `RiftNativeSystemApps.kt`, `RiftNativeWorkspaceApps.kt` | Terminal, Task Manager, Files, Editor, Dev Lab, Workspace Records and Settings. |
| Native Git | `RiftNativeGit.kt` | Git/GitHub workflow with Android Keystore credential access. |
| Vortex bridge/agents | `RiftVortexBridgeClient.kt`, `RiftVortexLocalAgent.kt` | Fixed local Binder/accessibility development surfaces. |
| RiftLLM Dev/training service | `RiftLlmDevClient.kt`, `RiftTrainDataTaskRunner.kt`, `RiftNativeShellServices.kt` | Fixed bounded standalone RiftLLM API/training commands. |

## Live RiftBrowser page surfaces

These exist only in explicit RiftBrowser-owned WebViews and do not provide general Android/shell authority.

| Surface | Owner | Purpose |
| --- | --- | --- |
| `RiftMcpNative` | `RiftBrowserMcpAppBridge.kt` | Exact-origin MCP JSON-RPC channel. |
| `RiftMcpAppNative` | `riftbrowser-mcp-app.js` | Page-side response receiver. |
| `RiftAIAdapters` | `ai-adapter-registry.js` | Supported AI-site semantic selectors. |
| installed-app `Rift` API | `RiftBrowserAppHost.kt` | Capability-gated fixed API for one installed package at its deterministic per-app `https://app-<sha>.riftos.local` origin. |

Guest pages must not receive RiftShell, RiftFS, Workspace, Keystore, generic native dispatch or arbitrary Binder authority.

## Packaged JavaScript

The only JavaScript copied into the generated RiftOS `www` asset namespace for OS execution is:
- `src/riftpp-core.js` -> `RiftPlusPlusCore` compiler surface;
- `src/riftvm.js` -> RiftVM implementation.

They execute under the bounded headless runtime when invoked by native RiftShell.

## Retained reference/compatibility source

The repository still contains older web-runtime/local-first modules such as `src/riftos.js`, `riftcore.js`, `riftgit.js`, `riftdevlab.js`, `riftshell-batch.js`, `riftrt.js`, `riftllm-bridge.js`, `riftrepo.js`, `riftvault.js`, `riftbuild.js`, `riftmemory-control.js`, `riftlocal-platform.js` and related globals. Focused tests and migration/reference logic may use these files, but Gradle does not package the old HTML/DOM shell. In particular, `RiftShellMcp`, `RiftShellMcpNative`, `RiftAndroid` and the old general native-dispatcher path are **not live trusted-shell APK authority**.

## Change rule

When a cross-layer surface is added/removed:
1. identify its owner;
2. update the owning subsystem README and this inventory;
3. update producer/consumer atomically;
4. extend the appropriate architecture validator;
5. avoid preserving unexplained duplicate authority.
