# N3 Architecture / Impact Engine

**N3.0 CONTRACT/BASELINE SOURCE-IMPLEMENTED; BUILDER + INSTALL + LIVE PROOF PENDING — 2026-09-25.**

Machine authority:
- `riftarchitecture/n3-contract-v1.json`
- `riftarchitecture/n3-phase-authority.json`

Frozen N3.0 contract SHA-256:
`7a8e4f1e69c76643c5c5093d19615ce70b97eeafed644f1679265b21112094ba`

Initial N3.0 phase-authority source SHA-256:
`78362a797d9a52c6481a1bab9a5d3bac2ee316db0007d6e2bf54c7b777a5e96a`

Installed pre-N3.0 baseline:
- RiftOS source `3e9cfb5b7514e3a82d4d739fa0f3d92aba1ba23b`
- Builder run `36209390484` / run number `389`
- installed ABI `armeabi-v7a`
- MCP tool count `24`
- MCP manifest SHA-256 `78ee72f5865650742a7ad8ab381e22632fd4f295ef74d6e5584fd45519fdfb64`

The prelude build is satisfied: Project Intelligence already recognizes the exact future N3 machine-authority JSON paths as build-config repository state before the N3.0 files are introduced.

## Purpose

N3 turns the already-promoted Project Intelligence, Observer/Contracts and N2 evidence substrate into one bounded pre-change architecture/impact analysis engine.

Before a proposed change can reach the future N4 Planner, N3 must derive:

- owning subsystem;
- dependency and propagation impact;
- architecture invariants;
- required documentation;
- relevant tests;
- build/package impact;
- security/capability boundaries;
- completeness/incomplete reasons;
- evidence provenance.

N3 is analysis only. It does not plan, mutate, authorize, grant capabilities, execute Batch jobs, or activate canonical memory.

## Hard prerequisites

All prerequisites are satisfied for N3.0:

- N1.8 Repository Consistency Observer promoted;
- N2.0-N2.12 Federated Rift Memory Kernel correctness program promoted;
- B1 persistent jobs promoted;
- B2A recovery promoted;
- B2B rollback promoted;
- Local Agent Batch promoted;
- first-class MCP Batch promoted;
- N3 machine-authority prelude promoted on run 389.

The canonical N2 memory runtime remains inactive. N3 may consume promoted N2 diagnostics/context only as non-authoritative evidence until a later explicit runtime-activation gate exists.

## Authority rules

1. Source/build/runtime evidence is authoritative.
2. Documentation is claim data and cannot override source truth.
3. Memory cannot override current source ownership, dependency, invariant or capability evidence.
4. Ambiguous ownership is unresolved; N3 never guesses.
5. Incomplete/truncated required input makes the N3 result incomplete.
6. Cross-project evidence never silently merges.
7. Bound exhaustion is fail-closed, never silent truncation-as-complete.
8. N3 cannot mutate files, run tools, grant permissions, or activate the Planner.
9. N4 may consume N3 only after N3.6 promotion.

## Frozen input authorities

N3.0 binds the following existing evidence contracts:

- `rift-semantic-impact-v1` from Project Intelligence v2 candidate impact;
- `rift-semantic-propagation-v1` from Project Intelligence v2 propagation;
- `rift-cross-boundary-contracts-v1`;
- `rift-documentation-claims-v1`;
- `rift-proof-obligations-v1`;
- promoted N2 diagnostics as bounded contextual evidence only.

No duplicate graph, dependency scanner, owner registry or memory truth system is introduced by N3. Existing source authorities remain the producers; N3 composes their evidence.

## Frozen bounds

N3.0 may not exceed the proven substrate bounds:

- projects: 32;
- changed files: 4096;
- changed symbols: 1000;
- reference symbols: 80;
- references: 800;
- dependencies: 800;
- dependents: 800;
- tests: 300;
- documentation rows: 300;
- affinity targets: 128;
- propagation seeds: 64;
- propagation closure nodes: 1024;
- propagation reverse edges: 4096;
- propagation depth: 16.

An implementation may choose tighter limits, but it may not silently exceed or reinterpret these contract ceilings.

## N3 correctness corpus

N3.0 freezes the following mandatory correctness classes:

