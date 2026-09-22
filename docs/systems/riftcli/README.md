# RiftCLI Native Architecture

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-21.**

Gate N0 is proven on the installed Android device. Gate N1 plus its replay/job/cancellation/provenance hardening is also proven on the installed Android device (Builder run #255 / source `121edf6b3255beca33a45351d3952c7026b5cb4b`) on `armeabi-v7a`.

Status: **N1, N1.5 persistent push/events, and N1.6 Batch V2 are live-proven on the installed Android device. N1.5 was promoted on 2026-09-21 from installed source `0814fb8cf8ca31186d6e639fc7ad3b965d822897`: an external Chrome SSE subscriber received the same sequenced lifecycle events that DebugHub recorded through `event.created -> cli.event.send -> cli.ack`, without polling, and a forced disconnect/reconnect replayed only the missed cursor range with no duplicates.**

RiftCLI is being rebuilt from scratch as RiftOS's native engineering supervisor. The previous Experimental RiftCLI Kotlin/swarm/IR/lifecycle implementation was intentionally retired rather than used as the new foundation.

## Permanent dependency direction

```text
external reasoning source
        |
        v
MCP / RiftShell
        |
        v
RiftOS Local Agent
        |
        v
RiftCLI intelligence/tool layer
        |
        v
thin Kotlin/JNI host -> C++ RiftCLI core
        |
        v
bounded RiftOS authorities
```

The external reasoning source may be ChatGPT, another AI client, a human, or deterministic automation, but it does **not** host RiftCLI directly. The RiftOS Local Agent owns the CLI host boundary and uses RiftCLI as an internal gated tool/intelligence layer.

RiftCLI **never calls a model or inference API**. There is no model-backend interface inside the CLI. The native process-local enable switch remains the only CLI enable gate, defaults OFF after each process start, and is non-persistent.

## Language and host boundary

The canonical RiftCLI implementation language is **C++**.

Kotlin is Android host glue only:

- load `libriftcli.so`;
- marshal shell arguments and the current working directory through JNI;
- parse the native result envelope;
- return the result to RiftShell/MCP.

Kotlin must not become the owner of planning, project memory, architecture reasoning, research, verification, task state, or mutation policy.

## Source ownership

Current owners:

- `android/app/src/main/cpp/riftcli/rift_cli_core.cpp` — native CLI command/state core.
- `android/app/src/main/cpp/riftcli/rift_cli_core.h` — native core interface.
- `android/app/src/main/cpp/riftcli/rift_cli_jni.cpp` — UTF-safe JNI adapter only.
- `android/app/src/main/cpp/CMakeLists.txt` — native build definition.
- `android/app/src/main/java/com/riftos/app/RiftCliHost.kt` — thin Android JNI host.
- `android/app/src/main/java/com/riftos/app/RiftVortexLocalAgent.kt` — RiftOS Local Agent ownership boundary; hosts the internal RiftCLI intelligence adapter.
- `android/app/src/main/java/com/riftos/app/RiftNativeShell.kt` — compatibility `rift-cli` shell entry plus the existing CLI execution supervisor; direct shell entry is routed through `RiftOsLocalAgent` before native CLI execution.

## Target ABIs

RiftCLI is native-first with:

- `arm64-v8a` — primary Android ABI;
- `armeabi-v7a` — required 32-bit compatibility ABI.

Both ABIs are declared in the Android native build contract. A release is not considered RiftCLI-capable unless both native libraries are packaged and verified.

## N0 proof and N1 authority model

Gate N0 proved the native bootstrap on-device. The enable switch remains process-local, defaults OFF after every RiftOS process start, and still requires the literal `CONFIRM-EXPERIMENTAL` acknowledgement.

The current host boundary is now fixed: MCP/RiftShell ingress enters the existing RiftOS Local Agent, the Local Agent invokes RiftCLI as an internal intelligence/tool layer, and the existing CLI execution supervisor delegates only through bounded RiftOS authorities. The `rift-cli` shell command is retained as a compatibility/development surface, but it is routed through `RiftOsLocalAgent` before reaching the native CLI core.

**Freeze:** after this Local Agent hosting boundary is validated, no new CLI intelligence capability is added until N1.8.0 Repository Consistency Observer is fully torture-tested and promoted. Observer integration, Memory, Planner, Command Registry, Execution Supervisor expansion, Debug/history expansion, and Security/validation expansion remain blocked behind that gate.

Gate N1 changes the authority model deliberately: once RiftCLI is explicitly enabled, it may authorize **full RiftOS authority** through existing RiftOS subsystem boundaries. That includes mutation, Git, build, device/local-agent, browser/network-backed RiftOS services, and other currently exposed native RiftOS actions.

Full authority does **not** mean a hidden raw Android/Linux escape hatch. RiftCLI delegates through the same bounded RiftOS owners and provenance systems already used by the rest of RiftOS.

RiftCLI still has no embedded model backend and never calls ChatGPT or another model by itself.

N1 command surface:

```text
rift-cli help
rift-cli status
rift-cli architecture
rift-cli enable CONFIRM-EXPERIMENTAL
rift-cli disable
rift-cli driver request ...
```

Each accepted N1 driver request authorizes at most **one RiftOS action**. Dependent work is issued as later external-driver requests after inspecting the previous result.

Every authority-bearing driver request must carry a unique process-local `request-id`. RiftCLI retains **all accepted request IDs for the lifetime of the RiftOS process**; IDs are never evicted while that process lives, so an old transport retry cannot become executable again after a disable/re-enable cycle. The fail-closed capacity is 4096 unique authority requests. Once that capacity is reached, RiftCLI rejects new authority requests until RiftOS is restarted. Disable/re-enable clears active driver-loop state but does not clear replay protection.

### Push-first job execution with live-poll fallback

N1 does not keep ChatGPT/MCP blocked on long CLI work. N1.5 changes observation from rapid polling to a persistent push-first event stream while retaining list/poll/cancel as recovery and debugging controls.

Both authority lanes are job-based, and RiftCLI permits **exactly one outstanding authority job globally** across shell + ToolHost + Batch V2. A second authority action is rejected until the current job reaches a terminal state; job controls remain available.

- RiftShell actions run on a dedicated single-thread CLI worker so the normal RiftShell worker remains free to service observation/cancellation requests.
- Direct `rift_*` ToolHost actions run through the existing confined ToolSandbox on a CLI job lane with **no fixed CLI wall-clock timeout**.
- a fair process-wide `RiftCliExecutionGate` serializes actual CLI execution across authority lanes.
- `RiftCliEventBus` owns a process-local 256-event replay ring, bounded 96 KiB events, bounded 48 KiB inline results, monotonic restart-safe sequence IDs, and batch-step-aware coalescing.
- the already-open device WSS carries `cli.event` envelopes to the relay; the relay forwards to bounded driver WebSocket or SSE subscribers without per-event Durable Object storage writes.
- reconnect uses sequence cursors and device-owned replay; duplicate replay delivery is filtered per subscriber.
- automatic polling is disabled by the native contract (`driverObservationMode=persistent-push-steady-state`, `automaticPolling=false`, `pollFallbackOnly=true`); polling is **fallback only** for recovery, explicit inspection, or results too large to inline.
- normal non-CLI MCP calls keep their existing bounded timeouts.

Every submitted action returns a process-local `jobId`. The external driver normally observes lifecycle/terminal events over push. It may use new, unique driver request IDs to call:

```text
--tool rift_cli_job_list   --tool-args {"requestId":"<original-request-id>"}
--tool rift_cli_job_poll   --tool-args {"jobId":"<job-id>"}
--tool rift_cli_job_cancel --tool-args {"jobId":"<job-id>"}
```

`rift_cli_job_list` provides recovery when the original submit response is lost: the driver can locate the already-started job by its original `request-id` without replaying the action. List responses are metadata-only; full output/result data is returned only by explicit `rift_cli_job_poll` for a concrete job ID. Terminal job data is limited to 2 MiB per job, 16 retained jobs per lane, and 5 minutes of retention; larger successful results are reported as `completed_result_too_large`.

Cancellation is explicit and observable. A queued job that is cancelled before execution ends as `cancelled`. A running operation first enters `cancelling`; if it still completes successfully, the terminal state is `completed_after_cancel_request`. If interruption is observed after execution may already have touched state, the terminal state is `cancelled_may_have_applied` instead of pretending rollback is proven. Disabling RiftCLI requests cancellation of both shell and ToolHost CLI jobs. The idempotent `rift_cli_job_list`, `rift_cli_job_poll`, and `rift_cli_job_cancel` controls remain available while CLI authority is disabled so the external driver can verify whether a previously-authorized job actually stopped.

### RiftCLI Batch V2

N1.6 adds a **new** bounded batch mechanism and does not resurrect either retired batch path.

`rift_cli_batch` accepts at most 16 prevalidated sequential steps in `validate` or `execute` mode. Each step has a unique bounded ID and is either a ToolHost step or an allowlisted RiftShell step. The full plan is validated before authority execution begins. Nested `rift_cli_batch`, `rift_shell_exec`, `rift_workspace_exec`, CLI job-control tools, recursive `rift-cli`, and the old RiftShell `batch` command are rejected.

One Batch V2 job reserves the same global `RiftCliExecutionGate` for its entire lifetime, so unrelated authority cannot interleave between steps. Per-step results are bounded, `stop` and `continue` failure policies are explicit, cancellation is checked before and after each step, cancellation interrupts are never converted into ordinary step failures, and mutations are recorded with `rift-cli-batch` provenance. Push events include unique `stepId` metadata so separate step transitions cannot be coalesced together.

The retired RiftShell batch implementation and multi-operation `rift_workspace_exec` remain fail-fast disabled.

## JNI text contract

JNI does not use modified-UTF shortcuts for the CLI payload boundary.

The adapter explicitly transcodes:

- Java/Kotlin UTF-16 -> standard UTF-8 for C++;
- standard UTF-8 -> Java/Kotlin UTF-16 for results.

Malformed surrogate or UTF-8 input is replaced with U+FFFD rather than being allowed to corrupt the protocol.

## Engineering design rule

RiftCLI may eventually **plan globally but act incrementally**.

A future task may require coordinated understanding of many files/subsystems, but each mutation must remain:

- individually attributable;
- individually observable;
- bounded;
- recoverable;
- followed by evidence/verification before dependent mutations proceed.

RiftCLI must not reintroduce the retired opaque RiftShell/workspace-exec batch-edit model. Batch V2 is the only CLI multi-step lane: it is bounded, fully prevalidated, sequential, observable per step, provenance-recorded, cancellation-aware, and owns one global authority reservation for the whole plan.

## Reusable RiftOS infrastructure

The reset does not delete proven shared infrastructure.

RiftCLI may later consume bounded interfaces to:

- Project Intelligence / Source Intelligence;
- Workspace Records;
- File Identity and Diff evidence;
- Patch Manifest / writer provenance;
- Native Git;
- Dev Lab;
- RiftBuild;
- Local Agent;
- MCP;
- RiftBrowser.

Those systems remain independent RiftOS owners. RiftCLI must consume their contracts instead of copying their implementations into the CLI.

## Retired implementation

The reset retires the former CLI-specific:

- `RiftExperimentalCli`;
- `RiftCliPatchLifecycleV1`;
- `RiftDocumentationParityV1`;
- `RiftVerificationPlannerV1`;
- `RiftResearchLedgerV1`;
- `RiftPlusPlusV0`;
- `RiftIrV1` / `RiftIrCliV1`;
- `RiftSwarmCoordinatorV0`;
- `RiftTextEncoderTaskRunner`;
- Experimental CLI patch/lifecycle docs and tests.

Rift++ Core itself is **not** retired. The unrelated `src/riftpp-core.js` language/runtime work remains owned by its own subsystem.

## Promotion roadmap

Each gate must be implemented, documented, adversarially tested, and independently audited before moving forward.

### Gate N0 — native bootstrap

- C++ core loads through JNI.
- `rift-cli status` executes native code.
- ARM64 and ARM32 libraries build.
- final APK contains both libraries.
- process-local enable resets on restart.
- unsupported commands fail closed.
- no old CLI implementation remains wired.
- no model/API/backend path exists.

### Gate N1 — Driver Protocol

**Live-proven on Builder run #255 / source `121edf6b...`.** Bounded native protocol for external reasoning input:

- unique process-local request identity plus session/task/project identity;
- goal and assumptions;
- evidence references;
- requested RiftOS capability;
- one proposed action;
- acceptance/rejection reasons;
- next-safe-action hints;
- explicit bounded continuation state.

The loop is **external continuation only**:

1. ChatGPT (or another external driver) sends a driver request.
2. RiftCLI validates/authorizes it and either dispatches one RiftOS action or returns `need_more_info`.
3. If more information is needed, the external driver must explicitly send the next driver request with the next loop step.
4. RiftCLI never recursively calls itself or a model.
5. `loopMax` is capped at **8** and `loopStep` must remain below that cap.

Once enabled, N1 may authorize the full RiftOS authority surface, but only one bounded action per accepted request. No model client is added to RiftCLI.

### Gate N1.5 — Persistent push/events

**LIVE-PROVEN on installed Android source `0814fb8cf8ca31186d6e639fc7ad3b965d822897` (2026-09-21).** Job lifecycle and terminal results are pushed over the existing persistent relay connection. Device memory owns the bounded replay ring; the relay owns bounded fan-out only. Driver WebSocket and SSE subscribers use monotonic cursors, reconnect replay and ACKs. The live proof attached an external Chrome SSE subscriber, observed `sseClients: 1`, ran a read-only `version` job, and received the same `job.submitted`, `job.started`, and `job.completed` sequences that DebugHub independently correlated through `event.created`, `cli.event.send`=`queued`, and `cli.ack`=`received`. No job polling was used for lifecycle observation. A second proof forced `sseClients: 0`, created events while disconnected, then reconnected from the prior cursor and replayed exactly the missed range in order with no duplicate or older event. Poll/list/cancel remain recovery/debug controls rather than the steady-state observation loop.

Current source also feeds bounded N1.5 transport metadata into the passive process-wide RiftDebugHub. Component `riftcli.event-bus` records `event.created`; component `mcp.relay` records device-WSS queue attempts, replay, `relay.ready`, socket lifecycle and `cli.ack`. This lets an installed build distinguish local event creation from device-to-Cloudflare receipt without storing event/result payload bodies or relay credentials. A matching relay ACK proves Cloudflare received a sequence; it does not by itself prove an external SSE/WebSocket subscriber consumed it.

### Gate N1.6 — RiftCLI Batch V2

**Live-proven on Builder run #259 / source `eaa2a390438784be435929e49283f9e6281b8ed0`.** `rift_cli_batch` provides at most 16 fully prevalidated sequential steps under one global authority reservation, with bounded results, per-step push events, stop/continue failure policy, truthful cancellation and `rift-cli-batch` provenance. Installed-device proof covered validation, successful 3-step mutation/readback, `rift-cli-batch` Workspace Records provenance, stop/continue failure semantics, nested/retired-batch rejection and replay protection. Retired RiftShell `batch` and multi-op `rift_workspace_exec` stay disabled.

### Gate N1.7 — abuse/reconnect/batch stress

Next promotion gate. Basic SSE reconnect/replay, ACK correlation and duplicate-free cursor recovery are already proven under N1.5. N1.7 exercises forced relay/device restarts, repeated replay gaps, slow subscribers/backpressure, subscriber caps, large-result fallback, batch cancellation between steps, global no-interleave behavior, concurrent-driver pressure, failure policies, bounds and cleanup on the installed build.

Live stress on 2026-09-21 against source `6d21cd5fd2d9ef2331c8ec42a8654d7de07dd31f` proved repeated manual relay replacement recovers on the next MCP call, concurrent read-only MCP bursts drain back to zero pending requests, CLI disable/re-enable events advance through the relay, and normal reconnects preserve the active resume cursor. The same run also exposed one real recovery defect: after an `EOFException` coincided with loss of the device WebSocket attachment/room in-memory cursor, `relay.ready` returned `resumeAfter=0` and the phone replayed 29 already-ACKed events. Duplicate ACKs were rejected as non-advancing, so authority did not repeat, but the transport generated an unnecessary replay storm.

The first cursor-recovery source fix was then installed and live-proven on source `a2b29555445fdcc7ea2ad228f98fd8a1af5a6833`: repeated relay replacement resumed from the exact current ACK high-water instead of zero, replay count stayed zero, five consecutive replacements recovered on the very next MCP call, 80/80 concurrent read-only MCP calls succeeded across five 16-wide waves, in-flight relay replacement did not strand seven simultaneous scans, and large/oversized transport bounds failed closed at the intended layer.

The same live pass proved the external SSE ceiling: eight browser SSE streams opened and the ninth returned `Too many MCP SSE subscribers`. Closing all tabs exposed a second lifecycle defect: Cloudflare/Chrome did not propagate abort/cancel for every stream, leaving eight server-side SSE clients registered. Deliberate ~37 KiB CLI event pressure then evicted six via `backpressureDropped`, proving the bounded 512 KiB queue and fail-closed backpressure path work live; two idle streams remained because they continued to appear drainable to the Worker.

Current source now closes the remaining restart/liveness gaps in two layers. `RiftRelaySettings` persists the highest relay-ACKed CLI sequence as a monotonic app-private scalar and `RiftMcpRelayClient` restores it before the first `device.hello`, so Android process death no longer resets the recovery cursor to zero.

For SSE lifecycle, stable identity is now mandatory: production clients provide `Mcp-Session-Id`, while browser diagnostics may use a validated `?subscriber=<id>` that becomes an isolated `diag:<id>` session. Anonymous streams are rejected. The Worker returns the Durable Object stream directly, Cloudflare request-signal cancellation/passthrough are explicitly enabled, and every SSE connection has an absolute 180-second lease plus up to 30 seconds of jitter. Lease expiry closes/removes the stream and reconnect relies on `Last-Event-ID`. Existing byte backpressure and two-heartbeat no-drain eviction remain secondary protection.

The SSE lifecycle and true Android process-restart portions are now live-proven on source `679dba5a78fc1bd8a66f846933dcb05c139285f1` (2026-09-21). Browser stress exercised stable diagnostic sessions, the independent eight-client ceiling and ninth-client rejection, session replacement, backpressure, and lease cleanup to `sseClients: 0` without generating cleanup traffic. A real Android force-stop/reopen then restored `relay.ready.resumeAfter=1790021835452094` with `cli.replay.send count=0`; RiftCLI correctly restarted OFF, was explicitly re-enabled only after warning, and the next event ACK advanced normally. N1.7 still retains the rest of its abuse matrix before full promotion.

### Gate N1.8 — Repository Consistency Observer

**Hard pre-N2 gate; N1.8.0 source-implemented, promotion pending.**

N1.8.0 now provides the repository fact-graph substrate in `RiftRepositoryConsistencyObserver.kt`. It consumes the existing PI-v2 graph rather than rescanning source, assigns stable content-independent IDs to facts/edges/findings, keeps content hashes separate, canonicalizes graph ordering into a deterministic SHA-256 identity, uses a bounded verified rebuildable app-private cache and exposes the foundation through existing `project kind=consistency`. Whole-repository consistency reuses the shared PI-v2 builder with observer-only 1024-file/1024-edge input bounds, forces exact repository-content verification, and consumes a separate repository-file evidence set so metadata-only files remain represented. Installed source `7ee74c5034bb14c30945d28971c56f35424e5301` live-proved the original content-only false-clean regressions fixed. The current source additionally hardens persisted semantics with PI cache schema v4 producer provenance: semantic cache reuse requires both `RiftSourceIntelligenceV2.VERSION` and a trusted full `BuildConfig.RIFT_SOURCE_SHA`; mismatched/missing/untrusted producer caches are rejected and cache load/rejection state is observable.

N1.8.0 remains unpromoted until the rebuilt installed APK proves old-cache rejection/rebuild, then loads the new current-producer cache after restart, and completes the canonical torture matrix. The test must attack deterministic repeatability, tracked content-only changes, add/delete/rename/move/copy, stale/corrupt/cross-build cache, force-stop/restart, concurrent readers, repository mutation during scan, exact bound crossings, path/identity edge cases, file-type/exclusion/oversize behavior, dependency graph shapes, resource pressure, result-surface parity and cold/warm/restart differential rebuilds. A false-clean, silent skip/truncation, nondeterministic hash, stale node/cache, mixed before/after graph, hang/crash or authority mutation blocks promotion and becomes permanent regression coverage.

A future staged subsystem scan planner is **architecture-locked but implementation-blocked until N1.8.0 promotion**. Once allowed, normal operation should scan the owning subsystem/domain first, reconcile it completely, and expand only across affected graph edges or proof obligations. Full-repository clean scans remain the independent oracle and are mandatory whenever coverage/graph state is uncertain. Later N1.8 gates upgrade the observer from this substrate into repository-wide semantic integrity: incremental invalidation and reverse-dependency propagation, syntax/import resolution, stable semantic identity, caller/dependent propagation, manifest/config/schema/build/JNI/protocol contract checks, test ownership, and README/docs/ROADMAP/TODO/current-status claim consistency.

Deterministic evidence outranks inference; inferred links cannot block promotion alone. Every deterministic finding must include evidence and the graph path explaining the mismatch. Incremental state must periodically equal a clean graph rebuild. The observer remains evidence-only and cannot mutate/approve/push by itself.

Promotion is split into N1.8.0-N1.8.7: fact graph/schema, syntax/import integrity, semantic propagation, cross-boundary contracts, documentation claims, focused proof obligations, adversarial benchmarks/ablations, and installed-device promotion. Canonical specification: `docs/systems/workspace/REPOSITORY_CONSISTENCY_OBSERVER.md`.

### Gate N2 — Federated Rift Memory Kernel

**Hard pre-N3 program; roadmap only until implemented and promoted. N2 is blocked until N1.8 promotion.**

N2 is no longer a generic "engineering state" bucket. It is one canonical Rift Memory Kernel with multiple specialized cognitive engines operating over the same canonical IDs/evidence/transactions.

Kernel authority owns:

- canonical IDs/schema/scope/time;
- immutable/content-addressed evidence;
- canonical event history and current-state projection;
- evidence vs claim vs belief separation;
- trust/provenance;
- protected policy/authority;
- reconciliation and transaction outcomes;
- snapshot/replay/rollback/integrity;
- projection dirty/rebuild state.

Specialists own interpretation and rebuildable projections only:

- temporal/graph;
- episodic;
- consolidation;
- semantic;
- belief/reflection;
- skill/procedural;
- failure intelligence;
- causal;
- commitment;
- predictive/expected-state;
- exact/entity/project/temporal/BM25/vector and other retrieval indexes.

The retrieval path is Memory Router -> specialist retrieval -> fusion/arbitration -> Context Compiler. Semantic similarity never establishes truth.

The learning/update path is Planner -> Tools -> Observer -> Validator -> Difference/Surprise -> Reconciliation -> governed canonical memory transaction.

Storage starts with a replaceable SQLite reference `MemoryStore`. A later RiftStore backend may replace SQLite responsibilities only after identical benchmark workloads prove enough benefit to justify its complexity without weakening crash consistency or integrity.

N2 is implemented and promoted as N2.0-N2.12: contract/baseline freeze; canonical JSON/evidence/event ledgers; SQLite backend; reconciliation; temporal graph; episodic/consolidation/semantic; belief/predictive; skill/failure/causal/commitment/policy; router/fusion/Context Compiler; Observer/Validator closed loop; RiftStore competition; fsck/poisoning/crash/scale hardening; public/private benchmarks plus incremental hybrids and ablations.

Full frozen program: `../riftmemory/N2_FEDERATED_MEMORY_ROADMAP.md`.

### Hard N3 barrier

N3 does not start because the N2 architecture exists or because one happy-path demo works. N2.12 must first prove the mandatory weakest-link categories: canonical integrity, temporal accuracy, Observer reconciliation, project isolation, provenance, poisoning resistance, procedural learning, cold restart, crash recovery, rollback, projection rebuild, integrity/security and bounded Android resource behavior.

### Gate N3 — Architecture and impact engine

Before a proposed change, derive:

- owning subsystem;
- dependency impact;
- architecture invariants;
- required docs;
- relevant tests;
- build/package impact;
- security/capability boundaries.

### Gate N4 — Planner

Dependency-aware task graph with:

- global architectural planning;
- incremental execution;
- explicit preconditions/postconditions;
- checkpoints;
- failure recovery;
- no multi-operation opaque batch mutation.

### Gate N5 — Research/evidence

Bounded evidence ledger with:

- source/claim relationships;
- authoritative-source preference;
- freshness/version identity;
- disputed/unresolved assumptions;
- independent verification requirements.

### Gate N6 — Verification

Candidate-bound:

- compile;
- tests;
- regression;
- security;
- dependencies;
- docs parity;
- APK/package validation;
- device/E2E checks;
- performance when relevant.

### Gate N7 — adversarial professional-engineering loop

Attack RiftCLI with:

- corrupt/stale memory;
- renamed/moved files;
- dependency cycles;
- interrupted writes;
- misleading passing tests;
- dirty repositories;
- stale docs;
- failed builds;
- malformed driver requests;
- huge project graphs;
- restart/resume;
- conflicting architectural assumptions.

Every discovered defect becomes a regression test before promotion.

## Bootstrap validation

The source gate must verify:

- exact Kotlin native-host snapshot;
- exact C++ source snapshot;
- CMake native build wiring;
- both ABI filters;
- shell routes `rift-cli` only through `RiftCliHost`;
- Local Agent no longer routes through the retired Experimental CLI;
- forbidden old CLI source files are absent;
- native source contains no model/API/network/process execution surface;
- documentation/source ownership points to this subsystem.

The Builder must continue verifying both `libriftcli.so` ABI payloads in every final signed APK. RiftCLI regression coverage must include the native driver protocol, dispatcher/provenance bridge, persistent push/replay transport, and Batch V2 while separately locking the retired batch paths off.


## Failure signatures

- `rift-cli` routes to any Kotlin planner/brain instead of `RiftCliHost` -> ownership regression.
- `RiftCliHost.kt` gains project memory/planning/research/verification logic -> duplicate-core regression.
- C++ core gains a model/API client or network transport -> dependency-direction regression.
- RiftCLI authorizes mutation/tool/network-backed RiftOS work while disabled -> authority regression.
- RiftCLI recursively dispatches `rift-cli` internally instead of requiring a new external-driver continuation -> loop-boundary regression.
- a driver loop exceeds 8 steps or advances without an explicit external request -> bounded-loop regression.
- a duplicate authority-bearing `request-id` executes again instead of being replay-rejected -> idempotency regression.
- a long CLI action blocks the normal RiftShell worker instead of returning a job ID and publishing push lifecycle events -> async-execution regression.
- normal job observation requires rapid HTTP/MCP polling instead of persistent relay push -> push-channel regression.
- reconnect replay duplicates already-consumed events, regresses subscriber cursors, or loses retained events because the device resumes only from relay high-water -> replay regression.
- a slow SSE subscriber can build an unbounded write queue -> event-backpressure regression.
- a lost submit response cannot be recovered by original `request-id` -> job-recovery regression.
- cancellation reports terminal `cancelled` before the worker actually resolves, or loses the `cancelled_may_have_applied` / `completed_after_cancel_request` distinction -> cancellation-truth regression.
- a CLI-driven mutation bypasses RiftPatchSessions provenance -> provenance regression.
- either ARM64 or ARM32 native library is missing from the final APK -> ABI parity regression.
- JNI uses modified UTF helpers instead of explicit UTF-16/UTF-8 conversion -> text-boundary regression.
- a retired Experimental RiftCLI Kotlin/swarm/IR source returns -> reset regression.
- retired RiftShell `batch` or multi-operation `rift_workspace_exec` becomes executable again -> retired-batch regression.
- Batch V2 bypasses whole-plan prevalidation, global authority reservation, per-step bounds/events/provenance, or cancellation checks -> Batch V2 regression.

## Fix map

Native CLI commands/process-local state -> `android/app/src/main/cpp/riftcli/rift_cli_core.cpp`.

Native core public interface -> `android/app/src/main/cpp/riftcli/rift_cli_core.h`.

JNI marshalling only -> `android/app/src/main/cpp/riftcli/rift_cli_jni.cpp`.

Native library definition -> `android/app/src/main/cpp/CMakeLists.txt`.

Android library loader/result envelope -> `android/app/src/main/java/com/riftos/app/RiftCliHost.kt`.

Shell command routing -> `android/app/src/main/java/com/riftos/app/RiftNativeShell.kt`.

Local Agent remains an independent RiftOS owner; enabled CLI reaches it only through normal RiftShell dispatch -> `android/app/src/main/java/com/riftos/app/RiftNativeShellServices.kt`.

ABI/source snapshot ownership -> `android/app/build.gradle.kts`.

Source regression gate -> `scripts/test-rift-cli-native-bootstrap.mjs` + `scripts/test-rift-cli-driver-protocol.mjs` + `scripts/test-rift-cli-push-channel.mjs` + `scripts/test-rift-cli-batch-v2.mjs` + wiring/transport validators.

Final APK native-library proof -> public Builder `scripts/verify-riftos-apk.sh`.

## Validation

Source validation must verify:

- retired CLI-specific Kotlin/swarm/IR/tokenizer files are absent;
- `RiftCliHost` is transport-only and loads `libriftcli.so`;
- RiftShell routes `rift-cli` only through that host;
- CMake owns one `riftcli` shared library;
- Gradle pins NDK/CMake plus `arm64-v8a` and `armeabi-v7a`;
- exact C++ source snapshot matches the declared build contract;
- core defaults OFF and enablement is process-local;
- enabled authority is delegated through existing RiftOS owners rather than raw process/network clients;
- shell and ToolHost dispatch remain one action per accepted request;
- every authority-bearing request requires a bounded unique `request-id`; accepted IDs are retained without eviction for the whole RiftOS process lifetime, duplicate IDs are replay-rejected across disable/re-enable cycles, and the 4096-entry protection set fails closed at capacity until process restart;
- re-enabling an already-enabled CLI preserves replay/loop state;
- shell actions use the separate serialized CLI worker rather than blocking the normal RiftShell worker;
- direct CLI ToolHost actions use async jobs with no fixed CLI wall-clock timeout while normal MCP timeouts remain unchanged;
- persistent relay push is the normal observation path, with bounded device-owned replay, per-subscriber cursors, ACK handling and bounded WebSocket/SSE fan-out;
- `rift_cli_job_list`, `rift_cli_job_poll` and `rift_cli_job_cancel` provide recovery/explicit observation/cancellation for job lanes;
- jobs retain the original `request-id` so lost submit responses can be recovered without replay;
- actual CLI execution is globally serialized across shell and ToolHost lanes while poll/list/cancel remain responsive;
- cancellation exposes `cancelling`, `cancelled_may_have_applied` and `completed_after_cancel_request` truthfully;
- ToolHost dispatch dynamically accepts current/future `rift_*` tools but rejects `rift_shell_exec` and `rift_workspace_exec` in that lane;
- driver loops are process-local, identity-bound, strictly monotonic, externally continued and capped at 8 steps;
- CLI shell mutations retain `RiftPatchSessions` provenance and direct tool mutations retain ToolSandbox provenance;
- Batch V2 accepts at most 16 prevalidated sequential steps, holds one global authority reservation for the whole plan, rejects nested/retired batch paths, emits unique per-step events, preserves `rift-cli-batch` provenance and does not swallow cancellation interrupts;
- JNI uses explicit UTF-16/UTF-8 transcoding;
- the native core itself still contains no model/API client or raw process/network execution primitive.

Builder validation must additionally prove the final signed APK contains:

- `lib/arm64-v8a/libriftcli.so`;
- `lib/armeabi-v7a/libriftcli.so`;
- no x86/x86_64 RiftCLI library.

N1, N1.5 and N1.6 are installed-device proven. N1.5 now has live external SSE push-without-poll proof plus disconnect/reconnect cursor replay with ACK correlation and no duplicate/older replay. Large-result fallback, slow-subscriber/backpressure behavior, subscriber caps, forced restart recovery, repeated reconnect abuse, cancellation/no-interleave and broader bounds remain N1.7 stress work. N2 remains roadmap-only and N3 stays blocked until the full N2.12 promotion evidence is complete.
