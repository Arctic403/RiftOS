# RiftOS Roadmap

This roadmap describes intended work, not shipped capability. Current implementation status is tracked in `docs/PROJECT_STATUS.md`.

## Now: Rift AI workspace + local MCP + browser stability

- Keep `main` authoritative and use `android-apk` for staged validation when useful.
- Keep **Rift MCP** local-only: no remote relay, WSS pairing client, public MCP endpoint or process-start network service.
- Keep `RiftToolHost` as the single capability authority for MCP permissions, audit and tool dispatch.
- Preserve the optimized ChatGPT compatibility path: mutation-scoped processing, compact one-shot tool context and serialized tool calls.
- Stabilize the shell-rendered **Rift AI** cockpit: hidden ChatGPT Web transport, selective project context, live logs and persistent working-tree review.
- Keep the model path ChatGPT-Web-only; do not add an OpenAI API key/endpoint path to Rift AI.
- Harden RiftBrowser move/resize/focus and long-chat behavior across phones, tablets and DeX.
- Continue RiftEngine/Servo integration behind a hardware compatibility gate; Android System WebView remains the compatibility backend until that gate passes.
- Add focused on-device diagnostics and exported test results rather than emulator-heavy CI.

## Local Rift MCP expansion

Current tool family:

```text
rift_info
rift_stat
rift_list
rift_read_text
rift_write_text
rift_mkdir
rift_remove
rift_move
```

Planned capability families may be added behind explicit local grants:

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

High-impact operations should require explicit developer-mode capability grants. Normal apps and arbitrary webpages must not receive Rift MCP privileges. MCP remains a protocol surface over `RiftToolHost`; it is not the RiftOS kernel API.

## Rift AI project intelligence

Current checkpoint: compact recursive project tree, selective existing MCP reads, persistent mutation journal, unified-style diff, Accept all and Revert all.

Next improvements should stay lightweight:

- `rift_search_text` for fast project-wide symbol/text lookup without reading every file;
- `rift_read_many` with strict aggregate byte caps;
- `rift_apply_patch` for diff-sized edits instead of whole-file rewrites;
- ignored-directory rules (`.git`, build output, dependency caches) in project metadata;
- per-file/hunk accept/revert on top of the current session-wide baseline;
- changed-files summaries that do not hash the full project continuously.

The project should never be injected wholesale into ChatGPT. Tree metadata guides the model; file contents are fetched only when needed.

## RiftEngine

Move RiftBrowser toward a lightweight Rust-native engine based on Servo:

1. define a renderer-neutral `RiftBrowserEngine` boundary;
2. integrate RiftEngine/Servo on supported Android versions;
3. validate ChatGPT login/cookies/streaming and long-chat memory behavior;
4. port the exact-origin MCP compatibility adapter;
5. verify file chooser/download/navigation/window resizing;
6. keep WebView only as a compatibility backend until RiftEngine is proven.

See `docs/RIFTBROWSER_ENGINE_MIGRATION.md`.

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

The Android SDK/Gradle toolchain should remain outside the installed phone app to avoid recreating large on-device build/runtime bloat.

## Longer term

- stable RiftRT ABI and richer surface/input primitives,
- app package signing/trust metadata,
- crash/session diagnostics export,
- controlled native plugin packaging compiled into RiftOS,
- more complete tablet/desktop multi-window ergonomics,
- richer local project intelligence while preserving the ChatGPT-Web-only model path.

## Non-goals

- bundling a full Android SDK/emulator into RiftOS,
- arbitrary downloaded native ELF execution,
- giving normal webpages unrestricted Android or RiftFS access,
- restoring the removed DOM Agent V1/V2/V3 protocol,
- restoring the removed remote Rift MCP relay/WSS pairing architecture,
- adding a direct model API/key path to Rift AI,
- making MCP the internal RiftOS capability API,
- reintroducing the removed local LLM runtime on low-memory Android devices.
