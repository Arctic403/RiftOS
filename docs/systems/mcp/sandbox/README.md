# RiftToolSandbox and Rift Code Mode

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-20.**

## Purpose

`RiftToolSandbox` is the workspace-only execution engine behind normal Rift MCP filesystem tools, project inspection and Rift Code Mode / Project Intelligence v2.

It owns canonical workspace containment, single-operation filesystem methods, bounded search/read results, per-call transaction/rollback safety, snapshots, project intelligence and archive safety. Model-facing `rift_workspace_exec` is hard-limited by `RiftToolHost` to exactly one operation per call; multi-op/batch execution is fail-fast disabled.

## Source ownership

Primary:
- `RiftToolSandbox.kt`
- `RiftBoundedAsync.kt` — 45-second request deadline and cooperative cancellation

Related narrow owners:
- `RiftToolHost.kt` — schemas/grants/aliases;
- `RiftProjectExporter.kt` — paged whole-project export;
- `RiftWorkspaceRecords.kt` — persistent change records/checkpoint state;
- `RiftPatchSessions.kt` — bounded mutation provenance claims consumed asynchronously by Workspace Records.

## Canonical root

Physical authority is exactly:

`filesDir/riftfs/workspace`

Tool paths are represented as `workspace/...`.

Legacy `tool-sandbox/workspace` and `browser-sandbox/workspace` are one-time migration inputs only. They are never addressable roots after migration.

Old workspace scaffold metadata under `.rift` migrates into app-private system metadata; empty legacy scaffold directories can be removed.

## Core bounds

- ordinary tool file/payload bound: 8 MiB;
- ordinary list: 5000 entries;
- internal Code Mode operation-array ceiling: 192; model-facing `rift_workspace_exec` is currently capped to exactly 1 operation by `RiftToolHost`;
- Code Mode result byte budget: 700 KiB;
- bounded workspace read: 240000 chars;
- search matches: 300;
- total search scan budget: 64 MiB;
- directory-hash content budget: 256 MiB;
- project-index refresh source budget: 128 MiB;
- rollback snapshot: 64 MiB and 50000 entries;
- patch edits: 96;
- Code Mode list entries: 1200;
- searchable/indexed file max: 2 MiB;
- rollback backup set: 64 MiB;
- search preview: 320 chars;
- symbol results: 240;
- reference results: 400;
- index files scanned: 25000;
- graph edges: 600;
- persisted index rows: 4000;
- persisted index file: 8 MiB;
- hunks: 128;
- snapshot files: 50000;
- archive entries: 50000;
- archive source: 256 MiB;
- archive expansion: 512 MiB.

## Single-operation methods

The sandbox dispatcher implements:
- info;
- stat/hash/list/read/write;
- mkdir/remove/move/copy;
- archive/extract;
- workspace audit/scan;
- project export;
- workspace diff;
- workspace exec.

All paths are canonicalized beneath the workspace root.

## Code Mode operation family

`workspace.exec` supports:
- project;
- snapshot;
- stat;
- hash;
- list;
- search;
- symbols;
- references;
- read;
- read_range;
- read_symbol;
- write;
- replace;
- patch;
- patch_range;
- apply_hunks;
- mkdir;
- remove;
- move;
- rename;
- copy;
- archive;
- extract.

Both canonical flat operations and the same unambiguous shorthand normalization recognized by ToolHost are accepted.

## Snapshot guards

Before each `rift_workspace_exec` call:
- `expectedSnapshot` can require an exact scoped project snapshot;
- `expectedExportSnapshot` can require the current RiftProjectExporter snapshot id.

A mismatch rejects the call before mutation.

`returnSnapshot` optionally returns a fresh scoped snapshot after the one-operation call.

## Transactions

Every `workspace.exec` call creates a private cache transaction directory. Public ToolHost requests contain exactly one operation, so this transaction currently protects one visible operation at a time.

For a mutating operation:
- write/replace/patch/patch_range/apply_hunks/mkdir/remove capture `path`;
- move/rename capture both `from` and `to`;
- copy/archive/extract capture `to`.

