# Boot and Engine Startup

## 2026-10-10 — New bootstrap/recovery ownership roadmap (PLANNED, NOT VERIFIED)

For active implementation gates see [external Core/Shell roadmap](../android-host/EXTERNAL_CORE_SHELL_ROADMAP.md). The real Android `Application` is the protected Core-process bootstrap; `ContentProvider` must not prematurely create an embedded Core before one-time startup selection. The permanent host is planned to own external artifact verification, exact version selection, crash/restart supervisor, previous external N-1 rollback and startup safety. Core process death is handled on **a later permitted restart by a surviving host/OS component**; not by the terminated Core. `RiftShellActivity` stays the Android graphical entrypoint while the real presentation/controller moves to an independent Shell artifact, recovering without resetting Core sessions. MCP/relay stay APK-hosted. **This is a future plan, not a claim the current E1 selector/supervisor has passed physical Android proof**. The older diagrams below record their dated state.

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

The current RiftOS boot path creates the Android-native engine directly. It does not load an HTML shell or sequential JavaScript module chain.

## Source ownership

- `MainActivity.kt` — visible OS boot/composition.
- `RiftMcpRuntime.kt` — lazy process-owned native service graph.
- `RiftWorkspaceRecords.kt` / `RiftWorkspaceWatcher.kt` — workspace record startup.
- `RiftNativeDesktop.kt` — desktop bootstrap.
- `RiftNativeSystemApps.kt` / `RiftNativeWorkspaceApps.kt` — built-in app bodies.
- `RiftBrowserWindow.kt` / `RiftBrowserAppHost.kt` — explicit renderer owners.
- `android/app/src/main/AndroidManifest.xml` — Android component entry points.
- `android/app/build.gradle.kts` — required native source and packaged-asset gates.

Retained `index.html`, `src/riftandroid-entry.js` and `src/riftandroid-preload.js` describe the retired web-shell boot path and are not packaged as the active OS bootstrap.

## Runtime flow

```text
Android launches MainActivity
 -> register Activity with RiftMcpRuntime
 -> apply native window/inset policy
 -> start Workspace Records watcher
 -> construct RiftNativeDesktop
 -> construct RiftBrowser/native built-ins
 -> populate native launcher
 -> bootstrap desktop
 -> start process-owned outbound MCP relay client
```

`RiftMcpRuntime` lazily creates native shell, tool host, MCP server, relay client, native Git and Vortex bridge outside WebView ownership. C0.1 removed the Codynex/Rift++ editor Binder clients and shell commands; editor-specific operation must be owned by independently installed software, not RiftShell.

## Android components

Manifest-declared runtime components currently include:
- `MainActivity` — launcher / singleTask native desktop Activity;
- `RiftMcpActivity` — browsable `riftos://mcp` configuration/status Activity;
- `RiftBrowserPreviewActivity` — non-exported preview renderer;
- `RiftVortexAccessibilityService` — user-enabled fixed-scope local UI agent.

## Packaged JavaScript at boot

No JavaScript is required to boot the OS desktop.

Gradle copies only:
- `src/riftpp-core.js`
- `src/riftvm.js`
- `src/semnexis-bootstrap.js`

They are loaded later by the bounded headless QuickJS runtime when requested. Rift++ uses `riftpp-core.js` + `riftvm.js`; `semx` uses `semnexis-bootstrap.js`.

## Critical invariants

- boot cannot depend on `index.html`, `riftandroid-entry.js`, `RiftAndroid`, `RiftOSCore` or a shell WebView;
- native desktop must exist before attaching renderer/program content;
- process-owned MCP/shell services must not be destroyed with browser renderers;
- manifest components and actual Activity/service source must stay synchronized;
- generated asset sync must remain limited to the explicitly required headless modules.

## Failure signatures

- APK starts a web splash/HTML shell instead of native desktop → old bootstrap returned;
- cold launch requires `RiftAndroid`/`RiftOSCore` → stale web boot dependency;
- MCP/native shell unavailable until browser opens → process runtime initialization regression;
- old JS behavior appears after build → unexpected Gradle asset packaging;
- launcher misses installed program already under `C:/Programs` → native launcher/package scan issue.

## Fix map

Startup composition → `MainActivity.kt`.

Process-owned services → `RiftMcpRuntime.kt`.

Component declarations → `AndroidManifest.xml`.

Packaged asset rules → `android/app/build.gradle.kts`.

Desktop boot state → `RiftNativeDesktop.kt`.

## Validation

Inspect the Gradle asset include set and manifest, run source wiring/transport validation, then perform a true cold APK launch. Confirm the native desktop appears without browser initialization, native Terminal/MCP works before opening RiftBrowser, and Activity/background cycles do not reset process-owned shell/MCP state.
