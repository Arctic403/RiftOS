# RiftOS Public and Cross-Layer Surfaces

This inventory separates live packaged authority from retained reference/compatibility JavaScript. A name in retained source is not automatically a live APK surface.

## Live native/system surfaces

| Surface | Owner | Purpose |
| --- | --- | --- |
| `RiftShellExecutor` | `RiftNativeShell.kt` | Process-owned bounded shell executor used by native Terminal and MCP. |
| Rift++ headless runtime | `RiftHeadlessJsRuntime.kt` | Loads trusted Rift++ Core/RiftVM assets in QuickJS; no DOM/WebView/network/process authority. |
| Semnexis bootstrap runtime | `RiftHeadlessJsRuntime.kt` + `RiftNativeShell.kt` | Fixed bounded `semx` compiler/IR/backend commands over the packaged Semnexis asset; source/output/artifact budgets are enforced, only the two ARM32 artifact commands may write to exact fixed RiftFS paths, generated artifacts are not executed, and the host has no process/network authority. |
| Bounded `qjs` developer runtime | `RiftHeadlessJsRuntime.kt` + `RiftNativeShell.kt` | Evaluates bounded classic JavaScript with captured output and read-only confined RiftFS text access; no file-write/process/network/Android/Git authority. |
| MCP tool catalog | `RiftToolHost.kt` | Canonical fixed 19-tool schemas/permissions/audit, including passive `rift_debug`. |
| Workspace/Code Mode | `RiftToolSandbox.kt` + `RiftToolHost.kt` | Workspace-only filesystem and Project Intelligence; model-facing `rift_workspace_exec` is one operation per call with per-call rollback, while multi-op/batch execution is disabled. |
| Workspace Records | `RiftWorkspaceRecords.kt` | Private local history/diff record source. |
| Native desktop | `RiftNativeDesktop.kt` | Android window/taskbar/z-order/geometry authority. |
| Native built-ins | `RiftNativeSystemApps.kt`, `RiftNativeWorkspaceApps.kt` | Terminal, Task Manager, Files, Editor, Dev Lab, Workspace Records and Settings. |
| Native Git | `RiftNativeGit.kt` | Git/GitHub workflow with Android Keystore credential access. |
| Native RiftBuild | `RiftBuildLocalExecutor.kt`, `RiftApkV2Signer.kt`, `RiftBuildInstaller.kt` | Workspace-bounded Android validation/materialization/package flow plus bounded APK v2 signing/verification and allowlisted PackageInstaller handoff; supports NativeActivity proofs and the code-bearing Codynex E0 editor via `classes*.dex`, with no raw process or arbitrary package authority. |
| Vortex bridge/agents | `RiftVortexBridgeClient.kt`, `RiftVortexLocalAgent.kt` | Fixed local Binder/accessibility development surfaces. |
| RiftLLM Dev/training service | `RiftLlmDevClient.kt`, `RiftTrainDataTaskRunner.kt`, `RiftNativeShellServices.kt` | Fixed bounded standalone RiftLLM API/training commands. |

## Current source pending installed-device promotion

| Surface | Owner | Purpose |
| --- | --- | --- |
| Codynex LR0 local bridge | `RiftCodynexBridgeClient.kt`, `RiftMcpRuntime.kt`, `RiftNativeShellServices.kt` | Explicit-package Binder bridge exposed only through the fixed `codynex` shell family; bounded request/response/timeout/source limits; next RiftOS Builder/install pass must prove installed-device behavior. |
| Codynex E0 local editor packaging | `RiftBuildLocalExecutor.kt` + mirrored `com.codynex.editor*` payload + `codynex_editor_vm` | `prepare-codynex-editor` verifies local Codynex source hashes, extracts compiled DEX/native payload from the installed RiftOS APK, materializes canonical VM/compiler/source assets, then uses the local pack/sign/verify/install path; installed-device promotion is pending the next RiftOS rebuild. |

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