A path is captured only once, and a child capture is skipped when an ancestor is already snapshotted.

Existing files/directories are copied into the rollback store before mutation. Total rollback material is capped at 64 MiB.

If an operation throws:
1. every captured target is cleared;
2. original file/directory/missing state is restored;
3. index invalidation follows restoration;
4. rollback errors are appended to the surfaced operation error;
5. transaction staging is removed.

The code never silently claims atomicity when rollback itself failed.

## Dry-run

Dry-run allows reads and content-edit operations whose effects can be restored through the transaction.

Structural mkdir/remove/move/rename/copy/archive/extract are rejected in dry-run mode.

After successful dry-run execution the transaction is rolled back; rollback failure is surfaced as an error.

Response reports `committed=false`.

## Patch-session provenance

Before a mutating MCP filesystem call or non-dry-run `workspace.exec` call, the sandbox derives the mutation target set from the single normalized operation and opens one `RiftPatchSessions` claim with `origin=mcp`. The MCP request id is retained and `rift_workspace_exec` may carry a bounded optional `intent` string. Intent is evidence only; it never changes permission classification.

After successful mutation the claim is committed against resulting file states. On failure it is aborted. If later filesystem observation cannot correlate the claim, Workspace Records records the event as `unattributed-local` rather than trusting stale metadata.

Dry-run calls create no patch-session claim because their temporary mutation is rolled back by definition.

## Change summary

Before/after snapshots generate bounded changed-file metadata.

For small text files the transaction computes added/removed line deltas. Changed files may include post-change SHA-256 when within ordinary tool bounds.

This is a per-call change summary, not a permanent AI-session journal.

## Result bounding

Each `rift_workspace_exec` result is counted against the 700 KiB Code Mode result budget.

When the budget is exceeded, execution continues but the affected operation receives a compact `resultOmitted=true` summary rather than returning unbounded content.

## Project Intelligence v2

Persistent cache:
`filesDir/rift-project-intelligence-v2.json`

The cache:
- version-checks at load;
- max file size 8 MiB;
- keeps at most 4000 persisted indexed files;
- is advisory;
- is validated against current file mtime/size;
- can be deleted/rebuilt safely;
- is persisted through a temporary file + rename;
- is outside the user workspace.

Live indexing scans at most 25000 source files, skips ignored/generated/binary directories/files, and indexes source files no larger than 2 MiB.

Ignored directories include common build/cache/vendor trees plus `.vortex-bridge`.

## Project views

The existing `project` operation provides bounded:
- graph;
- impact;
- validation;
- consistency views.

Graph resolves project dependency evidence without guessing unresolved system includes into fake local edges.

Impact combines definitions/references/dependencies/dependents/docs/tests.

The N1.8.0 `consistency` view is an evidence-only foundation derived from the existing PI-v2 graph. It does not rescan source or create a second symbol/dependency index. Public `project kind=graph` remains human-preview bounded at 120 matched files and 600 dependency edges, while consistency reuses the same internal PI-v2 builder with observer-only 1024-file/1024-edge input bounds so the current repository can be covered completely without enlarging the public graph response. It emits stable fact/edge identities, separate content hashes, a deterministic repository graph hash, explicit completeness/bounds, and a verified rebuildable private cache under `filesDir/rift-repository-consistency-v1`. The default response is compact so a whole-repository proof does not exceed Code Mode's 700 KiB result budget; it returns graph identity, completeness, counts, cache verification and bounded fact/edge/finding previews. `query=full` explicitly requests the complete arrays and is intended for smaller scopes/debugging. N1.8.0 only establishes graph/schema/hash/cache invariants; syntax/import correctness findings and documentation claims belong to later N1.8 gates.

Patch 5 extracts source parsing into `RiftSourceIntelligenceV2`, which is now the single parser used by both the persistent PI-v2 index and candidate before/after semantic deltas. The candidate path is not model-scoped: `RiftWorkspaceRecords.semanticImpactSeed()` derives its changed paths from the exact Patch Manifest V1 candidate, then the sandbox derives project roots, symbols/signature deltas, dependency deltas, current dependents, one-pass changed-symbol references, test affinity, `docs/SOURCE_OWNERSHIP.md` owners, nearest READMEs and global project docs.

