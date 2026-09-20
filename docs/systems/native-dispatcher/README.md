# Native Dispatcher — Retired Boundary

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

This README documents an intentionally absent subsystem.

The former broad `RiftNativeDispatcher.kt` JavaScript-to-Android capability dispatcher has been removed. Current RiftOS uses narrow native owners instead of one general native-call authority.

## Source ownership

There is no maintained `RiftNativeDispatcher.kt` source file.

The retired boundary is verified against:
- current Android Kotlin source tree;
- `MainActivity.kt`;
- Gradle packaging;
- Android manifest;
- current narrow native service owners;
- retained JavaScript that still contains historical `core.native.call(...)` migration/reference calls.

## Current absence proof

Current source contains:
- no `RiftNativeDispatcher.kt`;
- no Kotlin reference to `RiftNativeDispatcher`;
- no manifest component for it;
- no Gradle wiring for it;
- no Kotlin `native.call` bridge implementation.

Therefore no live Android component currently provides a generic method-name dispatcher equivalent to the retired class.

## Replacement ownership

Former broad responsibilities are split among explicit owners:

- workspace MCP/Code Mode -> `RiftToolSandbox`;
- MCP schemas/grants -> `RiftToolHost`;
- shell commands -> `RiftNativeShell`;
- shell service families -> `RiftNativeShellServices`;
- native system windows -> `RiftNativeSystemApps`;
- Files/Editor/Settings/Workspace Records UI -> `RiftNativeWorkspaceApps`;
- Dev Lab transactions -> `RiftNativeDevLab`;
- Git -> `RiftNativeGit`;
- browser rendering -> `RiftBrowser*`;
- installed app renderer/capabilities -> `RiftBrowserAppHost`;
- Vortex Binder -> `RiftVortexBridgeClient`;
- Accessibility/local agent -> `RiftVortexLocalAgent`;
- RiftLLM dev/training routes -> fixed client/task owners;
- preview -> `RiftBrowserPreviewActivity`;
- secrets -> `RiftSecretStore`.

## Retained JavaScript

Several retained `src/` modules still contain historical calls of the form:

`core.native.call("method.name", args)`

Those calls do **not** prove a live dispatcher exists.

Current Gradle packages exactly `riftpp-core.js`, `riftvm.js`, and `semnexis-bootstrap.js` from `src/` for the bounded headless runtime. The old web shell/platform/runtime modules are retained reference/test/migration source unless another subsystem audit proves a separate packaged consumer.

When a retained native-call shim references a route that no longer exists, the correct migration is to remove/fail that shim or wire the feature through its narrow native owner—not recreate a generic dispatcher.

## Security boundary

The retired dispatcher must not return as a convenience layer.

A generic method-name bridge would collapse subsystem-specific validation, path confinement, capability checks and lifecycle ownership back into one high-authority object.

New native functionality must:
1. have one explicit owner;
2. define bounded input/output;
3. enforce its own permissions/capabilities;
4. expose only the narrow caller path required;
5. document and validate that path.

## Non-ownership boundaries

This retired subsystem owns no runtime behavior.

Its README exists only to enforce the architectural absence and direct fixes toward the actual owner.

## Critical invariants

- `RiftNativeDispatcher.kt` remains absent;
- no general JS-to-Android dispatcher is introduced under another name;
- no WebView receives raw filesystem/shell/device authority;
- retained `core.native.call` strings are never treated as proof of live Android capability;
- feature migrations terminate in narrow subsystem owners;
- validators/docs continue to reject broad-dispatcher regressions.

## Failure signatures

- new `RiftNativeDispatcher` class appears -> architecture regression;
- one new class starts switching over unrelated method families -> dispatcher reintroduction;
- retained JS call is made live by adding a broad method bridge instead of a narrow owner -> migration regression;
- browser page gains raw native/filesystem/shell object -> authority collapse;
- docs claim a retained `core.native.call` route is live without current packaged caller/receiver proof -> documentation regression.

## Fix map

Do not fix failures here by recreating this subsystem.

Identify the capability family and patch its narrow owner:
- shell -> RiftShell;
- workspace tools -> MCP sandbox/tool host;
- Git -> RiftNativeGit;
- Files/Editor/Settings -> native workspace apps;
- browser -> RiftBrowser;
- installed apps -> RiftBrowserAppHost;
- local agent/Vortex -> dedicated agent/bridge;
- RiftLLM -> dedicated bridge/task owners;
- secrets -> RiftSecretStore.

## Validation

Source verification must prove:
- retired source absent;
- zero Kotlin references;
- zero manifest/Gradle wiring;
- no equivalent broad switch/dispatcher has replaced it;
- retained JS native calls are classified as non-authoritative unless separately packaged;
- current native capability families resolve to narrow owners.

Installed-device testing is not required to prove file absence, but broader architecture acceptance still depends on Builder/device tests of the replacement owners.
