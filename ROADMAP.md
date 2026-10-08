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

## RiftBuild external-provider boundary

**PROMOTED + FROZEN.** The device-proven build path is external to RiftOS:

1. **DEVICE PROVEN** — external Compile through the project-owned managed compiler registry;
2. **DEVICE PROVEN** — external Preflight;
3. **DEVICE PROVEN** — deterministic external APK Pack;
4. **DEVICE PROVEN** — external APK-v2 Sign using only generic `signing.identity`;
5. **DEVICE PROVEN** — external Verify plus independent RiftOS `RiftApkV2Verifier`;
6. **DEVICE PROVEN** — Android install/launch, Rift++ editor launch, typing, clear, preview, and downstream editor native compile/preflight/pack/sign output;
7. **RETIREMENT LOCKED** — embedded build orchestration, language-specific compile shortcuts, native clang toolchain/prepared-app materialization, APK packing, and APK signing are absent from RiftOS.

The permanent RiftOS side is reusable infrastructure only: `RiftLocalBuildCapability`, `RiftJvmDexService`, `RiftBuildManagedToolchains`, `RiftManagedJvmToolService`, `RiftNativeBufferCompilerService`, `RiftBuildPlatformTools`, `RiftApkV2Verifier`, `RiftBuildInstaller`, and `RiftRappCapabilityBroker`.

Future work may extend this boundary only when device proof demonstrates a missing **generic reusable primitive**. Provider-specific recipes remain outside RiftOS.

### RiftOS-native RAPP lane

**LIVE GENERIC PLATFORM BOUNDARY.** `riftos-app-abi/1` owns normalized app/lifecycle/input events, bounded generic frames, runtime adapters, durable `state.bin`, and permission-gated host effects. `pack-rapp`, `install-rapp`, `launch-rapp`, and `rapp-list` remain platform operations because they package and host RAPP programs, not Android APKs.

`riftpp-generic-v1` is the forward Rift++ adapter; older RPA/RWS adapters remain compatibility lanes. Language semantics stay in adapters/providers rather than the host.

## Semnexis self-hosting bootstrap

Current source is `0.7.0-quickjs-bootstrap`. The installed gate remains `semnexis-bootstrap-self-test/17`, while the source/machine self-hosting frontier has advanced through a real Semnexis-source frontend and semantic-graph slice. Generated ARM32 now parses `fn main() -> i32 { let x = 12 + 3 * (4 + 1); return x; }` into a 12-node Arena AST, lowers it into 22 deterministic semantic facts, and resolves the return `x` NameRef to the local symbol. QuickJS remains only the bootstrap host.

Immediate order:
1. keep installed `/17` claims frozen until the next APK is actually built/installed;
2. expand the Semnexis-written frontend to multiple locals/statements and multiple functions;
3. add function-symbol/call resolution and converge the compact pressure graph onto the canonical Program Graph schema;
4. move graph verification, then baseline type/effect/capability solving, into Semnexis;
5. move Native IR emission/verification into Semnexis and prove bootstrap-vs-self-hosted equivalence;
6. preserve frozen SNIRV0–SNIRV7 compatibility and all ARM32 machine regressions throughout.

## Local Rift MCP expansion

Current source tool family (21 registered tools):

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
rift_mcp_reconcile
rift_debug
rift_local_agent_batch
rift_workspace_exec
```

Lost-turn recovery is source-implemented through the device-owned `RiftMcpOperationJournal` and read-only `rift_mcp_reconcile`. Journaled calls carry one stable operation identity through ToolHost mutation provenance; terminal identities are not replayed after restart, and response delivery is kept separate from execution completion. After an ambiguous UI freeze/timeout/disconnect, callers should reconcile journal state before issuing another mutation.

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

Next improvements should stay lightweight:

- richer language-aware semantic resolution for aliases, generated sources and package/module namespaces without turning RiftOS into a heavyweight language-server host;
- use project impact output to select the smallest relevant local test/check set before external Android/native builds;
- add deliberate source-control-oriented review surfaces only if they have a real caller/UI and tests, rather than reviving an unreachable hidden session journal.

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
- **N1.6 RiftCLI Batch V2 — historical proof only; retired in the current rollback branch.** Builder #259 / source `eaa2a390...` remains historical evidence that the former CLI-owned batch design worked, but current RiftCLI reports `batchV2=false`, `batchV2MaxSteps=0`, `batchOwner=riftos-local-agent`, and the `rift_cli_batch` dispatcher fails closed. **Current batch owner: RiftOS Local Agent — source-complete, new build/install proof pending.** `rift_local_agent_batch` prevalidates at most 16 fixed-scope steps, binds request IDs to exact normalized plans, owns one process-local Local Agent execution lease, persists bounded results/status atomically, supports cancellation/result paging, never replays unfinished jobs after restart, and does not resurrect RiftShell `batch` or multi-op `rift_workspace_exec`.
- **N1.7 abuse/restart/backpressure/batch stress — next promotion gate; zero-poll steady-state lock implemented in source.** Basic SSE reconnect/replay, ACK correlation and duplicate-free cursor recovery are already proven under N1.5. Current source now hard-locks `driverObservationMode=persistent-push-steady-state`, `automaticPolling=false` and `pollFallbackOnly=true`; regression coverage also requires that the only poll call sites remain the explicit `rift_cli_job_poll` fallback path rather than an internal polling loop. This hardening still requires Builder/install proof before it is called installed. Remaining N1.7 stress work covers forced relay/device restarts, repeated reconnect gaps, slow-subscriber/backpressure handling, subscriber caps, large-result fallback, cancellation between batch steps, global no-interleave behavior, concurrent-driver pressure, both failure policies, bounds and cleanup on the installed build.
- **N2 Federated Rift Memory Kernel — HARD PRE-N3 PROGRAM.** Build one canonical evidence/event/transaction/reconciliation authority with multiple rebuildable specialist cognitive engines rather than one monolithic algorithm or competing truths. N2 starts with a replaceable SQLite reference `MemoryStore`, then develops RiftStore as an experimental backend that may replace SQLite responsibilities only when identical benchmarks prove a real correctness/resource/performance win. N2.0-N2.12 cover the canonical JSON model, immutable/content-addressed evidence, bi-temporal history, trust/reconciliation, temporal graph, episodic/consolidation/semantic/belief/skill/failure/causal/commitment/predictive lanes, router/fusion/arbitration/Context Compiler, Observer+Validator closed loop, poisoning/fsck/crash/rebuild/scale hardening, public/private benchmarks, incremental hybrids and ablations. Full specification: `docs/systems/riftmemory/N2_FEDERATED_MEMORY_ROADMAP.md`.
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
