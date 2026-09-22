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

## In-house RiftBuild Android pipeline

RiftBuild is being promoted from retained/non-executing design to a bounded native local build controller.

Immediate order:
1. **SOURCE IMPLEMENTED + INSTALLED PROVEN** — native doctor/validate/plan/run records;
2. **SOURCE IMPLEMENTED + INSTALLED PROVEN** — direct Rift++ V0 ARMv7/AArch64 ELF materialization, fixed binary manifest and deterministic unsigned APK packaging under `D:/Builds`;
3. **SOURCE IMPLEMENTED** — bounded Android-Keystore APK Signature Scheme v2 signing + independent v2 verification;
4. **SOURCE IMPLEMENTED** — exact-package, user-confirmed PackageInstaller handoff + first-launch proof recording;
5. **PROOF NEXT** — Builder compile/install this signer patch, then sign → verify → install → launch the existing proof APK on-device;
6. **THEN RETURN TO RIFTLLM+** as the first real repository compiled through the native Rift++/RiftBuild path;
7. broader Gradle/NDK compatibility adapters stay deferred unless RiftLLM+ proves they are actually needed.

RiftBuild must not add arbitrary shell execution, downloaded executable toolchains, automatic Git push, CLI enablement or new MCP authority.

## Semnexis self-hosting bootstrap

Current source is `0.7.0-quickjs-bootstrap`. The source/machine pressure loop now reaches additive `SNIRV7`, Arena-backed AST storage, typed Arena record reads/writes, record stack arguments, bounded 256-frame native recursion, and a recursive-descent Arena parser/evaluator on ARM32. QuickJS remains only the bootstrap host.

Immediate order:
1. **NEXT APK GATE** — build/install current RiftOS and prove installed `semnexis-bootstrap-self-test/17`;
2. add real parser failure/error propagation and richer grammar only when the self-host parser exposes the need;
3. keep expanding Semnexis-written compiler pieces and recursive AST traversal through machine-verified pressure probes;
4. preserve frozen SNIRV0–SNIRV6 compatibility while adding new IR versions only for genuinely new semantics;
5. move toward a Semnexis-written compiler/self-host boundary once the language/runtime surface is sufficient instead of predesigning unrelated features.

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

Implemented foundation: compact `RIFT_PROJECT_V2` project descriptor, full workspace reachability through `rift_workspace_exec` (Rift Code Mode), Project Intelligence v2 snapshots, restart-persistent incremental symbol indexing, bounded import/include dependency graph edges, focused project graph and impact views, validation-plan discovery, reference lookup, exact symbol/ranged reads, SHA-256 guarded range/hunk patching, built-in dependency/build/cache ignore rules, dry-run validation, compact changed-file summaries, and copy-on-write rollback for each one-operation `rift_workspace_exec` mutation. Public multi-op/batch execution is currently fail-fast disabled because it can hang the agent/runtime; coordinated changes must use explicit sequential calls.

Project Intelligence v2 deliberately stays behind the existing `project` Code Mode operation. `kind=graph`, `kind=impact` and `kind=validation` reuse the already-published `kind`/`query` operation fields, so no parallel agent tool catalog or second task controller is required.

Project Intelligence V2 remains the canonical indexing foundation. N1.8 builds a Repository Consistency Observer above it rather than creating a second project index. The observer adds incremental fact invalidation/recomputation, precise dependency propagation, cross-boundary contract checks, documentation/README/ROADMAP/TODO claim consistency, proof obligations, clean-vs-incremental graph parity, and adversarial benchmarking. Full locked specification: `docs/systems/workspace/REPOSITORY_CONSISTENCY_OBSERVER.md`.

PI-v2 improvements must continue to support that observer without turning RiftOS into an unbounded language-server host:

- richer language-aware semantic resolution for aliases, generated sources and package/module namespaces;
- use project impact output to select the smallest relevant local test/check set before external Android/native builds;
- preserve bounded incremental analysis and explicit incompleteness when evidence limits are hit;
- add review surfaces only when they have a real caller/UI and regression coverage.

The project should never be injected wholesale into ChatGPT. RiftOS exposes full project reachability through the local executor; only bounded search/read results needed for reasoning cross the existing ChatGPT Web transport.