1. ambiguous owner → unresolved/fail-closed;
2. API change → direct dependencies, dependents, references, tests and docs are surfaced or result is incomplete;
3. dependency cycle → deterministic bounded traversal;
4. same symbol in multiple projects → strict project isolation;
5. build-config change → build/package impact plus config-reading verification consumers;
6. security/capability change → explicit boundary impact;
7. stale/truncated PI evidence → no false-clean result;
8. docs conflict with source → source wins; docs remain claims requiring update;
9. memory conflicts with source → memory cannot override source authority;
10. exact-bound/+1 behavior → exact may complete, +1 fails closed;
11. process restart → same source/candidate inputs produce the same bounded evidence.

Zero tolerance:
- guessed ambiguous owner: 0;
- missed direct dependency impact: 0;
- cross-project contamination: 0;
- architecture invariant bypass: 0;
- security/capability boundary omission: 0;
- stale-evidence false clean: 0;
- memory authority escalation: 0;
- planner/mutation authority in N3: 0;
- silent bound truncation: 0.

Comparative/performance benchmarks remain deferred until the entire RiftCLI stack is complete and live.

## Phase plan

### N3.0 — Contract and baseline freeze

Freeze:
- lifecycle authority;
- prerequisites;
- input/output schemas;
- authority boundaries;
- correctness corpus;
- resource bounds;
- zero-tolerance thresholds;
- macro implementation plan.

**Exit:** Builder/source gates pass, exact installed provenance is proven, machine contract identities match, N2 remains inactive, and N3 authority remains analysis-only.

### N3.1 — Owner / subsystem resolution

Derive the owning subsystem for each candidate source change from source ownership contracts, path/project identity and exact current evidence.

Requirements:
- deterministic owner identity;
- multiple-owner evidence represented explicitly;
- ambiguous/no owner is incomplete, never guessed;
- rename/move preserves or re-resolves ownership correctly;
- project scope mandatory.

### N3.2 — Dependency / API / propagation impact

Compose candidate impact + propagation into one bounded dependency-impact set.

Requirements:
- direct dependencies/dependents;
- changed API surface;
- exact references/callers;
- reverse closure where required;
- cycle-safe deterministic traversal;
- no unrelated-project leakage;
- incomplete PI/Propagation evidence propagates as incomplete.

### N3.3 — Architecture invariants + security/capability boundaries

Derive architecture constraints affected by the candidate.

Requirements:
- owner contracts;
- cross-boundary contracts;
- authority direction;
- permission/capability surface;
- sandbox/local-agent/CLI boundary;
- persistence/recovery/rollback invariants where applicable;
- explicit unresolved state when evidence is insufficient.

N3 cannot grant authority or declare a security boundary safe merely because no text match exists.

### N3.4 — Docs / tests / build / package impact

Derive required supporting evidence:

- owning documentation;
- current/status/history documents when source authority changes;
- directly affected tests;
- verification scripts consuming changed config;
- build/package/manifest/native/JNI impact;
- APK/device proof requirement when runtime-relevant.

This phase derives requirements; N6 later executes candidate-bound verification.

### N3.5 — Unified candidate architecture report

Produce one deterministic bounded report containing all required N3 output categories plus exact input/source hashes.

Promoted N2 memory may contribute contextual prior failure/commitment evidence only. It cannot change source-derived ownership, dependency, invariant or security conclusions.

N3.5 still has no Planner/mutation authority.

### N3.6 — Adversarial / restart / final promotion gate

Required adversarial proof includes:

- ambiguous ownership;
- delete/rename/move;
- dependency cycles;
- overloaded/duplicate symbols;
- cross-project name collision;
- stale PI cache/input;
- truncated/bound-exhausted evidence;
- docs/source contradiction;
- memory/source contradiction;
- security-boundary changes;
- build-config consumers;
- process restart determinism.

**Exit:** all zero-tolerance counters remain zero, exact bounded evidence is restart-deterministic, and N3 remains analysis-only. Only then may N4 Planner begin.

## Macro implementation plan

To reduce build churn without merging phase authority:

1. **N3-M1:** N3.1 + N3.2 — ownership and dependency impact.
2. **N3-M2:** N3.3 + N3.4 — invariant/boundary and required-evidence derivation.
3. **N3-M3:** N3.5 — unified architecture report.
4. **N3.6:** separate final adversarial/restart promotion gate.

Macro patches are execution groupings only. Every N3.x phase retains separate evidence and promotion authority.

## Current source status

N3.0 is source-implemented and awaiting Builder/install/live proof. N3.1-N3.6 remain blocked until N3.0 promotion.

The N2 canonical memory runtime remains inactive. N4 Planner remains blocked. No performance/comparative benchmark is authorized.
