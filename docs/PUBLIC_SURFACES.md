# RiftOS Public and Cross-Layer Surfaces

This inventory documents deliberate `Rift*` globals/bridge names. A zero-consumer global is not automatically dead: some names are callbacks or compatibility surfaces consumed by native code, installed applications, developer tools or a future module. The rule is that every exported surface must have an owner and a reason to exist.

## Trusted shell WebView

| Surface | Owner | Purpose / consumer |
| --- | --- | --- |
| `RiftAndroid` | `MainActivity` WebMessage listener | Native exact-origin host injected by Android; consumed by preload only. |
| `RiftPlatform` | `riftandroid-preload.js` | Read-only Android/APK platform descriptor. |
| `RiftNativeTransport` | `riftandroid-preload.js` | Trusted shell JSON message transport to `RiftAndroid`; consumed by kernel and shell-result bridge. |
| `RiftNative` | `riftcore.js` | Small native service facade over the kernel/native bridge. |
| `RiftOSCore` | `riftcore.js` + workspace adapters | Shared kernel/filesystem/process/permission core. Workspace modules replace it immutably to attach the active workspace API. |
| `RiftAndroidAPI` | `riftandroid-platform.js` | Shell-facing Android service facade for clipboard/share/notifications/browser/system operations. |
| `RiftKernel` | `riftandroid-platform.js` | System subset of the Android service facade. |
| `RiftAndroidBack` | `riftandroid-platform.js` | Callback invoked by native Android Back handling before the Activity exits. |
| `RiftApps` | `riftapps.js` | Installed `.rift` package registry/open/install API; consumed by shell, Files and RiftRT. |
| `RiftAppFiles` | `riftapps-files.js` | Package file import/export helper surface for UI/developer use. |
| `RiftDesktop` | `riftos.js` (+ legacy `riftdesktop-android.js` fallback) | Built-in app operations, persisted desktop settings and the public native/legacy desktop facade. |
| `RiftNativeDesktop` | `riftos.js` + Android `RiftNativeDesktop.kt` | Trusted-shell native-desktop state/request facade. Android owns window chrome/geometry; this surface mirrors authoritative state and sends bounded `desktop.*` requests. |
| `RiftDesktopPlatform` | `riftdesktop-native-compat.js` (+ legacy `riftdesktop-android-compat.js`) | Read-only descriptor for the active Android-native or fallback desktop compatibility layer. |
| `RiftDesktopHost` | `riftdesktop-window-host.js` | Legacy/fallback window-host compatibility surface; not authoritative when `RiftNativeDesktop` is active. |
| `RiftOSWindowManager` | `riftos.js`, wrapped by `riftrt.js` | Canonical window/process registration API shared with RiftRT. |
| `RiftGit` | `riftgit.js` | RiftShell/project GitHub workflow surface. |
| `RiftVault` | `riftvault.js` | Trusted local-first durable object store: content-addressed objects, manifests, verified restore, provider capability state and vault shell commands. |
| `RiftRepo` | `riftrepo.js` | Trusted local source-control API for `/workspace`: checkpoints, manifests, history, diffs, branches, tags, releases and safety rollback. |
| `RiftMemory` | `riftmemory-control.js` | Trusted build/workset cache control plane for local warm-cache prefetch, pin/unpin, status and pruning; does not claim the future C++ accelerator is active. |
| `RiftBuild` | `riftbuild.js` | Trusted build doctor/planner/run history API. Local compilation is fail-closed unless the native host advertises a verified local build executor. |
| `RiftLocalPlatform` | `riftlocal-platform.js` | Aggregates `rift repo`, `rift vault`, `rift build` and `rift memory` under one RiftShell family. |
| `RiftLlmBridge` | `riftllm-bridge.js` + `RiftLlmDevClient.kt` + `RiftTrainDataTaskRunner.kt` | Trusted-shell optional bridge to the standalone RiftLLM APK's fixed token-gated Binder Dev API; owns exact source sync and guarded Workspace publication, bounded local RiftCorpus operations, and the fixed frozen-B2 RiftTrainData/canary controller. It never exposes arbitrary paths/processes/models or RiftLLM-owned Dev Lab state. |
| `RiftDevLab` | `riftdevlab.js` | Trusted in-house Dev Lab API for isolated staging, live experimentation, evidence, snapshots and guarded publication into the local RiftOS workspace. Consumed by the built-in Dev Lab UI, direct `devlab` shell commands and the fixed-whitelist `riftos-agent devlab` controller. |
| `RiftShellBatch` | `riftshell-batch.js` | Batch parser/executor consumed by RiftShell. |
| `RiftShellMcp` | `riftos.js` | Trusted-shell wrapper around the RiftShell parser for native MCP shell execution. Never exposed to guest pages. |
| `RiftShellMcpNative` | `riftos.js` | Temporary trusted-shell compatibility shim used only when `RiftNativeShell` delegates a not-yet-ported command to `RiftShellBridge`; returns `mcp.shell.result` over `RiftNativeTransport`. |
| `RiftWorkspace` | `riftworkspace-web.js`, replaced by `riftworkspace-android-adapter.js` | Active workspace API. Android adapter is authoritative on Android. |
| `RiftWorkspaceJSON` | `riftworkspace-web.js` | JSON-safe trusted compatibility/RPC facade; resolves the active `RiftWorkspace` at call time. |
| `RiftWorkspaceLiveHost` | `riftworkspace-live-host.js` | Compatibility-named trusted-shell host for the local Workspace Records component. |
| `RiftWorkspaceNative` | `riftworkspace-live-host.js` | Trusted-shell callback surface receiving native workspace watcher events. |
| `RiftRuntime` | `riftruntime.js` | Read-only runtime/capability report. |
| `RiftRT` | `riftrt.js` | RiftRT launch/session/manager API. |
| `RiftMcp` | `riftmcp-system.js` | Launcher surface for the native Rift MCP Activity (`riftos://mcp`). |
| `RiftBrowserNative` | `riftos.js` | Native browser-state callback consumed by `MainActivity`/`RiftBrowserWindow`. |
| `RiftTransferUI` | `riftos.js` | Native transfer progress callback consumed by `MainActivity.sendNativeProgress`. |

