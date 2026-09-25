# N1.8 Repository Consistency Observer

Status: **N1.8.0 + N1.8.1 + N1.8.2 + N1.8.3 + N1.8.4 + N1.8.5 + N1.8.6 + N1.8.7 PROMOTED ON INSTALLED ARM32-COMPATIBLE ANDROID TARGET — FULL N1.8 OBSERVER PROGRAM COMPLETE**

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

## Future staged scan planner — ARCHITECTURE LOCKED, IMPLEMENTATION BLOCKED

This is the intended everyday execution architecture after N1.8.0 is fully promoted. It is **not implemented yet** and must not be layered onto an unstable foundation.

Hard sequencing rule:

> N1.8.0 must first pass the installed full-repository torture promotion matrix below. If any foundation test fails, fix and re-test N1.8.0. Do not add the staged planner until N1.8.0 is promoted.

The normal observer path should eventually be **staged and graph-driven**, while a clean whole-repository scan remains the correctness oracle.

### Subsystem domains

A scan stage is a bounded analysis domain such as a subsystem, engine, module, build target or owned documentation surface. Folder names are useful hints but are not authority.

Subsystem membership should be derived from available deterministic evidence such as:

- SOURCE_OWNERSHIP;
- package/module namespaces;
- build targets and source sets;
- imports/includes;
- Android manifest registration;
- protocol/tool/route ownership;
- README/subsystem documentation ownership;
- config/schema ownership;
- existing PI-v2 dependency evidence;
- historical stable graph evidence.

### Staged execution

Conceptual flow:

```text
changed fact/file
    -> identify owning subsystem/domain
    -> scan that domain completely
    -> reconcile local syntax/facts/contracts/tests/docs
    -> inspect outgoing and reverse edges
    -> enqueue only externally affected domains
    -> repeat until frontier is empty
```

A stage may expand into another domain only because deterministic or explicitly marked inferred evidence connects them. Mere repository proximity is not enough.

Each stage should eventually record at minimum:

- stage ID and subsystem/domain ID;
- input graph hash;
- scanner/rule versions;
- files/facts covered;
- outgoing frontier edges;
- proof obligations created/satisfied;
- output graph hash;
- completeness state and reasons;
- duration;
- peak/estimated memory where measurable;
- whether the result was reused from cache.

### Escalation levels

The planner should support explicit escalation:

- L0 — changed file/fact only;
- L1 — owning subsystem/domain;
- L2 — direct cross-boundary contracts;
- L3 — reverse/transitive affected closure;
- L4 — repository-wide semantic sweep;
- L5 — clean whole-repository oracle rebuild.

Most ordinary edits should stop at L1-L2. Shared APIs/protocols may reach L3. Uncertain, contradictory or corrupted observer state escalates toward L4/L5.

### Stop and expansion rules

The staged planner may stop only when:

- the active domain is complete;
- all deterministic affected edges have been reconciled;
- no unresolved proof obligation requires another domain;
- no incomplete/truncated evidence requires escalation;
- the frontier is empty.

If a dependency edge is missing or the graph is uncertain, the planner must expand/escalate rather than assume isolation.

### Clean-verdict rule

A staged scan may only claim cleanliness for the exact coverage it proved.

A repository-wide clean verdict requires one of:

1. complete traversal of the trusted affected closure with no unresolved/incomplete evidence and a graph state already validated against a clean oracle; or
2. a clean full-repository oracle pass.

The staged optimization must never turn partial coverage into a repository-wide "clean" claim.

### Why both staged and full scans remain

Staged scans are the normal performance path and should scale with the affected dependency closure rather than total repository size.

Full scans remain necessary because they do not depend on the incremental/staged graph already being correct. They are the independent oracle that catches:

- missing dependency edges;
- stale nodes;
- bad subsystem ownership;
- cache divergence;
- missed cross-boundary relationships;
- nondeterministic incremental state.

The final architecture is therefore hybrid:

```text
normal path: mutation -> staged planner -> affected closure -> findings/proofs
oracle path: clean full-repo rebuild -> canonical comparison
```

## N1.8.0 installed torture promotion matrix

N1.8.0 is not promoted by one successful full-repo scan. The installed build must survive an adversarial foundation test intended to expose every practical way the graph/schema/cache layer can lie or become inconsistent.

Tests should use the real RiftOS repository read-only where safe and a disposable fixture repository for destructive mutation/boundary cases. No fixture failure may be repaired by weakening the expected invariant.

### A. Full-repository completeness baseline

- cold full-repo scan returns without response omission;
- `complete=true`;
- no file/edge/fact/cache bound reason;
- observed file/dependency totals reconcile with the PI-v2 source evidence;
- fact/edge counts remain within declared graph bounds;
- zero silent skipped tracked inputs;
- repeated full scans do not progressively lose facts/edges.

### B. Determinism and ordering

For an identical repository state:

- run the observer repeatedly, including at least ten consecutive warm runs;
- every run must produce the same canonical graph SHA-256 and graph ID;
- second/subsequent cache state reports unchanged;
- traversal/directory enumeration order must not alter the graph;
- process restart must not alter the graph for identical input;
- cache deletion followed by rebuild must reconstruct the same canonical graph.

Any hash drift with identical repository state is a promotion blocker.

### C. Input-change sensitivity

Using disposable fixtures, mutate one thing at a time and prove the foundation observes the changed repository state rather than reusing stale identity:

- content-only source edit that changes no import;
- content-only documentation edit;
- whitespace-only edit;
- add file;
- delete file;
- rename file;
- move file between directories;
- copy file;
- replace file contents while preserving path;
- change one local dependency/import;
- add/remove a dependency edge;
- reorder declarations/imports where semantic relationships are otherwise unchanged.

Stable logical IDs may remain stable where designed, but relevant content/version facts and the canonical repository state must not remain falsely unchanged when a tracked input changed. If N1.8.0 lacks enough source facts to observe a tracked mutation, promotion is blocked until the foundation is hardened.

### D. Cache integrity and recovery

Exercise:

- no cache present;
- valid warm cache;
- cache deleted;
- truncated cache file;
- malformed JSON cache;
- wrong graph hash;
- wrong format/version;
- oversized cache;
- stale cache from an older repository state;
- temporary-file residue;
- restart after a successful cache write.

Expected behavior:

