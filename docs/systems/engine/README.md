# RiftOS Engine

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

This document was rebuilt from Kotlin, Gradle and manifest source. Historical documentation was not treated as evidence. If any listed owner changes, this document must be re-audited before it is treated as trusted again.

## Purpose

The RiftOS engine is the live Android-native control plane that owns OS lifecycle, desktop/window state, built-in applications, the native shell, RiftFS path authority, MCP execution, workspace tooling, installed-program hosting and the bounded Rift++ runtime.

“RiftKernel” remains the logical name of the protected RiftOS engine/process identity. It is **not** one JavaScript runtime and is **not** hosted in a shell WebView.

## Live engine authority

```text
Android process
├─ MainActivity
│  ├─ RiftNativeDesktop
│  │  ├─ RiftNativeSystemApps
│  │  ├─ RiftNativeWorkspaceApps
│  │  ├─ RiftBrowserWindow
│  │  └─ RiftBrowserAppHost
│  ├─ RiftWorkspaceRecords
│  └─ RiftWorkspaceWatcher
│
├─ RiftMcpRuntime
│  ├─ RiftNativeShell
│  │  ├─ RiftNativeGit
│  │  ├─ RiftNativeShellServices
│  │  └─ RiftHeadlessJsRuntime
│  ├─ RiftToolHost
│  │  └─ RiftToolSandbox
│  ├─ RiftMcpServer
│  ├─ RiftMcpRelayClient
│  └─ RiftVortexBridgeClient
│
└─ app-private storage
   ├─ filesDir/riftfs
   ├─ workspace records / project-intelligence state
   ├─ scoped preferences
   └─ Android Keystore-backed secrets
```

## Source ownership

Primary engine composition:
- `android/app/src/main/java/com/riftos/app/MainActivity.kt`
- `RiftMcpRuntime.kt`
- `RiftNativeDesktop.kt`
- `RiftNativeSystemApps.kt`
- `RiftNativeWorkspaceApps.kt`
- `RiftNativeShell.kt`
- `RiftNativeShellServices.kt`
- `RiftShellExecutor.kt`
- `RiftVolumePaths.kt`

Capability/tool engine:
- `RiftToolHost.kt`
- `RiftToolSandbox.kt`
- `RiftMcpServer.kt`
- `RiftMcpRelayClient.kt`
- `RiftWorkspaceRecords.kt`
- `RiftWorkspaceWatcher.kt`

Execution engines:
- `RiftHeadlessJsRuntime.kt` — trusted non-browser QuickJS host for Rift++ Core/RiftVM.
- `RiftBrowserEngine.kt` — browser renderer interface only.
- `RiftBrowserAndroidWebViewEngine.kt` — current RiftBrowser renderer backend.
- `RiftBrowserAppHost.kt` — installed HTML/JS program execution.
- `RiftBrowserPreviewActivity.kt` — bounded preview renderer.

Build/runtime boundary:
- `android/app/build.gradle.kts`
- `android/app/src/main/AndroidManifest.xml`

## Boot and lifecycle

`MainActivity.onCreate()` is the visible OS boot entry. It:

1. registers the active Activity with process-owned `RiftMcpRuntime`;
2. creates the native root View/inset policy;
3. starts `RiftWorkspaceRecords`/`RiftWorkspaceWatcher`;
4. creates `RiftNativeDesktop`;
5. creates RiftBrowser, native system apps and native workspace apps;
6. populates the launcher from built-ins plus valid packages under `C:/Programs`;
7. bootstraps the desktop;
8. starts the process-owned outbound MCP relay client.

No HTML page, JavaScript boot chain, `RiftAndroid` bridge or shell WebView participates in live OS boot.

During same-process MainActivity destruction/recreation, `RiftMcpRuntime` keeps native shell, MCP host/server/relay, native Git and Vortex bridge independent of browser renderer/Activity lifetime. This does **not** survive Android process death: process death destroys those in-memory singletons and a new process reconstructs them lazily.

## Logical process model

The native shell reports three protected logical authorities:
- `kernel` → `RiftKernel`
- `desktop` → `Rift Desktop`
- `shell` → `Native RiftShell`

Desktop windows are then reported as user-visible tasks. This is a RiftOS logical task model, not Android/Linux PID emulation. Protected authorities cannot be killed through the native shell.

## RiftFS and path engine

Physical RiftOS storage is app-private `filesDir/riftfs`.

`RiftVolumePaths.kt` owns the fixed native C:/D: display mapping. Workspace MCP tooling is separately hard-confined by `RiftToolSandbox` to `filesDir/riftfs/workspace`.

External Android folders are not part of the engine root namespace by raw path. Native Files owns user-granted SAF document-tree access.

## MCP/tool engine

`RiftMcpRuntime` constructs one process-owned `RiftToolHost` and `RiftMcpServer`.

The model-visible catalog is exactly 18 tools. Ordinary filesystem/Code Mode operations execute in `RiftToolSandbox`; `rift_shell_exec` executes through process-owned `RiftNativeShell`.

