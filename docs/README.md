# RiftOS Documentation Map

This directory is the maintenance map for RiftOS. The goal is simple: when a system breaks or needs improvement, a developer should be able to identify the owning subsystem, understand its boundaries, find the exact source files, reproduce its data/control flow, and know where to patch without first reverse-engineering the whole project.

## Documentation contract

Every active system README should answer the same questions:

1. **What** does this component do?
2. **Why** does it exist as a separate boundary?
3. **Where** is its source, state, storage, and public surface?
4. **How** does control/data move through it?
5. What does it explicitly **not own**?
6. What invariants must not be broken?
7. What symptoms appear when it fails?
8. Where should a fix normally be made?
9. Which tests/validation protect it?
10. What are the safe extension points?

When behavior changes, update the owning system README in the same patch. Historical experiment docs can remain, but active-system READMEs are the source of truth for current ownership.

## System index

| System | Primary documentation | Main source |
| --- | --- | --- |
| Boot/module loading | [`systems/boot/README.md`](systems/boot/README.md) | `src/riftandroid-entry.js`, `src/riftandroid-preload.js`, `index.html` |
| Shell UI/system windows | [`systems/shell-ui/README.md`](systems/shell-ui/README.md) | `index.html`, `styles.css`, `src/riftos.js` |
| Android host | [`systems/android-host/README.md`](systems/android-host/README.md) | `MainActivity.kt`, `src/riftandroid-platform.js` |
| RiftKernel | [`systems/kernel/README.md`](systems/kernel/README.md) | `src/riftcore.js` |
| RiftFS | [`systems/riftfs/README.md`](systems/riftfs/README.md) | `src/riftcore.js`, `RiftNativeDispatcher.kt` |
| Native dispatcher | [`systems/native-dispatcher/README.md`](systems/native-dispatcher/README.md) | `RiftNativeDispatcher.kt` |
| Transfer pipeline | [`systems/transfers/README.md`](systems/transfers/README.md) | `RiftTransfer*.kt`, `RiftTransferQueue` |
| Desktop/window manager | [`systems/desktop/README.md`](systems/desktop/README.md) | `src/riftdesktop-android*.js/css` |
| RiftBrowser | [`systems/browser/README.md`](systems/browser/README.md) | `RiftBrowserWindow.kt`, browser shell in `riftos.js` |
| Browser engine | [`systems/browser/engine/README.md`](systems/browser/engine/README.md) | `RiftBrowserEngine.kt`, `AndroidWebViewBrowserEngine.kt` |
| Browser MCP compatibility | [`systems/browser/mcp-compat/README.md`](systems/browser/mcp-compat/README.md) | `riftbrowser-mcp-app.js`, `RiftBrowserMcpAppBridge.kt` |
| AI site adapters | [`systems/browser/ai-adapters/README.md`](systems/browser/ai-adapters/README.md) | `assets/adapters/` |
| Rift MCP | [`systems/mcp/README.md`](systems/mcp/README.md) | native MCP classes + `src/riftmcp-system.js` |
| MCP server | [`systems/mcp/server/README.md`](systems/mcp/server/README.md) | `RiftMcpServer.kt` |
| MCP tool host | [`systems/mcp/tool-host/README.md`](systems/mcp/tool-host/README.md) | `RiftToolHost.kt` |
| MCP sandbox / Code Mode | [`systems/mcp/sandbox/README.md`](systems/mcp/sandbox/README.md) | `RiftToolSandbox.kt` |
| MCP relay client | [`systems/mcp/relay/README.md`](systems/mcp/relay/README.md) | `RiftMcpRelayClient.kt`, `RiftRelaySettings.kt` |
| Project exporter | [`systems/mcp/project-exporter/README.md`](systems/mcp/project-exporter/README.md) | `RiftProjectExporter.kt` |
| RiftWorkspace | [`systems/workspace/README.md`](systems/workspace/README.md) | `src/riftworkspace-*.js` |
| Workspace Records | [`systems/workspace/live/README.md`](systems/workspace/live/README.md) | `workspace-live/`, `RiftWorkspaceRecords.kt`, `RiftWorkspaceWatcher.kt` |
| App/package system | [`systems/apps/README.md`](systems/apps/README.md) | `src/riftapps*.js` |
| RiftRT | [`systems/riftrt/README.md`](systems/riftrt/README.md) | `src/riftrt.js` |
| Runtime capability report | [`systems/runtime-capabilities/README.md`](systems/runtime-capabilities/README.md) | `src/riftruntime.js` |
| RiftShell | [`systems/shell/README.md`](systems/shell/README.md) | shell in `riftos.js`, `riftshell-batch.js` |
| RiftGit | [`systems/git/README.md`](systems/git/README.md) | `src/riftgit.js` |
| Files app | [`systems/files-app/README.md`](systems/files-app/README.md) | `openFiles()` in `src/riftos.js` |
| Settings | [`systems/settings/README.md`](systems/settings/README.md) | `openSettings()` in `src/riftos.js` |
| Preview | [`systems/preview/README.md`](systems/preview/README.md) | `RiftPreviewActivity.kt` |
| Diagnostics / System Dump | [`systems/diagnostics/README.md`](systems/diagnostics/README.md) | `RiftSystemDump.kt` |
| Secrets | [`systems/secrets/README.md`](systems/secrets/README.md) | `RiftSecretStore.kt` |
| Public relay service | [`systems/relay-service/README.md`](systems/relay-service/README.md) | `relay/src/index.js` |
| Build and validation | [`systems/build-validation/README.md`](systems/build-validation/README.md) | `package.json`, `scripts/`, Gradle |

## Cross-cutting architecture documents

The cross-cutting architecture documents remain useful for larger views: [`TRUE_OS_ARCHITECTURE.md`](TRUE_OS_ARCHITECTURE.md), `ANDROID_NATIVE_ARCHITECTURE.md`, `RIFTBROWSER_ARCHITECTURE.md`, `RIFT_MCP_APP_ARCHITECTURE.md`, `RIFTWORKSPACE_WEB_ARCHITECTURE.md`, `RIFTRT-v1.md`, and `PROJECT_STATUS.md`. [`PUBLIC_SURFACES.md`](PUBLIC_SURFACES.md) inventories every deliberate Rift global/native bridge boundary. Operational references at the repository root are [`../LOCAL_MCP_MODE.md`](../LOCAL_MCP_MODE.md) for the transport split and [`../ROADMAP.md`](../ROADMAP.md) for planned work; the active model-facing wire format is [`RIFT_RAW_CHAT_PROTOCOL.md`](RIFT_RAW_CHAT_PROTOCOL.md). If an older document conflicts with a current system README and current source, treat the current source plus owning system README as authoritative and update the stale document.

## Debugging rule

Start from the visible symptom, identify the owning README above, then follow its **Failure signatures** and **Fix map** sections. Avoid fixing a problem in a neighboring layer merely because that layer can hide the symptom. For example, a stale MCP client action catalog is not a `RiftToolSandbox` bug, browser surface geometry is not an engine-network bug, and a native filesystem failure should not be patched by adding duplicate JavaScript storage logic.
