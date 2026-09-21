# N1.8 Repository Consistency Observer

Status: **N1.8.0 SOURCE-IMPLEMENTED — BUILDER/INSTALL PROMOTION PENDING; N1.8.1+ PENDING**

This document is the canonical architecture for RiftOS N1.8. It defines the repository-wide observer that sits above Workspace Records and Project Intelligence V2. The observer does not replace those systems. It consumes their evidence and adds the missing consistency/proof layer.

## Mission

The observer must answer a stronger question than "what changed?"

It must answer:

> What facts became invalid because of this change, what other artifacts depend on those facts, what now contradicts current code, and what proof is still missing before the repository can be called internally consistent?

A one-line change is allowed to invalidate any reachable source, import, reference, manifest entry, protocol field, build rule, test, README sentence, subsystem document, ROADMAP state, TODO, ownership ledger, or other declared contract.

The observer is not a linter and not an LLM reviewer. It is a bounded, evidence-first consistency engine.

## Existing foundations

N1.8 reuses the live RiftOS evidence stack:

- Workspace Records for observed filesystem state, before/after snapshots, provenance and candidate manifests;
- RiftSourceIntelligenceV2 for language classification, symbols, signatures and dependencies;
- Project Intelligence V2 for indexed symbols, references, dependency/dependent edges and validation views;
- Diff Engine V2 and File Identity V2 for exact change and rename/copy/rewrite evidence;
- Patch Manifest V1 for candidate identity and tamper-evident change-set hashing;
- SOURCE_OWNERSHIP ledgers and nearest README ownership;
- build/manifest/package/config files as first-class repository evidence.

No second competing project index is introduced.

## Core model

N1.8 adds a persistent/rebuildable **Repository Fact Graph**.

### Fact node classes

The graph may contain bounded typed nodes for:

- file;
- source symbol;
- signature;
- import/include/module specifier;
- package/module namespace;
- call/reference site;
- exported/public API;
- class/interface/implementation relation;
- constant and configured limit;
- JSON/config/schema key;
- Android manifest component/permission/intent route;
- JNI/native bridge endpoint;
- MCP tool/route/protocol field;
- CLI command/capability;
- build target/source set/workflow;
- test and assertion;
- README/document section;
- ROADMAP item/state;
- TODO/FIXME item;
- SOURCE_OWNERSHIP row;
- version/build provenance claim;
- generated artifact relationship;
- explicit invariant/guarantee.

### Typed edge classes

Edges include:

- imports/includes;
- resolves-to;
- references;
- calls;
- exports;
- declares;
- implements/extends;
- configured-by;
- registered-by;
- serialized-as;
- consumed-by;
- mirrored-by;
- owns;
- documented-by;
- claims;
- verifies;
- tested-by;
- generated-by;
- supersedes;
- deprecates;
- TODO-for;
- depends-on;
- reverse-depends-on.

Edges carry provenance, confidence, source location and evidence type.

## Evidence precedence

The observer must never allow weak inference to override stronger evidence.

Default precedence:

1. exact parser/compiler/build/runtime fact;
2. exact manifest/config/schema fact;
3. exact symbol/reference/dependency fact;
4. deterministic repository rule;
5. historical Workspace Records/Git evidence;
6. explicit ownership/contract mapping;
7. deterministic documentation claim extraction;
8. heuristic structural/lexical link;
9. semantic/embedding/LLM suggestion.

Only deterministic evidence may block promotion by itself. Heuristic or model-derived links may create review findings, never silently rewrite truth.

## Change algorithm

Every observed mutation follows the same model:

```text
filesystem mutation
    -> candidate identity
    -> changed facts
    -> invalidate affected graph nodes
    -> walk reverse dependency closure
    -> recompute only invalidated facts
    -> compare expected vs observed facts
    -> detect contradictions/orphans/stale claims
    -> generate proof obligations
    -> run smallest relevant verification set
    -> publish evidence-grade findings
```

