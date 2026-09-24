# Workspace Records

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**

## Purpose

Workspace Records is RiftOS's persistent, observational change recorder for the canonical workspace.

It records local workspace changes, maintains an explicit checkpoint baseline, exposes bounded diffs to the native UI and MCP, and never acts as an approval/deny/rollback gate.

## Source ownership

Live:
- `RiftWorkspaceRecords.kt` — process-wide record/checkpoint state and Diff Engine V2 adapter.
- `RiftDiffEngineV2.kt` — Android-framework-independent adaptive exact-LCS / patience-style multi-hunk engine.
- `RiftFileIdentityV2.kt` — bounded deterministic rename/copy/rewrite correlation and similarity evidence.
- `RiftPatchSessions.kt` — process-local writer provenance claims used to correlate asynchronous filesystem observations without granting approval authority.
- `RiftPatchManifestV1.kt` — deterministic tree/change-set/canonical-manifest hashing, private immutable freeze and event-record hash-chain primitives.
- `RiftSourceIntelligenceV2.kt` — shared Project Intelligence V2 lexical analyzer used when Workspace Records emits a candidate semantic-impact seed.
- `RiftWorkspaceWatcher.kt` — recursive `FileObserver` tree.
- `RiftNativeWorkspaceApps.kt` — native Workspace Records window.
- `RiftToolSandbox.kt` — read-only `workspace.diff` dispatch.
- `RiftNativeGit.kt` — checkpoint advancement after applicable Git pull/push operations.
- `MainActivity.kt` — watcher lifecycle.

Retained only:
- `workspace-live/*`
- `src/riftworkspace-live-host.js`

## Roots

Observed workspace:
`<filesDir>/riftfs/workspace`

Private records store:
`<filesDir>/rift-workspace-records`

Private subtrees:
- events/
- observed/
- checkpoint/
- manifests/
- state.json

Records state is deliberately outside the workspace so normal project/MCP tools cannot rewrite their own history.

## Process model

`RiftWorkspaceRecords.get(context)` is a process singleton.

It owns one serialized scheduled executor. Query/checkpoint work has a 30-second bounded Future lifecycle with cancellation on timeout, and watcher-scheduled work runs under the same cooperative 30-second deadline.

MainActivity creates a `RiftWorkspaceWatcher`, starts its tree installation on a daemon background thread during boot, and shuts the watcher down during Activity destruction. Watcher startup therefore does not synchronously walk the workspace on the Android UI thread.

The records singleton itself remains process-owned; Android process death reconstructs it from private state.

## Initial state

On first initialization:
- if no observed/checkpoint state exists, current workspace files seed both observed and checkpoint snapshots;
- otherwise state is loaded and the current tree is reconciled against observed state.

Initial seed therefore does not report every pre-existing file as a change.

## Observation

Watcher installs Android FileObservers under the canonical workspace only using an iterative queue. Installation is capped at 2048 watched directories and a 2-second installation budget; duplicate registration uses `putIfAbsent` so concurrent directory events cannot create duplicate observers.

Events include create/delete/modify/move/write/attrib/self-delete/self-move.

Ordinary file events settle for 220 ms before capture.

The recorder keeps at most 512 distinct pending path-debounce entries. A larger event storm cancels the per-path backlog and coalesces it into one full-tree `watch:burst` reconciliation.

Directory and self/move events schedule a full-tree reconciliation after 420 ms.

New directories receive recursive observers.

Watcher paths are canonicalized and rejected when outside the workspace.

## Reconciliation

Workspace Records now treats the watcher as the fast current-state path. When recursive watcher coverage is complete, reads flush pending path captures and do not perform periodic full-tree reconciliation. If watcher coverage is inactive/incomplete, ordinary queries fall back to a full reconciliation after 60 seconds; proof/freeze reads require a current fallback reconciliation immediately. Full-tree reconciliation also remains mandatory for queued tree-level events.

Unchanged files whose size and mtime still match observed metadata avoid a full hash on every reconciliation.

Changed candidates are snapshotted and SHA-256 compared before a record is created.

