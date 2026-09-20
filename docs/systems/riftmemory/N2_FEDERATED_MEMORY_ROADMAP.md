# RiftCLI N2 — Federated Rift Memory Kernel Roadmap

## Status

**ROADMAP / NOT IMPLEMENTED / NOT A CURRENT CAPABILITY — 2026-09-20.**

This document freezes the intended N2 architecture and promotion program before N3 begins.

The existing retained `src/riftmemory-control.js` cache/workset design remains inactive reference source. N2 does **not** activate, inherit authority from, or silently repurpose that retained implementation.

N3 is hard-blocked until N2 completes the promotion gate defined here.

## Mission

N2 is not "LLM + memory database."

N2 is a **federated persistent cognitive data system** for RiftCLI engineering state.

The governing phrase is:

> **ONE MEMORY KERNEL. MANY SPECIALIZED COGNITIVE ENGINES.**

The architecture must preserve:

- one canonical reality;
- one canonical ID/evidence/transaction authority;
- multiple specialist projections/views;
- one reconciliation authority;
- explicit uncertainty rather than forced agreement;
- evidence-grounded updates through Observer and Validator;
- rebuildable disposable indexes;
- crash/restart survivability;
- benchmarked value for every specialist.

No specialist engine may create an independent authoritative reality.

## Architecture law

The canonical control flow is:

```text
ONE CANONICAL REALITY
        |
        v
RIFT MEMORY KERNEL
        |
        +--> Canonical Evidence Ledger
        +--> Canonical Event Ledger
        +--> Claims / Current State / History
        +--> Protected Policy + Authority
        +--> Transaction / Snapshot / Integrity
        |
        v
SPECIALIZED MEMORY ENGINES
        |
        +--> Temporal / Graph
        +--> Episodic
        +--> Consolidation
        +--> Semantic
        +--> Belief / Reflection
        +--> Skill / Procedural
        +--> Failure Intelligence
        +--> Causal
        +--> Commitment
        +--> Predictive / Expected State
        |
        v
MULTI-INDEX PROJECTIONS
        |
        +--> Exact / Current-state
        +--> Entity / Project / Repo / Branch
        +--> Temporal / Validity
        +--> Graph adjacency
        +--> BM25 / lexical
        +--> Semantic / vector
        +--> Failure / procedural / causal
        +--> Authority / policy
        |
        v
MEMORY ROUTER
        |
        v
RETRIEVAL FUSION / ARBITRATION
        |
        v
CONTEXT COMPILER
        |
        v
PLANNER
        |
        v
TOOLS
        |
        v
OBSERVER
        |
        v
VALIDATOR
        |
        v
DIFFERENCE / SURPRISE ENGINE
        |
        v
RECONCILIATION
        |
        v
CANONICAL MEMORY TRANSACTION
        |
        +--> projection updates
        +--> projection verification
        +--> dirty/rebuild scheduling
```

## Research posture

N2 may borrow mechanisms, not architectures wholesale.

Research targets include Graphiti/Zep, EverMemOS, Hindsight-style evidence/belief separation, MemOS, Agent Zero Memory, A-MEM, Mem0 and other recent memory systems plus STATE-Bench, LongMemEval, LongMemEval-V2, LoCoMo/LoCoMo-Plus/LOCOMO-CONV, BEAM, HaluMem, ImplicitMemBench, PersonaMem and OmniMemEval where practical.

For every borrowed mechanism:

1. identify the exact memory problem it solves;
2. reproduce the smallest useful mechanism behind Rift interfaces;
3. preserve Rift evidence/authority/reconciliation semantics;
4. benchmark the specialist alone;
5. benchmark it in combination;
6. ablate it;
7. remove it if total-system value does not justify complexity.

Claims that a subsystem is "better" require benchmark evidence.

## Logical, durable and runtime separation

N2 has three representation layers.

### Logical

The logical memory language is strongly structured JSON-shaped data.

JSON defines human-readable schemas and API semantics. It does not dictate physical disk encoding.

Canonical object families include:

- Evidence;
- Observation;
- Claim;
- Episode;
- Semantic fact/concept;
- Belief;
- Prediction;
- Reflection;
- Skill;
- Failure;
- Project state;
- Commitment;
- Policy;
- Discrepancy;
- Causal relationship;
- Graph relationship;
- Configuration;
- Snapshot metadata;
- Memory transaction.