## Guest AI/browser WebView

These names exist only on exact allowed HTTPS origins and are not equivalent to trusted-shell globals.

| Surface | Owner | Purpose / consumer |
| --- | --- | --- |
| `RiftMcpNative` | `RiftBrowserMcpAppBridge` | Exact-origin WebMessage channel carrying MCP JSON-RPC to native. |
| `RiftMcpAppNative` | `riftbrowser-mcp-app.js` | Page-side response receiver invoked by native `deliver()`. |
| `RiftAIAdapters` | `ai-adapter-registry.js` | Site-specific semantic selectors for supported AI web UIs. |

The guest page must **not** contain `RiftAndroid`, `RiftShellMcp`, `RiftShellMcpNative`, RiftFS, RiftWorkspace or a general native dispatcher surface.

## Sandboxed installed-app surfaces

Installed `.rift` apps do not receive the trusted globals above. They use narrow `postMessage`/injected `Rift` APIs whose method sets are cross-checked by `scripts/validate-rift-wiring.mjs`. RiftRT guests may opt into the permission-gated `Rift.build` controller with `build.local`; that guest surface exposes only doctor/plan/submit/runs/artifacts and does not expose host shell execution. Workspace Records is a trusted shell component with access to these globals; its shadow root isolates layout only.

## Change rule

When adding, renaming or removing a `window.Rift*` / `globalThis.Rift*` export or native-injected Rift bridge name:

1. identify the owning system README;
2. add/update this inventory;
3. update both producer and consumer in one patch;
4. extend wiring validation if the surface crosses a trust boundary;
5. remove obsolete aliases instead of retaining unexplained duplicate paths.