## Native RiftCLI engineering program

The former 14-patch Experimental RiftCLI lifecycle program was retired on 2026-09-19. Its Kotlin lifecycle/swarm/IR/research/parity/verification implementation is no longer the active roadmap.

Reusable RiftOS evidence foundations remain live and independent:

1. **Diff Engine V2 — retained.** Workspace Records owns bounded deterministic multi-hunk evidence.
2. **File identity intelligence — retained.** Rename/copy/rewrite correlation remains Workspace evidence.
3. **Patch sessions and provenance — retained.** Explicit writer provenance remains shared by MCP/Shell/Files/Dev Lab/Git.
4. **Patch Manifest/tamper evidence — retained.** Candidate/tree/change-set hashing and record-chain primitives remain reusable.
5. **Project Intelligence V2 impact mapping — retained.** Current source graph/impact/validation evidence remains the canonical live project-intelligence owner.

RiftCLI is now rebuilt as a native C++ subsystem with this promotion sequence:

- **N0 Native bootstrap — proven.** C++ core + thin Kotlin JNI host, ARM64 primary + ARM32 compatibility, explicit process-local enable, exact native source snapshot, final APK native packaging, real `armeabi-v7a` device execution, fail-closed unsupported command handling, and force-stop/restart reset were proven on RiftOS run #250 (`6f7a6129…`).
- **N1 Driver Protocol — proven.** Bounded external-driver request-id/session/task/project/evidence/action contract. Builder run #255 / source `121edf6b…` proved full bounded RiftOS authority, replay protection, shell/ToolHost jobs, truthful cancellation, provenance, one global outstanding authority slot and bounded external continuation on the ARM32 target.
- **N1.5 Persistent push/events — LIVE-PROVEN on installed Android source `0814fb8cf8ca31186d6e639fc7ad3b965d822897` (2026-09-21).** The persistent device WSS now carries sequenced RiftCLI lifecycle events to the Cloudflare Durable Object, with bounded device-owned replay, per-subscriber WebSocket/SSE cursors, ACKs, reconnect recovery and inline terminal results. Live proof used an external Chrome SSE subscriber with `sseClients: 1`; a read-only `version` job produced `job.submitted` / `job.started` / `job.completed` sequences that DebugHub independently recorded as `event.created -> cli.event.send queued -> cli.ack received`, and the subscriber received the same sequences without polling. Replay proof then forced `sseClients: 0`, created events while disconnected, and reconnected with the prior cursor; only the missed sequences replayed, in order, with no duplicate/older event. Poll/list/cancel remain recovery/debug fallback controls. No Durable Object event-payload writes.
- **N1.6 RiftCLI Batch V2 — live-proven on Builder #259 / source `eaa2a390...`.** New `rift_cli_batch` lane with at most 16 fully prevalidated sequential steps, one global authority reservation for the whole plan, bounded per-step results, stop/continue failure policy, cancellation checks, per-step push events and `rift-cli-batch` provenance. Installed-device proof covered validation, successful sequential mutation/readback, stop/continue semantics, provenance, replay rejection and retired/nested batch rejection. This does **not** resurrect retired RiftShell `batch` or multi-op `rift_workspace_exec`.
- **N1.7 abuse/restart/backpressure/batch stress — active promotion gate.** Basic SSE reconnect/replay, ACK correlation and duplicate-free cursor recovery are proven. Source hard-locks `driverObservationMode=persistent-push-steady-state`, `automaticPolling=false` and `pollFallbackOnly=true`; regression coverage requires that polling remain an explicit fallback rather than an internal steady-state loop. Installed-device stress on 2026-09-21 additionally proved the independent eight-client SSE ceiling/ninth-client rejection, stable session replacement, bounded backpressure, lease-based stale-client cleanup to `sseClients: 0` without event pressure, and true Android force-stop/reopen cursor recovery with exact nonzero `resumeAfter` and replay count zero. Remaining N1.7 work is the rest of the abuse matrix: cancellation between batch steps, global no-interleave behavior, concurrent-driver pressure, both failure policies, bounds and cleanup.
- **N1.8 Repository Consistency Observer — HARD PRE-N2 GATE; content-identity hardening source-implemented, promotion/torture restart pending.** Installed source `5de7f5065160b2bbe263e8cc0d179f6099af4347` proved repaired whole-repo coverage (`complete=true`, 1017 facts / 1072 edges) and ten identical warm graph hashes, plus correct add/delete/rename/copy/move, dependency-target, concurrency and path-identity behavior. The torture fixture then exposed a hard false-clean: content-only source/doc edits changed real file SHA-256 but left the repository graph/file fact/cache unchanged, and delete/recreate of the same path with unrelated bytes returned the old graph identity. N1.8.0 therefore failed promotion exactly as designed. Source now hardens the foundation instead of weakening the test: PI-v2 cache schema v3 stores exact per-file SHA/size + semantic status, `symbolIndex` stays semantic-only, a separate repository-file evidence map represents every non-policy-excluded file, consistency forces full content verification, stale semantic entries are removed when files become unindexable, all mutation/rollback invalidation clears both evidence layers, and Repository Fact Graph/cache schema v2 binds file-fact content to byte SHA while keeping stable path identity. Local Android source validation is green; external Builder/Kotlin compile and a fresh installed torture run are required before promotion. **The staged subsystem/domain scan planner remains architecture-locked but implementation-blocked until this hardened N1.8.0 passes the complete torture gate.** N1.8.1-N1.8.7 remain pending. Canonical specification: `docs/systems/workspace/REPOSITORY_CONSISTENCY_OBSERVER.md`.
- **N2 Federated Rift Memory Kernel — HARD PRE-N3 PROGRAM and blocked until N1.8 promotion.** Build one canonical evidence/event/transaction/reconciliation authority with multiple rebuildable specialist cognitive engines rather than one monolithic algorithm or competing truths. N2 starts with a replaceable SQLite reference `MemoryStore`, then develops RiftStore as an experimental backend that may replace SQLite responsibilities only when identical benchmarks prove a real correctness/resource/performance win. N2.0-N2.12 cover the canonical JSON model, immutable/content-addressed evidence, bi-temporal history, trust/reconciliation, temporal graph, episodic/consolidation/semantic/belief/skill/failure/causal/commitment/predictive lanes, router/fusion/arbitration/Context Compiler, Observer+Validator closed loop, poisoning/fsck/crash/rebuild/scale hardening, public/private benchmarks, incremental hybrids and ablations. Full specification: `docs/systems/riftmemory/N2_FEDERATED_MEMORY_ROADMAP.md`.
- **N3 Architecture/impact engine — BLOCKED until N2.12 promotion.** N3 may consume Project Intelligence, owner contracts and the promoted Rift Memory Kernel only after N2 proves canonical integrity, project isolation, provenance, reconciliation, crash/restart safety, poisoning resistance, projection rebuild, procedural learning and benchmark/resource targets on the real Android target. Architecture or happy-path demos do not waive this barrier.
- **N4 Planner.** Professional dependency-aware planning with explicit preconditions/postconditions and recovery. Plan globally; mutate incrementally. No opaque multi-operation batch editing.
- **N5 Research/evidence.** Native bounded source/claim/assumption/freshness ledger with authoritative-source and independent-verification requirements.
- **N6 Verification.** Candidate-bound compile/test/security/dependency/docs/build/APK/device/performance evidence as applicable.
- **N7 Adversarial engineering loop.** Corrupt/stale memory, renamed files, dependency cycles, failed/interrupted builds, misleading tests, dirty repos, malformed driver input, restart/resume and huge-project stress. Every discovered defect becomes a regression test.

No later gate is promoted merely because a happy-path demo passes. Documentation and regression tests are part of each gate.

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

## RiftDebugHub integration

Foundation complete in current source:

- process-owned passive hub;
- bounded spans/events and secret-key redaction;
- reusable `RiftDebugAdapter` plug;
- MCP Server -> Tool Host trace propagation;
- one read-only `rift_debug` query surface;
- focused source/wiring regression and exact Gradle source declaration.

Next integration work is intentionally subsystem-by-subsystem: RiftCLI, Local Agent validation, RiftShell, RiftFS, Git, Builder, Binder bridges and Accessibility emit through adapters without routing execution through the debugger.
