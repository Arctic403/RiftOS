# RiftCLI Trust Kernel + Local Intelligence Architecture

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-22.**

Gate N0 is proven on the installed Android device. Gate N1 plus its replay/job/cancellation/provenance hardening is also proven on the installed Android device (Builder run #255 / source `121edf6b3255beca33a45351d3952c7026b5cb4b`) on `armeabi-v7a`.

Status: **N1, N1.5 persistent push/events, and N1.6 Batch V2 are live-proven on the installed Android device. N1.5 was promoted on 2026-09-21 from installed source `0814fb8cf8ca31186d6e639fc7ad3b965d822897`: an external Chrome SSE subscriber received the same sequenced lifecycle events that DebugHub recorded through `event.created -> cli.event.send -> cli.ack`, without polling, and a forced disconnect/reconnect replayed only the missed cursor range with no duplicates.**

RiftCLI is being rebuilt from scratch as RiftOS's engineering supervisor with a compiled native trust kernel and a replaceable Local Agent-owned intelligence package. The previous Experimental RiftCLI Kotlin/swarm/IR/lifecycle implementation was intentionally retired rather than used as the new foundation.

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
        +--> replaceable local RiftCLI intelligence package
        |      /workspace/.riftcli/
        |      planner / memory / skills / observer / policies / commands
        |      bounded QuickJS; no direct authority
        |
        v
compiled RiftCLI trust kernel
C++ gate + driver/replay/loop protocol + dispatch safety
        |
        v
compiled Local Agent execution supervisor
        |
        v
bounded RiftOS authorities
```

The external reasoning source may be ChatGPT, another AI client, a human, or deterministic automation, but it does **not** host RiftCLI directly. The RiftOS Local Agent owns the CLI host boundary.

**Architecture lock:** RiftCLI is split into a small compiled trust kernel and a replaceable local intelligence package. The local package lives under `/workspace/.riftcli/`, executes only inside bounded headless QuickJS, and may propose/request work but can never grant itself authority. All authority still comes from the compiled process-local gate plus the existing Local Agent/native execution supervisor.

The compiled trust kernel owns only security/protocol invariants: enable/disable state, authority declaration, replay protection, loop/request bounds, recursion prevention, dispatch validation, cancellation/job reservation and the native driver contract. Planner, project memory, skills, command policy, Observer interpretation, retry strategy, procedure knowledge and future CLI cognition belong in the replaceable local package unless a later proof shows a specific invariant must be native.

RiftCLI **never calls a model or inference API**. There is no model-backend interface inside the CLI. The native process-local enable switch remains the only CLI enable gate, defaults OFF after each process start, and is non-persistent.

## Language and host boundary

The canonical **trust-kernel/protocol** implementation language is C++. Kotlin remains Android host/supervisor glue. The canonical **replaceable intelligence layer** is local package code/data executed by the Local Agent in the bounded headless runtime.

Compiled code must not become the owner of planner policy, project memory content, skills, architecture reasoning, research procedures, task strategy or replaceable command behavior. Local package code must not gain direct process, network, Android, Git, shell or ToolHost authority; it can only return bounded request envelopes for the compiled supervisor to validate.

## Source ownership

Current owners:

- `android/app/src/main/cpp/riftcli/rift_cli_core.cpp` — native CLI command/state core.
- `android/app/src/main/cpp/riftcli/rift_cli_core.h` — native core interface.
- `android/app/src/main/cpp/riftcli/rift_cli_jni.cpp` — UTF-safe JNI adapter only.
- `android/app/src/main/cpp/CMakeLists.txt` — native build definition.
- `android/app/src/main/java/com/riftos/app/RiftCliHost.kt` — thin Android JNI host.
- `android/app/src/main/java/com/riftos/app/RiftLocalCliPackage.kt` — bounded loader/validator for the replaceable `/workspace/.riftcli/` intelligence package; local code has no direct authority and may re-enter only the native driver protocol.
- `/workspace/.riftcli/` — local replaceable package root (outside the APK and outside the RiftOS source repo); future commands/skills/planner/memory/observer/policies live here after their gates.
- `android/app/src/main/java/com/riftos/app/RiftVortexLocalAgent.kt` — RiftOS Local Agent ownership boundary; routes trust-kernel commands native and all other enabled CLI commands through the local package.
- `android/app/src/main/java/com/riftos/app/RiftNativeShell.kt` — compatibility `rift-cli` shell entry plus the existing CLI execution supervisor; direct shell entry is routed through `RiftOsLocalAgent` before native CLI execution.

## Target ABIs

RiftCLI is native-first with:

- `arm64-v8a` — primary Android ABI;
- `armeabi-v7a` — required 32-bit compatibility ABI.

Both ABIs are declared in the Android native build contract. A release is not considered RiftCLI-capable unless both native libraries are packaged and verified.

## N0 proof and N1 authority model

Gate N0 proved the native bootstrap on-device. The enable switch remains process-local, defaults OFF after every RiftOS process start, and still requires the literal `CONFIRM-EXPERIMENTAL` acknowledgement.

The current host boundary is now fixed: MCP/RiftShell ingress enters the existing RiftOS Local Agent, the Local Agent invokes RiftCLI as an internal intelligence/tool layer, and the existing CLI execution supervisor delegates only through bounded RiftOS authorities. The `rift-cli` shell command is retained as a compatibility/development surface, and `riftos-agent cli ...` exposes the same bounded path specifically for Local Agent hosting tests. Both route through `RiftOsLocalAgent` before reaching the native CLI core; raw `riftos-agent intelligence ...` remains unexposed.

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

# equivalent Local Agent-host boundary test surface
riftos-agent cli help
riftos-agent cli status
riftos-agent cli architecture
riftos-agent cli enable CONFIRM-EXPERIMENTAL
riftos-agent cli disable
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

**Post-N2 Batch hardening B1 is PROMOTED on installed source `cacbc36dca906f226953a926a9eb66138817863b`, Builder run `36161133278` / run number `381`.** `RiftCliPersistentJobStore.kt` now provides a sealed, bounded, app-private AtomicFile journal shared by hosted tool, shell and Batch V2 jobs. Every submitted job must persist before execution begins. Batch plans persist their normalized full plan plus SHA-256 `planHash`, current/completed step counters, bounded retained step results, cancellation state, recovery metadata and a batch-scoped authority lease. The lease reserves execution capacity only: it explicitly carries `authorizationBypass=false`, `perOperationAuthorizationRequired=true` and `observerValidatorBypass=false`; every tool/shell step still re-enters its normal authority/provenance path. Batch state is journaled before a step begins and again after its result commits. A nonterminal record found after process death becomes `recovery_required`; the store forbids blind whole-job replay and requires a later system-declared retry-safe/idempotent decision before any resume. `rift_cli_job_list/poll/cancel` can observe or close recovered jobs without re-executing them. Live force-stop proof interrupted a 16-step read-only batch at `currentStep=7` with six completed steps; restart recovered the same persisted-only job as `recovery_required`, released the stale lease as `released_on_process_loss`, preserved `blindReplayAllowed=false`, and replayed nothing. Explicit recovered cancellation persisted terminal `cancelled_after_recovery` with lease `released_after_recovery_cancel`. Local Agent and MCP batch exposure remain intentionally absent. **B2A retry-safe/idempotent recovery is PROMOTED on installed source `ac692ed4b3457f5947053e6648dc63702d498ca7`, Builder run `36171651321` / run number `382`.** `RiftCliRecoveryPolicy.kt` owns the compiled recovery verdict and sets `callerMayOverride=false`, `automaticRetryAllowed=false`, `wholeJobReplayAllowed=false`; only conservative read/context tool and shell operations are retry-safe/idempotent in B2A. Batch snapshots persist `executionCwd` plus pre-step `currentStepCwd`; recovery revalidates the stored normalized plan, recomputes and matches `planHash`, verifies completed-step/result counts, reacquires the global gate, never reruns completed steps, and resumes an uncertain step only when the system policy says both retry-safe and idempotent. `rift_cli_job_recover` is internal RiftCLI control, requires the explicit process-local enable gate because resume can execute authority, and remains absent from Local Agent/MCP. Installed live proof force-stopped a 16-step read-only batch at uncertain step 14 after 13 completed steps. Restart preserved the same job ID, exact `planHash=8dd4154b67862326f166888fe0271584fdf4e3e59731ef628b663ed07e5e55bb`, `executionCwd=/`, `currentStep=14`, `completedSteps=13` and 13 retained results, with lease `released_on_process_loss` and no replay. Recovery while the process-local CLI gate was OFF was rejected `cli-disabled`. After explicit enable, `rollback` remained denied with `rollbackSupported=false`; `resume` was accepted only under RiftOS-owned policy for uncertain `tree` (`retrySafe=true`, `idempotent=true`, `authoritativeMutation=false`, `callerMayOverride=false`, `automaticRetryAllowed=false`, `wholeJobReplayAllowed=false`). The same job completed 16/16 with the original first 13 results preserved and only steps 14-16 executed after recovery; the lease ended `released_after_recovery`. Post-proof Observer remained clean with Proofs `mode=none`. **B2B failure/rollback semantics is PROMOTED on installed source `24c2352e178a08583a5905799dd9880d296c9558`, Builder run `36176524043` / run number `383`.** `rollbackPolicy=on-failure` is opt-in and requires `failurePolicy=stop`; rollback-enabled plans reject every authoritative mutation except operations the system-owned policy explicitly marks rollback-capable. The first bounded set is shell `write`, `touch` and `mkdir`. RiftCLI persists each supported mutation's pre-state before execution with 128 KiB per-entry / 512 KiB total snapshot bounds, verifies the resulting post-state, stores rollback metadata privately with public snapshot-byte redaction, and rolls back in reverse order only when the live target still matches the recorded batch-produced post-state (or is already back at pre-state). Drift fails closed rather than overwriting newer work. Rollback outcomes are persisted as `rolled_back`, `rollback_failed` or `cancelled_during_rollback`, each with explicit lease-release/cancellation evidence. Recovered rollback revalidates the persisted normalized plan and `planHash`, validates journal identity/bounds against started steps, and executes rollback without replaying the uncertain operation. Installed run 383 live-proved automatic rollback, drift fail-closed behavior and process-loss recovered rollback. Automatic rollback job `cli-batch-job-9d9f06f0-7481-4732-85a6-9545162126c9` terminalized `rolled_back` and deleted its created file with lease `released_after_rollback`. Drift job `cli-batch-job-2acb1581-24f4-4ea3-99d5-6e9c8bc8dcb8` detected `stateDrift=true`, terminalized `rollback_failed`, and preserved externally newer content with lease `released_after_rollback_failure`. Recovered rollback job `cli-batch-job-7ef115f2-dd76-402e-b98b-9be8d1b722bb` restarted `recovery_required` at `currentStep=7` / `completedSteps=6`, rejected rollback while the CLI gate was OFF, then after explicit enable rolled back the persisted step-1 mutation without replaying the uncertain step; terminal state was `rolled_back`, `currentStep=7`, `completedSteps=6`, `wholeJobReplayAllowed=false`, lease `released_after_recovery_rollback`, and the proof file was absent. Post-proof Observer was clean with Proofs `mode=none`. **B2B is promoted. Local Agent Batch exposure is PROMOTED on installed source `24937bbcfb6d717a5bddbe79de251bd39026dacc`, Builder run `36201078437` / run number `386`; dedicated MCP Batch exposure is SOURCE IMPLEMENTED and pending Builder/install/live proof. The public MCP names are `rift_batch_submit|list|poll|cancel|recover`; `RiftToolHost` special-routes them into `RiftOsLocalAgent op=batch`, which retains the already-proven hard mapping to internal `rift_cli_batch|job_*` controls. Internal RiftCLI control names remain non-public.** Live proof covered bounded OFF-state list, OFF-state submit rejection, two-step read-only completion, mid-batch cancellation, force-stop recovery as `recovery_required` with `released_on_process_loss` and no whole-job replay, OFF-gated recover, resumed completion on the same job/hash, automatic rollback with the proof file absent afterward, and fail-closed invalid action/resolution/17-step bounds. `RiftOsLocalAgent op=batch` now exposes only `submit|list|poll|cancel|recover`. The translator hard-maps those actions to `rift_cli_batch`, `rift_cli_job_list`, `rift_cli_job_poll`, `rift_cli_job_cancel`, and `rift_cli_job_recover`, bounds plan/ID inputs, and always re-enters the already-proven native RiftCLI `driver request` authority path through `executeCliForLocalAgent`. It owns no executor and cannot choose arbitrary tool names. The process-local `CONFIRM-EXPERIMENTAL` gate and existing disabled-state job-control rules remain authoritative. `riftos-agent batch ...` remains a bounded shell acceptance-test surface only. First-class model-facing MCP Batch controls are now source-implemented as `rift_batch_submit|list|poll|cancel|recover`; Tool Host routes them through this promoted Local Agent boundary, while internal `rift_cli_*` controls remain non-public. Builder/install/direct-MCP live proof is complete on source `6127dccc8e27a0b3f88779d8292c6e71572b609f`, Builder run `36207328573` / run number `388`; first-class MCP Batch is promoted.

### Batch V2 exposure policy

Batch V2 is an internal RiftCLI multi-step authority lane today. **External/model-facing batch submission is gated on the current Batch V2 hardening chain, not on completion of every future RiftCLI N-gate.** The required order is single-command persistence → process/disconnect recovery → cancellation → CLI-local batch recovery → failure/rollback semantics → Local Agent exposure → MCP exposure. Comparative/performance benchmarks remain separately forbidden until the entire RiftCLI roadmap is complete and live. When the Batch V2 integration gate is proven, batching follows exactly:

`external reasoning -> MCP/relay -> RiftOS Local Agent -> RiftCLI -> rift_cli_batch -> bounded RiftOS authorities`

The external side submits one bounded batch job, not a list of independent MCP mutations. Local Agent/RiftCLI own full-plan prevalidation, step sequencing, one global authority reservation, cancellation/recovery, explicit `stop`/`continue` failure policy, bounded per-step results, per-step push events and `rift-cli-batch` provenance. The initial exposed cap remains **16 steps**. A transport disconnect must never justify replaying the mutation plan: request/job identity remains authoritative, and reconnect uses job/event recovery to continue observation. Observer, Validator, source-ownership and security/authority checks remain mandatory for batch work exactly as for single-step work.

This policy **does not** re-enable multi-operation `rift_workspace_exec` or the retired RiftShell `batch` command. Those remain disabled even after external Batch V2 exposure. Model-facing `rift_workspace_exec` stays one operation per call; restored batching is only the Local Agent-hosted RiftCLI job lane.

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

For SSE lifecycle, stable identity is mandatory: production clients provide `Mcp-Session-Id`, while browser diagnostics may use a validated `?subscriber=<id>` that becomes an isolated `diag:<id>` session. Anonymous streams are rejected. The Worker returns the Durable Object stream directly and Cloudflare request-signal cancellation/passthrough is enabled. SSE is treated as a long-lived stream rather than a short MCP request: 15-second heartbeats, abort handling and byte backpressure govern liveness, while every connection has an absolute 300-second lease plus up to 60 seconds of jitter for stale-stream recycling. Lease expiry closes/removes the stream and reconnect relies on `Last-Event-ID`; two-heartbeat no-drain eviction remains secondary protection.

The SSE lifecycle and true Android process-restart portions are now live-proven on source `679dba5a78fc1bd8a66f846933dcb05c139285f1` (2026-09-21). Browser stress exercised stable diagnostic sessions, the independent eight-client ceiling and ninth-client rejection, session replacement, backpressure, and lease cleanup to `sseClients: 0` without generating cleanup traffic. A real Android force-stop/reopen then restored `relay.ready.resumeAfter=1790021835452094` with `cli.replay.send count=0`; RiftCLI correctly restarted OFF, was explicitly re-enabled only after warning, and the next event ACK advanced normally. N1.7 still retains the rest of its abuse matrix before full promotion.

### Gate N1.8 — Repository Consistency Observer

**N1.8.0-N1.8.7 are fully promoted; the N1.8 Observer prerequisite for N2 is satisfied.**

N1.8.0 now provides the repository fact-graph substrate in `RiftRepositoryConsistencyObserver.kt`. It consumes the existing PI-v2 graph rather than rescanning source, assigns stable content-independent IDs to facts/edges/findings, keeps content hashes separate, canonicalizes graph ordering into a deterministic SHA-256 identity, uses a bounded verified rebuildable app-private cache and exposes the foundation through existing `project kind=consistency`. Whole-repository consistency reuses the shared PI-v2 builder with observer-only 1024-file/1024-edge input bounds, forces exact repository-content verification, and consumes a separate repository-file evidence set so metadata-only files remain represented. Installed source `7ee74c5034bb14c30945d28971c56f35424e5301` live-proved the original content-only false-clean regressions fixed. The current source additionally hardens persisted semantics with PI cache schema v4 producer provenance: semantic cache reuse requires both `RiftSourceIntelligenceV2.VERSION` and a trusted full `BuildConfig.RIFT_SOURCE_SHA`; mismatched/missing/untrusted producer caches are rejected and cache load/rejection state is observable.

N1.8.0 is now promoted on installed source `9d196567e38e781d97a24bb2c808b47cbc2303eb`. The final exact-current-build proof reproduced canonical graph SHA-256 `25869f703a8a4a7fe36b28ae6f9c3ab34daece15925c21b98166100eaab57d87`, graph identity, 1034 facts / 1088 edges / 260 repository files, `complete=true`, and verified unchanged cache state on the first observer read after a real Android force-stop/reopen. The promoted foundation retains permanent regression coverage for deterministic repeatability, tracked content-only changes, add/delete/rename/move/copy, stale/corrupt/cross-build cache, concurrent readers, mutation-during-scan, exact bound crossings, path/identity edge cases, file-type/exclusion/oversize behavior, dependency shapes, resource pressure, result-surface safety and cold/warm/restart parity. No future CLI intelligence lane may weaken those observer invariants.

The future staged subsystem scan planner is **architecture-locked and no longer blocked by N1.8.0**. Its implementation now belongs to N1.8.1+ work; normal operation should scan the owning subsystem/domain first, reconcile it completely, and expand only across affected graph edges or proof obligations. Full-repository clean scans remain the independent oracle and are mandatory whenever coverage/graph state is uncertain. Later N1.8 gates upgrade the observer from this substrate into repository-wide semantic integrity: incremental invalidation and reverse-dependency propagation, syntax/import resolution, stable semantic identity, caller/dependent propagation, manifest/config/schema/build/JNI/protocol contract checks, test ownership, and README/docs/ROADMAP/TODO/current-status claim consistency.

Deterministic evidence outranks inference; inferred links cannot block promotion alone. Every deterministic finding must include evidence and the graph path explaining the mismatch. Incremental state must periodically equal a clean graph rebuild. The observer remains evidence-only and cannot mutate/approve/push by itself.

Promotion is split into N1.8.0-N1.8.7: fact graph/schema, syntax/import integrity, semantic propagation, cross-boundary contracts, documentation claims, focused proof obligations, adversarial correctness/layer-isolation proof, and installed-device promotion. Performance/comparative benchmarking remains deferred until the full RiftCLI stack is 100% complete and live. Canonical specification: `docs/systems/workspace/REPOSITORY_CONSISTENCY_OBSERVER.md`.

### Gate N2 — Federated Rift Memory Kernel

**Hard pre-N3 program. N2.0-N2.4 are promoted; N2-M1 is promoted on installed source `694c1e31a6c3f4bd4317edd121208be894be2586`, Builder run `36048901054` / run number `346`, and N2-M2 is promoted on installed source `18f1156075e08cb94573a9392031ac64552313f2`, Builder run `36067080197` / run number `350`, with all N2.3/N2.4 diagnostics green and clean post-install Observer continuity; N2.5 + N2.6 are promoted together under N2-M3 on installed source `62382a94f50dd6052e1754c1496da2a0f794c0af`, Builder run `36075992479` / run number `355`, N2.7-N2.12 remain pending, and canonical memory runtime authority remains inactive. N2.1-N2.11 execute in six macro patches (1+2, 3+4, 5+6, 7+8, 9, 10+11) while retaining separate per-phase evidence gates; N2.12 remains separate. Performance/comparative benchmarking stays deferred until the full RiftCLI stack is 100% complete and live.**

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

Storage starts with a replaceable SQLite reference `MemoryStore`. During N2, RiftStore may only prove exact MemoryStore correctness/conformance and cannot replace the SQLite production reference. Any comparative replacement decision is deferred until the full RiftCLI stack is complete/live and then requires measured benefit without weakening crash consistency or integrity.

N2 is implemented and promoted as N2.0-N2.12: contract/correctness-baseline freeze; canonical JSON/evidence/event ledgers; SQLite backend; reconciliation; temporal graph; episodic/consolidation/semantic; belief/predictive; skill/failure/causal/commitment/policy; router/fusion/Context Compiler; Observer/Validator closed loop; RiftStore interface/conformance; fsck/poisoning/crash/bounded-capacity hardening; and final correctness/adversarial promotion. Public/private comparative benchmarks, incremental hybrid scoring and ablations are deferred post-CLI.

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