- corrupt/untrusted cache is rejected;
- repository truth is rebuilt from authoritative evidence;
- no corrupt cache can produce a clean verdict;
- rebuilt graph converges to the same canonical state as a clean scan.

### E. Process death / restart

Test clean process boundaries:

- scan, force-stop RiftOS, reopen, rescan;
- force-stop after a previous warm-cache run;
- restart with cache present;
- restart with cache removed/corrupted in a disposable scenario;
- repeat restart cycles.

Expected:

- no stale in-memory-only facts survive incorrectly;
- canonical graph is reconstructed deterministically;
- cache verification remains valid;
- observer authority remains read-only.

Where safe tooling permits, attempt interruption during a long disposable scan/cache operation. A partially completed operation must not be accepted as complete repository truth.

### F. Concurrency and reentrancy

Against a disposable or read-only target:

- multiple simultaneous consistency reads;
- consistency read while ordinary PI-v2 graph/read operations run;
- rapid sequential consistency requests;
- duplicate identical requests;
- concurrent reads around a restart/reconnect boundary.

Expected:

- no graph corruption;
- no mixed-result cache state;
- no deadlock/hang;
- identical-state requests converge on identical canonical graph identity;
- bounds/completeness remain truthful.

### G. Mutation-during-scan race

In a disposable repository, mutate repository state while a scan is in progress:

- edit a file;
- add/delete a file;
- rename/move a dependency target.

The observer must not silently publish a graph that mixes incompatible before/after states while claiming complete. Acceptable outcomes are a coherent snapshot, explicit invalidation/retry, or explicit incomplete/changed-during-scan state. A false clean mixed-state graph blocks promotion.

### H. Boundary and overflow tests

Generate disposable fixtures around every relevant limit:

- just below / exactly at / just above consistency input file bound;
- just below / exactly at / just above consistency dependency-edge bound;
- just below / exactly at / just above observer fact bound;
- just below / exactly at / just above observer edge bound;
- cache-size boundary;
- PI-v2 per-file size boundary;
- PI-v2 total indexed-byte boundary where practical.

Expected above a bound:

- explicit incomplete/bound reason;
- never silent truncation;
- never repository-wide clean.

Also verify ordinary RiftOS remains comfortably below bounds with measured headroom.

### I. Path and identity stress

Disposable fixtures should include:

- deeply nested paths;
- long but valid paths;
- spaces;
- punctuation;
- Unicode names;
- same filename in different directories;
- case-distinct paths on the actual filesystem semantics;
- rename chains A -> B -> C;
- move + edit;
- copy + edit;
- delete then recreate same path with different content.

Stable identities must not collide, cross-wire or retain stale relationships.

### J. File-type and exclusion stress

Verify behavior for:

- supported source files;
- README/Markdown/docs;
- JSON/config files;
- build files;
- binary files;
- generated/build/cache directories;
- intentionally excluded/vendor content;
- unsupported text formats;
- empty files;
- very large files near the index limit.

Intentional exclusions must be deterministic and documented. A tracked/owned file that is skipped for size/type/bounds must make coverage incomplete unless the architecture explicitly classifies it as outside observer authority.

### K. Dependency-shape stress

Disposable graphs should include:

- no dependencies;
- one dependency;
- long chain;
- wide fan-out;
- wide fan-in;
- diamond dependency;
- cycle;
- self-reference;
- unresolved external dependency;
- unresolved local dependency;
- multiple same-name targets in different domains;
- dependency deletion/rename.

The foundation must remain deterministic and must not confuse unresolved external references with silently missing local coverage.

### L. Resource/latency pressure

Measure:

- cold full-repo latency;
- warm full-repo latency;
- repeated-run latency;
- peak/estimated memory where available;
- cache size;
- response size.

Stress fixtures should scale upward until a declared bound is reached. The observer must degrade by explicit incompleteness/rejection, not by hang, crash, silent omission or false clean.

### M. Result-surface integrity

Verify:

- compact full-repo response stays within Code Mode result budget;
- full detail mode is explicit;
- compact counts/hash/cache/completeness match the underlying full graph;
- previews never affect canonical graph identity;
- result omission cannot be mistaken for scan success;
- malformed/partial result surfaces cannot be interpreted as complete by callers.

### N. Differential/oracle tests

For the same fixture state:

- build from cold/no cache;
- build from warm cache;
- restart and rebuild;
- vary file creation/enumeration order;
- where later incremental machinery exists, compare incremental/staged output against clean rebuild.

Canonical deterministic state must agree. Any divergence is a correctness bug, not an acceptable optimization difference.

### O. False-clean kill conditions

N1.8.0 automatically fails promotion if any test demonstrates:

- `complete=true` despite known skipped/truncated tracked evidence;
- unchanged canonical repository state after a tracked mutation that the foundation is expected to represent;
- nondeterministic graph hash for identical state;
- corrupt/stale cache accepted as truth;
- stale nodes after delete/rename;
- identity collision;
- mixed before/after race state published as clean;
- hang/deadlock/crash under bounded stress;
- result omission interpreted as success;
- any mutation/authority action performed by the observer itself.

Every real defect found by this torture pass becomes a permanent regression case before promotion.

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

## Deferred post-CLI benchmark program

Performance/comparative benchmarking is intentionally deferred until the full RiftCLI stack is 100% complete and live. N1.8 promotion is correctness-only: deterministic mutation torture, exact/+1 bounds, restart parity, fail-closed behavior, false-positive stress and cross-oracle continuity remain mandatory, but no precision/recall/F1, latency, memory, throughput or external benchmark comparison is an N1.8 promotion gate.

The Rift mutation corpus remains active during N1.8 as a correctness/adversarial fixture corpus. After the complete CLI is live, the same corpus becomes part of the unified benchmark campaign alongside the external benchmark families and performance metrics below.

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