## Snapshots

Text snapshot limit: 1 MiB.

Files above the limit or recognized as binary are tracked by metadata/hash only.

Observed text snapshots support per-event diffs.

Checkpoint text snapshots support changed-since-baseline diffs.

Snapshot paths are canonicalized beneath the private observed/checkpoint roots.

Snapshot writes use temporary-file + rename publication.

## Event records

Workspace Records keeps a rolling maximum of 256 recent event JSON records for local diagnostics and record-chain verification. Long-term source history remains RiftGit authority. Active candidate patch/session provenance is maintained separately in bounded persisted state, so semantic-impact reads do not replay the event history to reconstruct the current candidate. Tracking policy v2 excludes the same generated/cache directory families used by Project Intelligence and performs a one-time operational rebaseline when upgrading from the old all-files policy.

Each event record contains:
- format;
- sequence/id;
- patchId;
- structured provenance (`origin`, `operation`, optional intent/request id, attribution confidence and before/after claim state);
- timestamp;
- workspace-relative path;
- action;
- source;
- before/after metadata;
- whether text diff is available;
- bounded diff.

Actions:
- created;
- deleted;
- type-changed;
- modified;
- renamed;
- copied;
- rewritten.

Identity-aware records also carry a bounded `identity` object containing version, relation kind, source/destination paths, similarity score, method and whether the relation is exact.

Event JSON is written atomically.

Maximum stored event records: 2000; older files are pruned.

## Patch Session V1 provenance

`RiftPatchSessions` exists because FileObserver delivery is asynchronous: the writer often finishes before Workspace Records captures the final file state. Writers therefore declare intended workspace paths before mutation and commit a short-lived claim after success.

Exact file/deletion claims are matched against the observed resulting SHA/existence state and are recorded with `confidence=state-bound`. Directory replacement flows such as native Git import use `confidence=scope-bound`, which is deliberately weaker and must not be treated as cryptographic authorship. Claims expire after 15 seconds and the active claim set is bounded.

Current explicit writer origins are `mcp`, `native-shell`, `native-editor`, `devlab`, and `native-git`. Claim ingress accepts only explicit `workspace/...` or `D:/Workspace/...` path forms; other RiftFS paths cannot be misclassified as Workspace provenance. A mutation with no valid claim is never guessed: Workspace Records emits a unique `unattributed-*` patchId with `origin=unattributed-local`, `attributed=false`, and `confidence=none`.

Provenance is observational evidence only. It does not approve a patch, grant a capability, change MCP permissions, advance a trusted checkpoint or make OBSERVE blocking.

## Patch Manifest V1 and tamper evidence

Patch 4 adds deterministic candidate identity without enabling enforcement.

Candidate manifests bind:
- the operational checkpoint kind/time/sequence/Git metadata and deterministic base-tree SHA-256;
- deterministic current-result tree SHA-256;
- every changed path with status and before/after kind/size/content SHA-256;
- a separate change-set SHA-256;
- a structural-diff SHA-256 over changes plus File Identity V2 relations;
- retained patch-session evidence and whether that session evidence is complete;
- current record-chain integrity;
- current trusted-checkpoint state.

Tree/change-set hashes use content identity, not mtime. Volatile generation/freeze timestamps are intentionally excluded from the hashed manifest so identical stable inputs produce the same manifest identity.

`freezeCandidate()` is an internal Workspace Records API for the future Local Agent gate. It is not mapped through MCP. Frozen canonical manifests live under private `manifests/` using their 64-hex SHA-256 as filename. Existing same-hash files must match byte-for-byte. One manifest is limited to 8 MiB and the store is capped at 512 manifests; reaching either bound fails the freeze rather than silently dropping evidence.

Candidate workspace path count is capped at 50000 before a full manifest can be sealed.

### Event record chain

New Patch-4-era event records carry chain version/epoch, previous-record hash and record hash. Legacy records from before the chain epoch remain readable but are not retroactively rewritten or claimed as tamper-evident.

