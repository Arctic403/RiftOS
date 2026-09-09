# RiftOS Roadmap

This roadmap describes intended work, not shipped capability. Current implementation status is tracked in `docs/PROJECT_STATUS.md`.

## Now: stabilize Rift Bridge + Android desktop

- Keep `main` authoritative and use `android-apk` for staged validation when useful.
- Harden RiftBrowser native-surface move/resize/focus behavior across phones, tablets and DeX.
- Make **Rift Bridge** the only supported ChatGPT/tool integration path.
- Keep the device as the capability authority: remote adapters must not bypass local read/write grants or the audit surface.
- Deploy and validate the remote MCP relay against ChatGPT custom apps, then remove any remaining historical Agent-only documentation.
- Add focused on-device diagnostics and exported test results rather than emulator-heavy CI.
- Continue reducing legacy web/iOS-only code from Android packaging.

## Rift Bridge expansion

Rift Bridge is a RiftOS system app and capability router. MCP is an external adapter, not the RiftOS kernel API.

Current sandbox tool family:

```text
info
stat
list
readText
writeText
mkdir
remove
move
```

Planned capability families can be added behind explicit device-side grants:

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

High-impact operations should require explicit developer-mode capability grants. Normal apps and arbitrary webpages must not receive Rift Bridge privileges.

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
- OAuth/multi-device authentication for Rift Bridge relay deployments.

## Non-goals

- bundling a full Android SDK/emulator into RiftOS,
- arbitrary downloaded native ELF execution,
- giving normal webpages unrestricted Android or RiftFS access,
- injecting first-class tool behavior into `chatgpt.com` by scraping or modifying its DOM,
- making MCP the internal RiftOS capability API,
- reintroducing the removed local LLM runtime on low-memory Android devices.