**N1.8.0 is promoted.** The installed ARM32-compatible Android build from source `9d196567e38e781d97a24bb2c808b47cbc2303eb` passed the strengthened Builder source gate and the exact-current-build process-boundary proof. Before restart the clean repository stabilized at `complete=true`, 1034 facts / 1088 edges / 260 files, graph SHA-256 `25869f703a8a4a7fe36b28ae6f9c3ab34daece15925c21b98166100eaab57d87`, with verified unchanged cache state. After a real Android force-stop/reopen, the **first** consistency read reproduced the exact same SHA, graph ID, counts, completeness, and verified `changed=false` cache state. RiftCLI also returned to `enabled=false`, confirming the process-local non-persistent gate reset. Earlier torture evidence covers cache integrity/recovery, mutation sensitivity, concurrency/reentrancy, mutation-during-scan coherence, path/identity stress, file/dependency/symbol boundaries, exact/+1 byte budgets, oversized snapshot fail-soft behavior, cache eviction/rebuild, and safe result omission. No N1.8.0 false-clean kill condition remains open. N1.8.1 is now source-implemented with promotion pending Builder/install/torture; N1.8.2-N1.8.7 remain pending.

Current source provides:
- canonical fact/edge/finding required-field schema in `RiftRepositoryConsistencyObserver.kt`;
- stable identities derived from semantic identity tuples, separate from content hashes;
- deterministic sorted canonical graph SHA-256 and graph ID;
- explicit completeness/incomplete-reason reporting and hard fact/edge/finding/cache bounds;
- verified atomic app-private cache that is non-authoritative and rebuildable;
- derivation from the existing PI-v2 graph only, with no observer-owned source scan/index;
- existing Code Mode `project kind=consistency` read-only view with compact-by-default whole-repo output and explicit `query=full` detail mode;
- content-verified repository file evidence separated from the semantic symbol index: every non-policy-excluded file carries exact size/SHA-256 plus semantic status/reason, while binary/non-text files may remain explicit metadata-only evidence;
- Project Intelligence cache schema v5 integrity-seals the complete persisted semantic payload with canonical SHA-256, then separately binds semantically parsed entries to exact content SHA **and** producer provenance (`RiftSourceIntelligenceV2.VERSION` + trusted `BuildConfig.RIFT_SOURCE_SHA`); missing/mismatched seals, caches from another analyzer/build, malformed/oversized caches, or local/untrusted source identities are rejected instead of reused;
- project graph construction canonicalizes in-scope semantic/repository path iteration, package-qualified Kotlin/Java imports resolve only when the local target is unambiguous, and Python/generic symbol fallbacks use unique-match-or-unresolved semantics rather than traversal-order `firstOrNull`; ambiguous evidence therefore cannot silently create a deterministic-looking `resolves-to` edge;
- consistency forces repository-content verification, while ordinary PI-v2 views retain their lighter reuse path; all existing mutation/rollback invalidation clears semantic and repository-file evidence together;
- Repository Fact Graph schema/cache v2 binds file-fact content to repository byte SHA without making SHA part of stable file identity;
- long stable-key identity hashes the complete normalized key while stored/display keys are capped at 2048 characters with a deterministic SHA-256 suffix, so deep shared-prefix paths cannot collide or crash the observer;
- the observer snapshot remains optional/non-authoritative: payloads over 4 MiB and write/verification failures return explicit `persisted=false` diagnostics instead of failing graph construction;
- exact installed byte-budget proof: 128 MiB semantic input remains accepted while 128 MiB + 1 fails closed with `semantic-total-byte-bound`; 256 MiB repository hash input does not report the hash bound while 256 MiB + 1 fails closed with `repository-content-hash-byte-bound`;
- PI-v2 semantic extraction is additionally bounded per file at 4096 unique symbols and 4096 unique dependencies; overflow aborts semantic evidence for that file with explicit `semantic-symbol-bound` / `semantic-dependency-bound` incompleteness while retaining repository byte evidence, preventing a valid <=2 MiB source file from creating an unbounded semantic collection;
- the consistency/internal-evidence graph counts all dependency rows for truncation evidence but performs expensive dependency target resolution only for the bounded 1024-edge observer feed; ordinary PI graph views retain their existing behavior;
- independent regression coverage in `scripts/test-rift-repository-consistency-v1.mjs` wired into the main Builder chain.

Earlier installed proof on source `6dfaea915aa47ca61f5efb9a55a72379b9efb77f` exposed the PI-v2 120-file/600-edge presentation-cap problem; Patch 10.31 moved consistency onto the shared 1024-file/1024-edge internal feed. Installed source `5de7f5065160b2bbe263e8cc0d179f6099af4347` then returned the full RiftOS repository as `complete=true` with 1017 facts, 1072 edges, zero incompleteness reasons and verified cache state. Ten consecutive warm full-repository runs produced the identical graph SHA-256 `195d7567eaa4ef0cc7a71ca2d7f17251427c346ca4198d7b3456d086fdbc1e4f`, identical graph ID/counts and `changed=false`. Structural fixture cases also behaved correctly for add/delete/rename/copy/move, dependency-target changes, unresolved-target state, three concurrent consistency reads, Unicode/space paths and case-distinct paths.

The torture suite then found a hard promotion blocker in the installed v1 foundation. A content-only `Main.kt` edit from `42` to `43` changed the real file SHA-256 from `3e25f46c18b15829f424ae91a11fd3efd4fcbd17c2c764b07b22da08b1191abc` to `e136706cf5c3316f63fa875516ee48dbc1d5eeb0c32f7f66ed35fbc59430719e`, but the repository graph stayed `b60ea1716d94b36239a3a8dff440f3e4223a766c399d3db643feb4b177da224c`, the file fact content hash stayed unchanged and cache reported `changed=false`. A README content-only edit reproduced the same defect. Deleting `Case.kt` and recreating the same path with completely different bytes caused the graph to return to the exact old pre-delete graph identity, proving path existence—not file bytes—was driving the file fact. N1.8.0 therefore failed promotion exactly as the torture gate required.

Source hardening first corrected the content-truth defect: PI-v2 persists repository-file evidence, consistency forces exact content verification, metadata-only/non-semantic files remain explicitly represented, stale semantic entries are removed when files become unindexable, mutation invalidation clears both evidence layers, and Repository Fact Graph v2 includes file size/SHA-256/semantic status in file-fact content while stable identity remains `{kind,path}`. Installed source `7ee74c5034bb14c30945d28971c56f35424e5301` then re-ran the three original kill cases successfully. Patch 10.34 next bound persisted semantics to both content SHA and producer provenance. Installed source `1c0e644f7424d30f20acdc1da5cf540833802ff2` live-proved the v4 producer contract in both directions: the old pre-v4 cache was rejected with `cache-schema-version`, and after a true Android force-stop/reopen the first PI-v2 graph read reported schema 4 `loaded`, no rejection reason, analyzer version 2, exact source SHA and `semanticProducerTrusted=true`.