Candidate semantic working-set bounds are 4096 changed paths, 1024 source files and 8 MiB before/after source text. Impact bounds are 32 project roots, 1000 output changed symbols, 80 reference-search symbols, 800 references, 800 dependency rows, 800 dependent rows, 300 tests, 300 documentation targets and 128 test-affinity targets. Missing text, truncated index/delta or any exceeded bound records an explicit incomplete reason.

The deterministic impact payload is SHA-256 bound as `semanticImpactSha256` using Patch Manifest canonical JSON. Cache refresh diagnostics are attached after the semantic hash and do not alter evidence identity.

The analyzer is lexical Project Intelligence, not a compiler AST or correctness proof. Compiler/build/test gates remain separate requirements.

Validation discovers repository check/test/build guidance.

These are Project Intelligence v2 behavior behind the existing tool schema, not separate MCP tools.

## Search / symbols / references

Text search is bounded by file size, match count and preview size.

Symbol/reference results are bounded independently.

Index invalidation is coalesced within a mutation call and persisted after refresh/invalidation.

Workspace files remain authoritative; stale cache rows cannot override source.

## Archive security

Archive/extract use staged destinations.

Extraction rejects:
- absolute paths;
- drive-letter paths;
- `.` and `..`;
- duplicate entries;
- targets escaping the staging root.

Entry count and expanded-byte limits are enforced before publish.

## Request lifecycle

Sandbox calls execute through one serialized worker with a bounded 16-request queue and a separate watchdog. Each request has a 45-second deadline beginning at submission time, including queue delay. Timeout returns one terminal error, interrupts the Future and exposes the same monotonic deadline to long filesystem loops through `RiftDeadline.check()`.

Code Mode checks the deadline around expensive archive/search/hash/index work and before/after the one normalized operation. Project Intelligence invalidations are coalesced within the call and flushed once. If cancellation occurs during a transactional mutation, the worker clears the interrupt only long enough to complete bounded rollback before accepting later work.

## Shutdown

Shutdown attempts to persist dirty PI-v2 state, then stops both the bounded sandbox worker and its watchdog.

## Non-ownership boundaries

Sandbox does not own:
- model-visible tool names/grants -> Tool Host;
- shell execution -> RiftNativeShell;
- external SAF folders -> Files;
- whole-project export paging format -> Project Exporter;
- permanent workspace record UI/checkpoint -> Workspace Records.

## Critical invariants

- every tool path remains beneath physical workspace;
- mutation capture happens before mutation;
- rollback max remains 64 MiB;
- failed rollback is surfaced;
- dry-run never permits structural operations;
- expected snapshots reject stale edits before mutation;
- result payload remains bounded;
- PI cache is advisory/app-private/versioned;
- mutation invalidation keeps index coherent;
- archive traversal/duplicate/expansion attacks are rejected.

## Failure signatures

- workspace tool accesses sibling RiftFS root -> containment regression;
- failed one-operation mutation leaves partial target state -> rollback regression;
- rollback failure reported as success -> atomicity regression;
- dry-run structural op succeeds -> dry-run contract regression;
- stale PI cache overrides changed file -> cache-validation regression;
- generated `.vortex-bridge` evidence appears as source -> indexing regression;
- Code Mode result grows beyond the per-call budget -> result-bound regression;
- ZIP traversal or duplicate path accepted -> archive regression.

## Fix map

Path/mutation/search/index/transaction behavior -> `RiftToolSandbox.kt`.

Tool schemas/grants -> `RiftToolHost.kt`.

Export paging/snapshot id -> `RiftProjectExporter.kt`.

Workspace persistent records -> `RiftWorkspaceRecords.kt`.

## Validation

Second source audit must recheck canonical root/path guards, operation set, 192-op bound, 700 KiB result bound, transaction capture-before-execute, 64 MiB rollback cap, dry-run restriction/rollback, snapshot guards, index persistence/version/bounds/ignored paths, search/symbol/reference limits, archive staging/traversal and shutdown persistence.
