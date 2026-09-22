# RiftOS

RiftOS is an Android-hosted user-space operating environment. The current source architecture is Android-native: Android owns the real process/security boundary, RiftOS supplies its own desktop, filesystem namespace, shell, application surfaces, workspace tooling and local MCP capability layer.

## Verification status

**ENGINE DOCUMENTATION VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

The native migration in the local source tree is source-audited. It is **not yet claimed build/device-proven** until the updated source validators, Android Builder, install and live abuse gate pass.

## Current engine

```text
Android process
├─ MainActivity
│  ├─ RiftNativeDesktop
│  ├─ RiftNativeSystemApps
│  ├─ RiftNativeWorkspaceApps
│  ├─ RiftBrowserWindow
│  ├─ RiftBrowserAppHost
│  ├─ RiftWorkspaceRecords
│  └─ RiftWorkspaceWatcher
│
├─ RiftMcpRuntime
│  ├─ RiftNativeShell
│  ├─ RiftNativeGit
│  ├─ RiftToolHost
│  │  └─ RiftToolSandbox
│  ├─ RiftMcpServer
│  ├─ RiftMcpRelayClient
│  └─ RiftVortexBridgeClient
│
├─ RiftHeadlessJsRuntime
│  └─ QuickJS -> Rift++ Core + RiftVM
│
└─ app-private RiftFS / preferences / Keystore
```

`RiftKernel` is the logical name of the protected RiftOS engine identity. It is not a JavaScript kernel hosted in a shell WebView.

## Native desktop and built-ins

`MainActivity` boots the native desktop directly. `RiftNativeDesktop` owns window frames, bounds, focus, z-order, minimize/maximize/restore/close, launcher and taskbar behavior.

Current built-in bodies are native Android Views:
- Files
- Editor
- Dev Lab
- Workspace Records
- Settings
- RiftShell Terminal
- Task Manager

RiftBrowser is a separate renderer surface attached to native desktop windows.

## RiftFS

RiftOS storage is rooted at app-private `filesDir/riftfs`.

`RiftVolumePaths` supplies fixed display aliases:
- `C:/` — RiftOS system/program state
- `D:/` — user/workspace data

`D:/Workspace` maps to the canonical workspace backing tree. MCP filesystem/Code Mode operations are independently hard-scoped to `filesDir/riftfs/workspace`.

Native Files also supports user-granted Android Storage Access Framework document-tree mounts under its `/Android` view. Those mounts are not raw filesystem escape paths and are not exposed to workspace MCP tools.

## Native RiftShell

`RiftNativeShell` is process-owned through `RiftMcpRuntime`. It does not depend on a particular Activity or WebView lifetime while the Android process remains alive, and it does not expose Android/Linux `/system/bin/sh`. Android process death destroys the in-memory singleton and a new process recreates it lazily.

The shell owns native navigation/file commands and finite routes for native Git, chat handoff, Dev Lab, Vortex, local agents, RiftLLM fixed services, the Codynex LR0 Binder bridge, production Rift++, Semnexis, and experimental RiftCLI.

The legacy shell `mount`/`umount` and generic `rift` wrappers are retired in the current source.

## Rift++

Production `riftpp` commands run through bounded non-browser QuickJS:

```text
RiftNativeShell
 -> RiftHeadlessJsRuntime
 -> src/riftpp-core.js
 -> src/riftvm.js
```

Gradle currently copies exactly three files from `src/` into the generated RiftOS `www` assets: `riftpp-core.js`, `riftvm.js`, and `semnexis-bootstrap.js`. The first two serve production Rift++; the third is the bounded Semnexis QuickJS bootstrap compiler.

The headless runtime provides bounded trusted text I/O, UTF-8 and SHA-256 helpers. It does not provide DOM, WebView, arbitrary Android calls, raw process execution or ambient network sockets.

## Semnexis

`semx` uses the same bounded headless QuickJS host with the separately packaged `src/semnexis-bootstrap.js` compiler asset. Current source is `0.7.0-quickjs-bootstrap` with versioned `SNIRV0`–`SNIRV7`, borrowed `Slice<u8>`, flat records, Arena-backed AST storage, typed Arena load/store, bounded 256-frame native recursion, and source/machine-proven recursive-descent parsing on ARM32. The source-embedded gate is `semnexis-bootstrap-self-test/17`; installed-device promotion waits for the next RiftOS APK build/install.