All objects require canonical IDs, scope and version/schema identity appropriate to their class.

### Durable

The durable layer provides atomic transactions, crash recovery, history and compact physical storage.

SQLite is the **first reference/control backend**, not the conceptual architecture.

The backend API must be replaceable from the first implementation.

### Runtime

Runtime state is optimized and disposable:

- hot current-state cache;
- graph adjacency;
- temporal indexes;
- lexical/BM25 indexes;
- vector indexes;
- entity/project/repo/branch indexes;
- failure/procedural/causal indexes;
- compiled context caches.

Deleting every runtime projection must not delete canonical knowledge.

## Canonical object requirements

A final schema is frozen only after N2.0 experiments, but canonical objects must be able to represent:

- globally unique ID;
- type/schema/version;
- project and scope;
- subject/entity identity;
- value/content reference;
- state/trust class;
- confidence when probabilistic;
- valid time;
- observed/transaction time;
- created/updated transaction;
- evidence references;
- provenance;
- authority/trust classification;
- supersession/conflict relationships;
- branch: reality/hypothesis/simulation/counterfactual;
- integrity/hash where applicable.

Evidence and belief are never collapsed into one object class.

## Canonical Evidence Ledger

Evidence is immutable or append-only whenever practical.

Evidence sources include:

- tool output;
- build logs;
- tests;
- files;
- Git state;
- runtime observations;
- commands;
- network state;
- explicit user decisions/corrections;
- Observer output;
- Validator output;
- failure/recovery events.

Evidence records carry:

- ID;
- source;
- timestamp;
- project/scope;
- integrity/hash;
- authority/trust classification;
- original reference/content location.

Large evidence is content-addressed:

```text
memory object
  -> evidence_ref
  -> content hash
  -> immutable blob
```

One large artifact is stored once and reused by hash reference.

## Evidence is not belief

Mandatory promotion pipeline:

```text
Evidence
  -> Interpretation
  -> Candidate Claim
  -> Verification
  -> Trusted/Verified Belief
```

A tool saying X creates evidence that the tool reported X. It does not automatically establish X as canonical truth.

Repeated exposure to the same assertion does not automatically increase trust.

## Canonical Event Ledger and bi-temporal truth

Memory mutations are recorded as events.

Current state is a projection of event history.

The kernel must distinguish:

- **valid time** — when a fact/state was true in the world/project;
- **observation/transaction time** — when Rift learned/recorded it.

History must support reconstruction of:

- current belief state;
- previous state;
- project state at a chosen time;
- what Rift knew at a chosen transaction;
- why a belief changed;
- which evidence caused the transition.

Superseded history is retained rather than silently rewritten.

## Canonical relations

The graph vocabulary may evolve under schema versioning, but must support relationships such as:

- SUPERSEDES;
- CONTRADICTS;
- VERIFIED_BY;
- DERIVED_FROM;
- DEPENDS_ON;
- BELONGS_TO;
- CAUSED;
- FIXED_BY;
- FAILED_BECAUSE;
- REQUIRES;
- BLOCKS;
- INVALIDATES;
- OBSERVED_IN;
- RECOVERED_BY;
- EXPECTS;
- LEARNED_FROM.

Causal edges are not inferred merely from event ordering.

## Specialist engines

### Temporal / Graph engine

Purpose:

- current and historical state;
- validity intervals;
- supersession;
- contradiction;
- provenance;
- dependency/entity relations;
- point-in-time reasoning.

Graph state is a rebuildable projection over canonical IDs.

### Episodic engine

Preserves specific experiences:

- what happened;
- conditions/environment;
- actions/tools;
- failures/successes;
- state before/after;
- evidence references.

Rich episodes remain durable without forcing raw history into every model context.

### Consolidation engine

Progressive reversible abstraction:

```text
Observations
  -> Episodes
  -> Episode clusters
  -> Patterns
  -> Semantic knowledge
  -> Candidate skills
```

Every abstraction retains lineage to lower-level evidence.

Heavy consolidation belongs on the deep/asynchronous path, never the correctness-critical commit path.

### Semantic engine

Stores durable concepts/facts:

- architecture;
- APIs;
- stable tool behavior;
- project conventions;
- dependencies;
- durable configuration/capabilities.

Semantic memory must be grounded in canonical evidence/episodes.

### Belief / Reflection engine

Maintains explicit distinction among:

