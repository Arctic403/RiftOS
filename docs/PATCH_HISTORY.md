# RiftOS Patch History

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-18.**

This file records source-first implementation patches. It is not authority by itself: source code, Gradle packaging, manifest state, focused tests and direct audits outrank this history. Each entry describes what changed, where, why, how it works, what it affects, validation performed, limits/risks and rollback scope.

## Build-validation hotfix — verification marker date contract

Builder run `35371827181` for source `84c0a39c7f7e8e2edf27529b566460dd7ef8f087` passed source integrity and all code/wiring checks, then failed only because `scripts/validate-rift-docs.mjs` still hard-coded `2026-09-17` while the subsystems changed by Patches 1–5 had been correctly re-verified on `2026-09-18`.

The validator now checks verification-marker class plus a valid non-future ISO audit date instead of one global hard-coded date. This preserves per-subsystem verification history and removes the false requirement that untouched source must claim a newer audit date. No runtime, MCP, filesystem, Git, Local Agent or acceptance authority changed.

## Patch 5 — Semantic diff and Project Intelligence V2 impact mapping

### What changed

Added a candidate-bound semantic impact layer that reuses Project Intelligence V2 rather than trusting the patch author to declare affected APIs, callers, tests or documentation.

### Where

Primary source:
- `RiftSourceIntelligenceV2.kt`
- `RiftWorkspaceRecords.kt`
- `RiftToolSandbox.kt`
- `RiftToolHost.kt`

Validation:
- `scripts/test-rift-semantic-impact-v1.mjs`
- exact Gradle Kotlin source snapshot
- Workspace/MCP/Engine/build-validation documentation

### Why

Patch 4 proves exactly which bytes changed, but a physical diff cannot by itself determine which symbols, imports, callers, tests, documentation owners or build surfaces can be affected. The semantic layer must derive that scope independently from the frozen candidate rather than accepting a model-provided list.

### How

`RiftSourceIntelligenceV2` is the single lexical parser used by both normal PI-v2 indexing and before/after semantic comparison. The previous private symbol/dependency parser was removed from `RiftToolSandbox` so the two evidence paths cannot drift.

Workspace Records derives an internal semantic seed from the exact Patch Manifest V1 candidate. The seed includes exact changed-path identities plus bounded before/after source text. It is not an MCP method and cannot accept a caller-selected path scope.

PI-v2 then derives project roots from the changed paths, refreshes the current index, computes added/removed symbols, signature changes, added/removed dependencies, conservative API-surface changes, current dependencies, current dependents, changed-symbol references, relevant tests, documentation ownership from `docs/SOURCE_OWNERSHIP.md`, nearest README fallbacks, and global project docs. Build/config and documentation changes are classified separately.

The deterministic evidence payload is hashed as `semanticImpactSha256` using Patch Manifest canonical hashing. Cache-refresh diagnostics are attached after that hash and are not part of semantic identity.

The process-owned ToolHost exposes only an internal `candidateImpactAsync` seam for the future Local Agent. It is deliberately absent from `tools()`, aliases and MCP backend mappings.

### Bounds and completeness

Semantic seed:
- maximum 4096 changed paths;
- maximum 1024 changed source files;
- maximum 8 MiB combined before/after source text.

Impact:
- maximum 32 derived project roots;
- maximum 1000 changed symbol names in the output;
- maximum 80 changed symbols used for one-pass textual reference discovery;
- maximum 800 references;
- maximum 800 dependency rows;
- maximum 800 dependent rows;
- maximum 300 tests;
- maximum 300 documentation targets;
- maximum 128 changed source targets for test-affinity expansion.

Crossing a bound, missing before/after source text, a truncated PI index, or a truncated semantic delta adds an explicit incomplete reason. The system never silently calls partial impact evidence complete.

### Effects

Patch 5 can discover affected code/tests/docs from the candidate itself and bind that analysis to the candidate hash. It still cannot accept, deny, publish, advance trusted state or block existing workflows. Development mode remains OBSERVE.

The parser is intentionally a bounded lexical Project Intelligence layer, not a compiler AST or proof of correctness. Later validation patches must still require language/compiler/build/tests and may deny incomplete semantic evidence.

### Validation