This is deliberately closer to an incremental build database than a whole-repository rescan.

## Required analyzers

### 1. Incremental syntax layer

Per-language syntax state must detect:

- malformed syntax;
- incomplete declarations;
- broken parse regions;
- newly invalid imports/includes;
- changed declaration boundaries.

The architecture should support incremental parsing where practical and recover around syntax errors rather than abandoning the file.

### 2. Semantic identity layer

Text names alone are not authority.

The observer progressively upgrades PI-v2 identities so it can distinguish:

- same-name symbols in different scopes/modules;
- aliases and re-exports;
- overloads/signatures;
- interface/implementation relations;
- package/module-qualified names;
- generated versus authored sources.

When precise identity is unavailable, the finding must say that the relation is heuristic.

### 3. Import/module resolution layer

Every local import/include/module edge that should resolve inside the project must be checked.

Findings include:

- unresolved local import;
- removed target;
- renamed target with stale importer;
- ambiguous resolution;
- case/path drift;
- package/source-set mismatch;
- generated-source expectation not satisfied.

External dependencies are modeled separately and never treated as missing merely because they are not local files.

### 4. Reference/call/contract layer

Changed or removed symbols must propagate to:

- direct references;
- callers;
- implementers;
- constructors;
- interface users;
- serializers/deserializers;
- JNI/native bridge pairs;
- protocol producers/consumers;
- command/tool registries;
- build/manifest registration points.

One broken connection is enough to produce a deterministic finding when evidence is exact.

### 5. Configuration and schema consistency

The observer must compare duplicated or mirrored repository facts, including:

- counts;
- limits;
- route names;
- tool names;
- protocol versions;
- config keys;
- manifest declarations;
- source snapshots;
- Gradle required-source lists;
- workflow expectations;
- ABI/platform declarations;
- capability names.

Example: code says max SSE clients = 8 while docs say 4 -> contradiction.

### 6. Documentation Claim Graph

Documentation is parsed into bounded claims rather than treated as one text blob.

Claim classes include:

- numeric claim;
- existence/non-existence claim;
- ownership claim;
- version/status claim;
- "implemented/pending/removed/proven" state;
- route/API/tool name claim;
- file/path claim;
- limit/bound claim;
- dependency relation claim;
- security/invariant claim.

Claims are linked to possible proving facts.

Claim states:

- `verified`;
- `contradicted`;
- `stale`;
- `orphaned`;
- `unverified`;
- `superseded`;
- `heuristic-link`.

README, subsystem docs, ROADMAP, TODO/FIXME, PROJECT_STATUS, PATCH_HISTORY and SOURCE_OWNERSHIP are all in scope.

The system must distinguish historical documentation from current-state documentation. PATCH_HISTORY may truthfully describe an old state without being flagged stale merely because current code evolved.

### 7. ROADMAP/TODO state consistency

ROADMAP and TODO items are modeled as commitments with state.

Examples:

- ROADMAP says "pending" but deterministic code/tests prove implementation exists -> stale-state finding;
- TODO says remove a route that no longer exists -> completed/orphaned TODO review;
- ROADMAP says feature is live but required source/registration is absent -> contradiction;
- completed feature has no regression test or owner doc -> proof obligation.

The observer does not auto-delete TODOs or rewrite ROADMAP state.

### 8. Test and verification ownership

Affected graph nodes map to the smallest relevant checks.

The observer should derive:

- directly owning tests;
- transitive tests through affected APIs;
- build/compile checks;
- manifest/config checks;
- docs consistency checks;
- device/runtime proof requirements when static evidence cannot prove behavior.

Passing unrelated tests must not satisfy an unresolved proof obligation.

## Finding schema

Every finding must include:

- stable finding ID;
- category;
- severity;
- deterministic vs inferred;
- changed source fact;
- conflicting or missing fact;
- exact file/line evidence where available;
- graph path explaining why the artifacts are connected;
- confidence;
- proof source;
- remediation class;
- whether promotion is blocked;
- whether evidence is truncated/incomplete.