- evidence: "I observed X";
- claim/fact candidate: "X appears true";
- belief: "Y may explain X";
- prediction: "If Z occurs, X should change";
- reflection: "previous belief is likely incorrect."

Beliefs may remain probabilistic or conflicted.

### Skill / Procedural engine

Repeated verified experience may become a structured skill containing:

- purpose/scope;
- preconditions/applicability;
- required capabilities;
- ordered steps;
- expected observations;
- failure branches;
- validation;
- rollback;
- successful/failed examples;
- confidence/reliability;
- version/lifecycle.

Lifecycle:

```text
Candidate -> Tested -> Validated -> Stable -> Deprecated -> Superseded
```

One success never makes a skill globally applicable.

### Failure Intelligence engine

First-class debugging memory:

- failure signature/error;
- context/environment;
- project state;
- recent changes;
- attempted solutions;
- failed solutions;
- successful recovery;
- regression test;
- root-cause confidence.

Debug retrieval should strongly weight this lane when failure signatures match.

### Causal engine

Tracks cause/effect separately from correlation.

Confidence stages:

- Hypothesis;
- Weak evidence;
- Repeated correlation;
- Strong evidence;
- Experimentally verified.

Temporal precedence alone never creates a verified causal edge.

### Commitment engine

Tracks unfinished obligations:

- user requests;
- required future work;
- deferred work;
- blockers;
- validation still required;
- incomplete tasks/checkpoints.

Cold restart must not conceptually erase unfinished engineering work.

### Policy / Authority memory

Protected namespaces store:

- permissions;
- user-defined rules;
- security boundaries;
- validation requirements;
- capability limits;
- protected configuration.

Learned memory cannot silently overwrite protected policy.

### Predictive / Expected-state memory

N2 stores expectations separately from observations.

Example:

```text
Expected: 18 tools
Observed: 17 tools
=> discrepancy, not silent overwrite
```

Predictions/expectations are candidates for comparison, not facts.

## Difference / Surprise Engine

The Difference Engine compares expected state against observed state.

High-surprise events receive higher learning priority.

Importance may consider:

- novelty;
- surprise;
- future usefulness;
- risk;
- task impact;
- evidence quality;
- recurrence.

Routine low-information successes should not dominate durable memory.

## Observer and Validator integration

Observer answers:

> What happened?

Validator answers:

> Was it correct/acceptable?

Observer produces structured observations but cannot directly rewrite trusted claims.

Required flow:

```text
Observation
  -> Difference detection
  -> Reconciliation
  -> Candidate memory transaction
  -> Validator/policy gate as required
  -> Commit/provisional/quarantine/reject
```

A file changing and a build failing are both preserved. A mutation is not marked successful solely because the file changed.

## Reconciliation authority

Reconciliation is kernel-owned and determines whether new evidence:

- confirms;
- strengthens;
- weakens;
- contradicts;
- supersedes;
- invalidates;
- belongs to another scope/project;
- requires more observation;
- remains provisional;
- is quarantined;
- is rejected.

Specialists may disagree.

Disagreement may create a `CONFLICTED` canonical state rather than a fabricated consensus.

## Arbitration

Retrieval/arbitration considers:

- evidence authority;
- current direct observation;
- temporal validity;
- verification status;
- confidence;
- project/scope match;
- provenance quality;
- specialist agreement/disagreement;
- supersession;
- causal support;
- exact identity;
- graph distance;
- lexical/semantic similarity;
- recency;
- procedure applicability;
- failure similarity;
- user priority.

Semantic/vector similarity never establishes truth and cannot overrule stronger current evidence by itself.

## Memory Router

The external reasoning driver should not manually query every lane.

The router maps intent to specialist/index combinations.

Examples:

- "what is true now?" -> current-state + entity + temporal;
- "why did this fail?" -> failure + causal + recent episodes;
- "how did we fix this before?" -> skill + failure + episodes;
- "what changed?" -> temporal + episodes + graph;
- "what proves this?" -> evidence + provenance.

## Retrieval modes

### NO MEMORY

No persistent memory retrieval.

Used as a benchmark/control and for tasks that explicitly forbid memory.

### FAST MEMORY

Current state + exact/entity/project indexes with strict low latency.

### DEEP MEMORY

Graph + semantic + temporal + causal + procedural fusion.

### FORENSIC MEMORY

History + contradictions + provenance + raw evidence references + full reconciliation trail.

