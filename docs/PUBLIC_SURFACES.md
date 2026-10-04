# RiftOS Public and Cross-Layer Surfaces

This inventory separates live packaged authority from retained reference/compatibility JavaScript. A name in retained source is not automatically a live APK surface.

## Live native/system surfaces

| Surface | Owner | Purpose |
| --- | --- | --- |
| `RiftShellExecutor` | `RiftNativeShell.kt` | Process-owned bounded shell executor used by native Terminal and MCP. |
| Rift++ headless runtime | `RiftHeadlessJsRuntime.kt` | Loads trusted Rift++ Core/RiftVM assets in QuickJS; no DOM/WebView/network/process authority. |
| Semnexis bootstrap runtime | `RiftHeadlessJsRuntime.kt` + `RiftNativeShell.kt` | Fixed bounded `semx` compiler/IR/backend commands over the packaged Semnexis asset; source/output/artifact budgets are enforced, only the two ARM32 artifact commands may write to exact fixed RiftFS paths, generated artifacts are not executed, and the host has no process/network authority. |
| Bounded `qjs` developer runtime | `RiftHeadlessJsRuntime.kt` + `RiftNativeShell.kt` | Evaluates bounded classic JavaScript with captured output and read-only confined RiftFS text access; no file-write/process/network/Android/Git authority. |
| MCP tool catalog | `RiftToolHost.kt` | Canonical fixed 21-tool schemas/permissions/audit, including passive `rift_debug`, persistent read-only `rift_mcp_reconcile`, and the persistent `rift_local_agent_batch` control plane. Batch may orchestrate bounded device/UI work plus existing ToolHost/Sandbox engineering operations such as read/write/patch/search/symbol/reference/audit/scan/diff/export; raw RiftShell batching remains disabled. |
| Workspace/Code Mode | `RiftToolSandbox.kt` + `RiftToolHost.kt` | Workspace-only filesystem and Project Intelligence; model-facing `rift_workspace_exec` is one operation per call with per-call rollback, while multi-op/batch execution is disabled. |
| Workspace Records | `RiftWorkspaceRecords.kt` | Private local history/diff record source. |
| Native desktop | `RiftNativeDesktop.kt` | Android window/taskbar/z-order/geometry authority. |
| Native built-ins | `RiftNativeSystemApps.kt`, `RiftNativeWorkspaceApps.kt` | Terminal, Task Manager, Files, Editor, Dev Lab, Workspace Records and Settings. |
| Native Git | `RiftNativeGit.kt` | Git/GitHub workflow with Android Keystore credential access. |
| Native RiftBuild | `RiftBuildLocalExecutor.kt`, `RiftBuildNativeApp.kt`, `RiftBuildKotlinCompiler.kt`, `RiftBuildManagedToolchains.kt`, `RiftManagedJvmToolService.kt`, `RiftNativeBufferCompilerService.kt`, `RiftApkV2Signer.kt`, `RiftBuildInstaller.kt` | Workspace-bounded Android validation/materialization/package flow plus project-owned hot compiler/runtime infrastructure. Compiler registrations select a bounded execution engine (`native-buffer-v1` or isolated `dex-json-v1`) and an exact-hash payload/seed through `riftbuild-hot.json`; Kotlin is an external managed compiler payload, not RiftOS compiler authority. The same registry can add later compiler adapters (for example Codynex) without adding compiler-specific RiftOS code. APK v2 signing/verification and allowlisted PackageInstaller handoff remain unchanged. |
| Rift++ app diagnostics | `RiftAppDiagnosticBridge.kt` + `RiftNativeShell.kt` | Allowlisted localhost-UDP receiver for fixed Rift++ proof/editor packages. `riftcrash` controls bounded start/status/capture/latest/reset state and persists advisory evidence under `D:/Diagnostics/riftpp`; it does not parse Rift++/ELF semantics or provide privileged tombstone access. |
| Vortex bridge/agents | `RiftVortexBridgeClient.kt`, `RiftVortexLocalAgent.kt` | Fixed local Binder/accessibility development surfaces. |
| RiftLLM Dev/training service | `RiftLlmDevClient.kt`, `RiftTrainDataTaskRunner.kt`, `RiftNativeShellServices.kt` | Fixed bounded standalone RiftLLM API/training commands, including fixed frozen-B2 priming, no-argument RiftPack qualification start/status, and fixed no-argument real process-death recovery start/status. |

## Current source pending installed-device promotion

| Surface | Owner | Purpose |
| --- | --- | --- |
| Managed compiler hot path | `RiftBuildManagedToolchains.kt`, `RiftNativeBufferCompilerService.kt`, `RiftManagedJvmToolService.kt`, `RiftBuildLocalExecutor.kt` | Project-owned exact-hash compiler payloads execute through `native-buffer-v1` or `dex-json-v1`; Projects use the generic compiler registry plus project-owned `runtimeProfile -> prepare-native-app -> pack -> sign -> verify -> install-proof`. The former App0/Seed0/editor special-case RiftBuild prepare routes are retired. |
| Codynex Editor transport | `RiftCodynexEditorBridgeClient.kt`, `CodynexEditorBridgeService.kt`, `RiftNativeShell.kt` | Explicit Binder transport from RiftOS into `com.codynex.editor`; supports bounded file/folder transfer plus editor-owned compile, preview/native proof, and fresh-compile-before-pack/sign APK builds. RiftOS has no Codynex compiler/provider authority. |
| Codynex Editor compiler/runtime | `CodynexEditorToolchainPort.kt`, `CodynexCompilerRuntime.kt`, `CodynexRuntimeBridge.kt`, `codynex_editor_vm` | The editor owns bounded compiler execution and preview. Compiler semantics come from the editor-owned payload API, with `.codynex/toolchains/compiler.js` as the hot override and bundled `codynex_compiler.js` as fallback; RiftOS only mirrors/gates the generic host/runtime payload. |

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
- `src/riftvm.js` -> RiftVM implementation;
- `src/semnexis-bootstrap.js` -> bounded Semnexis bootstrap compiler, versioned SNIRV0–SNIRV7 Native IR codec, Arena/state lowering, ARM32 proof/runtime backends and canonical machine verifier.

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
