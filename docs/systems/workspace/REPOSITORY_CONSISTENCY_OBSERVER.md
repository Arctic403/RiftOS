# N1.8 Repository Consistency Observer

Status: **N1.8.0 CONTENT-TRUTH + CACHE-PROVENANCE HARDENING SOURCE-IMPLEMENTED — BUILDER/INSTALLED TORTURE RESTART PENDING; N1.8.1+ PENDING**

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

**Foundation content truth, producer provenance, Patch 10.35 restart-order determinism and the exact byte-budget boundaries are live-proven, but N1.8.0 remains unpromoted. Patch 10.36 upgrades PI persistence to integrity-sealed schema v5. The continuing torture audit also found two source blockers after that cache fix: valid stable keys longer than 2048 characters could crash identity construction, and the optional rebuildable observer snapshot could throw when its serialized payload exceeded 4 MiB. Current source fixes both: identity hashes the full normalized stable key while stored/display keys are bounded with a SHA-256 suffix, and observer-cache oversize/write/verification failures return explicit non-authoritative cache diagnostics instead of failing an otherwise valid graph. Builder/install/restart proof of the combined source is still required before promotion.**

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

Patch 10.37 was then built and installed at source `164843c6613a2d7433b2d7ffd160725930863839`. Live proof passed the v5 producer/restart path, Local Agent-hosted RiftCLI boundary, long stable keys, >4 MiB snapshot fail-soft behavior, 4096/4097 symbol and dependency bounds, and the 1024-edge resolver CPU bound. The continuing K regression sweep exposed one new hard blocker: the exact 128 MiB + 1 semantic-total fixture could become falsely clean on a warm scan because cached semantic rows returned before the old `bytesScanned` total-budget counter advanced. Patch 10.38 source separates actual re-analysis bytes from `semanticBytesAccounted`, charges every eligible file before cached reuse, and adds a permanent ordering regression. N1.8.0 remains unpromoted until the rebuilt APK proves cold/warm exact/+1 parity and the remaining matrix completes.

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