The router selects the least expensive mode that can safely answer the need.

## Multi-index projections

Candidate rebuildable indexes:

- exact ID;
- current state;
- entity;
- project;
- repository;
- branch;
- temporal;
- validity;
- claim;
- evidence;
- failure signature;
- procedural;
- causal;
- commitment;
- graph adjacency;
- authority/policy;
- BM25/lexical;
- semantic/vector.

Indexes are projections, never canonical memory.

## Retrieval fusion and Context Compiler

Fusion ranks candidate context using the arbitration signals above.

The Context Compiler then:

- deduplicates;
- resolves superseded facts;
- retains unresolved contradictions;
- prefers current verified facts;
- preserves provenance IDs;
- isolates project scope;
- includes applicable skills;
- strips irrelevant detail;
- enforces token budgets;
- emits minimal sufficient context.

Raw transcript dumping is not the target architecture.

## Rebuildable projections

Mandatory property:

Delete the graph cache, vector index, BM25 index, temporal index and entity index.

Then rebuild all of them from canonical memory without losing knowledge.

Projection update failure after canonical commit never invalidates the canonical transaction.

A failed projection becomes `DIRTY` and is rebuilt/repaired later.

## Protected namespaces and cognitive branches

Logical namespaces may include:

- `/system/config`;
- `/system/policy`;
- `/system/schema`;
- `/memory/evidence`;
- `/memory/events`;
- `/memory/claims`;
- `/memory/episodes`;
- `/memory/semantic`;
- `/memory/skills`;
- `/memory/failures`;
- `/memory/projects`.

Graph links may cross namespaces.

Mutation authority may not.

Hypothetical reasoning is branch-isolated:

- REALITY;
- HYPOTHESIS;
- SIMULATION;
- COUNTERFACTUAL.

Simulation output cannot become reality without real evidence/reconciliation.

## Memory transaction protocol

All canonical writes follow a governed pipeline:

```text
Candidate memory
  -> schema validation
  -> scope resolution
  -> authority classification
  -> trust classification
  -> evidence attachment
  -> conflict search
  -> temporal reconciliation
  -> Observer verification when applicable
  -> Validator/policy check
  -> canonical transaction
  -> projection updates
  -> projection verification
  -> commit result
```

Outcomes:

- COMMITTED;
- PROVISIONAL;
- QUARANTINED;
- REJECTED.

Canonical commit durability is never dependent on a disposable vector/graph/BM25 projection succeeding.

## Trust states

Canonical state must support more than Boolean truth:

- VERIFIED;
- TRUSTED;
- PROVISIONAL;
- UNVERIFIED;
- CONFLICTED;
- QUARANTINED;
- SUPERSEDED;
- INVALIDATED;
- HISTORICAL;
- ARCHIVED.

## SQLite-first MemoryStore reference backend

N2 starts with SQLite as the reference/control backend.

Research and benchmark:

- transactions;
- WAL where supported/appropriate;
- crash recovery;
- integrity checks;
- JSON/JSONB capabilities when available;
- FTS5/BM25 when available;
- generated/expression indexes where useful;
- snapshot/version metadata.

Android/platform SQLite capabilities must be detected rather than assumed. Bundled SQLite is permitted only if its APK/RAM/CPU/storage cost is justified.

The logical kernel does not depend on SQLite-specific semantics.

Required abstraction:

```text
Rift Memory Kernel
        |
        v
MemoryStore API
        |
        +--> SQLite reference backend
        |
        +--> RiftStore experimental backend
```

## RiftStore experimental backend

RiftStore is a later N2 experiment, not an assumption.

Goal:

> beat the SQLite control for Rift's actual canonical-memory workload without sacrificing correctness.

Do **not** attempt to clone all of SQL/SQLite.

Candidate RiftStore physical design:

- append-oriented canonical transaction/event log;
- content-addressed immutable blob store;
- typed structured record arena;
- dictionary/symbol interning;
- compact current-state checkpoints;
- rebuildable secondary indexes.

Candidate physical strategies to benchmark:

- append log + B+ tree;
- append log + LSM-style indexes;
- page-based copy-on-write tree;
- immutable segments + compaction manifests;
- mmap-backed typed records;
- hybrid append log + hot B+ tree/hash projections.

Logical JSON remains human-readable even if physical records use interned numeric IDs or typed binary encoding.

RiftStore may replace SQLite only through measured subsystem-by-subsystem wins.

