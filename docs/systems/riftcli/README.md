# RiftCLI Native Architecture

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**

Bootstrap-0 source ownership, JNI routing, dual-ABI Gradle wiring and zero-authority boundaries were audited against current code. Native Builder/APK/device proof remains pending.

Status: **Bootstrap-0 / native foundation only**

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

## Bootstrap-0 behavior

Bootstrap-0 intentionally implements only:

```text
rift-cli help
rift-cli status
rift-cli architecture
rift-cli enable CONFIRM-EXPERIMENTAL
rift-cli disable
```

The enable switch is process-local and defaults OFF on every process start.

Even when explicitly enabled, Bootstrap-0 has:

- no filesystem mutation authority;
- no Git authority;
- no Local Agent authority;
- no arbitrary process/shell authority;
- no network authority;
- no model/API authority;
- no project memory yet;
- no project graph yet;
- no planner yet;
- no verification engine yet;
- no autonomous tool execution.

This is deliberate. Native execution and ABI parity must be proven before engineering intelligence is layered onto the core.

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

Add a bounded native protocol for external reasoning input:

- driver/session/task identity;
- project identity;
- goal and assumptions;
- evidence references;
- proposed action;
- requested capability;
- acceptance/rejection reasons;
- next-safe-action hints.

No network/model call is added.

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

The Builder must additionally verify both `libriftcli.so` ABI payloads in the final signed APK before Bootstrap-0 can be promoted.


## Failure signatures

- `rift-cli` routes to any Kotlin planner/brain instead of `RiftCliHost` -> ownership regression.
- `RiftCliHost.kt` gains project memory/planning/research/verification logic -> duplicate-core regression.
- C++ core gains a model/API client or network transport -> dependency-direction regression.
- Bootstrap-0 can mutate Workspace/Git/device state -> authority regression.
- `riftos-agent` routes through RiftCLI before an explicit promoted bridge exists -> Local Agent authority regression.
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

Local Agent direct routing while CLI has no authority -> `android/app/src/main/java/com/riftos/app/RiftNativeShellServices.kt`.

ABI/source snapshot ownership -> `android/app/build.gradle.kts`.

Source regression gate -> `scripts/test-rift-cli-native-bootstrap.mjs` + `scripts/validate-rift-wiring.mjs`.

Final APK native-library proof -> public Builder `scripts/verify-riftos-apk.sh`.

## Validation

Source validation must verify:

- retired CLI-specific Kotlin/swarm/IR/tokenizer files are absent;
- `RiftCliHost` is transport-only and loads `libriftcli.so`;
- RiftShell routes `rift-cli` only through that host;
- Local Agent bypasses RiftCLI at Bootstrap-0;
- CMake owns one `riftcli` shared library;
- Gradle pins NDK/CMake plus `arm64-v8a` and `armeabi-v7a`;
- exact C++ source snapshot matches the declared build contract;
- core defaults OFF and enablement is process-local;
- model, network, mutation, tool, planner, graph and project-memory authority remain false;
- JNI uses explicit UTF-16/UTF-8 transcoding;
- no process/network execution primitives exist in the native bootstrap.

Builder validation must additionally prove the final signed APK contains:

- `lib/arm64-v8a/libriftcli.so`;
- `lib/armeabi-v7a/libriftcli.so`;
- no x86/x86_64 RiftCLI library.

Installed-device promotion still requires executing `rift-cli status`, `architecture`, enable/disable and restart-reset behavior on the target Android device.
