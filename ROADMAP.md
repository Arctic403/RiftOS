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
3. **Patch sessions and provenance — implemented in current source.** Explicit writer claims are state-bound where possible, directory replacement claims are labeled lower-confidence, and unknown writers remain `unattributed-local`.
4. **Immutable patch manifest and tamper-evident evidence — implemented in current source.** Deterministic base/result/change-set/structural hashes, internal SHA-addressed manifest freeze, forward record chain, pruning anchors, crash-safe head recovery and operational-vs-trusted checkpoint separation are present; trusted promotion remains absent.
5. **Semantic diff + Project Intelligence impact mapping — implemented in current source.** One shared PI-v2 analyzer now drives normal indexing and candidate before/after deltas; impact scope is derived from Patch Manifest V1, bounded/incomplete explicitly, mapped through callers/dependents/tests/docs, and exposed only through an internal Local Agent seam.
6. **Local Agent validation state machine/policy core — OBSERVE core implemented.** `RiftCliPatchLifecycleV1` defines clean acquisition through final local evaluation verification and computes WOULD_ACCEPT/WOULD_DENY only; it cannot block, publish or promote trust.
7. **Independent research-verification ledger — collection/claim core implemented.** `RiftResearchLedgerV1` binds sources to claims and requires authoritative support for critical claims. Independent re-check remains the final evaluator's responsibility; RiftOS does not fetch/cryptographically verify remote research content yet.
**Pre-Patch-8 stress-foundation repair — implemented in current source.** Live abuse fixed candidate-created governance/build-manifest scope and moved lifecycle sessions into RiftFS system storage with fail-closed process-restart drift detection. Exact regressions are locked by `test-rift-cli-stress-foundation.mjs`. The external source of the observed untracked-file deletion was not proven; restart drift is contained/detected rather than guessed safe.

8. **Documentation/README/roadmap/patch-note parity gate — OBSERVE implementation complete.** `RiftDocumentationParityV1` now derives exact maintained-path ownership/governance requirements from the final candidate, hard-fails missing/stale ownership and mandatory owner-doc/patch-history updates, requires exact structured source/governance reviews, and binds the recomputed parity-plan SHA into final evaluator verification. Arbitrary prose truth remains an independent-evaluator responsibility.
9. **Impact-derived tests/security/dependency verification planner — target-selection foundation implemented.** PI-v2 impact derives changed/dependent/test/doc/build targets and lifecycle requires security/dependency/test/build evidence as applicable. It is not an autonomous test/build runner.
10. **Hermetic/reproducible evidence and stale-result invalidation — stale binding implemented; hermetic execution pending.** Evidence records bind source/candidate identities and final policy rejects stale manifests; build environment/artifact hashes are required, but builds are not yet hermetic/reproducible by construction.
11. Trust-boundary enforcement and bypass closure — **not implemented; OBSERVE only.**
12. **Immutable verification bundle and decision trail — verification-bundle foundation implemented.** Candidate/semantic/evidence/policy hashes and bounded evaluator echo checks exist; the lifecycle session/decision store is not yet an immutable/hash-chained decision trail.
13. Builder provenance handshake from accepted source identity to APK artifact identity — **not implemented.**
14. Adversarial torture/re-audit before ENFORCE can be considered normal — **pending.**

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