If a custom replacement adds complexity without sufficient accuracy/latency/RAM/storage/recovery benefit, keep SQLite.

## Physical-format benchmark

Benchmark identical logical datasets using:

- raw prose baseline;
- pretty JSON;
- minified JSON;
- SQLite text JSON;
- SQLite JSONB when available;
- CBOR;
- dictionary-coded structured records;
- custom typed binary records only if later justified.

Measure, never assume:

- bytes;
- parse/encode speed;
- write/read speed;
- peak RAM;
- reconstruction accuracy;
- graph reconstruction;
- index rebuild;
- context-token output.

No fixed compression ratio may be claimed without measurement.

## Content addressing and dictionary encoding

Large logs/diffs/build outputs/tool responses/documents/repository snapshots should be de-duplicated by content hash.

Repeated schema values/relations/states/project IDs may use interned dictionary IDs physically.

The logical API continues to expose readable values.

## Fast commit vs deep consolidation

Correctness-sensitive commit path:

1. append/commit canonical transaction;
2. update the smallest required current-state metadata;
3. acknowledge durable commit.

Potentially asynchronous/rebuildable work:

- embeddings;
- deep graph projections;
- BM25/vector rebuild;
- consolidation;
- skill mining;
- archive compression;
- optional analytics.

Asynchrony must not weaken correctness.

## Integrity checker / memory fsck

Create a canonical memory integrity checker.

It checks:

- missing evidence;
- broken references;
- dangling graph edges;
- invalid schema/version;
- conflicting current facts;
- unsupported trusted claims;
- broken provenance;
- cross-project contamination;
- invalid dependencies;
- skills referencing missing capabilities;
- tasks marked complete without required validation;
- corrupted content hashes;
- dirty/unrebuildable projections.

## Snapshot / replay / rollback / explain

The kernel must eventually support bounded commands/interfaces equivalent to:

- memory explain <id>;
- memory history <entity>;
- memory diff <range>;
- memory conflicts;
- memory verify <scope>;
- memory provenance <id>;
- memory snapshot;
- memory replay;
- memory rollback <transaction>.

Rollback creates a new canonical transaction or restores from a verified snapshot according to the final transaction model; history is not silently erased.

## Crash consistency

Inject process death/failure during:

- evidence append;
- transaction commit;
- claim creation;
- current-state projection;
- graph/index update;
- consolidation;
- skill creation;
- snapshot;
- Observer reconciliation.

After restart:

- canonical memory must be valid;
- committed transactions must reconstruct exactly;
- partial transactions must not masquerade as committed;
- disposable indexes may be dirty and rebuild.

## Memory poisoning defenses

Explicit adversarial tests:

- prompt injection inside files;
- poisoned documentation;
- fake logs;
- malicious/untrusted tool output;
- false completion claims;
- sleeper memories;
- cross-project contamination;
- repeated misinformation;
- stale instructions;
- hidden instructions inside evidence.

Evidence content is data unless a trusted authority explicitly promotes an instruction/policy.

Repetition alone never upgrades trust.

## Scale gates

Benchmark at minimum:

- 10K records;
- 100K;
- 1M;
- 10M;

and larger only when useful.

Measure:

- retrieval accuracy;
- P50/P95/P99 latency;
- disk;
- RAM;
- CPU;
- write amplification;
- index size;
- index rebuild time;
- consolidation time;
- context-token output;
- startup/recovery time;
- ARM32 and ARM64 behavior.

## Benchmark program

### Public suites

Run, where licensing/tooling/model access make practical:

- LongMemEval;
- LongMemEval-V2;
- LoCoMo;
- LoCoMo-Plus;
- LOCOMO-CONV;
- BEAM;
- HaluMem;
- ImplicitMemBench;
- PersonaMem;
- STATE-Bench;
- OmniMemEval.

Keep model, prompts and infrastructure as consistent as possible across baseline and candidate runs.

### Private Rift suite

Public benchmarks are insufficient.

Private N2 tests cover:

- Observer reconciliation;
- stale memory;
- reversed decisions;
- temporal updates;
- cross-project isolation;
- poisoning;
- cold restart;
- crash recovery;
- rollback;
- procedural transfer;
- skill invalidation;
- failure recurrence;
- index corruption;
- projection rebuild;
- implicit memory;
- speculative-branch isolation;
- evidence provenance;
- false-completion prevention;
- protected policy overwrite attempts.