## RiftBrowser

Chromium is not the OS engine. It is owned only by explicit `RiftBrowser*` classes.

`RiftBrowserEngine` is the renderer interface and `RiftBrowserAndroidWebViewEngine` is the current implementation. Gradle has a hard preBuild validator that rejects WebKit/WebView ownership outside the explicit RiftBrowser source allowlist.

`RiftBrowserWindow` implements a bounded tab/navigation/desktop-mode API, but the current native composition only has live callers for open, Back, state and the bounded inspector. Forward/reload/direct navigation/desktop-mode/tab-management methods are implemented but currently unwired; they are not claimed as active UI features. File chooser ownership and the exact-origin MCP compatibility path remain inside RiftBrowser-owned code.

## Installed Rift programs

`MainActivity` scans valid package manifests already present under `C:/Programs`, and `RiftBrowserAppHost` can run those HTML/JS programs in dedicated capability-gated WebViews at `https://app.riftos.local`.

Current source contains the bounded `RiftBuildInstaller` proof installer, restricted to verified signed `com.riftpp.nativeproof` artifacts, Android-managed unknown-source/user confirmation, persisted install status and an exact NativeActivity launch proof. That newest signer/install path is still source-only until the next Builder/install pass; it is **not** a general Rift app installer. The old `src/riftapps.js` installer/runtime remains retained repository/reference source and is not packaged by Gradle.

## Rift MCP

`RiftToolHost` is the canonical device-side capability registry. The device manifest currently declares exactly 19 MCP tools, including passive/read-only `rift_debug`. A client may temporarily expose fewer actions if its connector catalog is cached; reconnect/refresh is required when the client count differs from the manifest.

Ordinary filesystem/Code Mode tools execute in `RiftToolSandbox` and are confined to the workspace. `rift_shell_exec` is a separately permissioned route to the process-owned native shell.

`RiftToolSandbox` includes:
- bounded filesystem operations;
- SHA-256 file/tree hashing;
- snapshots;
- local project audit/scan;
- deterministic project export;
- Project Intelligence v2 indexing;
- symbol/reference/dependency views;
- N1.8 Repository Consistency Observer content-truth + cache hardening above PI-v2/Workspace Records: installed Patch 10.35 source `8ecc5433dcca153a669b09f942bc91e64965c0fa` live-proved deterministic restart parity at graph `d9064a2ba8f1742116aa0f88e67110a5022907a9bf699bab9d61f2a27e7109d7`, ambiguity fail-closed resolution, 1024-file/1024-edge exact bounds, mutation-snapshot coherence, path/content identity, metadata-only evidence, concurrent reads and observer-cache eviction/rebuild; the torture audit then found that PI cache v4 protected producer provenance and file SHA but did not integrity-seal cached `symbols`/`dependencies`, so Patch 10.36 upgrades persistence to PI cache v5 with a canonical SHA-256 whole-payload seal and fail-closed integrity rejection; Builder/install/restart proof of v5 is still required before N1.8.0 promotion, while the staged subsystem/domain planner remains design-only;
- guarded range/hunk patches;
- guarded one-operation Code Mode mutations with copy-on-write rollback; multi-op/batch execution is fail-fast disabled;
- bounded archive/extract;
- private Workspace Records integration.

The optional relay is outbound-only and does not widen filesystem authority.

## Workspace Records

`RiftWorkspaceRecords` and `RiftWorkspaceWatcher` are native/process-local. They observe the canonical workspace tree and persist private record/checkpoint data outside the workspace itself.

The current built-in Workspace Records UI is native. The old `workspace-live` HTML/JS component remains repository reference source and is not the live built-in.

