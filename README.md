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

The shell owns native navigation/file commands and finite routes for native Git, chat handoff, Dev Lab, Vortex, local agents, RiftLLM fixed services, production Rift++ and experimental RiftCLI.

The legacy shell `mount`/`umount` and generic `rift` wrappers are retired in the current source.

## Rift++

Production `riftpp` commands run through bounded non-browser QuickJS:

```text
RiftNativeShell
 -> RiftHeadlessJsRuntime
 -> src/riftpp-core.js
 -> src/riftvm.js
```

Those are the **only** files under `src/` currently copied by Gradle into the generated RiftOS `www` assets.

The headless runtime provides bounded trusted text I/O, UTF-8 and SHA-256 helpers. It does not provide DOM, WebView, arbitrary Android calls, raw process execution or ambient network sockets.

## RiftBrowser

Chromium is not the OS engine. It is owned only by explicit `RiftBrowser*` classes.

`RiftBrowserEngine` is the renderer interface and `RiftBrowserAndroidWebViewEngine` is the current implementation. Gradle has a hard preBuild validator that rejects WebKit/WebView ownership outside the explicit RiftBrowser source allowlist.

`RiftBrowserWindow` implements a bounded tab/navigation/desktop-mode API, but the current native composition only has live callers for open, Back, state and the bounded inspector. Forward/reload/direct navigation/desktop-mode/tab-management methods are implemented but currently unwired; they are not claimed as active UI features. File chooser ownership and the exact-origin MCP compatibility path remain inside RiftBrowser-owned code.

## Installed Rift programs

`MainActivity` scans valid package manifests already present under `C:/Programs`, and `RiftBrowserAppHost` can run those HTML/JS programs in dedicated capability-gated WebViews at `https://app.riftos.local`.

The current native source does **not** contain a live native package installer. The old `src/riftapps.js` installer/runtime code is retained repository/reference source and is not packaged by Gradle. Do not claim package installation is active until a native owner is implemented and verified.

## Rift MCP

`RiftToolHost` is the canonical device-side capability registry. The current catalog is exactly 18 model-visible tools.

Ordinary filesystem/Code Mode tools execute in `RiftToolSandbox` and are confined to the workspace. `rift_shell_exec` is a separately permissioned route to the process-owned native shell.

`RiftToolSandbox` includes:
- bounded filesystem operations;
- SHA-256 file/tree hashing;
- snapshots;
- local project audit/scan;
- deterministic project export;
- Project Intelligence v2 indexing;
- symbol/reference/dependency views;
- guarded range/hunk patches;
- transactional multi-file mutations;
- bounded archive/extract;
- private Workspace Records integration.

The optional relay is outbound-only and does not widen filesystem authority.

## Workspace Records

`RiftWorkspaceRecords` and `RiftWorkspaceWatcher` are native/process-local. They observe the canonical workspace tree and persist private record/checkpoint data outside the workspace itself.

The current built-in Workspace Records UI is native. The old `workspace-live` HTML/JS component remains repository reference source and is not the live built-in.

## Git and secrets

The live Git owner is `RiftNativeGit`, not `src/riftgit.js`.

Git credentials are accessed through `RiftSecretStore`/Android Keystore-backed storage and native Settings. Shell arguments do not carry the GitHub token.

## JavaScript source status

The repository still contains the former web-shell runtime under `src/`, including `riftcore.js`, `riftos.js`, `riftrt.js`, `riftgit.js`, `riftworkspace-*.js` and other modules.

Except for `riftpp-core.js` and `riftvm.js`, those files are **not packaged as the active Android OS runtime**. They are retained for tests, migration/reference behavior and future porting work.

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
