# RiftMemory — Retained Cache/Workset Controller Design

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Classification

RiftMemory is retained/inactive reference source.

Retained implementation:
- src/riftmemory-control.js

Current Android source contains no native RiftMemory owner or command, and Gradle does not package the module.

The RiftCLI N2 memory architecture is implemented incrementally from a **separate canonical roadmap** and does not activate this retained cache controller:

- `N2_FEDERATED_MEMORY_ROADMAP.md` — one canonical Rift Memory Kernel, multiple specialist cognitive engines, SQLite-first replaceable storage, RiftStore interface/conformance experimentation, Observer/Validator reconciliation and hard pre-N3 correctness-promotion gates.
- `../../../riftmemory/n2-contract-v1.json` — machine-readable N2.0 terminology, MemoryStore API, correctness corpus, zero-tolerance thresholds, installed baseline and benchmark-deferral contract.
- `../../../riftmemory/n2-phase-authority.json` — machine-readable N2.0-N2.12 lifecycle authority.

N2.0-N2.2 are promoted. N2.1 and N2.2 are promoted separately together under N2-M1 on installed source `694c1e31a6c3f4bd4317edd121208be894be2586`, Builder run `36048901054` / run number `346`: `RiftMemoryModelV1.kt` owns the canonical model/ledger schema, `RiftMemoryStoreV1.kt` owns the frozen backend-neutral interface, `RiftSqliteMemoryStoreV1.kt` is the Android SQLite reference backend, and `RiftMemoryN2M1SelfTest.kt` provides diagnostic-only round-trip/migration, commit/rollback/reopen, copied-DB corruption detection, and real process-death rollback/restart evidence surfaced through `rift_info`. The real restart proof produced `processRestartRecovered=true`, `crashRollbackRecovered=true` and `restartPromotionReady=true`. N2-M2 is promoted on installed source `18f1156075e08cb94573a9392031ac64552313f2`, Builder run `36067080197` / run number `350`: `RiftMemoryReconciliationV1.kt` implements bounded candidate/trust/authority/conflict reconciliation with all four frozen outcomes plus supersession/invalidation, `RiftMemoryTemporalGraphV1.kt` implements bounded disposable current/temporal/graph projections and point-in-time reconstruction, and `RiftMemoryN2M2SelfTest.kt` exercises project/branch/policy isolation, poisoning resistance, graph edges and exact close/reopen rebuild. Canonical memory runtime authority remains inactive; M2 remains diagnostic-only by design despite promotion. N2-M3 is promoted on installed source `62382a94f50dd6052e1754c1496da2a0f794c0af`, Builder run `36075992479` / run number `355`: `RiftMemoryConsolidationV1.kt` provides bounded reversible observation/episode/cluster/pattern/semantic derivation with preserved evidence/version lineage and provisional derived trust, `RiftMemoryBeliefDifferenceV1.kt` provides explicit belief/prediction/reflection/discrepancy semantics with bounded surprise/importance scoring and stronger-observation resolution, and `RiftMemoryN2M3SelfTest.kt` proves those lanes in a separate diagnostic DB without Local Agent/RiftCLI authority. N2-M4 is promoted on installed source `d39960832a701311461058670b5b93597ae612c9`, Builder run `36094853587` / run number `368`: `RiftMemoryProceduralFailureV1.kt` owns bounded skill/failure/causal/commitment/policy specialist semantics, `RiftMemoryRetrievalContextV1.kt` owns bounded hybrid retrieval/arbitration/context compilation, and `RiftMemoryN2M4SelfTest.kt` proves separate N2.7/N2.8 correctness in a diagnostic-only DB exposed read-only through `rift_info`. N2.7 and N2.8 carry separate promotion rows pinned to that source/run; installed diagnostics, SQLite integrity, M1-M3 continuity, and the final six-consumer authority proof passed. N2-M5 / N2.9 is promoted on installed source `d650e57dff09a878f02edfef7e175ed02d42f750`, Builder run `36100246139` / run number `370`: `RiftMemoryObserverValidatorLoopV1.kt` converts bounded Observer/Validator snapshots into evidence-only canonical candidates, explicit discrepancy records and ordinary N2.3 reconciliation, while `RiftMemoryN2M5SelfTest.kt` proves incomplete-evidence fail-closed behavior, stale-state correction, stale/replay rejection, failed-validation correction, false-completion reopening, project isolation, restart durability and SQLite integrity in `n2-m5-proof.sqlite`. Every N2.9 diagnostic passed; M1-M4 continuity and the clean post-install Observer baseline passed; Local Agent/RiftCLI still do not own the M5 engine and canonical runtime remains inactive. N2.1-N2.11 are executed as six macro patches (1+2, 3+4, 5+6, 7+8, 9, 10+11) without merging their individual evidence/promotion gates; N2.12 stays separate. The retained cache controller is still not packaged or authoritative. Comparative/performance benchmarking is deferred until the entire RiftCLI stack is 100% complete and live.