Pruning records the exact last-pruned sequence/time and, when applicable, the immediate predecessor hash. The updated anchor is persisted before old files are deleted. Chain verification skips rows at or below the persisted pruning boundary.

On startup, a lagging persisted chain head can fast-forward only when the retained valid chain proves that old head was an ancestor. A missing/modified unrelated head is not silently adopted.

### Operational versus trusted checkpoint

Existing Git/manual baselines are now explicitly `kind=operational` and carry recorder sequence. Separate trusted-checkpoint fields exist but have no promotion API and remain absent until a later Local Agent gate is implemented. Patch 4 therefore cannot convert an operational checkpoint into trusted state.

Patch-session completeness is sequence-based: a manifest can only call retained session evidence complete when the operational checkpoint sequence is known and all pruned records are at or before that checkpoint. Migrated old checkpoints with no sequence remain incomplete instead of being guessed complete.

## Semantic impact seed

Patch 5 adds an internal `semanticImpactSeed()` derived from the same live Patch Manifest V1 candidate. It never accepts a caller-selected path list. The seed carries exact changed-path identity and, only for source files, bounded checkpoint/current text needed for before/after semantic comparison.

Bounds are 4096 changed rows, 1024 source files and 8 MiB combined before/after source text. If a source snapshot was binary/oversized, text was pruned/unavailable, or a working-set bound is exceeded, the seed records an omission reason and `complete=false`. This evidence is consumed by PI-v2 through the internal Local Agent path and is not exposed as a new MCP tool.

## Diff Engine V2 and bounds

Text diffs are produced by `RiftDiffEngineV2`, not by the recorder itself.

The engine is deterministic and emits independent unified-style hunks with three context lines. It normalizes CRLF/CR to LF for comparison and uses an adaptive strategy:
- exact LCS for bounded regions up to 250000 matrix cells;
- patience-style unique-line anchors plus longest-increasing-subsequence ordering for larger regions;
- recursive bounded sub-diffs around those anchors;
- replacement-block fallback for huge ambiguous regions with no safe unique anchors or after the recursion bound.

The exact-matrix threshold applies to the actually allocated `(n+1) x (m+1)` matrix, preventing unbounded quadratic memory use on low-RAM Android devices while still giving minimal LCS behavior to normal edits. Widely separated edits therefore remain separate hunks instead of collapsing the entire middle of a file into one replacement. Empty-file creation/deletion and byte-only changes such as line-ending normalization remain visible even when the normalized line delta is zero.

Rendered diff bounds remain:
- maximum 64000 characters;
- maximum changed lines represented: 420 across all hunks;
- three context lines around represented change groups.

Text output identifies `Rift-Diff-Version: 2` and the adaptive strategy. Rename/copy evidence can supply distinct before/after paths so diff headers preserve structural movement. Binary/oversized changes remain metadata/hash summaries rather than file contents.

## File Identity V2

`RiftFileIdentityV2` adds structural identity evidence without turning Workspace Records into mutation authority.

Exact SHA-256 matches are authoritative **content identity evidence, not proof of user intent**:
- a removed path plus a newly-added path with the same SHA can be correlated as an exact rename;
- a newly-added path matching an unchanged surviving source can be correlated as an exact copy.

Unmatched text candidates use a deterministic bounded heuristic:
- maximum 64 candidates per side;
- maximum 1024 line-similarity comparisons total during a full correlation/reconciliation pass;
- a cheap size-similarity prefilter runs before line comparison;
- rename threshold: 60%;
- same-path major-rewrite threshold: 25% or lower;
- major-rewrite classification is disabled below 512 bytes;
- similarity is a weighted normalized-line multiset Dice score plus size similarity.

Heuristic relations are marked `exact=false` and method `bounded-line-dice`; consumers must not treat them as cryptographic proof. Exact relations are marked `exact=true` and method `sha256`. If candidate/comparison bounds are reached, the query exposes `similaritySkipped=true` rather than pretending correlation was exhaustive.

## Query

`query(args)` is read-only.

Path filter accepts:
- blank / full workspace;
- project-relative path such as `RiftOS-main/src`;
- normal MCP-prefixed form `workspace/RiftOS-main/src`.