`RiftToolSandbox` owns:
- workspace containment;
- bounded read/write/list/search;
- hashing and snapshots;
- Project Intelligence v2 indexing through the shared `RiftSourceIntelligenceV2` lexical analyzer;
- symbols/references/graph views;
- internal Patch-5 candidate semantic-impact evidence derived from Patch Manifest V1, with no added MCP tool;
- guarded patches;
- transactional multi-file mutations;
- archive/extract bounds;
- workspace record integration.

MCP is not the RiftOS filesystem engine as a whole; it is a narrower capability surface over workspace plus the separately permissioned native shell.

## Rift++ execution engine

Production `riftpp` commands route:

```text
RiftNativeShell
 -> RiftHeadlessJsRuntime
 -> QuickJS
 -> packaged src/riftpp-core.js
 -> packaged src/riftvm.js
```

The headless runtime exposes only bounded trusted functions such as confined RiftFS text I/O, UTF-8 and SHA-256. It has no DOM, WebView, arbitrary Android call, raw process execution or ambient network socket.

Gradle packages **only** `src/riftpp-core.js` and `src/riftvm.js` from the old `src/` tree into the generated `www` assets.

## Browser/program execution engine

Chromium is not the OS engine. It is a renderer owned only by explicit `RiftBrowser*` classes.

`RiftBrowserEngine` is the renderer contract. `RiftBrowserAndroidWebViewEngine` is the current implementation. `RiftBrowserWindow` implements bounded tab/navigation/desktop-mode methods, but the current Kotlin composition only calls open, Back, state and inspect; forward/reload/direct navigation/desktop-mode/tab-management APIs are presently implemented-but-unwired, and MainActivity supplies a no-op state sink.

Installed HTML/JS programs already present under `C:/Programs/<id>/package.json` are opened by `RiftBrowserAppHost` in dedicated capability-gated WebViews at `https://app.riftos.local`.

There is no live Kotlin `RiftRT` class and `src/riftrt.js` is not packaged by Gradle. Historical RiftRT JavaScript remains repository reference/test source unless and until a live native owner explicitly packages/uses it.

## Packaged versus retained JavaScript

### Packaged live headless assets
- `src/riftpp-core.js`
- `src/riftvm.js`

### Retained repository/reference/test source, not live APK shell
Examples include:
- `src/riftcore.js`
- `src/riftandroid-entry.js`
- `src/riftandroid-preload.js`
- `src/riftandroid-platform.js`
- `src/riftos.js`
- `src/riftrt.js`
- `src/riftapps.js`
- `src/riftgit.js`
- `src/riftworkspace-*.js`
- `src/riftdevlab.js`
- `src/riftlocal-platform.js`

These files may still be used by Node regression tests, migration/reference work or historical contracts. Their globals must not be documented as live Android APK authority unless Gradle/source proves they are packaged and invoked.

## Hard invariants

- `MainActivity` owns no WebView/WebKit imports.
- only explicit `RiftBrowser*` sources own Chromium/WebKit code;
- no trusted shell WebView exists;
- no `RiftShellBridge` or general `RiftNativeDispatcher` exists;
- native shell/MCP authority is process-owned;
- workspace MCP cannot escape `riftfs/workspace`;
- only Rift++ Core/VM JS is packaged for headless execution;
- logical RiftKernel identity must not be confused with `src/riftcore.js`;
- Android framework/process sandbox remains the real host-security boundary.

## Failure signatures

- desktop/shell/MCP dies because a WebView renderer died → process/renderer ownership regression;
- docs say `riftcore.js` is the installed kernel → documentation regression;
- `src/riftrt.js` behavior is assumed live without a packaged caller → runtime-status regression;
- MCP accesses outside workspace → sandbox containment regression;
- Rift++ requires a browser → headless runtime regression;
- WebKit import appears outside `RiftBrowser*` → build ownership violation;
- same-process Activity recreation destroys native shell/Git/MCP singleton state → process ownership regression.

## Fix map

OS composition/lifecycle → `MainActivity.kt`.

Process-owned engine services → `RiftMcpRuntime.kt`.

Window/task authority → `RiftNativeDesktop.kt`.

Native built-ins → `RiftNativeSystemApps.kt` / `RiftNativeWorkspaceApps.kt`.

Shell/path/command authority → `RiftNativeShell.kt` / `RiftVolumePaths.kt`.

Workspace capability engine → `RiftToolHost.kt` / `RiftToolSandbox.kt`.

Rift++ runtime → `RiftHeadlessJsRuntime.kt` + `src/riftpp-core.js` + `src/riftvm.js`.

Browser renderer → `RiftBrowser*` classes only.

## Validation

Engine validation must include:
- Gradle packaged-asset inspection;
- manifest/component inspection;
- exact WebKit-owner scan;
- exact MCP tool catalog check;
- source ownership check;
- absence checks for retired dispatcher/bridge classes;
- source validators;
- Android Builder compile/package/sign gate;
- installed-device cold boot, same-process Activity recreation, true process-kill/relaunch, native window abuse, browser renderer failure and MCP/native-shell survival.

Static documentation is trusted only after those source relationships have been rechecked.