Severity classes:

- `critical`: repository can execute/deploy with dangerous or authority-breaking mismatch;
- `error`: deterministic broken dependency/contract/claim;
- `warning`: likely stale or incomplete but not fully proven;
- `info`: review/cleanup opportunity.

No generic "something may be stale" output is acceptable without a dependency path and evidence.

## False-positive controls

- exact evidence outranks heuristics;
- historic docs are classified separately from current-state docs;
- generated/vendor/build/cache trees remain excluded unless explicitly owned;
- external dependencies are not treated as unresolved local files;
- inferred documentation links cannot block promotion alone;
- every bounded/truncated analysis must report incompleteness rather than silently claiming clean;
- suppressions, when added, must be scoped, justified, visible and testable.

## Execution tiers

### Tier 0 — mutation hot path

Target: near-immediate.

Runs:

- changed-file parse;
- changed fact extraction;
- direct dependency invalidation;
- exact local import/reference checks;
- cheap mirrored-constant/config checks.

### Tier 1 — affected graph reconciliation

Runs over the reverse transitive closure only:

- dependents/callers;
- docs/README/ROADMAP/TODO ownership;
- contract/schema pairs;
- relevant tests;
- contradiction detection.

### Tier 2 — deep verification

Only where required:

- compiler/type checks;
- static-analysis/dataflow rules;
- Android/Gradle/native build;
- focused test execution;
- generated-source verification;
- device/runtime proof.

Deep analysis must not become the per-keystroke hot path.

## Incremental correctness oracle

Incremental speed is not trusted by assumption.

At defined checkpoints the observer performs a clean rebuild of the Repository Fact Graph and compares it with the incremental graph.

Required invariants:

- identical canonical facts for identical repository state;
- identical deterministic findings;
- no missing reverse dependencies;
- no stale nodes surviving deletion/rename;
- stable canonical graph hash independent of traversal order.

Any incremental/clean divergence is a correctness failure.

## Benchmark program

N1.8 ships with a dedicated mutation benchmark rather than judging itself by feature count.

### Rift mutation corpus

The corpus must include at minimum:

- remove/rename import target;
- path case drift;
- rename public method with stale caller;
- signature/parameter type change;
- interface/implementation mismatch;
- constructor change;
- duplicated constant 8 -> 9 while docs remain 8;
- renamed JSON/config key with stale consumer;
- route/protocol field rename on producer only;
- JNI declaration/native implementation mismatch;
- manifest activity/service/provider removed or renamed;
- Gradle required-source drift;
- build workflow/source-set drift;
- tool registry count/name drift;
- stale README capability claim;
- stale ROADMAP pending/live status;
- stale TODO;
- stale SOURCE_OWNERSHIP path;
- deleted test still claimed as proof;
- indirect break five or more graph edges away;
- rename/copy/rewrite;
- syntax damage in one changed line;
- misleading but plausible documentation;
- historic patch note that must NOT be falsely flagged;
- generated/external dependency cases that must NOT become false positives.

Every discovered real defect is added permanently to the corpus.

### External benchmark families

Where applicable, compare conventional static-analysis portions against established benchmark families such as:

- NIST SARD/Juliet;
- OWASP Benchmark;
- public requirements/code traceability datasets.

These do not measure the full Rift problem. They supplement, not replace, the Rift mutation corpus.

### Metrics

Required metrics:

- precision;
- recall;
- F1;
- false-positive count;
- false-negative count;
- deterministic contradiction recall;
- broken-local-dependency recall;
- documentation-drift recall;
- maximum correctly traced dependency distance;
- incremental p50/p95 latency;
- clean rebuild latency;
- peak memory;
- bytes/files analyzed;
- incremental-vs-clean graph parity;
- benchmark coverage by mutation class.

No "best" claim is allowed without measured comparable evidence.