## Source ownership

Retained implementation:
- `src/riftmemory-control.js`

Roadmap-only N2 specification:
- `docs/systems/riftmemory/N2_FEDERATED_MEMORY_ROADMAP.md`

Current Kotlin N2 owners are explicitly listed in `docs/SOURCE_OWNERSHIP.md`: the canonical model/MemoryStore/SQLite/reconciliation/temporal/cognitive/specialist/retrieval sources plus their diagnostic self-tests. They remain diagnostic and non-authoritative to Local Agent/RiftCLI until the corresponding promotion/activation gates pass. Future N2 owners still require explicit ownership entries and fresh source audit rather than inheriting authority from this retained controller document.

## Retained design

The JavaScript describes:
- /system/riftmemory/v1;
- warm cache under /system/riftmemory/v1/cache;
- SHA-256-addressed cached objects;
- prefetch;
- pin/unpin;
- status;
- prune;
- flush.

It depends on retained RiftVault for content storage.

Its status explicitly describes the hot tier as Android process memory **not directly managed in this MVP** and nativeAccelerator=false.

The proposed C++/JNI accelerator is not active.

## Activation proof

Verified current state:
- zero Kotlin `RiftMemory` references;
- zero native `rift memory` shell command;
- zero Gradle include for riftmemory-control.js;
- no wildcard src/** packaging;
- only retained JS family consumes globalThis.RiftMemory;
- legacy generic RiftLocalPlatform shell wrapper is retired.

## Virtual-memory wording

RiftMemory is not current Android virtual RAM, swap, paging or memory expansion.

The retained design is a local flash/cache control-plane concept.

Do not describe it as increasing device RAM or providing live remote-backed memory.

## Trust rule

VERIFIED status for this README means the **inactive retained classification** is verified.

It does not certify the retained cache/prune algorithms or any future C++/JNI accelerator.

## Critical invariants

- retained source remains unpackaged;
- no current RAM-expansion claim is made;
- RiftVault dependency remains reference-only;
- no native cache accelerator is implied by the retained design;
- future activation requires explicit ownership, resource bounds and storage/wear analysis;
- the N2 roadmap does not make the retained cache controller live;
- N3 must remain blocked until the roadmap's N2.12 promotion gate is actually satisfied.

## Failure signatures

- docs call RiftMemory current virtual RAM;
- native shell advertises `rift memory`;
- Gradle packages riftmemory-control.js silently;
- nativeAccelerator is claimed active without a native implementation;
- retained cache control is confused with Android memory management;
- roadmap-only N2 capabilities are described as implemented/current;
- N3 begins before N2.12 promotion evidence exists.

## Fix map

Retained design -> src/riftmemory-control.js.

Historical wrapper -> src/riftlocal-platform.js.

Future native cache/data-plane -> requires new owner and audit.

Future RiftCLI N2 cognitive memory -> `N2_FEDERATED_MEMORY_ROADMAP.md`; every implemented source owner must be added here and to `docs/SOURCE_OWNERSHIP.md` when implementation begins.

## Validation

Second audit must prove:
- retained source exists;
- zero Kotlin/native activation;
- zero Gradle packaging;
- no live shell command;
- references remain retained-only;
- public-surface docs classify it as retained.