The Patch 10.34 restart proof exposed a promotion blocker instead of closing N1.8.0. The repository remained on the same clean Git HEAD with zero modified/deleted/untracked files and consistency counts remained 1017 facts / 1072 edges / 258 repository files, but canonical graph SHA changed from `93bc7228ea90278bbb1d2421bad89850c1422c590bdd0113698f4f24de06736b` before restart to `e3868cc6f0a260036a7383d5a78fa26eedb805d97e3fdb191f0e9d3856241dbd` after restart; subsequent warm runs stabilized on the new hash. Audit found the semantic cause in PI-v2 dependency resolution: persisted indexes reload in canonical path order, while Python module and generic symbol fallbacks used traversal-order `firstOrNull`, so ambiguous candidates could resolve to a different target solely because iteration order changed. Patch 10.35 removed that false-clean/false-edge class by sorting in-scope graph maps, adding unambiguous package-qualified Kotlin/Java local import matching, and requiring unique Python/symbol candidates before emitting a resolved target. Ambiguous candidates now remain unresolved instead of choosing an arbitrary path.

Installed Patch 10.35 source `8ecc5433dcca153a669b09f942bc91e64965c0fa` then passed the exact restart proof that had failed previously. The old v4 cache was rejected on source-SHA change and rebuilt; the new baseline became `d9064a2ba8f1742116aa0f88e67110a5022907a9bf699bab9d61f2a27e7109d7` with 1017 facts / 1071 edges / 258 files and 55 resolved / 703 unresolved PI dependencies. After a true Android force-stop/reopen, the first PI read loaded the trusted v4 cache with all 258 RiftOS semantic entries reused and the first consistency read stayed exactly on `d9064a2b...` with `changed=false`. Ten further warm runs, concurrent/reentrant reads, exact/above file and dependency limits, exact/above 2 MiB semantic-file bounds, mutation-during-scan, Unicode/space/case/long-path identity, copy/delete/recreate behavior, metadata-only evidence, ambiguity fail-closed behavior, package-qualified Kotlin resolution and 32-project observer-cache eviction/rebuild all passed. Evicting the real RiftOS observer snapshot rebuilt the exact same `d9064a2b...` graph and the next warm run returned `changed=false`.

The continuing cache-integrity audit then found a new hard blocker in PI cache v4. Whole-cache producer provenance and per-file source SHA protected the origin and repository bytes, but the cached semantic payload itself was not integrity-sealed. A syntactically valid corrupted row could preserve the correct source SHA while altering or dropping cached `language`, `symbols` or `dependencies`; `verifyContent=true` would hash the repository file, see the matching source SHA and reuse that corrupted semantic row. Patch 10.36 upgrades PI persistence to schema v5 and seals the complete persisted payload with `cacheSha256 = RiftPatchManifestV1.sha256Canonical(payload)`. Load reconstructs the canonical body excluding only the seal, rejects missing/invalid seals as `cache-integrity-missing`, rejects mismatches as `cache-integrity-mismatch`, and performs this check before producer validation or semantic-row loading. Old v4 caches therefore rebuild by schema version. Permanent regression coverage locks the v5 seal and verification order. Local Android source validation is green. N1.8.0 remains unpromoted until external Builder/Kotlin/JS regression proof, install, live v4→v5 rejection/rebuild, true restart v5 reload and exact canonical graph parity pass; the staged subsystem planner remains implementation-blocked.

Patch 10.37 was then built and installed at source `164843c6613a2d7433b2d7ffd160725930863839`. Live proof passed the v5 producer/restart path, Local Agent-hosted RiftCLI boundary, long stable keys, >4 MiB snapshot fail-soft behavior, 4096/4097 symbol and dependency bounds, and the 1024-edge resolver CPU bound. The continuing K regression sweep exposed one new hard blocker: the exact 128 MiB + 1 semantic-total fixture could become falsely clean on a warm scan because cached semantic rows returned before the old `bytesScanned` total-budget counter advanced. Patch 10.38 separates actual re-analysis bytes from `semanticBytesAccounted` and charges every eligible file before cached reuse.

Installed source `ce8fbbd3f7179c8b153f44daaa2aedb6f2734ea4` then closed that blocker. A fresh exact-128-MiB fixture completed cold with 64 indexed files / 134,217,728 bytes scanned and warm with 64 cached files reused / zero bytes re-analyzed; semantic evidence remained complete both times. A fresh 128-MiB-plus-one-byte fixture failed closed both cold and warm with one skipped semantic file and `semantic-total-byte-bound`, while repository evidence remained complete. Observer propagation was exact: the exact fixture returned `complete=true`; +1 returned `complete=false` with `semantic-evidence-incomplete` and `semantic-total-byte-bound`. The clean RiftOS repository itself returned 1034 facts / 1088 edges / 260 files at canonical SHA-256 `2551bb5c0ee03b329bc7de068b9afdbc06d98c39f94839f7b5e5b2ba148aee25`; ten consecutive warm runs and three concurrent consistency reads beside an ordinary PI graph read stayed identical with verified unchanged cache state. Compact result mode remained within the Code Mode budget, while explicit full-detail mode was safely surfaced as `resultTruncated=true` / `resultOmitted=true` when the wrapper could not return the full graph.

Patch 10.42 strengthened the remaining cache-failure proof in the permanent Builder regression. Instead of merely checking that failure strings exist, the test isolates `persistSnapshot()` and proves structurally that write failure is trapped before verification, both write and verification failure branches return non-authoritative `persisted=false` / `verified=false` diagnostics without throwing, failure residue is cleaned, and the success state is reachable only after verification. Builder source `9d196567e38e781d97a24bb2c808b47cbc2303eb` executed that gate successfully, and the installed build then passed the exact process-boundary restart proof. Patch 10.43 records N1.8.0 promotion; runtime observer code remains unchanged.

### N1.8.1 — syntax/import integrity

Status: **PROMOTED ON INSTALLED SOURCE `198a3f31e22a5d385378fee087aa5f115aed6d5a`.**

