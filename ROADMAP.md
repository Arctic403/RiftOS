# RiftOS Roadmap

This roadmap describes intended work, not shipped capability. Current implementation status is tracked in `docs/PROJECT_STATUS.md`.

## Now: stabilize the Android desktop

- Keep `main` authoritative and use `android-apk` for staged validation when useful.
- Harden RiftBrowser native-surface move/resize/focus behavior across phones, tablets and DeX.
- Harden **Rift Agent v3 Persistent Tool Runtime** against ChatGPT DOM changes without widening its exact-origin sandbox.
- Exercise automatic rebootstrap/recovery paths for long conversations and ChatGPT UI changes while keeping normal task overhead tiny.
- Add focused on-device diagnostics and exported test results rather than emulator-heavy CI.
- Continue reducing legacy web/iOS-only code from Android packaging.

## Next: RiftScript Studio

Build a privileged RiftOS developer application inspired by Blender's scripting workspace, but scoped to the RiftOS engine.

Planned surfaces:

- live JavaScript/module editing and hot reload,
- live CSS/DOM editing,
- RiftDesktop/window inspector,
- RiftKernel/service inspector,
- RiftFS/RiftWorkspace explorer and editor,
- RiftBrowser state/console inspection,
- structured logs and event tracing,
- reusable `.rift.js` developer scripts,
- in-device test runner,
- developer overlay filesystem with revert/commit,
- Development Snapshot export containing source state, diffs, diagnostics, tests and logs.

Native Kotlin/manifest/Gradle source may be edited/staged/exported in RiftScript, but compiled Android code still requires a new APK build before it can become active.

## Persistent Agent expansion

Rift Agent v3 establishes the custom persistent-tool runtime: one compact bootstrap per ChatGPT conversation, tiny normal-turn marker, browser-enforced validation, hidden result plumbing and no model-visible session token. Keep that architecture custom rather than converting RiftOS to MCP.

Possible future RiftScript-only tool families:

```text
fs.*
window.*
apps.*
engine.*
kernel.inspect/*
browser.inspect/*
settings.*
logs.*
tests.*
snapshot.*
```

High-impact operations should require explicit developer-mode capability grants. Normal apps and arbitrary webpages must not receive RiftScript privileges. Internal nonces/task IDs should remain browser-side rather than being exposed in model prompts.

## Build handoff

A future Development Snapshot should be directly patchable outside the phone:

```text
RiftScript Studio
   -> export snapshot/patch
   -> GitHub / external patching
   -> Android APK build
   -> signed artifact
```

The Android SDK/Gradle toolchain should remain outside the installed phone app to avoid recreating the large on-device build/runtime bloat that RiftOS intentionally removed.

## Longer term

- stable RiftRT ABI and richer surface/input primitives,
- app package signing/trust metadata,
- crash/session diagnostics export,
- controlled native plugin packaging compiled into RiftOS,
- more complete tablet/desktop multi-window ergonomics,
- optional remote build integration with explicit user action,
- broader Rift Agent capability sets only after capability and audit surfaces exist.

## Non-goals

- bundling a full Android SDK/emulator into RiftOS,
- arbitrary downloaded native ELF execution,
- giving normal webpages unrestricted Android or RiftFS access,
- converting the ChatGPT browser adapter into native OpenAI MCP integration,
- adding a separate OpenAI API requirement for Rift Agent,
- reintroducing the removed local LLM runtime on low-memory Android devices.