Source audit verifies one shared parser owner, no returned private parser duplicate, exact candidate-derived seed, bounded incomplete semantics, deterministic impact hashing, ownership-ledger lookup, internal ToolHost routing, unchanged 18-tool MCP catalog, exact source declaration and focused-test wiring.

### Rollback

Remove `RiftSourceIntelligenceV2.kt`, restore the previous private PI-v2 parser in `RiftToolSandbox`, remove candidate-impact/semantic-seed integration, focused test/docs/source declaration, and leave Patch 4 physical manifest evidence intact.

## Patch 4 — Immutable candidate manifest and tamper-evident record chain

### What changed

Added deterministic candidate identity and tamper-evident evidence primitives without enabling patch blocking or trusted-state promotion.

### Where

Primary source:
- `RiftPatchManifestV1.kt`
- `RiftWorkspaceRecords.kt`

Validation:
- `scripts/test-rift-patch-manifest-v1.mjs`
- exact Gradle Kotlin source snapshot
- Workspace/Workspace Records/MCP/build-validation documentation

### Why

Patch Sessions identify writer provenance, but provenance alone does not bind an approval to exact bytes. Patch 4 creates a content-derived candidate identity and a forward hash chain so later validation stages can prove which base tree, result tree, structural changes and retained evidence were evaluated.

### How

The manifest uses deterministic canonical JSON and SHA-256. Tree identity is derived from sorted path/kind/size/content-SHA fields; mtimes and freeze timestamps are excluded. Change-set and structural-diff digests are separate. The sealed manifest contains the operational checkpoint identity, current tree identity, every changed path with before/after content identity, structural identity relations, retained patch-session evidence, current record-chain integrity and the current trusted-checkpoint state.

`freezeCandidate()` is internal-only. It writes a canonical manifest to the private records store under its SHA-256 filename. Existing identical manifests are verified byte-for-byte. No MCP tool exposes freeze or trusted promotion.

New event records are sealed with chain version, chain epoch, previous-record hash and record hash. Legacy pre-Patch-4 event files remain readable but are outside the new chain epoch. Pruning persists a verification anchor and pruned-through sequence before deleting old records. Startup may fast-forward a lagging persisted head only when the retained chain proves the old head is an ancestor; arbitrary mismatches are not silently healed.

Operational checkpoints now carry a recorder sequence. Provenance completeness is decided from checkpoint/pruning sequence boundaries rather than timestamp guesses. Trusted checkpoint fields exist as inert state only; no source path can promote them yet.

### Effects

`rift_workspace_diff` remains read-only but now has source support to report:
- operational checkpoint identity;
- separate trusted-checkpoint state;
- candidate manifest summary;
- record-chain integrity.

Candidate byte changes alter result-tree/change-set identity. Base changes alter base-tree identity. Evidence changes can also alter the manifest SHA, forcing later validation to re-evaluate rather than inherit stale approval.

### Bounds and risks

- maximum frozen manifests: 512;
- maximum one frozen manifest: 8 MiB;
- candidate workspace-path bound: 50000;
- event record retention remains 2000;
- pre-Patch-4 legacy records are not retroactively hash-chained;
- migration from an old operational checkpoint has unknown checkpoint sequence until a new checkpoint is created, so session-evidence completeness must remain false rather than guessed;
- trusted promotion is intentionally absent until the Local Agent policy gate exists.

### Validation

Static source/impact audit verifies deterministic hashing, immutable-by-hash storage, chain sealing/verification, sequence-based pruning evidence, crash-safe head recovery, no MCP freeze mapping, exact Gradle source declaration and focused-test wiring. Android/Gradle compile and device proof remain required before installed-runtime promotion.

### Rollback

Patch 4 can be reverted by removing `RiftPatchManifestV1.kt`, its Workspace Records integration, test/docs/source declaration and private-manifest/chain state readers. Existing private manifest/event files are non-workspace metadata and must not be treated as workspace source during rollback.

## Patch 3 — Patch sessions and provenance

### What changed

Added bounded writer provenance that correlates MCP, native Shell, native Editor, Dev Lab and native Git mutations with asynchronous Workspace Records events.

### Where

Primary source:
- `RiftPatchSessions.kt`
- `RiftWorkspaceRecords.kt`
- writer integrations in `RiftToolSandbox.kt`, `RiftNativeShell.kt`, `RiftNativeWorkspaceApps.kt`, `RiftNativeDevLab.kt`, `RiftNativeGit.kt`
- optional `intent` schema field in `RiftToolHost.kt`