### Specialist benchmarks

Measure independently:

- temporal engine;
- episodic engine;
- consolidation;
- semantic engine;
- belief/reflection;
- skill engine;
- failure engine;
- causal engine;
- router/retriever;
- fusion/arbitration;
- Context Compiler;
- Observer/Validator reconciliation;
- SQLite/RiftStore storage backends.

### Incremental hybrid benchmarks

Required sequence:

```text
Baseline
+ temporal graph
+ episodic memory
+ consolidation
+ semantic memory
+ belief/reflection
+ skill/procedural memory
+ causal/failure intelligence
+ full arbitration/router/context compiler
```

Record marginal accuracy, latency, RAM, storage and token cost at each addition.

### Ablations

Disable each major subsystem and rerun the relevant benchmark.

At minimum:

- no temporal graph;
- no vector retrieval;
- no BM25;
- no episodic memory;
- no skill engine;
- no failure engine;
- no causal engine;
- no reconciliation;
- no content-addressed evidence;
- no consolidation;
- no Observer-driven update;
- no Context Compiler.

A subsystem that cannot earn its complexity may be removed.

## Federation cost rule

Federation must beat its overhead.

For every specialist measure:

- correctness/accuracy gain;
- latency cost;
- RAM cost;
- disk/index cost;
- CPU/write amplification;
- context-token cost;
- tool-call reduction;
- reliability improvement.

"More components" is not evidence of a better system.

## Weakest-link promotion rule

Critical categories are not averaged away.

Required categories:

- retrieval;
- temporal accuracy;
- Observer reconciliation;
- project isolation;
- provenance;
- poisoning resistance;
- procedural learning;
- cold restart;
- crash recovery;
- rollback;
- integrity;
- security.

If any mandatory category misses its frozen target, N2 remains below promotion.

## Zero-tolerance correctness goals

Target:

- false trusted memory = 0;
- cross-project contamination = 0;
- unsupported trusted claims = 0;
- successful policy/memory poisoning = 0;
- lost required persistent memory = 0;
- incorrect stale-memory use in required tests = 0;
- broken provenance = 0;
- false completion caused by memory = 0;
- irrecoverable projection/index corruption = 0.

These are correctness goals. Performance promotion uses explicit measured thresholds rather than fictional "100%" claims.

## N2 implementation and promotion gates

### N2.0 — Contract and benchmark freeze

Before implementation:

- freeze canonical terminology and authority boundaries;
- define MemoryStore interface;
- freeze baseline corpus/private scenarios and hashes;
- freeze benchmark harness/model/prompt versions where practical;
- record installed ARM32 baseline plus ARM64 Builder target;
- define required correctness thresholds and performance regression budgets;
- complete source ownership/docs contracts;
- confirm retained `riftmemory-control.js` remains inactive.

**Exit:** reproducible pre-N2 baseline exists and N2 interfaces are frozen enough to implement without moving the goalposts.

### N2.1 — Canonical JSON model + evidence/event ledger

Implement:

- IDs/schema/version/scope/time/trust;
- evidence vs claims vs belief distinction;
- immutable/content-addressed evidence references;
- canonical event ledger;
- bi-temporal fields;
- reality/hypothesis/simulation branches;
- protected policy/config namespaces.

**Exit:** current/history reconstruction and provenance tests pass without specialist indexes.

### N2.2 — SQLite reference MemoryStore

Implement the replaceable storage interface with SQLite reference backend.

Prove:

- atomic canonical transaction;
- process-death recovery;
- integrity checks;
- snapshot/version metadata;
- content-addressed evidence;
- physical-format benchmark baseline.

**Exit:** SQLite is the control backend for all later comparisons.

### N2.3 — Reconciliation + trust + transaction engine

Implement:

- candidate-memory pipeline;
- conflict search;
- scope/authority classification;
- trust states;
- supersession/invalidation;
- COMMITTED/PROVISIONAL/QUARANTINED/REJECTED outcomes;
- projection dirty-state semantics.

**Exit:** contradictions and stale state do not silently overwrite canonical truth.

### N2.4 — Temporal/graph + current-state projections

Implement:

- valid/transaction time;
- entity/dependency graph;
- supersession/contradiction/provenance edges;
- exact current-state projection;
- point-in-time reconstruction.

All projections rebuild from canonical ledgers.

