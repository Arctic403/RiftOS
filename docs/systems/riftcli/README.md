# RiftCLI Native Architecture

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-20.**

Gate N0 is proven on the installed Android device (RiftOS run #250 / source `6f7a61295d6c75ae97cdde59231d767d39eb8152`): native C++ status/architecture, `armeabi-v7a` execution, explicit process-local enable, fail-closed unsupported command handling, and force-stop/restart reset back to disabled all passed.

Status: **Gate N1 / native Driver Protocol in development**

RiftCLI is being rebuilt from scratch as RiftOS's native engineering supervisor. The previous Experimental RiftCLI Kotlin/swarm/IR/lifecycle implementation was intentionally retired rather than used as the new foundation.

## Permanent dependency direction

```text
external reasoning driver
        |
        v
MCP / RiftShell
        |
        v
thin Kotlin JNI host
        |
        v
C++ RiftCLI core
        |
        v
bounded RiftOS authorities
```

The driver may be ChatGPT, another AI client, a human, or deterministic automation.

RiftCLI **never calls a model or inference API**. There is no model-backend interface inside the CLI. The reasoning driver is replaceable; project engineering state and verification policy will remain local.

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
- `android/app/src/main/java/com/riftos/app/RiftNativeShell.kt` — existing `rift-cli` shell routing entry.

## Target ABIs

RiftCLI is native-first with:

- `arm64-v8a` — primary Android ABI;
- `armeabi-v7a` — required 32-bit compatibility ABI.

Both ABIs are declared in the Android native build contract. A release is not considered RiftCLI-capable unless both native libraries are packaged and verified.

## N0 proof and N1 authority model

Gate N0 proved the native bootstrap on-device. The enable switch remains process-local, defaults OFF after every RiftOS process start, and still requires the literal `CONFIRM-EXPERIMENTAL` acknowledgement.

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

### Live-poll job execution

N1 does not keep ChatGPT/MCP blocked on long CLI work.

Both authority lanes are job-based, and Gate N1 permits **exactly one outstanding authority job globally** across shell + ToolHost. A second authority action is rejected until the current job reaches a terminal state; job list/poll/cancel controls remain available.

- RiftShell actions run on a dedicated single-thread CLI worker so the normal RiftShell worker remains free to service polling/cancellation requests.
- Direct `rift_*` ToolHost actions run through the existing confined ToolSandbox on a CLI job lane with **no fixed CLI wall-clock timeout**.
- a fair process-wide `RiftCliExecutionGate` serializes actual CLI execution across both lanes, preventing shell-vs-ToolHost mutation races while leaving list/poll/cancel responsive.
- normal non-CLI MCP calls keep their existing bounded timeouts.

Every submitted action returns a process-local `jobId`. The external driver then uses new, unique driver request IDs to call:

```text
--tool rift_cli_job_list   --tool-args {"requestId":"<original-request-id>"}
--tool rift_cli_job_poll   --tool-args {"jobId":"<job-id>"}
--tool rift_cli_job_cancel --tool-args {"jobId":"<job-id>"}
```

`rift_cli_job_list` provides recovery when the original submit response is lost: the driver can locate the already-started job by its original `request-id` without replaying the action. List responses are metadata-only; full output/result data is returned only by explicit `rift_cli_job_poll` for a concrete job ID. Terminal job data is limited to 2 MiB per job, 16 retained jobs per lane, and 5 minutes of retention; larger successful results are reported as `completed_result_too_large`.

Cancellation is explicit and observable. A queued job that is cancelled before execution ends as `cancelled`. A running operation first enters `cancelling`; if it still completes successfully, the terminal state is `completed_after_cancel_request`. If interruption is observed after execution may already have touched state, the terminal state is `cancelled_may_have_applied` instead of pretending rollback is proven. Disabling RiftCLI requests cancellation of both shell and ToolHost CLI jobs. The idempotent `rift_cli_job_list`, `rift_cli_job_poll`, and `rift_cli_job_cancel` controls remain available while CLI authority is disabled so the external driver can verify whether a previously-authorized job actually stopped.

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

RiftCLI must not reintroduce the retired multi-operation batch-edit model.

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

Current gate. Add a bounded native protocol for external reasoning input:

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

Once enabled, N1 may authorize the full RiftOS authority surface, but only one bounded action per accepted request. Shell and ToolHost actions are submitted as live-poll jobs; the external driver recovers/lists, polls or cancels them explicitly. No model client is added to RiftCLI.

### Gate N2 — Engineering State

Persistent native project memory:

- project identity and roots;
- project/subsystem relationships;
- architecture decisions;
- known invariants;
- hazards and rejected approaches;
- task history;
- test/build history;
- checkpoints and recovery;
- evidence references and confidence.

Current source-layout truth should come from Project Intelligence rather than duplicated stale source snapshots.

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

The Builder must continue verifying both `libriftcli.so` ABI payloads in every final signed APK. N1 additionally requires source regression coverage for the native driver protocol and the RiftShell dispatcher/provenance bridge.


## Failure signatures

- `rift-cli` routes to any Kotlin planner/brain instead of `RiftCliHost` -> ownership regression.
- `RiftCliHost.kt` gains project memory/planning/research/verification logic -> duplicate-core regression.
- C++ core gains a model/API client or network transport -> dependency-direction regression.
- RiftCLI authorizes mutation/tool/network-backed RiftOS work while disabled -> authority regression.
- RiftCLI recursively dispatches `rift-cli` internally instead of requiring a new external-driver continuation -> loop-boundary regression.
- a driver loop exceeds 8 steps or advances without an explicit external request -> bounded-loop regression.
- a duplicate authority-bearing `request-id` executes again instead of being replay-rejected -> idempotency regression.
- a long CLI action blocks the normal RiftShell worker instead of returning a live-poll `jobId` -> polling regression.
- a lost submit response cannot be recovered by original `request-id` -> job-recovery regression.
- cancellation reports terminal `cancelled` before the worker actually resolves, or loses the `cancelled_may_have_applied` / `completed_after_cancel_request` distinction -> cancellation-truth regression.
- a CLI-driven mutation bypasses RiftPatchSessions provenance -> provenance regression.
- either ARM64 or ARM32 native library is missing from the final APK -> ABI parity regression.
- JNI uses modified UTF helpers instead of explicit UTF-16/UTF-8 conversion -> text-boundary regression.
- a retired Experimental RiftCLI Kotlin/swarm/IR source returns -> reset regression.
- an opaque multi-operation batch mutation path returns -> incremental-execution regression.

## Fix map

Native CLI commands/process-local state -> `android/app/src/main/cpp/riftcli/rift_cli_core.cpp`.

Native core public interface -> `android/app/src/main/cpp/riftcli/rift_cli_core.h`.

JNI marshalling only -> `android/app/src/main/cpp/riftcli/rift_cli_jni.cpp`.

Native library definition -> `android/app/src/main/cpp/CMakeLists.txt`.

Android library loader/result envelope -> `android/app/src/main/java/com/riftos/app/RiftCliHost.kt`.

Shell command routing -> `android/app/src/main/java/com/riftos/app/RiftNativeShell.kt`.

Local Agent remains an independent RiftOS owner; enabled CLI reaches it only through normal RiftShell dispatch -> `android/app/src/main/java/com/riftos/app/RiftNativeShellServices.kt`.

ABI/source snapshot ownership -> `android/app/build.gradle.kts`.

Source regression gate -> `scripts/test-rift-cli-native-bootstrap.mjs` + `scripts/test-rift-cli-driver-protocol.mjs` + `scripts/validate-rift-wiring.mjs`.

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
- direct CLI ToolHost actions use live-poll jobs with no fixed CLI wall-clock timeout while normal MCP timeouts remain unchanged;
- `rift_cli_job_list`, `rift_cli_job_poll` and `rift_cli_job_cancel` provide recovery/observation/cancellation for both job lanes;
- jobs retain the original `request-id` so lost submit responses can be recovered without replay;
- actual CLI execution is globally serialized across shell and ToolHost lanes while poll/list/cancel remain responsive;
- cancellation exposes `cancelling`, `cancelled_may_have_applied` and `completed_after_cancel_request` truthfully;
- ToolHost dispatch dynamically accepts current/future `rift_*` tools but rejects `rift_shell_exec` and `rift_workspace_exec` in that lane;
- driver loops are process-local, identity-bound, strictly monotonic, externally continued and capped at 8 steps;
- CLI shell mutations retain `RiftPatchSessions` provenance and direct tool mutations retain ToolSandbox provenance;
- JNI uses explicit UTF-16/UTF-8 transcoding;
- the native core itself still contains no model/API client or raw process/network execution primitive.

Builder validation must additionally prove the final signed APK contains:

- `lib/arm64-v8a/libriftcli.so`;
- `lib/armeabi-v7a/libriftcli.so`;
- no x86/x86_64 RiftCLI library.

Installed-device promotion still requires the N1 build to prove `status`/architecture identity, request-id replay rejection, shell-job submit/list/poll/cancel, ToolHost-job submit/list/poll/cancel, disable-time cancellation, one bounded external continuation loop and restart-reset behavior on the target Android device.