N1.8.1 extends the existing PI-v2 evidence path rather than introducing a second parser/index. After the installed torture runs exposed false-positive semantics in the v3/v6 and v4/v7 implementations plus the structural-v3 regex-resume defect in v5/v8, `RiftSourceIntelligenceV2` is now analyzer v6 and PI persistence is cache schema v9 so syntax/import evidence produced by the broken v8 scanner cannot be reused. Changed files are still re-analyzed incrementally while warm unchanged rows retain bounded syntax and dependency-intent evidence across restart.

Current source provides:
- conservative bounded structural syntax evidence (`bounded-structural-v4-conservative`) with at most 128 deterministic issues per file; Kotlin interpolation remains bounded to its matching expression, JavaScript template literals remain recursively masked including nested templates/interpolations while preserving newlines, dynamic `import()`/`require()` extraction remains executable-code-only, and regex scanning now resumes exactly at the first token after the closing `/` rather than skipping that token. Successfully parsed complex string/template spans are masked; malformed or unterminated constructs remain visible. This remains a hot-path structural check, not a compiler AST or replacement for build/compiler proof;
- dependency `localIntent` evidence for explicit local forms such as relative JavaScript/Python imports, quoted C/C++ includes and Rust local module/use forms;
- a separate read-only `project kind=integrity` view so the promoted N1.8.0 canonical graph remains isolated from N1.8.1 integrity evidence;
- full clean-oracle mode when the integrity query is blank and focused-seed mode when a path/query is supplied;
- deterministic dependency classification into `local-resolved`, `local-missing`, `ambiguous-local`, or `external-or-unclassified`, with error findings only where local intent is provable;
- bounded direct outgoing/reverse dependency frontier output for staged scan expansion without claiming repository-wide cleanliness from focused coverage;
- distinct `complete` and `clean` states, explicit incomplete reasons/bounds, and deterministic `integritySha256` over the exact file/dependency/finding/frontier evidence;
- permanent source regression `scripts/test-rift-integrity-v1.mjs`, wired into the main Builder source gate and ownership ledger.

Promotion evidence is complete. Builder/compile and installed analyzer v6/cache v9 proof passed; the first post-install integrity oracle on RiftOS-main returned `complete=true`, `clean=true`, 261 selected files, 144 syntax-checked files, 0 invalid files, 769 dependencies, 56 local-resolved, 0 local-missing, 0 ambiguous-local, 713 external/unclassified and 0 findings with deterministic integrity SHA-256 `d73568a332d466d2c137a5899288b8fe06c5b4e8640463d859479c49c376b0ab`. Warm and first post-force-stop reads reproduced the exact hash with 261 reused, 0 bytes reanalyzed and cache v9 `loaded`. Installed torture fixtures proved malformed syntax detection without incompleteness, clean nested JS templates/regex/Kotlin interpolation, fake dynamic-import text suppression, external Node/Android imports, relative and package-qualified local resolution, local-missing and ambiguity findings, rename/delete/move path drift and recovery, outgoing/reverse focused frontiers, and full-oracle parity. A 241-target focused fan-out proved fail-closed output bounds: `frontierCount=241`, preview 240, `frontierTruncated=true`, `clean=true`, `complete=false`, reason `integrity-frontier-output-bound`; the same 246-file fixture then returned `complete=true`, `clean=true`, 243 local-resolved and 0 findings under the full oracle. `incremental.filesRemoved` counts stale semantic rows pruned during refresh; API-driven mutations may show 0 when eager invalidation removed the row before refresh. No N1.8.1 false-clean kill condition remains open.

**Post-promotion nullable-flow hardening (LIVE-PROVEN on run 355):** During N2-M3 pre-build review, the run-353 installed Observer was intentionally tested against eleven restored unsafe dereferences of values returned by nullable `MemoryStore` APIs in `RiftMemoryN2M3SelfTest.kt`. Validation/claims/consistency/integrity/contracts remained clean and proofs incorrectly returned zero unresolved obligations. Current source therefore advances `RiftSourceIntelligenceV2` to analyzer v7, PI cache schema 10 and `bounded-structural-v5-conservative`. The new bounded `kotlin-nullable-dereference` rule tracks locals sourced from the typed nullable `getCanonicalRecord` / `getContentBlob` APIs, recognizes safe-call/non-null assertion, explicit `!= null` conjunctions, guarded blocks, early/Elvis exits and `requireNotNull` / `checkNotNull`, and feeds deterministic error findings through the existing read-only integrity lane. Generic `firstOrNull` / `singleOrNull` inference is intentionally not treated as blocking until compiler-grade flow evidence exists. Cache schema 10 prevents pre-hardening syntax evidence from being reused. The M3 source itself is repaired. Installed run 355 live-proved the hardening: a temporary unsafe nullable MemoryStore fixture produced deterministic `n1.8.1-syntax-kotlin-nullable-dereference` with proof source `rift-source-intelligence-v7-bounded-structural-v5-nullability-v1`; removing the fixture restored the exact clean integrity state. The same run also live-proved the new claims regression-literal ownership rule and its repository-wide 2,048 exact / +1 fail-closed boundary.

Required outcomes remain:
- incremental changed-file analysis;
- local import/include/module resolution;
- deletion/rename/path drift detection;
- clean/incremental parity tests.

### N1.8.2 — semantic dependency propagation

Status: **PROMOTED ON INSTALLED SOURCE `9cc74b25c94fd3e23e93f64d3d132e65e63fe3a6`, BUILDER RUN `35929751856` / RUN NUMBER `317`.**

N1.8.2 adds a separate read-only `project kind=propagation` lane above the already promoted N1.8.0/N1.8.1 evidence paths. It reuses the existing PI-v2 symbol/dependency index and local dependency resolver rather than creating a second source index.