The locked N1.8 Repository Consistency Observer reuses Workspace Records + Project Intelligence V2 rather than creating a second index. N1.8.0 must survive the installed full-repository torture gate before promotion; if it fails, the foundation is hardened and re-tested rather than stacking another observer layer on top. Installed Patch 10.35 source `8ecc5433dcca153a669b09f942bc91e64965c0fa` fixed the restart-order divergence and live-proved exact process-restart graph parity at `d9064a2ba8f1742116aa0f88e67110a5022907a9bf699bab9d61f2a27e7109d7`, plus warm determinism, concurrency, exact file/edge boundaries, mutation coherence, path/content identity, ambiguity fail-closed semantics and observer-cache eviction/rebuild. The continuing torture audit then found that PI cache v4 lacked an integrity seal over the cached semantic payload itself, meaning a syntactically valid corrupted row with a correct source SHA could theoretically alter cached symbols/dependencies and still be reused. Patch 10.36 therefore upgrades PI persistence to cache v5 with a canonical SHA-256 whole-payload seal; missing or mismatched seals fail closed before producer-bound rows are loaded. Local Android source validation is green; Builder/Kotlin compile, install and true restart proof of v5 remain required. The future everyday execution model is design-locked as staged subsystem/domain scanning with graph-driven frontier expansion, while clean full-repository scans remain the independent correctness oracle. That staged planner is not implemented until N1.8.0 promotion. The canonical architecture, torture matrix, benchmark corpus and N1.8.0-N1.8.7 gates are in `docs/systems/workspace/REPOSITORY_CONSISTENCY_OBSERVER.md`. N2 is blocked until N1.8 promotion.

## Git and secrets

The live Git owner is `RiftNativeGit`, not `src/riftgit.js`.

Git credentials are accessed through `RiftSecretStore`/Android Keystore-backed storage and native Settings. Shell arguments do not carry the GitHub token.

## JavaScript source status

The repository still contains the former web-shell runtime under `src/`, including `riftcore.js`, `riftos.js`, `riftrt.js`, `riftgit.js`, `riftworkspace-*.js` and other modules.

Except for `riftpp-core.js`, `riftvm.js`, and `semnexis-bootstrap.js`, those files are **not packaged as active Android headless-runtime assets**. They are retained for tests, migration/reference behavior and future porting work.

Presence in `src/` does not mean “live APK code.”

## Android target

Current Gradle source declares:
- package: `com.riftos.app`
- compile SDK: 36
- target SDK: 36
- minimum SDK: 26
- Java source/target: 17
- version name: `0.11.11-relay-client`
- release minification: disabled

Manifest runtime components are the native launcher Activity, Rift MCP Activity, RiftBrowser preview Activity and the user-enabled fixed-scope Accessibility service.

## Current promotion gate

For this native migration, the order is:

1. code-first source audit;
2. documentation rebuilt from source;
3. second independent audit for missed/stale claims;
4. `npm run check` / native architecture validators;
5. explicit push only when authorized;
6. external Android Builder;
7. install exact artifact;
8. cold-start + lifecycle + browser-renderer + MCP/shell abuse;
9. only then treat the migrated runtime as device-proven.

## Documentation

Start with:
- [`docs/systems/engine/README.md`](docs/systems/engine/README.md) — verified engine authority map;
- [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md) — current source status and unproven boundaries;
- [`docs/README.md`](docs/README.md) — subsystem maintenance index;
- [`docs/SOURCE_OWNERSHIP.md`](docs/SOURCE_OWNERSHIP.md) — source-to-owner ledger;
- [`docs/TRUE_OS_ARCHITECTURE.md`](docs/TRUE_OS_ARCHITECTURE.md) — host/security boundary;
- [`docs/ANDROID_NATIVE_ARCHITECTURE.md`](docs/ANDROID_NATIVE_ARCHITECTURE.md) — Android-native composition;
- [`docs/systems/build-validation/README.md`](docs/systems/build-validation/README.md) — promotion gates.

No documentation is authoritative merely because it exists. Treat a subsystem README as trusted only after it has been audited against its current owning source.

## RiftDebugHub

Current source includes a process-wide passive debugger foundation:

- `RiftDebugHub` retains a bounded in-memory event timeline and active-span table;
- `RiftDebugAdapter` and `RiftDebugSink` are the common plug for additional subsystems;
- MCP Server and Tool Host already publish correlated parent/child spans;
- MCP results return `riftos/traceId`;
- the read-only `rift_debug` tool exposes `status`, `events`, `active` and `components`;
- secret-like attribute keys are redacted, payload bodies are not retained, and all fields/collections are bounded;
- the debugger has no execution, mutation, cancellation, filesystem, network or model authority.

See [the verified debugger subsystem document](docs/systems/debugger/README.md).