Validation:
- `scripts/test-rift-patch-sessions.mjs`

### Why

FileObserver delivery occurs after a writer may have returned. A simple thread-local or “last writer” value could falsely attribute an unrelated later change.

### How

Writers declare bounded target paths before mutation, capture before-state, and commit short-lived claims after successful mutation. Exact file/deletion claims are correlated against resulting state and SHA-256. Directory replacement flows are explicitly lower-confidence scope claims. Claims expire after 15 seconds and are bounded. Unknown writers are recorded as `unattributed-local`; provenance is never guessed.

Only explicit `workspace/...` and `D:/Workspace/...` forms enter workspace provenance, preventing unrelated RiftFS paths from stealing workspace events.

### Effects

Workspace Records events now contain `patchId` and structured provenance. Dev Lab and Git receipts may surface patch IDs. MCP tool count remains 18. The optional intent field is evidence only and does not change permission classification.

### Bounds and risks

- 512 paths per provenance session;
- 4096 active claims;
- 15-second claim lifetime;
- intent <=500 characters;
- origin/operation/request labels <=120 characters;
- scope-bound directory claims are not cryptographic authorship proof.

### Validation

Source-first impact audit verified all five writer integrations, exact 43-file Kotlin snapshot at Patch 3 freeze, test/package ownership, strict workspace-only ingress, honest unattributed fallback and unchanged 18-tool MCP catalog.

### Rollback

Remove `RiftPatchSessions.kt`, writer hooks, Workspace Records provenance fields, optional intent schema and focused test/docs. Filesystem behavior itself remains owned by the original writer subsystems.

## Patch 2 — File Identity V2

### What changed

Added bounded rename/copy/rewrite identity correlation to Workspace Records.

### Where

Primary source:
- `RiftFileIdentityV2.kt`
- `RiftWorkspaceRecords.kt`
- relation-aware headers in `RiftDiffEngineV2.kt`

Validation:
- `scripts/test-rift-file-identity-v2.mjs`

### Why

Delete+add records could not distinguish structural movement/copy from unrelated file creation, and large rewrites were reported as ordinary modifications.

### How

Exact SHA-256 equality provides exact content-identity evidence for rename/copy candidates. Remaining text candidates use bounded size-prefiltered line similarity. Full reconciliation is limited to 64 candidates per side and 1024 line comparisons. Heuristic results are explicitly marked non-exact and never treated as user intent.

### Effects

Workspace diff/records can surface renamed, copied and rewritten relationships while preserving raw before/after evidence. No mutation or approval authority was added.

### Bounds and risks

Heuristic matching can remain incomplete when the comparison budget is exhausted. Exact content identity does not prove why a user moved or copied a file.

### Validation

Focused source test locks exact-vs-heuristic semantics, bounds, relation-aware Workspace Records wiring and documentation ownership.

### Rollback

Remove `RiftFileIdentityV2.kt` and relation wiring; Workspace Records falls back to independent path changes.

## Patch 1 — Diff Engine V2

### What changed

Replaced Workspace Records' one-middle-block text diff with a deterministic bounded multi-hunk engine.

### Where

Primary source:
- `RiftDiffEngineV2.kt`
- `RiftWorkspaceRecords.kt`

Validation:
- `scripts/test-rift-diff-engine-v2.mjs`

### Why

The previous prefix/suffix algorithm collapsed widely separated edits into one giant replacement and could obscure independent changes.

### How

Normal regions use exact LCS with the actually allocated `(n+1) x (m+1)` matrix capped at 250000 cells. Larger regions use patience-style unique-line anchors with longest-increasing-subsequence ordering and bounded recursion. Huge ambiguous regions fall back to replacement blocks rather than unbounded quadratic allocation.

### Effects

Widely separated edits produce independent hunks. Empty-file creation/deletion and byte-only line-ending changes remain visible. Binary/oversized files remain hash/metadata evidence.

### Bounds and risks

Rendered output remains capped at 64000 characters and 420 changed lines. Large ambiguous regions may intentionally use a non-minimal replacement fallback to protect low-memory Android devices.

### Validation