A leading `workspace/` is normalized away. `..` is rejected.

Query bounds:
- requested recent-record limit clamped to 1..250;
- changed-file rows capped at 500;
- identity relations capped at the same 250-row query bound;
- combined raw query payload budget: 96000 characters across files, records and identity relations;
- diffs are omitted first when needed;
- omitted file/record/relation counts and `responseTruncated` are reported.

The response also includes `identity.version`, `similarityComparisons`, `similaritySkipped` and bounded `identity.relations` computed between checkpoint and observed state. The existing `files` array is retained for compatibility; identity relations are additional structural evidence rather than replacements for raw path changes.

## MCP

`RiftToolSandbox` maps the ToolHost read-only `rift_workspace_diff` tool to:

`workspaceRecords.query(args)`

It does not expose checkpoint mutation.

ToolHost classifies `rift_workspace_diff` as read-only.

## Native UI

The Android-native Workspace Records window:
- refreshes on explicit user action/open;
- queries at most 80 recent records;
- requests `includeDiff=false` for compact display;
- displays changed files and recent events.

During this audit the UI was fixed to read the actual `action` field, so records show created/modified/deleted/type-changed instead of defaulting to a generic “change”.

There is no approve/deny/revert/checkpoint button in this window.

## Checkpoints

`checkpoint(args)`:
1. reconciles current state;
2. replaces the checkpoint map with current observed state;
3. rebuilds checkpoint text snapshots;
4. stores checkpoint timestamp/reason;
5. optionally stores Git root/head metadata;
6. persists state atomically.

Checkpoint changes the **comparison baseline**, not workspace content.

## Git integration

Native Git advances the records baseline for applicable workspace operations:
- after successful Git push: reason `git:push`;
- after successful Git pull/project replacement: reason `git:pull`.

Only display roots equal to `/workspace` or beneath `/workspace/` are accepted for this checkpoint hook.

Git root and head SHA are stored as checkpoint metadata.

## Persistence

state.json contains:
- sequence;
- checkpoint metadata;
- observed entry map;
- checkpoint entry map.

State writes use temp-file + rename.

Event files and text snapshots are private app files.

## Non-ownership boundaries

Workspace Records does not own:
- workspace mutation;
- patch application;
- approval/denial;
- rollback;
- Git remote operations;
- MCP permission policy.

It observes and reports.

## Source fixes in this audit

- Patch 4 introduced deterministic candidate manifests, private immutable-by-hash freeze support, forward event hash chaining, pruning anchors, sequence-based provenance completeness and inert trusted-checkpoint state without adding enforcement;
- Patch 3 introduced state-bound patch-session provenance, explicit writer origins and honest `unattributed-local` fallback without adding approval authority;
- Patch 2 introduced `RiftFileIdentityV2`, exact SHA rename/copy correlation, bounded heuristic rename/rewrite evidence, relation-aware diff headers and checkpoint-query identity summaries;
- Patch 1 introduced `RiftDiffEngineV2` and removed the recorder's legacy single-prefix/suffix middle-block diff implementation;
- separate edits now produce independent bounded hunks while large ambiguous files avoid unbounded exact-LCS allocation;
- native Workspace Records UI now reads the real `action` field;
- query path filters now accept the standard `workspace/...` MCP prefix while remaining workspace-relative internally;
- historical HTML/shadow-root UI claims were purged from the retained workspace-live README during the parent Workspace audit.

## Critical invariants

