# RiftKernel

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

`RiftKernel` is the logical name of RiftOS's protected engine authority. In the current Android architecture it is **not a standalone JavaScript kernel** and it is not hosted by a WebView.

The live engine is split across native owners documented in [`../engine/README.md`](../engine/README.md).

## Source ownership

The logical kernel identity is represented by:
- `RiftNativeShell.kt` — reports/protects the logical `kernel` task and owns shell/path/process-facing behavior;
- `RiftMcpRuntime.kt` — process-owned shell/MCP/Git/Vortex services;
- `MainActivity.kt` — visible native OS composition;
- `RiftNativeDesktop.kt` — native window/task authority;
- `RiftVolumePaths.kt` — native RiftFS display-path mapping;
- `RiftToolHost.kt` / `RiftToolSandbox.kt` — model capability/workspace authority.

`src/riftcore.js` still contains the historical `AndroidKernel`, `RiftOSCore`, `RiftFS`, `ProcessTable` and `PermissionBroker` implementation used by retained reference/tests. Gradle does **not** package that file into the current APK.

## Runtime flow

```text
Android process
 -> process-owned RiftMcpRuntime
 -> RiftNativeShell / MCP / native services
 -> app-private RiftFS

MainActivity
 -> RiftNativeDesktop
 -> native built-ins / explicit RiftBrowser renderers
```

The name `RiftKernel` therefore refers to this logical native engine boundary, not to one JS object.

## Critical invariants

- do not document `globalThis.RiftOSCore` as live APK authority;
- do not reintroduce a shell WebView to host kernel state;
- logical protected tasks (`kernel`, `desktop`, `shell`) must remain non-killable through RiftShell;
- Android/Linux remains the actual process/security kernel;
- native subsystem policy stays in narrow owners rather than a replacement catch-all kernel object.

## Failure signatures

- code expects `RiftOSCore` during normal APK boot → old web runtime leaked back into active architecture;
- `kernel` logical task can be terminated → native process model regression;
- browser crash takes down shell/MCP → logical kernel authority incorrectly depends on renderer lifecycle;
- docs point kernel fixes at `src/riftcore.js` for live Android behavior → stale documentation.

## Fix map

Live kernel/engine behavior → [`../engine/README.md`](../engine/README.md) and its narrow native owners.

Historical/reference JS semantics → `src/riftcore.js` only when a test or migration task explicitly targets that retained source.

## Validation

Verify Gradle does not package `src/riftcore.js`, inspect the native protected-process list, verify process-owned `RiftMcpRuntime`, and run the WebView ownership/native wiring validators. Device acceptance must prove native shell/MCP survives browser renderer and Activity lifecycle changes.