Focused source test locks algorithm bounds, multi-hunk structure, Workspace Records delegation, source declaration and docs ownership.

### Rollback

Remove `RiftDiffEngineV2.kt` and restore the prior Workspace Records text-diff routine, with the known loss of independent-hunk behavior.


## 2026-09-18 — Rift++ 0.8.0 Gate 1A scalable storage/view candidate

### What changed

- advanced the active Rift++ bootstrap compiler implementation to `0.8.0-bootstrap` while keeping source language `riftpp/1`;
- added persistent `Buffer<T,N>` with capacity up to 100000;
- added read-only zero-copy `Slice<T>` views;
- preserved frozen `Vec<T,N>` capacity/behavior at <=256;
- added RiftVM Buffer/Slice opcodes and persistent 32-way trie Buffer storage;
- Buffer/Slice remain data-only composites and cannot cross generic host-import boundaries;
- Buffer/Slice checkpoint persistence is explicitly denied;
- added `rift-tool semantic-compat` for ongoing frozen-semantic regression checks while leaving `gate0-verify` as the archival exact-reference/drift check;
- updated focused Core/VM/shell tests and strict wiring validation.

### Why

The self-hosted compiler needs token, AST and instruction storage far beyond Vec-256. A separate scalable persistent storage primitive preserves old Vec semantics while providing compiler-scale indexed storage without prematurely introducing pointer/ownership semantics.

Immutable Buffer versions make zero-copy Slice views safe: a Slice references one Buffer version and does not change when later Buffer updates produce a new version.

### Verified before this record

- direct 600-item Buffer/Slice execution passed with stable-view semantics;
- 20000-item Buffer stress passed at 520033 VM steps under the existing 1000000-step VM hard ceiling;
- ordinary compiled 100000-step budget rejected that stress workload as expected;
- full frozen Rift++ semantic compatibility suite passed after Buffer and after Slice;
- RiftLLM+ regression compile remained green;
- RiftOS audit/scan found no new Gate 1A issue beyond the pre-existing RiftSecretStore filename heuristic.

### Remaining promotion gate

Builder/APK/device proof remains required. After installation, run `rift-tool semantic-compat`, the Gate 1A functional fixture, `riftpp self-test`, and host-boundary checks before freezing Gate 1A.


## 2026-09-18 — Rift++ 0.9.0 Gate 1B text/numeric candidate

### What changed

- advanced active compiler implementation to `0.9.0-bootstrap` while retaining source language `riftpp/1` and `rift-exec-v1 / riftvm-1`;
- added `SourceText`, `TextCursor`, persistent `StringBuilder<N>`, numeric text parse/format;
- selected UTF-16 code units for the hot SourceText/cursor/builder representation;
- kept UTF-8 explicit at file/token/provenance/interchange boundaries through on-demand byte accounting;
- preserved frozen compatibility-string UTF-16 code-unit semantics;
- denied SourceText/TextCursor/StringBuilder checkpoint persistence and SourceText hashing through `value_sha256`;
- added independent Core and VM tests;
- added fixed `rift-tool text-model-benchmark` so the installed-device UTF-16/UTF-8 representation costs are recorded under a named benchmark instead of relying on an unpreserved historical multiplier.

### Verified source-side

- UTF-16/code-unit functional Gate 1B program PASS;
- edge/half-surrogate program PASS;
- 70,000-code-unit builder PASS;
- numeric parse/format positive/negative behavior PASS;
- full frozen semantic compatibility suite PASS;
- RiftLLM+ consumer regression compile PASS;
- no language/VM ABI version bump required because changes are additive to valid `riftpp/1` source.

### Promotion boundary

Gate 1B remains **not frozen** until Builder/install/device proof runs `semantic-compat`, exact Gate 1B fixtures, `text-model-benchmark`, self-test, authority-boundary regression and final audit.


## 2026-09-18 — Rift++ Core README maintenance-contract repair

Builder run `35389192989` for source `3c4ed2756ff9874fd011db0b8d22c295109c3efe` failed in `validate-rift-docs.mjs` because `docs/systems/riftpp-core/README.md` was missing the required `## Failure signatures` maintenance heading.

The Gate 1B compiler/runtime code was not the failing gate. The README now restores the required maintenance section with failure-routing signatures derived from the current Core/VM/tooling contracts. The validator itself was not weakened.