- records state stays outside workspace;
- recorder never mutates workspace content;
- no approval/deny/rollback gate;
- watcher cannot escape workspace;
- text snapshot <=1 MiB;
- event store <=2000 files;
- query recent-record limit <=250;
- raw query payload <=96000 chars;
- diff <=64000 chars / 420 changed lines;
- exact LCS allocation is bounded to 250000 matrix cells and larger regions use patience-style anchors/fallback;
- text diff output is deterministic Rift Diff V2, preserves independent change hunks, and reports existence/byte-only changes even when normalized lines are equal;
- exact rename/copy relations require SHA-256 identity; heuristic identity is explicitly non-exact;
- full-reconciliation similarity work is bounded to 64 candidates per side and 1024 line comparisons, with incompleteness exposed; reconciliation records reuse those budgeted results instead of rescoring every file;
- identity evidence never claims user intent or grants mutation/approval authority;
- provenance claims expire after 15 seconds, exact claims require resulting-state agreement, directory claims are labeled lower-confidence, and unknown writers remain unattributed;
- patchId/provenance are evidence only and cannot approve, authorize or advance trust;
- candidate identity excludes volatile generation/freeze time and binds deterministic base/result trees, change set, structural relations and evidence context;
- frozen manifests are private, SHA-addressed, <=8 MiB each and capped at 512;
- new event records are hash-chained; legacy pre-chain records are not falsely upgraded;
- chain pruning persists sequence/time/anchor before deletion and startup recovery only fast-forwards a proven ancestor head;
- operational and trusted checkpoint state are distinct; no trusted promotion API exists yet;
- checkpoint only advances operational baseline;
- MCP access is query/read-only;
- Git checkpoint occurs only after successful workspace Git operations.

## Failure signatures

- recent events all show “change” -> native UI action-field regression;
- `workspace/RiftOS-main` filter returns no data while relative form works -> prefix normalization regression;
- records files appear under workspace -> integrity regression;
- MCP can call checkpoint -> authority regression;
- query returns unbounded records/diffs -> bound regression;
- distant edits collapse into one giant middle replacement despite stable intervening lines -> Diff Engine V2 regression;
- large ambiguous text allocates an unbounded quadratic matrix -> low-memory safety regression;
- heuristic correlation exceeds 64 candidates per side / 1024 line comparisons -> identity bound regression;
- heuristic rename is exposed as exact or treated as proof of user intent -> evidence-semantics regression;
- exact copy/rename SHA identity is omitted from checkpoint relation evidence -> identity regression;
- manifest identity changes across identical stable inputs because a volatile timestamp entered the hash -> determinism regression;
- a frozen manifest filename/hash does not match canonical bytes -> evidence-integrity failure;
- record-chain mismatch is silently healed without proving the persisted head was an ancestor -> tamper-evidence regression;
- trusted checkpoint becomes present without a later gate-owned promotion path -> authority regression;
- changed workspace content disappears because recorder altered files -> ownership violation;
- watcher accepts path outside workspace -> containment failure.

## Fix map

Persistence/checkpoint/query -> `RiftWorkspaceRecords.kt`.

Text diff computation -> `RiftDiffEngineV2.kt`.

Rename/copy/rewrite identity evidence -> `RiftFileIdentityV2.kt`.

Writer-session provenance -> `RiftPatchSessions.kt` plus the explicit writer call sites.

Candidate manifest/hash-chain primitives -> `RiftPatchManifestV1.kt`.

Filesystem events -> `RiftWorkspaceWatcher.kt`.

Native display -> `RiftNativeWorkspaceApps.kt`.

MCP mapping -> `RiftToolSandbox.kt` / `RiftToolHost.kt`.

Git baseline hooks -> `RiftNativeGit.kt`.

## Validation

Second source audit must verify roots, observer containment/lifecycle, event settlement/reconciliation, Diff Engine V2 multi-hunk behavior and 250000-cell exact-LCS bound, File Identity V2 exact/heuristic semantics and 64/1024 bounds, Patch Session V1 attribution/fallback, Patch Manifest V1 deterministic tree/change-set/canonical hashes, 8 MiB/512 freeze bounds, record-chain verification/pruning anchor/crash recovery, sequence-based session completeness, operational-vs-trusted checkpoint distinction, relation-aware path headers, text/diff/event/query bounds, atomic private writes, query prefix normalization, read-only MCP mapping, native UI action field, Git push/pull checkpoint call sites, and absence of trusted promotion/approval/rollback APIs.

Device testing should mutate files through Editor, MCP, Shell, Git and Dev Lab and confirm one consistent record stream and expected checkpoint changes.
