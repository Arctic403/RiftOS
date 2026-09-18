# RiftOS Roadmap

This roadmap describes intended work, not shipped capability. Current implementation status is tracked in `docs/PROJECT_STATUS.md`.

## Now: native MCP connection + browser stability

- Keep `main` authoritative and use `android-apk` for staged validation when useful.
- Keep MCP execution local while adding an authenticated outbound WSS transport to a public MCP relay.
- Preserve the ChatGPT Web compatibility adapter until native plugin calls pass end-to-end testing.
- Keep `RiftToolHost` as the single capability authority for MCP permissions, audit and tool dispatch.
- Preserve the optimized ChatGPT compatibility path: mutation-scoped processing, compact one-shot tool context and serialized tool calls.
- Keep removed Rift AI cockpit/session/journal code out of the active runtime; AI-assisted development should use the MCP tool surface rather than a second hidden task controller.
- Harden RiftBrowser move/resize/focus and long-chat behavior across phones, tablets and DeX.
- Implement the RiftEngine/Servo migration behind a hardware compatibility gate; Android System WebView remains the current compatibility renderer until that gate passes.
- Add focused on-device diagnostics and exported test results rather than emulator-heavy CI.

## Local Rift MCP expansion

Current tool family (18 registered tools):

```text
rift_shell_exec
rift_info
rift_stat
rift_hash
rift_list
rift_read_text
rift_write_text
rift_mkdir
rift_remove
rift_move
rift_copy
rift_archive
rift_extract
rift_audit
rift_scan
rift_project_export
rift_workspace_diff
rift_workspace_exec
```

The browser compatibility path represents model-facing calls as `[RIFT_CALL]` / `[RIFT_END]` text and returns `[RIFT_RESULT]`; MCP JSON-RPC remains private to trusted transports.

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

## MCP project intelligence

Implemented foundation: compact `RIFT_PROJECT_V2` project descriptor, full workspace reachability through `rift_workspace_exec` (Rift Code Mode), Project Intelligence v2 snapshots, restart-persistent incremental symbol indexing, bounded import/include dependency graph edges, focused project graph and impact views, validation-plan discovery, reference lookup, exact symbol/ranged reads, SHA-256 guarded range/hunk patching, built-in dependency/build/cache ignore rules, dry-run validation, compact changed-file summaries and transactional batched write/replace/patch/mkdir/remove/move/archive/extract with rollback on batch failure.

Project Intelligence v2 deliberately stays behind the existing `project` Code Mode operation. `kind=graph`, `kind=impact` and `kind=validation` reuse the already-published `kind`/`query` operation fields, so no parallel agent tool catalog or second task controller is required.

Next improvements should stay lightweight:

- richer language-aware semantic resolution for aliases, generated sources and package/module namespaces without turning RiftOS into a heavyweight language-server host;
- use project impact output to select the smallest relevant local test/check set before external Android/native builds;
- add deliberate source-control-oriented review surfaces only if they have a real caller/UI and tests, rather than reviving an unreachable hidden session journal.

The project should never be injected wholesale into ChatGPT. RiftOS exposes full project reachability through the local executor; only bounded search/read results needed for reasoning cross the existing ChatGPT Web transport.

## Workspace trust and validation hardening — observe-first

A 14-patch closed-circuit validation program is now active as planned development work. Until the full system survives adversarial validation, its eventual Local Agent gate defaults to **OBSERVE**: it may compute WOULD_ACCEPT / WOULD_DENY evidence but must not block existing patch, Git, Dev Lab or build workflows. ENFORCE remains manual-only until graduation.

Current sequence/status:
1. **Diff Engine V2 — implemented in current source.** Workspace Records delegates text rendering to a bounded deterministic adaptive exact-LCS/patience engine with independent hunks.
2. **File identity intelligence — implemented in current source.** Exact SHA rename/copy content identity, bounded heuristic rename/rewrite correlation and checkpoint identity summaries.
3. Patch sessions and provenance.
4. Immutable patch manifest and tamper-evident evidence.
5. Semantic diff + Project Intelligence impact mapping.
6. Local Agent validation state machine/policy core.
7. Independent research-verification ledger.
8. Documentation/README/roadmap/patch-note parity gate.
9. Impact-derived tests/security/dependency verification planner.
10. Hermetic/reproducible evidence and stale-result invalidation.
11. Trust-boundary enforcement and bypass closure.
12. Immutable verification bundle and decision trail.
13. Builder provenance handshake from accepted source identity to APK artifact identity.
14. Adversarial torture/re-audit before ENFORCE can be considered normal.

OBSERVE and ENFORCE must execute the same verification pipeline; only authority differs. A candidate or trusted-base byte change invalidates previous acceptance evidence rather than inheriting stale approval.

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
- adding an undeclared direct model API/key path or hidden AI task controller,
- making MCP the internal RiftOS capability API,
- reintroducing the removed local LLM runtime on low-memory Android devices.