Current source provides:
- deterministic symbol identity from `path|kind|name|ordinal`, producing a stable `symbolId` that does not depend on declaration line number;
- a separate `signatureId` bound to the normalized declaration signature, so an API/signature change does not pretend the symbol itself became a different identity;
- explicit API-surface classification using the existing private/internal visibility rule;
- query seeds by exact `symbolId`, exact symbol name, or matching path;
- one-pass bounded reference evidence for selected seed symbol names, filtered through `RiftSourceIntelligenceV2.referenceCodeMask()` so supported-language comments/string-like regions do not become false references;
- seed-relevant candidate filtering: same-file symbol candidates take precedence, otherwise only candidates in resolved local dependency targets are considered; unrelated same-name matches are counted in `ignoredNameMatches` and do not consume the bounded reference evidence, while multi-candidate seed-relevant pools remain explicitly ambiguous; the 1024-reference analysis bound is checked only after code-position, definition and seed-relevance filtering, immediately before a real reference row is emitted;
- caller ownership from the smallest indexed symbol range containing the reference line;
- current local dependency reverse edges, resolved reference reverse edges and type/interface reverse edges;
- type relation extraction for `extends`, `implements`, Kotlin inheritance/interface lists and C++ inheritance, with explicit resolved/unresolved/ambiguous status;
- bounded breadth-first transitive reverse closure with explicit seed/symbol/reference/caller/type-relation/node/edge/depth limits;
- deterministic `propagationSha256` over the exact bounded seed/reference/caller/type/closure evidence;
- bounded output previews separate from completeness: preview truncation does not hide internal evidence, while hitting an analysis bound adds an explicit incomplete reason;
- permanent Builder source regression `scripts/test-rift-propagation-v1.mjs`, wired into `npm check` and Source Ownership;
- zero mutation authority inside the propagation view, and no call into the N1.8.0 canonical observer graph.

Initial bounds:
- 64 seed symbols/paths;
- 8192 symbol nodes;
- 1024 references;
- 512 callers;
- 512 type relations;
- 1024 reverse-closure paths;
- 4096 reverse edges;
- depth 16;
- 240 returned preview rows.

Promotion evidence is complete on installed source `9cc74b25c94fd3e23e93f64d3d132e65e63fe3a6`. Builder run `35929751856` / run number `317` supplied the installed artifact. Exact 1024-reference evidence is complete while the 1025th real reference fails closed only with `propagation-reference-bound`; exact 1024 closure paths are complete while +1 fails only with `propagation-closure-node-bound`; exact 4096 reverse edges are complete while +1 fails only with `propagation-closure-edge-bound`. The exact-edge fixture repeats at propagation SHA-256 `d2de4151c6db1f384460fc4b63f97978adea489f9f6933cca50fe44958bb97ca` with full warm reuse and zero rescanned bytes. A real Android force-stop/reopen reproduced the exact-symbol baseline SHA-256 `d23531420d8d3da679ae24022c1a419b336782e7e6343d848d42910d8b98a9a3`, symbol/signature identities and 0/1/2 closure on the **first** propagation read with cache v9 loaded, 37/37 files reused and 0 bytes rescanned. Post-restart N1.8.0 remained complete with zero findings and `changed=false` at graph SHA-256 `63031280817f503c995da6969588431b7e26c895df1a56e279e529d8b0ea5271`; N1.8.1 remained complete/clean with zero invalid, missing, ambiguous or finding rows at integrity SHA-256 `1752e660c1a71aa2342f4a00e8210287258d2ea7a971ff520ad00c26ae00023e`. Earlier installed torture already covered overload/line-shift identity, signature-only change behavior, caller attribution, ambiguity, lexical false-match suppression, inheritance/interface propagation, multi-hop closure, cycles, and the remaining exact/+1 bounds.

Promoted outcomes:
- stable symbol/signature identity;
- resolved/ambiguous references, callers and dependents;
- interface/implementation and API propagation;
- deterministic transitive reverse closure with explicit fail-closed bounds.

### N1.8.3 — cross-boundary contracts

Status: **PROMOTED on installed source `e6de353ead6e9377e36e1602e301e3e8231a5e43`, Builder run `35949668024` / run number `322`.**

N1.8.3 adds the separate read-only `project kind=contracts` oracle with schema `rift-cross-boundary-contracts-v1`. The implementation is isolated in `RiftCrossBoundaryContractsV1.kt`; `RiftToolSandbox` only dispatches the project view, so this phase does not deepen the existing sandbox god-file before the post-N1.8 refactor.

Current deterministic checks cover:
- config/build mirrors: literal Android `namespace` must equal literal `applicationId`, and every declared Gradle externalNativeBuild CMake path must exist;
- manifest/source mirrors: Android activity/service/receiver/provider names resolve to a matching managed class under the module namespace;
- native build pairs: each managed `System.loadLibrary()` consumer resolves to a CMake `add_library` producer;
- JNI/native pairs: Kotlin `external fun` and Java `native` declarations resolve to JNI-export symbols with JNI name mangling, and JNI exports resolve back to managed declarations;
- MCP registry consistency: each advertised `rift_*` tool has either normal `methodFor()` dispatch or an explicit special dispatch path;
- protocol producer/consumer mirrors: the Android relay client and Cloudflare relay worker agree on `rift-mcp-relay-v1`, and the CLI event producer/relay consumer agree on `rift.cli-event/1`;
- limit ordering: end-to-end synchronous timeout ownership remains strictly ordered as 75s sandbox < 90s native shell < 100s MCP server < 110s relay client < 120s relay worker. Workspace Records uses its own inner 60s operation budget; SSE stream lifetime is governed separately by heartbeat/abort/backpressure/lease semantics rather than the MCP request watchdog.

The scan is fail-closed with bounds of 4096 text/source files, 2 MiB per file, 64 MiB aggregate bytes and 1024 findings. Hitting a file, per-file, aggregate-byte or read bound marks the source scan incomplete before contract evaluation; contract findings are then intentionally suppressed so skipped source cannot manufacture false missing-member claims, and `partialFindingsSuppressed=true` makes that behavior explicit. The separate 1024-finding cap is reached during contract evaluation after a complete source scan, so retained findings remain observable there. Full ordered findings/evidence remain part of `contractsSha256` and full counts, while returned `findings`/`evidence` previews are capped at 240 rows each with explicit row counts and truncation flags so boundary-scale scans cannot overflow the project-tool response path. `complete` and `clean` are separate, the view has zero mutation authority and preview truncation alone does not make the scan incomplete.

Permanent regression: `scripts/test-rift-cross-boundary-contracts-v1.mjs`, wired into root `npm check`. It verifies the real source mirrors and includes deliberate protocol/timeout mismatch fixtures so the gate cannot pass only because the current repository happens to be clean.