**Exit:** temporal and graph benchmark targets pass, including index deletion/rebuild.

### N2.5 — Episodic + consolidation + semantic engines

Implement reversible:

- observation -> episode;
- episode clustering;
- pattern extraction;
- semantic candidate creation.

**Exit:** consolidation improves frozen workloads without breaking provenance or increasing hallucination beyond target.

### N2.6 — Belief/reflection + predictive/difference engine

Implement:

- evidence/claim/belief/prediction/reflection separation;
- expected-vs-observed discrepancy objects;
- surprise/importance scoring;
- unresolved conflict states.

**Exit:** expectation mismatch creates explicit discrepancy and stronger observation can reconcile it without destructive overwrite.

### N2.7 — Skill + failure + causal + commitment + policy engines

Implement specialist lanes and lifecycle rules.

**Exit:** procedural transfer, failure recurrence, causal confidence, unfinished-task restart and protected-policy tests meet frozen targets.

### N2.8 — Router + hybrid retrieval + arbitration + Context Compiler

Implement:

- NO/FAST/DEEP/FORENSIC modes;
- exact/entity/project/temporal/graph/BM25/vector specialist indexes;
- evidence-weighted arbitration;
- minimal sufficient context compilation;
- token budgets and project isolation.

**Exit:** hybrid retrieval beats or equals the control on required accuracy while respecting latency/RAM/token budgets; similarity alone cannot override direct verified evidence.

### N2.9 — Observer/Validator closed loop

Wire existing Rift Observer/Validator evidence into:

```text
Planner -> Tools -> Observer -> Validator -> Difference -> Reconciliation -> Memory transaction
```

No Observer or Validator directly mutates trusted truth.

**Exit:** stale state, failed validation and false-completion scenarios reconcile correctly across restart.

### N2.10 — RiftStore storage competition

Build the smallest useful RiftStore prototype behind the exact MemoryStore interface.

Benchmark against SQLite on identical logical datasets and operations.

RiftStore can replace individual SQLite-backed responsibilities only when it produces a measured win without weakening crash consistency, integrity, maintainability or resource budgets.

**Exit:** either:
- RiftStore earns specific production responsibilities; or
- SQLite remains production backend and RiftStore findings are documented.

There is no requirement that custom storage must win.

### N2.11 — Integrity, poisoning, crash and scale hardening

Run:

- memory fsck;
- crash injection;
- snapshot/replay/rollback;
- index corruption/rebuild;
- poisoning/adversarial inputs;
- 10K/100K/1M/10M scale runs;
- cold restart;
- ARM32 resource stress;
- ARM64 parity.

**Exit:** all zero-tolerance correctness categories pass and resource limits remain bounded.

### N2.12 — External benchmarks, ablations and final promotion

Run public benchmarks where practical plus full private Rift suite.

Run specialist metrics, incremental hybrids and ablations.

Produce a final evidence table showing:

- baseline;
- every specialist's marginal value;
- complete federation result;
- SQLite vs RiftStore;
- latency/RAM/storage/token costs;
- weakest-link categories;
- regressions and removals.

**N2 promotes only if evidence earns the claim.**

## Hard N3 barrier

**N3 MUST NOT START until N2.12 is promoted.**

Architecture alone does not unblock N3.

A promising demo does not unblock N3.

Passing average benchmark scores does not unblock N3 if a required weakest-link category fails.

The N2 promotion record must show:

- canonical truth integrity;
- project isolation;
- provenance;
- reconciliation;
- crash/restart safety;
- poisoning resistance;
- procedural learning;
- projection rebuild;
- benchmark evidence;
- bounded performance on the real low-memory Android target.

Only then may N3 consume the Rift Memory Kernel as a trusted engineering-state dependency.

## Final objective

The target system continuously accumulates verified operational knowledge while preserving:

- evidence;
- historical truth;
- current truth;
- confidence;
- provenance;
- temporal validity;
- procedures;
- failures;
- causality;
- commitments;
- project state;
- predictions;
- contradictions;
- policy/authority.

It must survive restart, crash, projection/index loss, stale/conflicting state, project changes and malicious/untrusted memory input.

Its success criterion is not architectural novelty.

Its success criterion is measured engineering usefulness with stronger correctness, retrieval, self-correction and resource efficiency than the frozen baseline.

**Build incrementally. Benchmark every specialist. Benchmark combinations. Run ablations. Remove what does not earn its place.**