## N1.8 promotion gates

### N1.8.0 — fact graph/schema

**Source implementation present; external Builder/Kotlin compile and installed-device proof still required for promotion.**

Current source provides:
- canonical fact/edge/finding required-field schema in `RiftRepositoryConsistencyObserver.kt`;
- stable identities derived from semantic identity tuples, separate from content hashes;
- deterministic sorted canonical graph SHA-256 and graph ID;
- explicit completeness/incomplete-reason reporting and hard fact/edge/finding/cache bounds;
- verified atomic app-private cache that is non-authoritative and rebuildable;
- derivation from the existing PI-v2 graph only, with no observer-owned source scan/index;
- existing Code Mode `project kind=consistency` read-only view with compact-by-default whole-repo output and explicit `query=full` detail mode;
- independent regression coverage in `scripts/test-rift-repository-consistency-v1.mjs` wired into the main Builder chain.

The first installed proof on source `cc172dc158c0f7163730d1339ae6f6bf51346531` verified the observer was live and a `relay/` subtree produced a complete deterministic graph twice with the same SHA-256, verified cache reuse and `changed=false` on the second run. The initial whole-repository response exceeded Code Mode's 700 KiB result budget because the view returned all fact/edge arrays; source was therefore hardened to compact-by-default before promotion. Promotion still requires a new external Kotlin/Android build and installed proof that the compact whole-repo view returns without result omission while preserving graph identity/cache behavior.

### N1.8.1 — syntax/import integrity

- incremental changed-file analysis;
- local import/include/module resolution;
- deletion/rename/path drift detection;
- clean/incremental parity tests.

### N1.8.2 — semantic dependency propagation

- symbol/signature identity;
- references/callers/dependents;
- interface/implementation and API propagation;
- transitive reverse closure with explicit bounds.

### N1.8.3 — cross-boundary contracts

- config/schema/manifest/build mirrors;
- JNI/native pairs;
- MCP/CLI/protocol producer-consumer contracts;
- registry/count/limit consistency.

### N1.8.4 — documentation/roadmap/TODO claims

- README/docs current-state claims;
- ROADMAP state;
- TODO lifecycle;
- historical-document classification;
- evidence-linked contradiction findings.

### N1.8.5 — proof obligations and focused verification

- affected test/check selection;
- deterministic unresolved obligations;
- deep verification escalation;
- no unrelated-test substitution.

### N1.8.6 — adversarial benchmark and ablation

- Rift mutation corpus;
- external benchmark subsets where applicable;
- precision/recall/latency/memory reporting;
- ablation proving the value of syntax, graph, claim and historical layers separately;
- false-positive stress.

### N1.8.7 — installed-device promotion

On the real ARM32-compatible Android target:

- mutate disposable repo fixtures;
- prove immediate affected-graph detection;
- prove stale docs/README/ROADMAP/TODO detection;
- prove far-transitive break detection;
- prove clean/incremental graph parity;
- prove restart/rebuild stability;
- prove bounded RAM/latency;
- prove zero authority mutation by the observer itself.

N1.8 is not promoted by architecture, unit tests or a happy-path demo alone.

## Safety and authority

The observer is evidence-only.

It may:

- observe;
- parse;
- index;
- compare;
- invalidate/recompute facts;
- produce findings;
- request/identify verification work.

It may not by itself:

- edit code;
- update docs;
- approve patches;
- push Git;
- enable RiftCLI;
- execute arbitrary authority-bearing work.

Any later auto-repair system must consume observer findings through a separate authorized planner/mutation path.

## Relationship to later gates

N1.8 becomes a hard precondition for N2.

The Federated Rift Memory Kernel may ingest observer findings/evidence later, but memory is not allowed to become the source of repository truth. Repository facts remain rebuildable from code/config/docs/history.

N3/N4/N5/N6 may consume the observer's graph and proof obligations, but none may weaken its evidence precedence or clean-rebuild oracle.