Promotion evidence is complete. Installed precursor builds proved each complete-scan finding family (config/application ID, Gradle/CMake, manifest/source, native-library producer, both JNI directions, MCP registry, relay protocol, CLI event schema and timeout ordering) plus exact/+1 finding, per-file-size and file-count bounds. Patch 10.58 changed only already-incomplete source-scan handling; final installed source `e6de353ead6e9377e36e1602e301e3e8231a5e43` / Builder run `35949668024` / run 322 re-proved the changed aggregate path: exact 64 MiB remains complete, while 64 MiB + 1 returns only `contracts-byte-bound`, `partialFindingsSuppressed=true` and zero secondary findings. The full RiftOS contracts read is `complete=true`, `clean=true`, 166 files / 3,401,644 bytes / 18 contracts / 0 findings with SHA-256 `d13a5edb0d3d3fb82ba6c013ad3a24a71c174ff09d6499782a0223ad66f2edd4`; its warm repeat and the first read after a real force-stop/reopen reproduced that exact SHA and counts. Post-restart N1.8.0 consistency, N1.8.1 integrity and N1.8.2 propagation all remain green. N1.8.3 is promoted; N1.8.4 is next.

### N1.8.4 — documentation/roadmap/TODO claims

Status: **PROMOTED on installed source `be1e3ddedec2512145ec7132c3a47419c139cb4e`, Builder run `35957909835` / run number `328`.** The read-only `project kind=claims` lane is implemented by `RiftDocumentationClaimsV1.kt` and exposed through a thin `RiftToolSandbox` dispatch. It reads the versioned machine authority `observer/phase-authority.json` and deterministic repository evidence to verify required current docs, relative Markdown link targets, source-ownership existence/coverage, authority-policy markers, ROADMAP phase state, PROJECT_STATUS promoted source/run bindings, canonical Observer status, structured TODO/FIXME discovery and deterministic historical-document classification. The authority file is repository source state, not documentation: schema `rift-observer-phase-authority-v1`, exact N1.8.0-N1.8.7 ordering, monotonic lifecycle ordering, promoted-source SHA validation, optional Builder-run validation and a 64 KiB hard cap all fail closed. This removes the promotion bootstrap loop where compiled lifecycle constants would immediately call newly synchronized promotion docs stale. Scans are bounded at 4096 text files, 2 MiB/file, 64 MiB aggregate bytes, 8192 claims, 1024 findings and 240 preview rows. `claimsSha256` binds the full ordered authority/claim set. Free-form prose inference is explicitly disabled (`freeFormProseInference=false`); unsupported language is never treated as truth by guesswork. Promotion evidence is complete. Run 327 proved the full semantic mutation matrix plus exact/+1 2 MiB file, 4096-file, 64 MiB aggregate and 8192-claim bounds; Patch 10.63 then externalized lifecycle authority into `observer/phase-authority.json`. Final installed run 328 proved the bootstrap refactor itself with clean full-repo claims at `claimsSha256=42182e5625905d101567ed4ced43f557a17db1aeb207a1db4f1fd378dde0a804`, exact fail-closed missing/invalid authority behavior, deterministic warm reads, exact first-read post-force-stop parity, and fresh N1.8.0-N1.8.3 continuity. N1.8.4 is promoted; N1.8.5 is next.

**Hard authority rule: documentation is never repository truth.** N1.8.4 treats README/docs, ROADMAP entries, TODOs, status files and source comments as non-authoritative claims that must be checked against stronger evidence.

Authority is directional:
1. current source, manifests, build configuration, generated registries and other machine-readable repository state are authoritative for implementation/existence/configuration claims;
2. exact Builder artifacts and installed-device/runtime evidence are authoritative for build/install/promotion claims;
3. Observer outputs derived from those authorities may summarize the evidence but do not supersede it;
4. README/docs, ROADMAP, TODOs, status prose and comments are claim surfaces only and may never override, repair, reinterpret or manufacture source/runtime truth.

Required behavior:
- if documentation disagrees with authoritative evidence, the contradiction finding points at the documentation/claim surface as stale or unsupported;
- the Observer must never mutate source or lower-confidence evidence to make it agree with documentation;
- documentation agreement cannot prove that a feature exists, is wired, is built, is installed or is promoted without corresponding authoritative evidence;
- absent or incomplete authoritative evidence yields unresolved/insufficient-evidence state rather than trusting documentation as a fallback;
- historical documents are excluded from current-state contradiction findings only when they are explicitly and deterministically classified as historical;
- promotion/current-state claims must bind to exact source/build/install evidence when that evidence class is required;
- TODO lifecycle claims are checked against source/evidence state: completed work still marked TODO and TODOs marked complete without supporting authority are both contradictions;
- README/docs current-state claims;
- ROADMAP state;
- TODO lifecycle;
- historical-document classification;
- evidence-linked contradiction findings.

### N1.8.5 — proof obligations and focused verification

Status: **PROMOTED on installed source `08146ba30ef713ac895e390adf5d515ef78fa728`, Builder run `36014191151` / run number `335`.**

`RiftProofObligationsV1.kt` adds the read-only `project kind=proofs` planner. It consumes the exact `rift-semantic-impact-v1` candidate evidence already produced from Patch Manifest V1 plus the existing project-validation command surface; it does not create a second change detector and it executes no verification itself.

The proof plan is deterministic and evidence-linked:

- directly affected tests are selected only from changed-test evidence, direct dependency/dependent evidence, or changed-symbol references;
- path/name-affinity tests remain explicitly supplemental and cannot discharge the affected-test obligation;
- `npm run check` / lint / build / general test-suite commands are classified as general or build checks and cannot substitute for a directly affected test;
- when source changes have no strongly evidenced affected test, the planner emits a stable `affected-tests` unresolved obligation rather than choosing an unrelated test;
- deep verification escalates for incomplete candidate impact, API-surface changes, build-config changes, source deletion, multi-project candidates, unclassified changes, missing affected-test evidence, or unavailable required build verification;
- source add/delete requires the documentation-claims lane because source-ownership obligations can change;
- source/build changes require the cross-boundary-contracts lane;
- all selected checks/tests/obligations are bounded and `proofsSha256` binds the full canonical **project-local** plan (`hashScope=project-local-plan-v1`) while previews remain capped; workspace-global candidate IDs, semantic-impact hashes and global changed-symbol lists remain diagnostic evidence and cannot perturb an unrelated project's proof identity;
- the planner is evidence-only: `executesVerification=false` and no result is represented as passed merely because it was selected.

Permanent regression `scripts/test-rift-proof-obligations-v1.mjs`, Gradle mandatory-source coverage, source ownership and `npm check` wiring are present. Builder compilation/signed-DEX proof, installed semantic fixtures, exact-bound/determinism/restart torture and N1.8.0-N1.8.4 continuity remain required before promotion.

**Post-promotion machine-authority consumer hardening (LIVE-PROVEN / CLOSED on run 360):** FAIL-005 proved that a promoted machine-authority edit could leave an older direct regression consumer outside the proof closure. PI-v2 semantic impact now performs a bounded exact-literal config-read scan across maintained verification scripts (tests plus `scripts/validate-*.mjs/js` and `scripts/verify-*.mjs/js`) for changed machine-authority/build-config targets and emits deterministic `config-read` direct-dependent edges. Supported forms include `read(...)`, `*Read(...)`, direct `readFileSync(...)` and `readFileSync(path.join(...))`; target/test scan overflow fails closed with `config-read-target-bound` / `config-read-test-bound`. `RiftSourceIntelligenceV2.isMachineAuthorityPath(...)` exposes the shared authority classification. `RiftProofObligationsV1` now emits `authority-consumer-tests`: directly evidenced consumers make it required, while no direct consumer makes it unresolved with `authority-consumer-evidence-missing`. `test-rift-semantic-impact-v1.mjs` locks the current N2 phase-authority consumer set (contract + M1 + M2 + M3 + M4 + `validate-rift-docs.mjs`), and `test-rift-proof-obligations-v1.mjs` locks required-vs-unresolved behavior. Builder/install proof plus a live whitespace-only `n2-phase-authority.json` mutation/revert fixture is required before this hardening is closed. **FAIL-006 follow-up (source-implemented / live proof pending):** the first FAIL-005 repair build exposed an over-escaped test-file discovery regex in `test-rift-semantic-impact-v1.mjs` that silently selected zero maintained tests. The claims oracle now performs bounded regression-discovery sanity for maintained tests that enumerate `scripts` via `readdirSync(...)`: the literal regex is compiled and applied to real sibling `test-*.mjs/js` names; invalid/unsupported or zero-match discovery becomes blocking `regression-discovery-pattern-empty`, with `MAX_REGRESSION_DISCOVERY_PATTERNS=512` and fail-closed `claims-regression-discovery-bound`. The corrected semantic-impact regression uses `/^test-.*\.(?:mjs|js)$/`. Builder/install proof plus a temporary wrong-discovery fixture is required before FAIL-006 closes. **FAIL-007 follow-up (source-implemented / live proof pending):** the next repair build exposed an older N2 contract assertion that froze the removed local text `val machineAuthority = listOf(` even though the stable `isMachineAuthorityPath(...)` behavior remained. `checkRegressionLiteralOwnership(...)` now accepts literal-path `read(...)` and maintained `*Read(...)` aliases, validates direct `assert.ok(alias.includes('literal'))` plus prefixed `*Assert.ok(...)` forms as well as marker loops, and applies `referenceCodeMask(...)` so comment/string lookalikes are ignored. Direct and loop assertions share the existing 2,048 fail-closed literal budget and `regression-literal-marker-missing`. The N2 contract now asserts the stable helper surface and exact authority paths. Run `359` live-proved FAIL-005 authority-consumer selection and FAIL-006 zero-match discovery detection. During FAIL-007 live claims verification, the generalized direct-literal rule exposed one false positive: raw JavaScript `\\$input` was compared before escape normalization even though the evaluated string and Kotlin source both contain `\$input`. Current source centralizes minimal deterministic decoding in `decodeRegressionLiteral(...)` for direct and loop literals, while the independent JS regression uses its own `decodeRegressionMarker(...)`; the existing RiftGit escaped-literal assertion is now a permanent false-positive control. Run `360` installed source `aa03a7a4a1229ff942a0b35ec086a9a9c81c35bf` and closed the cluster: baseline claims were clean, the RiftGit escaped-literal false-positive control stayed clean, a representative imported-`*Assert`/arrow-`*Read` wrong direct-literal fixture produced `regression-literal-marker-missing`, fixture removal restored the identical clean claims hash, and FAIL-005 authority-consumer selection plus FAIL-006 zero-match discovery detection were already live-proven on run 359.

### N1.8.6 — adversarial correctness and layer-isolation proof

Status: **SOURCE-IMPLEMENTED / PROMOTION PENDING.** Permanent regression `scripts/test-rift-observer-adversarial-v1.mjs` defines a canonical seven-fixture corpus (`da0fd2e1313a9c00fc3e0e382abf3e32cc8d33503e922e11365041953ec55012`) with explicit `mustFail`/`mustHold` outcomes across syntax, graph, claims and historical layers. The corpus contains four isolated true-positive failures and three false-positive controls, is replay-deterministic, chains every promoted Observer regression into the mandatory source gate, and performs no performance/comparative scoring.

- Rift mutation corpus as deterministic correctness fixtures;
- false-positive/false-negative stress with explicit expected outcomes;
- layer-isolation fixtures proving syntax, graph, claim and historical layers fail/hold independently;
- exact/+1 bounds, deterministic replay and fail-closed behavior;
- no external comparative benchmark or performance score is run here.

The external benchmark subsets, precision/recall/F1, latency, memory, throughput and comparative ablation campaign are deferred until the full CLI is 100% complete and live.

### N1.8.7 — installed-device promotion

Status: **PROMOTED on installed source `b9910bb2f88b198619ce34e4ccebf12e6c60352a`, Builder run `36024616083` / run number `339`.** The real-device sweep completed the full N1.8 promotion program. Under the global no-benchmark rule, the bounded RAM/latency item was satisfied only as verification of finite caps and the 75/90/100/110/120-second fail-closed request ladder; no performance timing, memory profiling or comparative scoring was run.

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

N1.8 is the hard precondition for N2, and that prerequisite is now satisfied by the fully promoted N1.8.0-N1.8.7 program. This does not itself activate N2; N2 begins only through its own implementation and promotion sequence.

The Federated Rift Memory Kernel may ingest observer findings/evidence later, but memory is not allowed to become the source of repository truth. Repository facts remain rebuildable from code/config/docs/history.

N3/N4/N5/N6 may consume the observer's graph and proof obligations, but none may weaken its evidence precedence or clean-rebuild oracle.
